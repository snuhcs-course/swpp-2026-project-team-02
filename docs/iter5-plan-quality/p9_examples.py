"""P9 step 1: hand-made example plans that should each fail one intended metric.

Usage: python p9_examples.py <backend_dir> <cases.json> <case_id> <out.json>
"""
import json, sys
from datetime import timedelta
sys.path.insert(0, sys.path[0])
import plan_metrics as pm

backend, cases_path, case_id, out_path = sys.argv[1:5]
subject_of = pm.setup(backend)
case = next(c for c in json.load(open(cases_path))["cases"] if c["case_id"] == case_id)
req, stats, _ = pm.generate(case)
free = sorted(seg for segs in pm.free_by_day(req).values() for seg in segs)
U0 = sum((b - a).total_seconds() / 60 for a, b in free)
shat = {t: (c + 1) / (n + 2) for t, (c, n) in stats.items()}
topics = sorted(stats, key=lambda t: (stats[t][0] / stats[t][1], t))  # weakest first (raw score)
raw = {t: stats[t][0] / stats[t][1] for t in topics}
weight = {t: 2 - raw[t] for t in topics}  # P10 weight on raw scores
S, E = req.preparation_start, req.exam_date
acc, L0 = 0.0, S
for a, b in sorted(free, reverse=True):
    need = 0.2 * U0 - acc
    seg = (b - a).total_seconds() / 60
    if seg >= need:
        L0 = b - timedelta(minutes=need); break
    acc += seg; L0 = a


def blocks(seg_list, size):
    out = []
    for a, b in seg_list:
        while (b - a).total_seconds() / 60 >= 30:
            end = min(b, a + timedelta(minutes=size))
            if (b - end).total_seconds() / 60 < 30 and end != b:
                end = b
            out.append((a, end)); a = end
    return out


def assign(blks, order_fn, total=None, got=None):
    """Give each block to the topic with the largest remaining deficit, avoiding repeats."""
    total = total or sum((b - a).total_seconds() / 60 for a, b in blks)
    target = {t: total * weight[t] / sum(weight.values()) for t in topics}
    got = dict(got) if got else {t: 0.0 for t in topics}
    rows, prev = [], None
    for a, b in blks:
        cand = sorted(topics, key=lambda t: order_fn(t, target, got), reverse=True)
        t = cand[0] if cand[0] != prev or len(cand) == 1 else cand[1]
        rows.append((t, a, b)); got[t] += (b - a).total_seconds() / 60; prev = t
    return rows


def to_rows(study):
    rows = [{"schedule_id": f"study-{i+1}", "start_date": a.strftime(pm.FMT), "end_date": b.strftime(pm.FMT), "topic": t}
            for i, (t, a, b) in enumerate(sorted(study, key=lambda x: x[1]))]
    rows += [{"schedule_id": f"external-{i+1}", "start_date": "", "end_date": "", "topic": ""} for i, _ in enumerate(req.busy_periods)]
    return rows


deficit = lambda t, target, got: target[t] - got[t]
all_blocks = blocks(free, 45)
early = [x for x in all_blocks if x[1] <= L0]
late = [x for x in all_blocks if x[1] > L0]
# good: use about 2 of every 3 early blocks, keep the last usable block free as a buffer, review in the final window
keep_early = [x for i, x in enumerate(early) if i % 4 != 3]  # every 4th block before the final window stays free
good_blocks = keep_early + late  # final window fully used for review


def good_assign(blks):
    """Early blocks by deficit; final-window blocks rotate topics weakest first (review)."""
    e = [x for x in blks if x[1] <= L0]
    l = [x for x in blks if x[1] > L0]
    review = [(topics[i % len(topics)], a, b) for i, (a, b) in enumerate(l)]
    got = {t: 0.0 for t in topics}
    for t, a, b in review:
        got[t] += (b - a).total_seconds() / 60
    total = sum((b - a).total_seconds() / 60 for a, b in blks)
    return assign(e, deficit, total, got) + review
examples = {
    "1 good (meets all)": good_assign(good_blocks),
    "2 fills 100% of time": assign(all_blocks, deficit),
    "3 cut into 10-min pieces": [(t, a + timedelta(minutes=10 * k), min(b, a + timedelta(minutes=10 * (k + 1))))
                                 for t, a, b in assign(good_blocks, deficit)
                                 for k in range(int((b - a).total_seconds() // 600) + 1)
                                 if a + timedelta(minutes=10 * k) < b],
    "4 ignores weak topics": assign(good_blocks, lambda t, target, got: (raw[t] + 0.01) * 10 - got[t] / 60),
    "5 crams each topic into one day": [],
    "6 starves 컴퓨터 일반": [],
}
# 5: same minutes as good, but each topic's sessions all on one date (consecutive blocks, weakest first)
seq = sorted(assign(good_blocks, deficit), key=lambda x: (topics.index(x[0]), x[1]))
blk = sorted(good_blocks)
examples["5 crams each topic into one day"] = [(t, a, b) for (t, _, _), (a, b) in zip(seq, blk)]
# 6: spreadsheet topics get every block except one per computer topic
ss = [t for t in topics if subject_of[t] == "스프레드시트 일반"]
cs = [t for t in topics if subject_of[t] == "컴퓨터 일반"]
rows6 = assign(good_blocks, deficit)
cs_seen = set()
fixed = []
for t, a, b in rows6:
    if t in cs and t in cs_seen:
        t = min(ss, key=lambda x: raw[x])
    cs_seen.add(t); fixed.append((t, a, b))
examples["6 starves 컴퓨터 일반"] = fixed

out = {"case": case_id, "usable_minutes": round(U0), "examples": {}}
for name, study in examples.items():
    v = pm.metrics(req, stats, to_rows(study), subject_of)
    out["examples"][name] = v
    fails = [k for k, ok in v["pass"].items() if not ok]
    print(f"{name:34s} U={v['M2']:.2f} R={v['M5a']:.2f} P15={v['M3_15']:.2f} M6={v['M6']:.2f} M7={v['M7']:.2f}/{v['M7_weak']} M8={v['M8']:.2f} D={v['M1b']:.3f} inv={v['M1a']} -> fails {fails}")
json.dump(out, open(out_path, "w"), ensure_ascii=False, indent=1, default=str)
