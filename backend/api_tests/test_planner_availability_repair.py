"""Regression tests for repairing model-selected dates outside user availability."""
import unittest
from datetime import datetime, time

from license_planner.models import AvailabilityWindow, PlanRequest, WeeklyAvailability
from license_planner.scheduler import move_plan_to_available_dates


class PlannerAvailabilityRepairTests(unittest.TestCase):
    def test_moves_an_unavailable_friday_allocation_to_monday_capacity(self):
        request = PlanRequest(
            self_assessment=None,
            problem_results=(),
            preparation_start=datetime(2026, 10, 30),  # Friday
            exam_date=datetime(2026, 11, 3),
            weekly_availability=WeeklyAvailability(
                ((AvailabilityWindow(time(18), time(21)),),) + ((),) * 6
            ),
        )

        repaired = move_plan_to_available_dates(request, [
            {"date": "2026/10/30", "topic": "함수와 데이터 관리", "minutes": 90},
        ])

        self.assertEqual(repaired, [
            {"date": "2026/11/02", "topic": "함수와 데이터 관리", "minutes": 90},
        ])


if __name__ == "__main__":
    unittest.main()
