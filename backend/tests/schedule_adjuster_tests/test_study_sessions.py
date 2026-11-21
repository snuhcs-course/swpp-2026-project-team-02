import unittest
from datetime import datetime

from license_planner.scheduler import MIN_STUDY_SESSION_MINUTES as PLANNER_MINIMUM
from schedule_adjuster.csv_io import parse_schedule_rows, render_schedule_csv
from schedule_adjuster.remaining_study import (
    RemainingSession, place_remaining_sessions, validate_remaining_destinations,
)
from schedule_adjuster.schedule_logic import move_study_entry, place_study_entry_manually
from schedule_adjuster.study_sessions import MIN_STUDY_SESSION_MINUTES, keeps_study_sessions, study_sessions
from study_availability import parse_availability_context

MONDAY_EVENING = parse_availability_context({"weekly_availability": {
    "monday": [{"start": "17:00", "end": "21:00"}], "tuesday": [{"start": "18:00", "end": "20:00"}]}})
PREPARATION = datetime(2026, 11, 16, 0, 0)
EXAM = datetime(2026, 11, 18, 0, 0)


def row(schedule_id, start, end, topic=""):
    return {"schedule_id": schedule_id, "start_date": start, "end_date": end, "topic": topic}


def as_rows(schedule):
    return {e.schedule_id: (e.start_datetime.strftime("%Y/%m/%d/%H/%M"),
                            e.end_datetime.strftime("%Y/%m/%d/%H/%M")) for e in schedule}


