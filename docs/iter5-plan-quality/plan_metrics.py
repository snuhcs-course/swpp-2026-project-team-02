"""P7 plan quality metrics (M1-M8) and offline plan generation for P8/P9.

Usage:
  python plan_metrics.py <backend_dir> <cases.json> <label> <out_dir>
Generates every case's plan with the backend's own tools (no model call),
computes the metrics and writes <label>_plans.jsonl and <label>_metrics.json.
"""
from __future__ import annotations
import csv, io, json, os, statistics, sys
from datetime import datetime, timedelta

FMT = "%Y/%m/%d/%H/%M"
TH = {"M1a": 0, "M1b": 0.15, "M2_lo": 0.50, "M2_hi": 0.85, "M3": 0.10, "M5a": 0.90,
      "M6": 0.80, "M7": 0.50, "M8": 0.25}  # P9: M7 0.75 -> 0.50, M8 0.30 -> 0.25


def setup(backend):
    sys.path.insert(0, backend)
    os.chdir(backend)
    global build_plan_request, PlannerTools, check_initial_capacity, ToolTrace, render_schedule_csv, free_by_day
    from api_service import build_plan_request
    from license_planner.tools import PlannerTools, check_initial_capacity
    from license_planner.models import ToolTrace
    from license_planner.csv_io import render_schedule_csv
    from license_planner import scheduler
    free_by_day = getattr(scheduler, "_entered_free_segments_by_day", None) or scheduler._free_segments_by_day
    q = json.load(open("assessment_questionnaire/questions.json"))["questions"]
    return {x["topic"]: x["subject"] for x in q}


def generate(case):
    try:
        req = build_plan_request(case["request"], require_assessment=True, shared_availability=False)
    except TypeError:
        req = build_plan_request(case["request"], require_assessment=True)
    check_initial_capacity(req)
    if req.assessment_items:
        raise RuntimeError("unresolved questions need the model")
    t = PlannerTools(req, ToolTrace())
    for step in (t.analyze_exam_results, t.build_study_schedule):
        out = step()
        if isinstance(out, str):
            raise ValueError(out)
    rows = list(csv.DictReader(io.StringIO(render_schedule_csv(req.busy_periods, t.schedule))))
    stats = {s.topic: (s.earned_score, s.possible_score) for s in t.topic_statistics}
    return req, stats, rows


def subtract(segs, cuts):
    for a, b in cuts:
        nxt = []
        for x, y in segs:
            if b <= x or y <= a:
                nxt.append((x, y)); continue
            if x < a: nxt.append((x, a))
            if b < y: nxt.append((b, y))
        segs = nxt
    return segs


