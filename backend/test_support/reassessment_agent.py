"""Offline scripted proposal source for HTTP contract tests; never production fallback."""
from datetime import datetime

from schedule_adjuster.csv_io import parse_schedule_rows
from schedule_adjuster.reassessment import resplit_study
from schedule_adjuster.reassessment_agent import duration, validate_proposal


class ScriptedReassessmentAgent:
    contexts = []

    def run(self, context):
        self.contexts.append(context)
        source = parse_schedule_rows(context["schedules"])
        results = [(t["topic"], points, correct) for t in context["topics"]
                   for points, correct in ((t["new_score_percent"], True), (100-t["new_score_percent"], False))]
        cutoff = datetime.strptime(context["replan_from"], "%Y/%m/%d/%H/%M")
        rows = resplit_study(source, set(context["protected_schedule_ids"]) & {r.schedule_id for r in source if r.start_datetime >= cutoff and r.schedule_type == "study"},
                            results, replan_from=cutoff, now=cutoff, first_new_study_id=context["first_new_study_id"])
        allocations = []
        for row in source:
            if row.schedule_id in context["revisable_schedule_ids"]:
                inside = [r for r in rows if r.schedule_type == "study" and row.start_datetime <= r.start_datetime and r.end_datetime <= row.end_datetime]
                allocations.append({"schedule_id": row.schedule_id, "pieces": [{"topic": r.topic, "minutes": duration(r)} for r in inside]})
        proposal = {"allocations": allocations, "rationale": "오프라인 계약 테스트의 고정 제안입니다."}
        return validate_proposal(context, proposal), proposal["rationale"], [{"tool": "propose_reassessment", "arguments": proposal}]
