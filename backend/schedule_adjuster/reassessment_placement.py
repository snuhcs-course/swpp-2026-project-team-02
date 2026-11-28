"""Place agent-chosen budgets, or prove bounded schedule feasibility.

This chooses only interval boundaries, never topic budgets. A search limit is
unknown feasibility, not a proof allowing a shorter review fallback.
"""
from functools import lru_cache


class PlacementSearchLimit(ValueError):
    pass


def place(intervals, names, lower, upper, *, single=(), review_runs=None, limit=60000):
    lengths = tuple(r["minutes"] for r in intervals)
    total = sum(lengths)
    lower, upper = tuple(lower), tuple(upper)
    if sum(lower) > total or sum(upper) < total or any(a > b for a, b in zip(lower, upper)):
        return None
    adjacent = tuple(i > 0 and intervals[i-1]["end_date"] == r["start_date"] for i, r in enumerate(intervals))
    single = {names.index(t) for t in single}
    runs = tuple((review_runs or {}).get(t, 0) for t in names)
    required_mask = sum(1 << i for i, n in enumerate(runs) if n)
    longest = current = 0
    for i, n in enumerate(lengths):
        current = current + n if adjacent[i] else n
        longest = max(longest, current)
    if any(n > longest for n in runs):
        return None
    nodes = 0

    @lru_cache(maxsize=limit)
    def search(row, offset, lo, hi, closed, last, run, satisfied):
        nonlocal nodes
        nodes += 1
        if nodes > limit:
            raise PlacementSearchLimit("placement feasibility search limit reached; do not infer physical impossibility")
        if row == len(lengths):
            if last >= 0 and run >= runs[last]:
                satisfied |= 1 << last
            return () if not any(lo) and satisfied & required_mask == required_mask else None
        if offset == 0 and row and not adjacent[row] and last >= 0:
            if run >= runs[last]:
                satisfied |= 1 << last
            if last in single:
                if hi[last]:
                    return None
                closed |= 1 << last
            last, run = -1, 0
        left = lengths[row] - offset
        minimum = min(15, lengths[row])
        future = left + sum(lengths[row+1:])
        if sum(lo) > future or sum(hi) < future:
            return None
        choices = sorted(range(len(names)), key=lambda i: (i != last if last in single else False, lo[i] == 0, i not in single, -lo[i], i))
        for topic in choices:
            if hi[topic] < minimum or closed & (1 << topic) or (offset and topic == last):
                continue
            if last in single and last != topic and hi[last]:
                continue
            maximum = min(left, hi[topic])
            if topic in single:
                amounts = [maximum]
                if hi[topic] > left and (row+1 == len(lengths) or not adjacent[row+1]):
                    continue
            else:
                preferred = [maximum, min(maximum, max(minimum, lo[topic])), left-15, hi[topic]-15]
                amounts = list(dict.fromkeys(preferred + list(range(maximum, minimum-1, -1))))
            for amount in amounts:
                if amount < minimum or amount > maximum or 0 < left-amount < minimum:
                    continue
                next_lo, next_hi = list(lo), list(hi)
                next_lo[topic] = max(0, next_lo[topic]-amount)
                next_hi[topic] -= amount
                next_closed, next_satisfied = closed, satisfied
                if last >= 0 and last != topic:
                    if run >= runs[last]:
                        next_satisfied |= 1 << last
                    if last in single:
                        next_closed |= 1 << last
                next_run = (run if topic == last else 0) + amount
                # Only the review threshold matters for this memoized state.
                next_run = min(next_run, runs[topic])
                next_row, next_offset = (row+1, 0) if amount == left else (row, offset+amount)
                result = search(next_row, next_offset, tuple(next_lo), tuple(next_hi), next_closed,
                                topic, next_run, next_satisfied)
                if result is not None:
                    return ((row, topic, amount),) + result
        return None

    try:
        result = search(0, 0, lower, upper, 0, -1, 0, 0)
    except RecursionError:
        raise PlacementSearchLimit("placement recursion limit reached; feasibility is unknown") from None
    if result is None:
        return None
    allocations = [{"schedule_id": r["schedule_id"], "pieces": []} for r in intervals]
    for row, topic, amount in result:
        allocations[row]["pieces"].append({"topic": names[topic], "minutes": amount})
    return allocations
