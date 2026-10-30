"""Deterministic demo boundaries; persistence is covered by the HTTP integration test."""
import unittest
from datetime import datetime, time

from demo_agents import DemoPlannerAgent
from license_planner.models import AvailabilityWindow, BusyPeriod, PlanRequest, WeeklyAvailability


class DemoPlannerTests(unittest.TestCase):
    def request(self, exam=datetime(2026, 11, 16)):
        return PlanRequest(
            self_assessment="demo", problem_results=(),
            preparation_start=datetime(2026, 10, 19), exam_date=exam,
            busy_periods=(BusyPeriod("external-1", datetime(2026, 10, 19, 18), datetime(2026, 10, 19, 19)),),
            weekly_availability=WeeklyAvailability(((AvailabilityWindow(time(18), time(22)),),) + ((),) * 6),
        )

    def test_demo_respects_windows_busy_periods_and_exam(self):
        request = self.request()
        tools, summary = DemoPlannerAgent().run(request)
        self.assertTrue(tools.schedule)
        self.assertIn("AI", summary)
        self.assertEqual(len({row.schedule_id for row in tools.schedule}), len(tools.schedule))
        for block in tools.schedule:
            self.assertEqual(0, block.start_datetime.weekday())
            self.assertGreaterEqual(block.start_datetime.hour, 18)
            self.assertLessEqual(block.end_datetime.hour, 22)
            self.assertLess(block.end_datetime, request.exam_date)
            self.assertFalse(any(block.start_datetime < busy.end_datetime and busy.start_datetime < block.end_datetime
                                 for busy in request.busy_periods))

    def test_short_period_reports_insufficient_capacity(self):
        with self.assertRaises(ValueError):
            DemoPlannerAgent().run(self.request(datetime(2026, 10, 19, 18, 15)))