def session_minutes(schedule):
    return [int((end - start).total_seconds() // 60) for start, end in study_sessions(schedule)]


def place(schedule, *instructions, now=datetime(2026, 11, 16, 16, 0), exam=EXAM,
          availability=MONDAY_EVENING):
    return place_remaining_sessions(
        schedule, tuple(RemainingSession(*item) for item in instructions), availability=availability,
        preparation_start=PREPARATION, exam_date=exam, replan_from=now, now=now)


class SessionHelperTests(unittest.TestCase):
    def test_planner_and_adjuster_use_the_same_minimum(self):
        self.assertEqual(MIN_STUDY_SESSION_MINUTES, 30)
        self.assertEqual(PLANNER_MINIMUM, MIN_STUDY_SESSION_MINUTES)

    def test_only_back_to_back_study_rows_form_one_session(self):
        schedule = parse_schedule_rows([
            row("study-1", "2026/11/16/18/00", "2026/11/16/18/20", "A"),
            row("study-2", "2026/11/16/18/20", "2026/11/16/18/35", "B"),
            row("external-3", "2026/11/16/18/35", "2026/11/16/18/45"),
            row("study-4", "2026/11/16/18/45", "2026/11/16/19/00", "A"),
            row("study-5", "2026/11/16/19/01", "2026/11/16/19/31", "A"),
        ])
        self.assertEqual(session_minutes(schedule), [35, 15, 30])

    def test_pre_existing_short_sessions_do_not_block_a_candidate(self):
        original = parse_schedule_rows([row("study-1", "2026/11/16/18/00", "2026/11/16/18/15", "A"),
                                        row("study-2", "2026/11/17/18/00", "2026/11/17/18/30", "B")])
        self.assertTrue(keeps_study_sessions(original, {"study-2"}, original, datetime(2026, 11, 16, 0, 0)))


class AutomaticMoveSessionTests(unittest.TestCase):
    def test_long_enough_target_moves_to_an_empty_interval(self):
        schedule = parse_schedule_rows([
            row("study-1", "2026/11/16/17/00", "2026/11/16/18/00", "A"),
            row("external-2", "2026/11/16/18/00", "2026/11/16/19/00"),
            row("external-3", "2026/11/16/20/00", "2026/11/16/21/00"),
        ])
        moved = move_study_entry(schedule, "study-1", MONDAY_EVENING, EXAM)

        self.assertEqual(as_rows(moved)["study-1"], ("2026/11/16/19/00", "2026/11/16/20/00"))
        self.assertEqual(session_minutes(moved), [60])

    def test_short_target_skips_an_isolated_gap_and_joins_existing_study(self):
        schedule = parse_schedule_rows([
            row("study-1", "2026/11/16/17/00", "2026/11/16/17/20", "A"),
            row("external-2", "2026/11/16/17/20", "2026/11/16/18/00"),
            row("external-3", "2026/11/16/18/20", "2026/11/16/19/00"),
            row("study-4", "2026/11/16/19/00", "2026/11/16/19/15", "B"),
            row("external-5", "2026/11/16/19/40", "2026/11/16/21/00"),
        ])
        moved = move_study_entry(schedule, "study-1", MONDAY_EVENING, EXAM)

        # 18:00-18:20 would be a lone 20-minute session; after study-4 it forms 35 minutes.
        self.assertEqual(as_rows(moved)["study-1"], ("2026/11/16/19/15", "2026/11/16/19/35"))
        self.assertEqual(session_minutes(moved), [35])

    def test_no_position_with_a_long_enough_session_is_not_enough_time(self):
        rows = [row("study-1", "2026/11/16/17/00", "2026/11/16/17/20", "A"),
                row("external-2", "2026/11/16/17/40", "2026/11/16/20/40"),
                row("external-3", "2026/11/17/18/20", "2026/11/17/20/00")]
        schedule = parse_schedule_rows(rows)
        before = render_schedule_csv(schedule)

        with self.assertRaisesRegex(ValueError, "^NOT_ENOUGH_TIME"):
            move_study_entry(schedule, "study-1", MONDAY_EVENING, EXAM)
        self.assertEqual(render_schedule_csv(schedule), before)

    def test_shortened_past_session_does_not_block_the_move(self):
        schedule = parse_schedule_rows([
            row("study-1", "2026/11/16/18/00", "2026/11/16/18/20", "A"),
            row("study-2", "2026/11/16/18/20", "2026/11/16/18/35", "B"),
            row("external-3", "2026/11/16/18/35", "2026/11/16/21/00"),
            row("study-4", "2026/11/17/18/00", "2026/11/17/18/30", "C"),
        ])
        moved = move_study_entry(schedule, "study-1", MONDAY_EVENING, EXAM)

        self.assertEqual(as_rows(moved)["study-1"], ("2026/11/17/18/30", "2026/11/17/18/50"))
        self.assertEqual(session_minutes(moved), [15, 50])

    def test_conflict_only_movement_also_keeps_sessions(self):
        schedule = parse_schedule_rows([
            row("study-1", "2026/11/16/18/00", "2026/11/16/18/20", "A"),
            row("external-2", "2026/11/16/18/20", "2026/11/16/18/40"),
            row("study-3", "2026/11/16/20/00", "2026/11/16/20/15", "B"),
        ])
        moved = move_study_entry(schedule, "study-1", None, EXAM)

        self.assertEqual(as_rows(moved)["study-1"], ("2026/11/16/20/15", "2026/11/16/20/35"))

    def test_manual_move_may_form_a_short_session(self):
        schedule = parse_schedule_rows([row("study-1", "2026/11/16/18/00", "2026/11/16/18/15", "A")])
        moved = place_study_entry_manually(
            schedule, "study-1", datetime(2026, 11, 17, 18, 0), availability=MONDAY_EVENING,
            preparation_start=PREPARATION, exam_date=EXAM, now=datetime(2026, 11, 16, 19, 0),
            confirm_outside_availability=False)

        self.assertEqual(session_minutes(moved), [15])


class RemainingPlacementSessionTests(unittest.TestCase):
    def test_short_contribution_may_start_where_an_earlier_placement_ends(self):
        schedule = parse_schedule_rows([
            row("study-1", "2026/11/16/13/00", "2026/11/16/14/00", "A"),
            row("study-2", "2026/11/16/14/00", "2026/11/16/14/20", "B"),
        ])
        placed = place(schedule, ("study-1", 60, "study-1"), ("study-2", 20, "study-2"))

        self.assertEqual(as_rows(placed), {"study-1": ("2026/11/16/17/00", "2026/11/16/18/00"),
                                           "study-2": ("2026/11/16/18/00", "2026/11/16/18/20")})
        self.assertEqual(session_minutes(placed), [80])

    def test_placement_that_leaves_a_future_row_alone_is_rejected(self):
        schedule = parse_schedule_rows([
            row("study-3", "2026/11/16/17/00", "2026/11/16/17/30", "C"),
            row("study-1", "2026/11/16/18/00", "2026/11/16/18/20", "A"),
            row("study-2", "2026/11/16/18/20", "2026/11/16/18/35", "B"),
        ])
        placed = place(schedule, ("study-1", 20, "study-1"))

        # 17:30 would join study-3 but leave study-2 as a lone 15-minute session.
        self.assertEqual(as_rows(placed)["study-1"], ("2026/11/16/18/35", "2026/11/16/18/55"))
        self.assertEqual(session_minutes(placed), [30, 35])

    def test_batch_with_no_passing_candidate_is_not_enough_time(self):
        rows = [row("study-3", "2026/11/16/17/00", "2026/11/16/17/30", "C"),
                row("study-1", "2026/11/16/18/00", "2026/11/16/18/20", "A"),
                row("study-2", "2026/11/16/18/20", "2026/11/16/18/35", "B"),
                row("external-4", "2026/11/16/18/35", "2026/11/16/21/00")]
        schedule = parse_schedule_rows(rows)
        before = render_schedule_csv(schedule)

        with self.assertRaisesRegex(ValueError, "^NOT_ENOUGH_TIME: 10 remaining minutes of 'study-1'"):
            place(schedule, ("study-1", 10, "study-5"), exam=datetime(2026, 11, 16, 21, 0))
        self.assertEqual(render_schedule_csv(schedule), before)

    def test_lone_short_contribution_does_not_fit(self):
        schedule = parse_schedule_rows([row("study-1", "2026/11/16/13/00", "2026/11/16/13/20", "A")])

        with self.assertRaisesRegex(ValueError, "^NOT_ENOUGH_TIME"):
            place(schedule, ("study-1", 20, "study-1"))

    def test_shortened_past_session_does_not_block_placement(self):
        schedule = parse_schedule_rows([
            row("study-1", "2026/11/16/17/00", "2026/11/16/17/20", "A"),
            row("study-2", "2026/11/16/17/20", "2026/11/16/17/35", "B"),
            row("study-3", "2026/11/16/19/00", "2026/11/16/19/30", "C"),
        ])
        placed = place(schedule, ("study-1", 20, "study-1"), now=datetime(2026, 11, 16, 18, 0))

        self.assertEqual(as_rows(placed)["study-1"], ("2026/11/16/19/30", "2026/11/16/19/50"))
        self.assertEqual(session_minutes(placed), [15, 50])

    def test_confirmed_destination_may_form_a_short_session(self):
        schedule = parse_schedule_rows([row("study-1", "2026/11/16/13/00", "2026/11/16/13/30", "A")])
        placed = validate_remaining_destinations(
            schedule, (RemainingSession("study-1", 15, "study-2", datetime(2026, 11, 16, 18, 0)),),
            availability=MONDAY_EVENING, preparation_start=PREPARATION, exam_date=EXAM,
            replan_from=datetime(2026, 11, 16, 16, 0), now=datetime(2026, 11, 16, 16, 0))

        self.assertEqual(as_rows(placed), {"study-2": ("2026/11/16/18/00", "2026/11/16/18/15")})
        self.assertEqual(session_minutes(placed), [15])


if __name__ == "__main__":
    unittest.main()
