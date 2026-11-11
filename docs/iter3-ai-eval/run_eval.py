"""Runs the Iteration 3 AI evaluation (P11) against a License Planner API.

Standard library only (Python 3.10+). Example:
    python run_eval.py --base-url http://swpp26t02.147-46-242-174.sslip.io/api/v1 --server-commit 09d66ef

Writes results/<timestamp>/ with runs.jsonl, runs.csv, summary.json and summary.md.
Criteria and thresholds are fixed in README.md and must not be changed after a run.
"""
import argparse
import csv
import hashlib
import json
import math
import statistics
import subprocess
import time
import urllib.error
import urllib.request
from datetime import datetime, timedelta
from pathlib import Path

FMT = "%Y/%m/%d/%H/%M"
DAYS = ("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
TIMEOUT_S = 120
PAUSE_S = 3
MAX_CONSECUTIVE_5XX = 5


# ---------- HTTP ----------

def post(url, body):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=data, method="POST",
                                 headers={"Content-Type": "application/json; charset=utf-8",
                                          "User-Agent": "swpp-team02-ai-eval"})
    started = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT_S) as resp:
            text = resp.read().decode("utf-8")
            status = resp.status
    except urllib.error.HTTPError as err:
        text = err.read().decode("utf-8", "replace")
        status = err.code
    except (TimeoutError, OSError) as err:  # socket timeout, connection errors
        elapsed = time.monotonic() - started
        kind = "TIMEOUT" if elapsed >= TIMEOUT_S - 1 or "timed out" in str(err) else "CONNECTION"
        return {"status": None, "error_kind": kind, "error": str(err), "seconds": round(elapsed, 2), "json": None}
    elapsed = time.monotonic() - started
    try:
        payload = json.loads(text)
    except json.JSONDecodeError:
        payload = None
    return {"status": status, "error_kind": None, "error": None if payload else text[:300],
            "seconds": round(elapsed, 2), "json": payload}


# ---------- geometry ----------

def dt(value):
    return datetime.strptime(value, FMT)


def overlap(a, b):
    return a[0] < b[1] and b[0] < a[1]


def windows_by_date(request):
    start, exam = dt(request["preparation_start"]), dt(request["exam_date"])
    out, day = [], start.date()
    while day <= exam.date():
        for w in request["weekly_availability"].get(DAYS[day.weekday()], []):
            a = datetime.combine(day, datetime.strptime(w["start"], "%H:%M").time())
            b = datetime.combine(day, datetime.strptime(w["end"], "%H:%M").time())
            a, b = max(a, start), min(b, exam)
            if a < b:
                out.append((a, b))
        day += timedelta(days=1)
    return out


def busy_intervals(request):
    return [(dt(b["start_date"]), dt(b["end_date"])) for b in request.get("busy_schedules", [])]


def available_minutes(request):
    total = 0.0
    busy = busy_intervals(request)
    for a, b in windows_by_date(request):
        pieces = [(a, b)]
        for l, r in busy:
            nxt = []
            for x, y in pieces:
                if r <= x or y <= l:
                    nxt.append((x, y))
                else:
                    if x < l:
                        nxt.append((x, l))
                    if r < y:
                        nxt.append((r, y))
            pieces = nxt
        total += sum((y - x).total_seconds() / 60 for x, y in pieces)
    return total


def inside_window(interval, windows):
    return any(w[0] <= interval[0] and interval[1] <= w[1] for w in windows)


def split_rows(rows):
    study, external = [], []
    for r in rows:
        (external if str(r.get("schedule_id", "")).startswith("external") else study).append(r)
    return study, external


# ---------- checks ----------

def pearson(x, y):
    if len(x) < 2:
        return None
    mx, my = statistics.mean(x), statistics.mean(y)
    dx, dy = [a - mx for a in x], [b - my for b in y]
    den = math.sqrt(sum(a * a for a in dx) * sum(b * b for b in dy))
    return None if den == 0 else sum(a * b for a, b in zip(dx, dy)) / den


