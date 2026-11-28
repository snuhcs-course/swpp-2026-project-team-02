"""Model-chosen reassessment allocations, with strict schedule preservation guards."""
from __future__ import annotations

from collections import defaultdict
import copy
from datetime import datetime, timedelta
import json
import re

from openrouter_client import create_response, decode_call, function_output, parse_reply, usage_record
from .csv_io import parse_schedule_rows
from .models import ScheduleEntry
from .reassessment import ReassessmentPreviewExpired
from .settings import DATETIME_FORMAT, load_api_key, load_model

SYSTEM_PROMPT = """You revise the remaining topics of an existing study plan using actual progress and new diagnostic scores.
Choose the allocation yourself; do not mechanically repeat a fixed score-weight formula.
Use each topic's existing planned workload, completed planned work, actual study minutes, unmet planned work,
currently revisable minutes and new score together. Unreported past time is NOT completed study.
A mastered topic with completed work usually needs less review; a newly weak topic can need more time even
when its previous target was met. Explain the tradeoff using these data in Korean.
Every topic below 100% on the new diagnostic must receive some remaining study. Do not omit a weak topic
because its original sessions were in the past or completed. Topics at 100% may receive zero if justified.
These are authored sample questions: do not declare mastery/숙달 or predict passing from a sample score.
Only revise the supplied revisable_schedule_ids. Cover each original interval exactly; never move, remove,
extend or add time. Keep protected rows and reports untouched. Choose a small number of useful pieces per
interval, each at least 15 whole minutes. An original interval shorter than 15 minutes must stay indivisible.
Use only the provided topic names. Call propose_reassessment with one entry for EVERY revisable interval.
You may allocate zero future minutes to a 100%-scoring topic when progress and diagnostics justify it. Do not infer
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


def build_context(schedule, keep, results, *, replan_from, now, first_new_study_id, reports):
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
    if sum(map(duration, revisable)) < len(scores):
        raise ValueError("NOT_ENOUGH_TIME: too few remaining minutes for reassessment")
    required = sum(earned < possible for possible, earned in scores.values())
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
        topics.append({"topic": topic, "new_score_percent": earned / possible * 100,
                       "planned_minutes": planned, "actual_study_minutes": actual,
                       "completed_planned_minutes": credited, "unmet_planned_minutes": max(0, planned - credited),
                       "revisable_minutes": sum(duration(r) for r in revisable if r.topic == topic)})
    revisable_ids = {r.schedule_id for r in revisable}
    return {"schedules": [wire(r) for r in schedule], "revisable_schedule_ids": [r.schedule_id for r in revisable],
            "revisable_intervals": [wire(r) | {"minutes": duration(r)} for r in revisable],
            "required_topic_names": [t["topic"] for t in topics if t["new_score_percent"] < 100],
            "protected_schedule_ids": [r.schedule_id for r in schedule if r.schedule_id not in revisable_ids],
            "replan_from": replan_from.strftime(DATETIME_FORMAT), "first_new_study_id": first_new_study_id,
            "study_reports": reports, "topics": topics, "total_remaining_minutes": sum(map(duration, revisable))}


def validate_proposal(context, proposal):
    if not isinstance(proposal, dict) or set(proposal) != {"allocations", "rationale"}:
        raise ValueError("provide allocations and rationale only")
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
    missing = [t["topic"] for t in context["topics"] if t["new_score_percent"] < 100 and t["topic"] not in allocated_topics]
    if missing:
        raise ValueError(f"missing weak topics {missing}; give EACH some future study, taking minutes from other topics without changing interval totals")
    return tuple(sorted(revised, key=lambda r: (r.start_datetime, r.end_datetime, r.schedule_id)))


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
        for _ in range(4):
            self.api_request_count += 1
            response = create_response(key, instructions=SYSTEM_PROMPT, input_items=history, tools=[tool],
                                       reasoning_effort="low", max_output_tokens=16384, model=self.model)
            self.usage_records.append(usage_record(response))
            output, calls, _ = parse_reply(response)
            history.extend(output)
            if not calls:
                raise RuntimeError("Reassessment agent did not propose an allocation")
            for call in calls:
                call_id, name, proposal = decode_call(call, [TOOL["name"]], seen)
                try:
                    rows = validate_proposal(context, proposal)
                except ValueError as error:
                    self.validation_errors.append(str(error))
                    history.append(function_output(call_id, {"error": str(error)}))
                    continue
                trace.append({"tool": name, "arguments": proposal})
                return rows, proposal["rationale"], trace
        raise RuntimeError("Reassessment agent exhausted its proposal validation attempts: " + "; ".join(self.validation_errors))