def metrics(req, stats, rows, subject_of):
    S, E = req.preparation_start, req.exam_date
    study = [(r["topic"], datetime.strptime(r["start_date"], FMT), datetime.strptime(r["end_date"], FMT))
             for r in rows if not r["schedule_id"].startswith("external")]
    ext = [r for r in rows if r["schedule_id"].startswith("external")]
    free = [seg for segs in free_by_day(req).values() for seg in segs]  # entered windows minus busy
    U0 = sum((b - a).total_seconds() / 60 for a, b in free)
    lens = [(b - a).total_seconds() / 60 for _, a, b in study]
    N, M = len(study), sum(lens)
    topics = list(stats)
    m = {t: 0.0 for t in topics}
    for (t, a, b), l in zip(study, lens):
        m[t] = m.get(t, 0) + l
    shat = {t: (c + 1) / (n + 2) for t, (c, n) in stats.items()}
    w = {t: 1 - shat[t] for t in topics}
    # M1
    raw = {t: c / n for t, (c, n) in stats.items()}
    inv = sum(1 for x in topics for y in topics if raw[x] < raw[y] and m[x] < m[y])  # raw scores (P9)
    tau_den = sum(1 + w[t] for t in topics)
    D = 0.5 * sum(abs(m[t] / M - (1 + w[t]) / tau_den) for t in topics) if M else 1.0
    # M2, M3
    U = M / U0 if U0 else 0.0
    p15 = sum(l < 15 for l in lens) / N if N else 0.0
    p30 = sum(l < 30 for l in lens) / N if N else 0.0
    # M4
    C = 0
    for i, (_, a, b) in enumerate(study):
        if a < S or b > E: C += 1
        if any(a < y and x < b for x, y in ((bp.start_datetime, bp.end_datetime) for bp in req.busy_periods)): C += 1
        if any(a < y and x < b for j, (_, x, y) in enumerate(study) if j != i): C += 1
        if subtract([(a, b)], free): C += 1  # part of the session lies outside the entered free time
    C += max(0, len(req.busy_periods) - len(ext))  # H5: busy rows missing from the plan
    # final window L (P9): the last 20% of usable minutes before the exam
    acc, L0 = 0.0, S
    for a, b in sorted(free, reverse=True):
        need = 0.2 * U0 - acc
        seg = (b - a).total_seconds() / 60
        if seg >= need:
            L0 = b - timedelta(minutes=need); break
        acc += seg; L0 = a
    # M5a (P9): sessions before the final window need a later free slot of the same length
    movable, pre = 0, 0
    for i, (_, a, b) in enumerate(study):
        if b > L0:
            continue
        pre += 1
        others = [(x, y) for j, (_, x, y) in enumerate(study) if j != i]
        segs = subtract(free, others)
        segs = [(max(x, b), min(y, E)) for x, y in segs]
        if any((y - x).total_seconds() / 60 >= lens[i] for x, y in segs if x < y):
            movable += 1
    R = movable / pre if pre else 1.0
    # M6
    elig = [t for t in topics if m[t] >= 60]
    days = {t: {a.date() for tt, a, _ in study if tt == t} for t in topics}
    M6 = sum(len(days[t]) >= 2 for t in elig) / len(elig) if elig else 1.0
    # M7
    final = {t for t, a, b in study if b > L0}
    weakest = min(shat.values()) if shat else 0
    M7 = len(final & set(topics)) / len(topics) if topics else 0.0
    weak_in = any(t in final for t in topics if shat[t] == weakest)
    # M8
    subj = {}
    for t in topics:
        subj[subject_of[t]] = subj.get(subject_of[t], 0) + m[t]
    M8 = min(subj.get(s, 0) for s in set(subject_of.values())) / M if M else 0.0
    v = {"N": N, "M": round(M), "U0": round(U0), "M1a": inv, "M1b": round(D, 3), "M2": round(U, 3),
         "M3_15": round(p15, 3), "M3_30": round(p30, 3), "M4": C, "M5a": round(R, 3),
         "M6": round(M6, 3), "M7": round(M7, 3), "M7_weak": weak_in, "M8": round(M8, 3)}
    v["pass"] = {"M1a": inv <= TH["M1a"], "M1b": D <= TH["M1b"], "M2": TH["M2_lo"] <= U <= TH["M2_hi"],
                 "M3": p15 <= TH["M3"], "M4": C == 0, "M5a": R >= TH["M5a"], "M6": M6 >= TH["M6"],
                 "M7": M7 >= TH["M7"] and weak_in, "M8": M8 >= TH["M8"]}
    return v


def summarize(results):
    ok = [r for r in results if r.get("metrics")]
    keys = ["M1a", "M1b", "M2", "M3", "M4", "M5a", "M6", "M7", "M8"]
    out = {"generated": len(ok), "total": len(results),
           "pass_rate": {k: round(sum(r["metrics"]["pass"][k] for r in ok) / len(ok), 3) for k in keys},
           "median": {k: round(statistics.median(r["metrics"][k] for r in ok), 3)
                      for k in ["M1b", "M2", "M3_15", "M3_30", "M5a", "M6", "M7", "M8"]},
           "all_pass": sum(all(r["metrics"]["pass"].values()) for r in ok)}
    return out


if __name__ == "__main__":
    backend, cases_path, label, out_dir = sys.argv[1:5]
    cases = json.load(open(cases_path))["cases"]
    subject_of = setup(backend)
    results = []
    for c in cases:
        rec = {"case_id": c["case_id"], "expected": c["expected_status"]}
        try:
            req, stats, rows = generate(c)
            rec.update(status=200, metrics=metrics(req, stats, rows, subject_of), rows=rows)
        except ValueError as e:
            rec.update(status=422, error=str(e)[:160])
        except Exception as e:
            rec.update(status="error", error=f"{type(e).__name__}: {e}"[:200])
        results.append(rec)
    with open(os.path.join(out_dir, f"{label}_plans.jsonl"), "w") as f:
        for r in results:
            f.write(json.dumps(r, ensure_ascii=False, default=str) + "\n")
    s = summarize(results)
    json.dump(s, open(os.path.join(out_dir, f"{label}_metrics.json"), "w"), ensure_ascii=False, indent=1)
    print(label, json.dumps(s, ensure_ascii=False))