def check_plan(case, rows, topics):
    req = case["request"]
    windows, busy = windows_by_date(req), busy_intervals(req)
    start, exam = dt(req["preparation_start"]), dt(req["exam_date"])
    study, external = split_rows(rows)
    iv = []
    hard = {"H1": bool(study), "H2": bool(study), "H3": True, "H4": True, "H5": True}
    for r in study:
        try:
            a, b = dt(r["start_date"]), dt(r["end_date"])
        except (KeyError, ValueError):
            hard["H1"] = hard["H2"] = False
            continue
        if not inside_window((a, b), windows):
            hard["H1"] = False
        if a < start or b > exam or a >= b:
            hard["H2"] = False
        if r.get("topic") not in topics:
            hard["H4"] = False
        iv.append((a, b, r.get("topic")))
    for i, x in enumerate(iv):
        if any(overlap(x, y) for y in iv[i + 1:]) or any(overlap(x, bz) for bz in busy):
            hard["H3"] = False
    sent = {(b["start_date"], b["end_date"]) for b in req.get("busy_schedules", [])}
    got = {(r.get("start_date"), r.get("end_date")) for r in external}
    hard["H5"] = sent <= got

    minutes = {t: 0.0 for t in topics}
    lengths = []
    for a, b, t in iv:
        m = (b - a).total_seconds() / 60
        lengths.append(m)
        if t in minutes:
            minutes[t] += m
    total = sum(minutes.values())
    exp = case["expect"]
    q = {}
    if exp["ordering_applies"]:
        top = max(minutes.values()) if minutes else 0
        q["Q1"] = total > 0 and any(minutes[t] == top for t in exp["weakest_topics"])
        scores = [exp["topic_scores"][t] for t in topics]
        r = pearson(scores, [minutes[t] for t in topics])
        q["Q2_value"] = None if r is None else round(r, 3)
        q["Q2"] = r is not None and r <= -0.5
    if case["case_id"] != "D03":
        q["Q3"] = all(minutes[t] > 0 for t in topics)
    avail = available_minutes(req)
    q["Q4_utilization"] = round(total / avail, 3) if avail else None
    q["Q5_short_share"] = round(sum(1 for m in lengths if m < 15) / len(lengths), 3) if lengths else None
    shares = {t: (minutes[t] / total if total else 0) for t in topics}
    return hard, q, {"study_sessions": len(study), "study_minutes": total,
                     "available_minutes": avail, "topic_minutes": minutes, "topic_shares": shares}


