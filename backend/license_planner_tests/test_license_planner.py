import unittest
from datetime import datetime, timedelta

from license_planner.catalog import get_certification
from license_planner.models import BusyPeriod, PlanRequest, ProblemResult
from license_planner.scheduler import generate_schedule, summarize_topic_results
from license_planner.user_availability import parse_weekly_availability


class PlannerScheduleTests(unittest.TestCase):
    def setUp(self):
        self.certification = get_certification("computer_specialist_level_2")
        self.topic_statistics = summarize_topic_results((
            ProblemResult(1, "차트와 분석", 1, 0),
        ))

    def make_request(self, *, end="22:00", busy_periods=()):
        return PlanRequest(
            self_assessment=None,
            problem_results=(),
            preparation_start=datetime(2026, 10, 5, 18, 0),
            exam_date=datetime(2026, 10, 6, 23, 0),
            busy_periods=tuple(busy_periods),
            weekly_availability=parse_weekly_availability({
                "monday": [{"start": "18:00", "end": end}],
            }),
        )

    def test_schedule_accepts_a_study_block_longer_than_one_hour(self):
        request = self.make_request()

        blocks = generate_schedule(
            request,
            self.certification,
            self.topic_statistics,
            [{"date": "2026/10/05", "topic": "차트와 분석", "minutes": 150}],
        )

        self.assertEqual(len(blocks), 1)
        self.assertEqual(blocks[0].end_datetime - blocks[0].start_datetime,
                         timedelta(minutes=150))
        self.assertEqual(blocks[0].start_datetime, datetime(2026, 10, 5, 18, 0))
        self.assertEqual(blocks[0].end_datetime, datetime(2026, 10, 5, 20, 30))

    def test_schedule_splits_only_when_busy_time_interrupts_availability(self):
        request = self.make_request(
            end="21:00",
            busy_periods=(BusyPeriod(
                schedule_id="99",
                start_datetime=datetime(2026, 10, 5, 19, 0),
                end_datetime=datetime(2026, 10, 5, 19, 30),
                topic="work",
            ),),
        )

        blocks = generate_schedule(
            request,
            self.certification,
            self.topic_statistics,
            [{"date": "2026/10/05", "topic": "차트와 분석", "minutes": 120}],
        )

        self.assertEqual(len(blocks), 2)
        self.assertEqual(sum((block.end_datetime - block.start_datetime
                              for block in blocks), timedelta()), timedelta(minutes=120))
        self.assertEqual(blocks[0].end_datetime, datetime(2026, 10, 5, 19, 0))
        self.assertEqual(blocks[1].start_datetime, datetime(2026, 10, 5, 19, 30))

    def test_schedule_rejects_minutes_beyond_available_time(self):
        request = self.make_request()

        with self.assertRaisesRegex(ValueError, "NOT_ENOUGH_TIME"):
            generate_schedule(
                request,
                self.certification,
                self.topic_statistics,
                [{"date": "2026/10/05", "topic": "차트와 분석", "minutes": 241}],
            )


if __name__ == "__main__":
    unittest.main()
