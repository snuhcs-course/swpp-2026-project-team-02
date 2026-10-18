"""Explicit, keyless HTTP demo. Never selected as a fallback by the live server."""
from datetime import datetime, timedelta

from license_planner.catalog import get_certification
from license_planner.models import StudyBlock
from license_planner.tools import PlannerTools
from schedule_adjuster.tools import ScheduleAdjustmentTools

DEMO_SUMMARY = "통합 데모: HTTP API와 파일 저장을 시연하는 고정 규칙 계산입니다. AI 진단·학습 시간 추정 결과가 아닙니다."


class DemoPlannerAgent:
    """One 30-minute example per topic, in available windows before the exam."""

    def run(self, request):
        tools = PlannerTools(request)
        topics = get_certification(request.certification_id).topics
        blocks = []
        day = request.preparation_start.date()
        while day < request.exam_date.date() and len(blocks) < len(topics):
            for window in request.weekly_availability.for_weekday(day.weekday()):
                start = max(datetime.combine(day, window.start_time), request.preparation_start)
                limit = min(datetime.combine(day, window.end_time), request.exam_date)
                while start + timedelta(minutes=30) <= limit and len(blocks) < len(topics):
                    end = start + timedelta(minutes=30)
                    if not any(start < busy.end_datetime and busy.start_datetime < end
                               for busy in request.busy_periods):
                        blocks.append(StudyBlock(f"study-{len(blocks) + 1}", start, end, topics[len(blocks)]))
                    # Leave room to demonstrate moving a missed session.
                    start += timedelta(minutes=60)
            day += timedelta(days=1)
        if len(blocks) != len(topics):
            raise ValueError("통합 데모 일정을 만들 시간이 부족해요. 시험일을 늦추거나 공부 시간을 늘려 주세요.")
        tools.schedule = tuple(blocks)
        return tools, DEMO_SUMMARY


class DemoAdjustmentAgent:
    """Use the same production adjustment tools and persistence as the live server."""

    def adjust(self, request):
        tools = ScheduleAdjustmentTools(request)
        target = next(e for e in request.original_schedule if e.schedule_id == request.schedule_id)
        tools.call("inspect_schedule", {"schedule_id": target.schedule_id})
        if target.schedule_type == "study":
            if target.topic:
                tools.call("inspect_topic_schedule", {"topic": target.topic})
            tools.call("reschedule_failed_study", {"schedule_id": target.schedule_id})
        else:
            tools.call("remove_cancelled_external_schedule", {"schedule_id": target.schedule_id})
        return tools, DEMO_SUMMARY