def pick_target(study, how):
    ordered = sorted(study, key=lambda r: r["start_date"])
    if how == "first_study":
        return ordered[0]
    if how == "last_study":
        return ordered[-1]
    return ordered[len(ordered) // 2]


def check_adjust(case, before, after_json, target):
    req = case["request"]
    windows, busy = windows_by_date(req), busy_intervals(req)
    j = {"J1": bool(after_json) and after_json.get("action") == "rescheduled_study"}
    if not j["J1"]:
        return j
    rows = {r["schedule_id"]: r for r in after_json.get("schedules", [])}
    old = {r["schedule_id"]: r for r in before}
    new = rows.get(target["schedule_id"])
    if new is None:
        j.update(J2=False, J3=False, J4=False, J5=False)
        return j
    j["J2"] = new["start_date"] != target["start_date"]
    old_len = dt(target["end_date"]) - dt(target["start_date"])
    j["J3"] = new.get("topic") == target.get("topic") and dt(new["end_date"]) - dt(new["start_date"]) == old_len
    j["J4"] = all(rows.get(k) == v for k, v in old.items() if k != target["schedule_id"]) and set(rows) == set(old)
    moved = (dt(new["start_date"]), dt(new["end_date"]))
    others = [(dt(r["start_date"]), dt(r["end_date"])) for k, r in rows.items() if k != target["schedule_id"]]
    # J5 checks the weekly window only; landing after the exam is reported separately as J6.
    weekly = windows_by_date({"preparation_start": req["preparation_start"],
                              "exam_date": max(moved[1], dt(req["exam_date"])).strftime(FMT),
                              "weekly_availability": req["weekly_availability"]})
    j["J5"] = inside_window(moved, weekly) and not any(overlap(moved, o) for o in others + busy)
    j["J6_after_exam"] = moved[1] > dt(req["exam_date"])
    return j


# ---------- run ----------

def classify(expected, resp):
    if resp["error_kind"] == "TIMEOUT":
        return "TIMEOUT"
    if resp["status"] is None:
        return "CONNECTION"
    payload = resp["json"] if isinstance(resp["json"], dict) else {}
    error = payload.get("error") if isinstance(payload.get("error"), dict) else {}
    code, message = error.get("code", ""), error.get("message", "")
    if resp["status"] >= 500:
        return "HTTP_5XX"
    if expected == "200":
        return "OK" if resp["status"] == 200 else "UNEXPECTED_STATUS"
    if expected == "422_NOT_ENOUGH_TIME":
        return "OK" if resp["status"] == 422 and "NOT_ENOUGH_TIME" in message else "UNEXPECTED_STATUS"
    if expected == "422_INVALID_INPUT":
        return "OK" if resp["status"] == 422 and code == "INVALID_INPUT" else "UNEXPECTED_STATUS"
    if expected == "200_or_422_NOT_ENOUGH_TIME":
        ok = resp["status"] == 200 or (resp["status"] == 422 and "NOT_ENOUGH_TIME" in message)
        return "OK" if ok else "UNEXPECTED_STATUS"
    return "UNEXPECTED_STATUS"


def percentile(values, p):
    if not values:
        return None
    s = sorted(values)
    k = max(0, min(len(s) - 1, math.ceil(p / 100 * len(s)) - 1))
    return s[k]


def rate(passed, total):
    return None if total == 0 else round(passed / total, 3)


def summarize(runs, cases_by_id):
    gen = [r for r in runs if r["kind"] == "generate"]
    adj = [r for r in runs if r["kind"] == "adjust"]
    feas = [r for r in gen if cases_by_id[r["case_id"]]["expected_status"] == "200"]
    ok_plans = [r for r in gen if r["status"] == 200 and r.get("hard")]
    t1_pass = sum(1 for r in feas if r["outcome"] == "OK" and all(r["hard"].values()))
    hard_viol = sum(1 for r in ok_plans if not all(r["hard"].values()))
    det = [r for r in gen if r["case_id"] in ("D01", "D02", "D06")]
    d03 = [r for r in gen if r["case_id"] == "D03"]
    lat = [r["seconds"] for r in runs if r["seconds"] is not None]
    timeouts = sum(1 for r in runs if r["outcome"] == "TIMEOUT")
    q1 = [r["quality"]["Q1"] for r in ok_plans if "Q1" in r["quality"]]
    q2 = [r["quality"]["Q2"] for r in ok_plans if "Q2" in r["quality"]]
    q3 = [r["quality"]["Q3"] for r in ok_plans if "Q3" in r["quality"]]
    j1 = [r for r in adj if r.get("adjust") and r["adjust"].get("J1")]
    j_all = sum(1 for r in j1 if all(r["adjust"].get(k) for k in ("J2", "J3", "J4", "J5")))
    e_attempts = sum(1 for r in gen if cases_by_id[r["case_id"]].get("adjust"))  # failed generations count as failed adjustments

    util = [r["quality"]["Q4_utilization"] for r in ok_plans if r["quality"].get("Q4_utilization") is not None]
    short = [r["quality"]["Q5_short_share"] for r in ok_plans if r["quality"].get("Q5_short_share") is not None]
    stab = []
    for cid in cases_by_id:
        shares = [r["metrics"]["topic_shares"] for r in ok_plans if r["case_id"] == cid]
        if len(shares) >= 2:
            stab.append(max(max(s[t] for s in shares) - min(s[t] for s in shares) for t in shares[0]))

    t = {
        "T1": {"value": rate(t1_pass, len(feas)), "pass": len(feas) > 0 and t1_pass / len(feas) >= 0.9},
        "T2": {"value": hard_viol, "pass": hard_viol == 0},
        "T3": {"value": f"det {sum(r['outcome'] == 'OK' for r in det)}/{len(det)}, D03 5xx/timeout "
                        f"{sum(r['outcome'] in ('HTTP_5XX', 'TIMEOUT') for r in d03)}",
               "pass": all(r["outcome"] == "OK" for r in det) and not any(r["outcome"] in ("HTTP_5XX", "TIMEOUT") for r in d03)},
        "T4": {"value": f"timeouts {timeouts}, p95 {percentile(lat, 95)}s",
               "pass": timeouts == 0 and (percentile(lat, 95) or 0) <= 90},
        "T5": {"value": rate(sum(q1), len(q1)), "pass": bool(q1) and sum(q1) / len(q1) >= 0.8},
        "T6": {"value": rate(sum(q2), len(q2)), "pass": bool(q2) and sum(q2) / len(q2) >= 0.8},
        "T7": {"value": rate(sum(q3), len(q3)), "pass": bool(q3) and sum(q3) / len(q3) >= 0.9},
        "T8": {"value": f"J1 {len(j1)}/{e_attempts}, J2-J5 {j_all}/{len(j1)}",
               "pass": e_attempts > 0 and len(j1) / e_attempts >= 0.9 and j_all == len(j1)},
    }
    outcomes = {}
    for r in runs:
        outcomes[r["outcome"]] = outcomes.get(r["outcome"], 0) + 1
    report = {
        "Q4_utilization_median": statistics.median(util) if util else None,
        "Q5_short_share_mean": round(statistics.mean(short), 3) if short else None,
        "Q6_share_range_max": round(max(stab), 3) if stab else None,
        "Q6_share_range_median": round(statistics.median(stab), 3) if stab else None,
        "latency_p50": percentile(lat, 50), "latency_p95": percentile(lat, 95),
        "latency_max": max(lat) if lat else None,
    }
    return {"thresholds": t, "verdict": "PASS" if all(v["pass"] for v in t.values()) else "FAIL",
            "report_metrics": report, "outcomes": outcomes,
            "counts": {"generate_runs": len(gen), "adjust_runs": len(adj)}}


def write_outputs(out, runs, summary, meta):
    out.mkdir(parents=True, exist_ok=True)
    with (out / "runs.jsonl").open("w", encoding="utf-8") as f:
        for r in runs:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    fields = ["case_id", "rep", "kind", "status", "outcome", "seconds", "hard", "quality", "adjust", "error"]
    with (out / "runs.csv").open("w", encoding="utf-8-sig", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields, extrasaction="ignore")
        w.writeheader()
        for r in runs:
            w.writerow({k: json.dumps(r.get(k), ensure_ascii=False) if isinstance(r.get(k), dict) else r.get(k)
                        for k in fields})
    (out / "summary.json").write_text(json.dumps({"meta": meta, **summary}, ensure_ascii=False, indent=2),
                                      encoding="utf-8")
    lines = [f"# AI 평가 결과 ({meta['started_at']})", "",
             f"- 대상: `{meta['base_url']}`", f"- 서버 commit: `{meta['server_commit']}`",
             f"- 모델: {meta['model']}", f"- 판정: **{summary['verdict']}**", "",
             "| 기준 | 값 | 통과 |", "| --- | --- | --- |"]
    for k, v in summary["thresholds"].items():
        lines.append(f"| {k} | {v['value']} | {'O' if v['pass'] else 'X'} |")
    lines += ["", "| 보고 지표 | 값 |", "| --- | --- |"]
    lines += [f"| {k} | {v} |" for k, v in summary["report_metrics"].items()]
    lines += ["", "| 결과 분류 | 횟수 |", "| --- | --- |"]
    lines += [f"| {k} | {v} |" for k, v in summary["outcomes"].items()]
    (out / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", required=True)
    ap.add_argument("--cases", default=str(Path(__file__).with_name("cases.json")))
    ap.add_argument("--server-commit", default="unknown")
    ap.add_argument("--model", default="unknown")
    ap.add_argument("--output-dir", type=Path, default=Path(__file__).parent / "results")
    ap.add_argument("--inference-config", help="JSON file recording provider, decoding settings and prompt/tool hashes")
    ap.add_argument("--only", nargs="*", help="run only these case_ids (for a dry run)")
    ap.add_argument("--reps", type=int, help="override repetitions (dry run only)")
    args = ap.parse_args()
    if args.reps is not None and args.reps < 1:
        ap.error("--reps must be positive")

    doc = json.loads(Path(args.cases).read_text(encoding="utf-8"))
    topics, cases = doc["topics"], doc["cases"]
    if args.only:
        unknown = set(args.only) - {c["case_id"] for c in cases}
        if unknown:
            ap.error("Unknown case IDs: " + ", ".join(sorted(unknown)))
        cases = [c for c in cases if c["case_id"] in args.only]
    by_id = {c["case_id"]: c for c in cases}
    base = args.base_url.rstrip("/")
    started = datetime.now().strftime("%Y%m%dT%H%M%S")
    meta = {"started_at": started, "base_url": base, "server_commit": args.server_commit,
            "model": args.model, "cases_version": doc["version"], "dry_run": bool(args.only or args.reps)}
    meta.update(cases_sha256=hashlib.sha256(Path(args.cases).read_bytes()).hexdigest(),
                runner_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                bank_sha256=doc.get("bank_sha256"),
                runner_commit=subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=Path(__file__).parent, text=True).strip(),
                inference_config=json.loads(Path(args.inference_config).read_text(encoding="utf-8")) if args.inference_config else "not recorded")
    out = args.output_dir / (started + f"_{time.time_ns() % 1000000000:09d}")
    out.mkdir(parents=True)

    def record_exchange(case_id, rep, kind, body, response):
        with (out / "exchanges.jsonl").open("a", encoding="utf-8") as stream:
            stream.write(json.dumps({"case_id": case_id, "rep": rep, "kind": kind,
                                     "request": body, "response": response}, ensure_ascii=False) + "\n")
    runs, consecutive_5xx = [], 0
    total = sum((args.reps or c["repetitions"]) for c in cases)
    n = 0
    for c in cases:
        for rep in range(1, (args.reps or c["repetitions"]) + 1):
            n += 1
            resp = post(base + "/study-plans", c["request"])
            record_exchange(c["case_id"], rep, "generate", c["request"], resp)
            outcome = classify(c["expected_status"], resp)
            run = {"case_id": c["case_id"], "rep": rep, "kind": "generate", "status": resp["status"],
                   "outcome": outcome, "seconds": resp["seconds"], "error": resp["error"],
                   "hard": {}, "quality": {}, "metrics": {}}
            if resp["status"] == 200 and isinstance(resp["json"], dict):
                run["hard"], run["quality"], run["metrics"] = check_plan(c, resp["json"].get("schedules", []), topics)
                if not all(run["hard"].values()):
                    run["outcome"] = "HARD_VIOLATION"
            elif resp["status"] == 200:
                run["hard"] = {"H1": False}
                run["outcome"] = "HARD_VIOLATION"
                run["error"] = "Invalid API JSON object"
            elif isinstance(resp["json"], dict):
                run["error"] = json.dumps(resp["json"].get("error"), ensure_ascii=False)
            runs.append(run)
            print(f"[{n}/{total}] {c['case_id']} #{rep} {resp['status']} {run['outcome']} {resp['seconds']}s", flush=True)
            consecutive_5xx = consecutive_5xx + 1 if run["outcome"] == "HTTP_5XX" else 0

            if c.get("adjust") and resp["status"] == 200 and isinstance(resp["json"], dict):
                time.sleep(PAUSE_S)
                study, _ = split_rows(resp["json"].get("schedules", []))
                if study:
                    target = pick_target(study, c["adjust"]["target"])
                    body = {"schedules": resp["json"]["schedules"], "schedule_id": target["schedule_id"],
                            "schedule_csv_file": resp["json"].get("schedule_csv_file"),
                            "weekly_availability": c["request"]["weekly_availability"],
                            "exam_date": c["request"]["exam_date"]}
                    aresp = post(base + "/schedules/adjust", body)
                    record_exchange(c["case_id"], rep, "adjust", body, aresp)
                    aout = classify("200", aresp)
                    arun = {"case_id": c["case_id"], "rep": rep, "kind": "adjust", "status": aresp["status"],
                            "outcome": aout, "seconds": aresp["seconds"], "error": aresp["error"],
                            "target": target["schedule_id"],
                            "adjust": check_adjust(c, resp["json"]["schedules"], aresp["json"], target)
                            if aresp["status"] == 200 else {"J1": False}}
                    if aresp["status"] != 200 and aresp["json"]:
                        arun["error"] = json.dumps(aresp["json"].get("error"), ensure_ascii=False)
                    runs.append(arun)
                    print(f"      adjust {target['schedule_id']} {aresp['status']} {aout} {aresp['seconds']}s", flush=True)
                    consecutive_5xx = consecutive_5xx + 1 if aout == "HTTP_5XX" else 0
            write_outputs(out, runs, summarize(runs, by_id), meta)  # keep partial results
            if consecutive_5xx >= MAX_CONSECUTIVE_5XX:
                print(f"STOP: {MAX_CONSECUTIVE_5XX} consecutive 5xx (provider outage?). Partial results in {out}")
                return
            time.sleep(PAUSE_S)
    summary = summarize(runs, by_id)
    write_outputs(out, runs, summary, meta)
    print(f"\nverdict {summary['verdict']} -> {out / 'summary.md'}")


if __name__ == "__main__":
    main()
