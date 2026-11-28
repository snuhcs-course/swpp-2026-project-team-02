"""Model-chosen reassessment allocations, with strict schedule preservation guards."""
from __future__ import annotations

from collections import defaultdict
import copy
from datetime import datetime, timedelta
import json
import math
import re

from openrouter_client import create_response, decode_call, function_output, parse_reply, usage_record
from .csv_io import parse_schedule_rows
from .models import ScheduleEntry
from .reassessment import ReassessmentPreviewExpired
from .settings import DATETIME_FORMAT, load_api_key, load_model
from .reassessment_placement import place

REVIEW_POLICY_VERSION = "review-sized-weak-additions-v5"

SYSTEM_PROMPT = """You revise the remaining topics of an existing study plan using actual progress and new diagnostic scores.
Choose the allocation yourself; do not mechanically repeat a fixed score-weight formula.
Use each topic's existing planned workload, completed planned work, actual study minutes, unmet planned work,
currently revisable minutes and new score together. Unreported past time is NOT completed study.
A mastered topic with completed work usually needs less review; a newly weak topic can need more time even
when its previous target was met. Explain the tradeoff using these data in Korean.
Every topic below high_score_threshold_percent on the new diagnostic must receive some remaining study.
Do not omit a low-scoring topic because its original sessions were in the past or completed.
BALANCED ADDITIONAL STUDY: declare weak_topic_allocations for EVERY topic below the high-score threshold,
with its final future minutes, your topic-specific review_minutes estimate, and a Korean reason connecting diagnostic score, reported study
(null means UNKNOWN, never zero), credited completed work and ORIGINAL revisable minutes.
Do not put all released time into topics that had zero future minutes while leaving other equally weak
topics unchanged. Reserve their original future work when there is enough extra time, then spread gains.
Let E = sum(final weak-topic minutes) - sum(original weak-topic revisable minutes), and N = weak-topic count.
When E>0, EACH weak-topic gain (final minus original) must cover at least MAX(its review_minutes,15).
The backend checks physical feasibility in the existing intervals (including one review-sized continuous run).
ONLY if the full minimum proposal is proven physically impossible may its minimum be replaced by 15 minutes;
model arithmetic errors or search limits do NOT authorize that fallback. If even 15 cannot fit, revise the proposal.
For N>=2, EACH gain must also be <=max(its effective minimum,ceil(3*E/(2*N))).
This is a concentration guard, NOT a formula choosing budgets: choose the actual
amounts within the range using score severity, evidence of study without improvement and original workload.
Substantial reported study with a low score can call for corrective practice; low or unknown study with
a low score calls for covering unlearned material. Do not treat smaller unmet work as automatic low priority.
If there is no net additional weak-topic time (E<=0), retain low-score coverage and explain scarce capacity.
PREFER propose_topic_budgets: choose topic totals and review estimates; the backend places your chosen
totals exactly. This avoids manual interval arithmetic without replacing your budget decisions.
EXCEPTION: ONLY topics scoring >= high_score_threshold_percent can have review_only_reasons.
The server computes that threshold as min(certification_passing_score + 10, 100).
For these high-scoring topics, review-only applies when >=95% of original planned time is still revisable,
OR explicitly reported actual study totals <=10 minutes. Missing reports are unknown, not zero study.
For EVERY such topic, choose its own useful review duration and explain it in review_sessions. Do not
use a fixed duration for all topics: consider the topic, workload, diagnostic score and study reports.
Allocate MAX(your declared duration, 15 minutes) as exactly one continuous review with no extra sessions.
If original revisable minutes exceed 15, strictly reduce them. Smaller remainders may reach the 15-minute floor.
When original remainder is zero, choose one new review session if explicitly low reported study triggers
the rule. A topic without an original planned workload does not trigger the 95% rule.
These are authored sample questions: do not declare mastery/숙달 or predict passing from a sample score.
Only revise the supplied revisable_schedule_ids. Cover each original interval exactly; never move, remove,
extend or add time. Keep protected rows and reports untouched. Choose a small number of useful pieces per
interval, each at least 15 whole minutes. An original interval shorter than 15 minutes must stay indivisible.
Use only the provided topic names. Call propose_reassessment with one entry for EVERY revisable interval.
RULE 3: A topic scoring >= high_score_threshold_percent WITHOUT review_only_reasons has NO per-topic
reduction limit or review floor: you may reduce its future allocation all the way to ZERO, even at scores
below 100%. Do not preserve its old allocation merely because neither review-only condition applies.
Decide its remaining allocation from the diagnostic, progress and other study needs. Do not infer
learning or exam readiness from time alone. Never invent reports, scores or timestamps.
If the tool rejects your proposal, correct it. Never return an unvalidated plan as prose."""

TOOL = {"name": "propose_reassessment", "description": "Validate and propose an allocation without saving anything.",
        "parameters": {"type": "object", "required": ["allocations", "rationale"], "additionalProperties": False,
            "properties": {"rationale": {"type": "string"}, "allocations": {"type": "array", "items": {
                "type": "object", "required": ["schedule_id", "pieces"], "additionalProperties": False,
                "properties": {"schedule_id": {"type": "string"}, "pieces": {"type": "array", "items": {
                    "type": "object", "required": ["topic", "minutes"], "additionalProperties": False,
                    "properties": {"topic": {"type": "string"}, "minutes": {"type": "integer"}}}}}}}}}}


def wire(row):
    return {"schedule_id": row.schedule_id, "start_date": row.start_datetime.strftime(DATETIME_FORMAT),
            "end_date": row.end_datetime.strftime(DATETIME_FORMAT), "topic": row.topic}


def duration(row):
    return int((row.end_datetime - row.start_datetime).total_seconds() // 60)


def build_context(schedule, keep, results, *, replan_from, now, first_new_study_id, reports, passing_score):
    if type(passing_score) not in (int, float) or not math.isfinite(passing_score) or not 0 <= passing_score <= 100:
        raise ValueError("certification passing_score must be configured between 0 and 100")
    high_score_threshold = min(passing_score + 10, 100)
    by_id = {r.schedule_id: r for r in schedule}
    if any(key not in by_id or by_id[key].schedule_type != "study" or by_id[key].start_datetime < replan_from for key in keep):
        raise ValueError("keep_schedule_ids must identify future study rows in schedules")
    highest = max((int(r.schedule_id[6:]) for r in schedule if r.schedule_type == "study"), default=0)
    if not isinstance(first_new_study_id, str) or not re.fullmatch(r"study-([1-9][0-9]*)", first_new_study_id) or int(first_new_study_id[6:]) <= highest:
        raise ValueError("first_new_study_id must be numbered above every current study row")
    if not isinstance(reports, dict):
        raise ValueError("study_reports must be an object keyed by current study ID")
    for key, report in reports.items():
        if key not in by_id or by_id[key].schedule_type != "study" or not isinstance(report, dict) or set(report) != {"status", "actual_minutes"}:
            raise ValueError("study_reports must identify current study rows and contain status/actual_minutes")
        status, actual = report["status"], report["actual_minutes"]
        if status not in ("done", "partly", "missed") or type(actual) is not int or not 0 <= actual <= 2147483647 or (actual == 0) != (status == "missed"):
            raise ValueError("invalid study report status or actual_minutes")
        if by_id[key].start_datetime >= replan_from and key not in keep:
            raise ValueError("every reported future study row must be protected by keep_schedule_ids")
    revisable = sorted((r for r in schedule if r.schedule_type == "study" and r.schedule_id not in keep and r.start_datetime >= replan_from),
                       key=lambda r: (r.start_datetime, r.schedule_id))
    if any(r.start_datetime < now for r in revisable):
        raise ReassessmentPreviewExpired("A study session has started. Preview the plan again.")
    if not revisable:
        raise ValueError("NOTHING_TO_REVISE: no future unreported study sessions")
    scores = defaultdict(lambda: [0.0, 0.0])
    for topic, possible, correct in results:
        scores[topic][0] += possible
        scores[topic][1] += possible if correct else 0
    required = sum(earned * 100 < possible * high_score_threshold for possible, earned in scores.values())
    if sum(max(1, duration(r) // 15) for r in revisable) < required:
        raise ValueError("NOT_ENOUGH_TIME: intervals cannot cover weak topics without short sessions")
    topics = []
    for topic in sorted(scores):
        rows = [r for r in schedule if r.schedule_type == "study" and r.topic == topic]
        planned = sum(map(duration, rows))
        actual = credited = 0
        for row in rows:
            report = reports.get(row.schedule_id)
            if report:
                actual += report["actual_minutes"]
                credited += duration(row) if report["status"] == "done" else min(duration(row), report["actual_minutes"])
        possible, earned = scores[topic]
        remaining = sum(duration(r) for r in revisable if r.topic == topic)
        has_reports = any(r.schedule_id in reports for r in rows)
        review_reasons = []
        high_score = earned * 100 >= possible * high_score_threshold
        if high_score and planned > 0 and remaining * 100 >= planned * 95:
            review_reasons.append("at_least_95_percent_remaining")
        if high_score and has_reports and actual <= 10:
            review_reasons.append("reported_actual_at_most_10_minutes")
        topics.append({"topic": topic, "new_score_percent": earned / possible * 100,
                       "planned_minutes": planned, "actual_study_minutes": actual if has_reports else None,
                       "completed_planned_minutes": credited, "unmet_planned_minutes": max(0, planned - credited),
                       "revisable_minutes": remaining, "has_study_reports": has_reports,
                       "review_only_reasons": review_reasons})
    required_names = [t["topic"] for t in topics if t["new_score_percent"] < high_score_threshold or t["review_only_reasons"]]
    total_remaining = sum(map(duration, revisable))
    capacity = sum((t["revisable_minutes"] - 1 if t["revisable_minutes"] > 15 else 15 if t["revisable_minutes"] else total_remaining)
                   if t["review_only_reasons"] else total_remaining for t in topics)
    if capacity < total_remaining:
        raise ValueError("NOT_ENOUGH_TIME: review reductions cannot preserve the total remaining time; no eligible topic can absorb released minutes")
    if sum(max(1, duration(r) // 15) for r in revisable) < len(required_names):
        raise ValueError("NOT_ENOUGH_TIME: intervals cannot cover required reviews without short sessions")
    revisable_ids = {r.schedule_id for r in revisable}
    return {"schedules": [wire(r) for r in schedule], "revisable_schedule_ids": [r.schedule_id for r in revisable],
            "revisable_intervals": [wire(r) | {"minutes": duration(r)} for r in revisable],
            "required_topic_names": required_names,
            "review_policy_version": REVIEW_POLICY_VERSION,
            "weak_addition_policy": {"fallback_minimum_minutes": 15, "minimum_source": "agent_review_minutes",
                                     "maximum_equal_share_fraction": 1.5, "original_weak_minutes": sum(t["revisable_minutes"] for t in topics if t["new_score_percent"] < high_score_threshold)},
            "certification_passing_score": passing_score, "high_score_threshold_percent": high_score_threshold,
            "protected_schedule_ids": [r.schedule_id for r in schedule if r.schedule_id not in revisable_ids],
            "replan_from": replan_from.strftime(DATETIME_FORMAT), "first_new_study_id": first_new_study_id,
            "study_reports": reports, "topics": topics, "total_remaining_minutes": sum(map(duration, revisable))}


def validate_proposal(context, proposal, *, validation_info=None):
    review_topics = {t["topic"]: t for t in context["topics"] if t["review_only_reasons"]}
    weak_topics = {t["topic"]: t for t in context["topics"] if t["new_score_percent"] < context["high_score_threshold_percent"]}
    expected_keys = {"allocations", "rationale"} | ({"review_sessions"} if review_topics else set()) | ({"weak_topic_allocations"} if weak_topics else set())
    if not isinstance(proposal, dict) or set(proposal) != expected_keys:
        raise ValueError(f"provide exactly these fields: {sorted(expected_keys)}")
    rationale = proposal["rationale"]
    if not isinstance(rationale, str) or not rationale.strip() or len(rationale) > 4000:
        raise ValueError("provide a nonempty rationale up to 4000 characters")
    allocations = proposal["allocations"]
    if not isinstance(allocations, list) or len(allocations) != len(context["revisable_schedule_ids"]):
        raise ValueError("cover every revisable interval exactly once")
    by_id = {}
    for item in allocations:
        if not isinstance(item, dict) or set(item) != {"schedule_id", "pieces"} or not isinstance(item["schedule_id"], str) or item["schedule_id"] in by_id:
            raise ValueError("invalid or duplicate allocation ID")
        by_id[item["schedule_id"]] = item["pieces"]
    if set(by_id) != set(context["revisable_schedule_ids"]):
        raise ValueError(f"cover only revisable IDs; missing={sorted(set(context['revisable_schedule_ids'])-set(by_id))}")
    topics = {t["topic"] for t in context["topics"]}
    source = parse_schedule_rows(context["schedules"])
    next_id = int(context["first_new_study_id"][6:])
    revised = []
    for row in source:
        if row.schedule_id not in by_id:
            revised.append(row)
            continue
        pieces = by_id[row.schedule_id]
        if not isinstance(pieces, list) or not pieces:
            raise ValueError("each interval needs at least one piece")
        normalized = []
        for piece in pieces:
            if not isinstance(piece, dict) or set(piece) != {"topic", "minutes"} or not isinstance(piece["topic"], str) or piece["topic"] not in topics or type(piece["minutes"]) is not int or piece["minutes"] <= 0:
                raise ValueError("pieces need a known topic and positive whole minutes")
            if normalized and normalized[-1]["topic"] == piece["topic"]:
                normalized[-1]["minutes"] += piece["minutes"]
            else:
                normalized.append(dict(piece))
        if sum(p["minutes"] for p in normalized) != duration(row):
            raise ValueError(f"{row.schedule_id}: expected {duration(row)} minutes, got {sum(p['minutes'] for p in normalized)}; preserve every interval exactly")
        if any(p["minutes"] < min(15, duration(row)) for p in normalized):
            raise ValueError("new pieces must be at least 15 minutes; keep an existing shorter interval indivisible")
        if len(normalized) == 1 and normalized[0]["topic"] == row.topic:
            revised.append(row)
        else:
            cursor = row.start_datetime
            for piece in normalized:
                end = cursor + timedelta(minutes=piece["minutes"])
                revised.append(ScheduleEntry(f"study-{next_id}", cursor, end, piece["topic"]))
                cursor = end
                next_id += 1
    allocated_topics = {r.topic for r in revised if r.schedule_id not in context["protected_schedule_ids"]}
    missing = [t["topic"] for t in context["topics"] if t["new_score_percent"] < context["high_score_threshold_percent"] and t["topic"] not in allocated_topics]
    if missing:
        raise ValueError(f"missing weak topics {missing}; give EACH some future study, taking minutes from other topics without changing interval totals")
    if review_topics:
        validate_reviews(context, proposal["review_sessions"], review_topics, revised)
    if weak_topics:
        decision = validate_weak_additions(context, proposal, weak_topics, revised)
        if validation_info is not None:
            validation_info["weak_additions"] = decision
    return tuple(sorted(revised, key=lambda r: (r.start_datetime, r.end_datetime, r.schedule_id)))


def weak_declarations(declarations, topics):
    if not isinstance(declarations, list) or len(declarations) != len(topics):
        raise ValueError("declare weak_topic_allocations for every weak topic")
    seen = {}
    for item in declarations:
        if (not isinstance(item, dict) or set(item) != {"topic", "minutes", "review_minutes", "reason"}
                or not isinstance(item["topic"], str) or item["topic"] not in topics or item["topic"] in seen
                or type(item["minutes"]) is not int or item["minutes"] < 1
                or type(item["review_minutes"]) is not int or item["review_minutes"] < 1
                or not isinstance(item["reason"], str) or not item["reason"].strip() or len(item["reason"]) > 2000):
            raise ValueError("weak declarations need unique topics, final minutes, positive review_minutes and individual reasons using score, reports and original remainder")
        seen[item["topic"]] = item
    return seen


def effective_weak_minimums(context, proposal, topics, declarations):
    reviews = declared_reviews(proposal.get("review_sessions", []), {t["topic"]: t for t in context["topics"] if t["review_only_reasons"]})
    names = [t["topic"] for t in context["topics"]]
    total = context["total_remaining_minutes"]

    def feasible(minimums):
        lower = [topics[t]["revisable_minutes"] + minimums[t] if t in topics else reviews.get(t, 0) for t in names]
        upper = [reviews.get(t, total) for t in names]
        return place(context["revisable_intervals"], names, lower, upper, single=reviews, review_runs=minimums)

    full = {t: max(15, declarations[t]["review_minutes"]) for t in topics}
    if feasible(full) is not None:
        return {"minimums": full, "fallback": False, "reason": "full review-sized minima fit existing intervals and total time"}
    fallback = {t: 15 for t in topics}
    if feasible(fallback) is None:
        raise ValueError("NOT_ENOUGH_TIME: neither proposed weak reviews nor 15-minute additions fit; change other budget choices without moving protected time")
    return {"minimums": fallback, "fallback": True, "reason": "exhaustive physical placement proved full review minima impossible; 15-minute additions fit"}


def validate_weak_additions(context, proposal, topics, revised):
    declarations = weak_declarations(proposal["weak_topic_allocations"], topics)
    amounts = defaultdict(int)
    for row in revised:
        if row.schedule_id not in context["protected_schedule_ids"]:
            amounts[row.topic] += duration(row)
    if any(declarations[t]["minutes"] != amounts[t] for t in topics):
        raise ValueError("weak_topic_allocations minutes must match the actual future allocation exactly")
    count = len(topics)
    extra = sum(amounts[t] - topics[t]["revisable_minutes"] for t in topics)
    if extra > 0:
        decision = effective_weak_minimums(context, proposal, topics, declarations)
        minimums = decision["minimums"]
        maximum = (3*extra + 2*count-1) // (2*count) if count >= 2 else extra
        bad = [f"{t}: original={topics[t]['revisable_minutes']}, proposed={amounts[t]}, gain={amounts[t]-topics[t]['revisable_minutes']}"
               for t in topics if not minimums[t] <= amounts[t] - topics[t]["revisable_minutes"] <= max(minimums[t], maximum)]
        if bad:
            raise ValueError(f"spread weak-topic additions: E={extra}, N={count}, minimums={minimums}, maximum=max(minimum,{maximum}), fallback={decision['fallback']}; " + "; ".join(bad))
        for topic, minimum in minimums.items():
            rows = sorted((r for r in revised if r.topic == topic and r.schedule_id not in context["protected_schedule_ids"]), key=lambda r: r.start_datetime)
            previous, run, longest = None, 0, 0
            for row in rows:
                run = run + duration(row) if previous == row.start_datetime else duration(row)
                longest = max(longest, run)
                previous = row.end_datetime
            if longest < minimum:
                raise ValueError(f"{topic}: actual allocation needs one continuous review of at least {minimum} minutes; this is an invalid proposal, not physical impossibility")
        return decision | {"extra_minutes": extra}
    return {"minimums": {}, "fallback": False, "extra_minutes": extra, "reason": "no net extra weak-topic time"}


def declared_reviews(reviews, topics):
    if not isinstance(reviews, list) or len(reviews) != len(topics):
        raise ValueError("declare exactly one review_sessions entry for every review-only topic")
    seen = {}
    for review in reviews:
        if (not isinstance(review, dict) or set(review) != {"topic", "minutes", "reason"}
                or not isinstance(review["topic"], str) or review["topic"] not in topics or review["topic"] in seen
                or type(review["minutes"]) is not int or review["minutes"] <= 0
                or not isinstance(review["reason"], str) or not review["reason"].strip() or len(review["reason"]) > 2000):
            raise ValueError("reviews need a unique review-only topic, positive whole minutes and an explanatory reason")
        topic, amount = review["topic"], max(review["minutes"], 15)
        seen[topic] = amount
        original = topics[topic]["revisable_minutes"]
        if original > 15 and amount >= original:
            raise ValueError(f"{topic}: one review must reduce the original {original} remaining minutes")
    return seen


def validate_reviews(context, reviews, topics, revised):
    for topic, amount in declared_reviews(reviews, topics).items():
        rows = sorted((r for r in revised if r.topic == topic and r.schedule_id not in context["protected_schedule_ids"]), key=lambda r: r.start_datetime)
        if (not rows or sum(duration(r) for r in rows) != amount
                or any(a.end_datetime != b.start_datetime for a, b in zip(rows, rows[1:]))):
            raise ValueError(f"{topic}: allocate exactly one continuous review of the declared {amount} minutes")


def materialize_budgets(context, proposal):
    names = [t["topic"] for t in context["topics"]]
    budgets = proposal.get("topic_minutes")
    if (not isinstance(budgets, dict) or set(budgets) != set(names)
            or any(type(n) is not int or n < 0 for n in budgets.values())
            or sum(budgets.values()) != context["total_remaining_minutes"]):
        raise ValueError(f"topic_minutes must cover every topic, use nonnegative whole minutes and sum to {context['total_remaining_minutes']}")
    high = {t["topic"]: t for t in context["topics"] if t["review_only_reasons"]}
    reviews = declared_reviews(proposal.get("review_sessions", []), high)
    if any(budgets[t] != n for t, n in reviews.items()):
        raise ValueError("high-score topic budgets must equal their declared review duration (minimum 15)")
    weak = {t["topic"]: t for t in context["topics"] if t["new_score_percent"] < context["high_score_threshold_percent"]}
    minimums = {}
    if weak:
        declarations = weak_declarations(proposal.get("weak_topic_allocations"), weak)
        if any(budgets[t] != declarations[t]["minutes"] for t in weak):
            raise ValueError("weak declarations must equal topic_minutes")
        if sum(budgets[t]-weak[t]["revisable_minutes"] for t in weak) > 0:
            minimums = effective_weak_minimums(context, proposal, weak, declarations)["minimums"]
    allocations = place(context["revisable_intervals"], names, [budgets[t] for t in names], [budgets[t] for t in names],
                        single=reviews, review_runs=minimums)
    if allocations is None:
        raise ValueError("chosen exact budgets cannot be placed in existing intervals with 15-minute pieces; revise budgets; this does not itself authorize a shorter review minimum")
    result = dict(proposal)
    del result["topic_minutes"]
    result["allocations"] = allocations
    return result


class ReassessmentAgent:
    def __init__(self):
        self.api_request_count = 0
        self.usage_records = []
        self.model = None
        self.validation_errors = []

    def run(self, context):
        history = [{"role": "user", "content": json.dumps(context, ensure_ascii=False)}]
        seen, trace = set(), []
        key = load_api_key()
        self.model = load_model()
        tool = copy.deepcopy(TOOL)
        item = tool["parameters"]["properties"]["allocations"]["items"]["properties"]
        item["schedule_id"]["enum"] = context["revisable_schedule_ids"]
        item["pieces"]["items"]["properties"]["topic"]["enum"] = [t["topic"] for t in context["topics"]]
        review_topics = [t["topic"] for t in context["topics"] if t["review_only_reasons"]]
        if review_topics:
            tool["parameters"]["required"].append("review_sessions")
            tool["parameters"]["properties"]["review_sessions"] = {"type": "array", "items": {
                "type": "object", "required": ["topic", "minutes", "reason"], "additionalProperties": False,
                "properties": {"topic": {"type": "string", "enum": review_topics}, "minutes": {"type": "integer", "minimum": 1},
                               "reason": {"type": "string"}}}}
        weak_topics = [t["topic"] for t in context["topics"] if t["new_score_percent"] < context["high_score_threshold_percent"]]
        if weak_topics:
            tool["parameters"]["required"].append("weak_topic_allocations")
            tool["parameters"]["properties"]["weak_topic_allocations"] = {"type": "array", "items": {
                "type": "object", "required": ["topic", "minutes", "review_minutes", "reason"], "additionalProperties": False,
                "properties": {"topic": {"type": "string", "enum": weak_topics}, "minutes": {"type": "integer", "minimum": 1},
                               "review_minutes": {"type": "integer", "minimum": 1},
                               "reason": {"type": "string"}}}}
        budget_tool = copy.deepcopy(tool)
        budget_tool["name"] = "propose_topic_budgets"
        budget_tool["description"] = "Choose all topic budgets; backend places them exactly without choosing their amounts. Preferred."
        params = budget_tool["parameters"]
        params["required"] = [k if k != "allocations" else "topic_minutes" for k in params["required"]]
        del params["properties"]["allocations"]
        params["properties"]["topic_minutes"] = {"type": "object", "additionalProperties": False,
            "required": [t["topic"] for t in context["topics"]], "properties": {t["topic"]: {"type": "integer", "minimum": 0} for t in context["topics"]}}
        tools = [budget_tool, tool]
        for _ in range(4):
            self.api_request_count += 1
            response = create_response(key, instructions=SYSTEM_PROMPT, input_items=history, tools=tools,
                                       reasoning_effort="low", max_output_tokens=16384, model=self.model)
            self.usage_records.append(usage_record(response))
            output, calls, _ = parse_reply(response)
            history.extend(output)
            if not calls:
                raise RuntimeError("Reassessment agent did not propose an allocation")
            for call in calls:
                call_id, name, proposal = decode_call(call, [t["name"] for t in tools], seen)
                try:
                    allocation = materialize_budgets(context, proposal) if name == "propose_topic_budgets" else proposal
                    info = {}
                    rows = validate_proposal(context, allocation, validation_info=info)
                except ValueError as error:
                    self.validation_errors.append(str(error))
                    history.append(function_output(call_id, {"error": str(error)}))
                    continue
                trace.append({"tool": name, "arguments": proposal, "validated_allocation": allocation, "validation": info})
                return rows, proposal["rationale"], trace
        raise RuntimeError("Reassessment agent exhausted its proposal validation attempts: " + "; ".join(self.validation_errors))
