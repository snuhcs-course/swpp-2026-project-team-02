import unittest
from datetime import datetime

from schedule_adjuster.csv_io import parse_schedule_rows, render_schedule_csv
from schedule_adjuster.remaining_study import RemainingSession, place_remaining_sessions
from study_availability import parse_availability_context

# Monday 2026/11/16 starts the plan; the week of 2026/11/23 is replaced by an empty week.
CONTEXT = parse_availability_context({
    "weekly_availability": {"monday": [{"start": "18:00", "duration_minutes": 120}],
                            "tuesday": [{"start": "18:00", "duration_minutes": 60}],
                            "wednesday": [{"start": "23:00", "duration_minutes": 120}]},
    "availability_overrides": {"2026/11/23": {}},
})
PREPARATION = datetime(2026, 11, 16, 0, 0)
EXAM = datetime(2026, 12, 1, 0, 0)
NOW = datetime(2026, 11, 16, 20, 30)


def row(schedule_id, start, end, topic=""):
    return {"schedule_id": schedule_id, "start_date": start, "end_date": end, "topic": topic}


ROWS = [
    row("study-1", "2026/11/16/18/00", "2026/11/16/19/00", "함수"),
    row("study-2", "2026/11/16/19/00", "2026/11/16/20/00", "차트"),
    row("external-3", "2026/11/17/18/00", "2026/11/17/18/30"),
    row("study-4", "2026/11/17/18/30", "2026/11/17/19/00", "함수"),
]


def entries(rows=ROWS):
    return parse_schedule_rows(rows)


def place(schedule, *instructions, now=NOW, replan_from=NOW, exam=EXAM, preparation=PREPARATION):
    return place_remaining_sessions(
        schedule, tuple(RemainingSession(*item) for item in instructions), availability=CONTEXT,
        preparation_start=preparation, exam_date=exam, replan_from=replan_from, now=now)


def as_rows(schedule):
    return {e.schedule_id: (e.start_datetime.strftime("%Y/%m/%d/%H/%M"),
                            e.end_datetime.strftime("%Y/%m/%d/%H/%M"), e.topic) for e in schedule}


class RemainingPlacementTests(unittest.TestCase):
    def test_sources_are_replaced_and_unselected_rows_are_protected(self):
        result = as_rows(place(entries(), ("study-1", 60, "study-1"), ("study-4", 20, "study-5")))
        self.assertEqual(result, {
            "study-2": ("2026/11/16/19/00", "2026/11/16/20/00", "차트"),
            "external-3": ("2026/11/17/18/00", "2026/11/17/18/30", ""),
            # The removed study-4 frees 18:30; study-1 (60 min) no longer fits there but its remainder does.
            "study-5": ("2026/11/17/18/30", "2026/11/17/18/50", "함수"),
            # Wednesday's overnight window, after Tuesday's single hour is too short.
            "study-1": ("2026/11/18/23/00", "2026/11/19/00/00", "함수"),
        })

    def test_repeated_topics_keep_their_own_topic_without_same_topic_ordering(self):
        rows = [row("study-1", "2026/11/16/18/00", "2026/11/16/18/30", "함수"),
                row("study-2", "2026/11/16/18/30", "2026/11/16/19/00", "함수")]
        result = as_rows(place(entries(rows), ("study-2", 30, "study-2"), ("study-1", 10, "study-3"),
                               now=datetime(2026, 11, 16, 17, 0), replan_from=datetime(2026, 11, 16, 17, 0)))
        self.assertEqual(result, {"study-3": ("2026/11/16/18/00", "2026/11/16/18/10", "함수"),
                                  "study-2": ("2026/11/16/18/10", "2026/11/16/18/40", "함수")})

    def test_original_chronological_order_decides_and_numeric_suffix_breaks_ties(self):
        # Identical intervals already overlap in the source; study-9 sorts before study-10 numerically.
        rows = [row("study-10", "2026/11/16/18/00", "2026/11/16/19/00", "늦은 번호"),
                row("study-9", "2026/11/16/18/00", "2026/11/16/19/00", "이른 번호")]
        early = datetime(2026, 11, 16, 19, 0)
        forward = place(entries(rows), ("study-10", 60, "study-10"), ("study-9", 60, "study-9"),
                        now=early, replan_from=early)
        backward = place(entries(rows), ("study-9", 60, "study-9"), ("study-10", 60, "study-10"),
                         now=early, replan_from=early)
        self.assertEqual(as_rows(forward), as_rows(backward))
        # Monday's window ends at 20:00, so the first hour-long fit is Tuesday.
        self.assertEqual(as_rows(forward)["study-9"][0], "2026/11/17/18/00")
        self.assertEqual(as_rows(forward)["study-10"][0], "2026/11/18/23/00")

    def test_lower_bound_is_the_latest_of_preparation_replan_from_and_next_minute(self):
        rows = [row("study-1", "2026/11/16/18/00", "2026/11/16/18/30", "함수")]
        cases = [
            (datetime(2026, 11, 16, 18, 10), datetime(2026, 11, 16, 18, 0), PREPARATION, "2026/11/16/18/11"),
            (datetime(2026, 11, 16, 17, 0), datetime(2026, 11, 16, 18, 20), PREPARATION, "2026/11/16/18/20"),
            (datetime(2026, 11, 16, 17, 0), datetime(2026, 11, 16, 17, 0), datetime(2026, 11, 16, 19, 0),
             "2026/11/16/19/00"),
        ]
        for now, replan_from, preparation, expected in cases:
            with self.subTest(expected=expected):
                result = place(entries(rows), ("study-1", 30, "study-1"), now=now, replan_from=replan_from,
                               preparation=preparation)
                self.assertEqual(as_rows(result)["study-1"][0], expected)

    def test_previous_day_overnight_window_is_used_after_midnight(self):
        after_midnight = datetime(2026, 11, 19, 0, 10)
        result = place(entries(), ("study-4", 30, "study-4"), now=after_midnight, replan_from=after_midnight)
        self.assertEqual(as_rows(result)["study-4"][:2], ("2026/11/19/00/11", "2026/11/19/00/41"))

    def test_empty_override_week_is_skipped_and_exam_end_is_inclusive(self):
        late = datetime(2026, 11, 19, 2, 0)
        result = place(entries(), ("study-2", 60, "study-2"), ("study-1", 60, "study-1"),
                       now=late, replan_from=late, exam=datetime(2026, 11, 30, 20, 0))
        rows = as_rows(result)
        self.assertEqual(rows["study-1"][:2], ("2026/11/30/18/00", "2026/11/30/19/00"))
        self.assertEqual(rows["study-2"][:2], ("2026/11/30/19/00", "2026/11/30/20/00"))

    def test_half_open_adjacency_to_protected_rows_is_allowed(self):
        rows = [row("external-1", "2026/11/17/18/00", "2026/11/17/18/30"),
                row("study-2", "2026/11/16/18/00", "2026/11/16/18/30", "함수")]
        tuesday = datetime(2026, 11, 17, 9, 0)
        result = as_rows(place(entries(rows), ("study-2", 30, "study-2"), now=tuesday, replan_from=tuesday))
        self.assertEqual(result["study-2"][:2], ("2026/11/17/18/30", "2026/11/17/19/00"))

    def test_existing_overlap_among_protected_rows_is_preserved(self):
        rows = [*ROWS, row("external-6", "2026/11/16/19/30", "2026/11/16/21/00")]
        result = as_rows(place(entries(rows), ("study-4", 30, "study-4")))
        self.assertEqual(result["study-2"], ("2026/11/16/19/00", "2026/11/16/20/00", "차트"))
        self.assertEqual(result["external-6"], ("2026/11/16/19/30", "2026/11/16/21/00", ""))
        self.assertEqual(result["study-4"][:2], ("2026/11/17/18/30", "2026/11/17/19/00"))

    def test_later_contribution_that_cannot_fit_rejects_the_whole_batch(self):
        schedule = entries()
        before = render_schedule_csv(schedule)
        # Only Wednesday 23:00 until the 00:20 exam remains; study-1 fits there, study-4 then does not.
        with self.assertRaisesRegex(ValueError, "NOT_ENOUGH_TIME") as raised:
            place(schedule, ("study-1", 60, "study-1"), ("study-4", 30, "study-4"),
                  now=datetime(2026, 11, 18, 0, 0), replan_from=datetime(2026, 11, 18, 0, 0),
                  exam=datetime(2026, 11, 19, 0, 20))
        self.assertIn("'study-4'", str(raised.exception))
        self.assertEqual(render_schedule_csv(schedule), before)

    def test_inconsistent_instructions_are_rejected(self):
        cases = {
            "empty": (),
            "unknown source": (("study-8", 10, "study-8"),),
            "external source": (("external-3", 10, "study-5"),),
            "duplicate source": (("study-1", 10, "study-5"), ("study-1", 10, "study-6")),
            "zero minutes": (("study-1", 0, "study-5"),),
            "more than scheduled": (("study-1", 61, "study-1"),),
            "kept ID with partial minutes": (("study-1", 20, "study-1"),),
            "fresh ID not above current": (("study-1", 20, "study-3"),),
            "fresh ID of another current row": (("study-1", 20, "study-2"),),
            "external replacement": (("study-1", 20, "external-9"),),
            "malformed replacement": (("study-1", 20, "study-x"),),
            "duplicate replacement": (("study-1", 20, "study-5"), ("study-4", 20, "study-5")),
        }
        for name, instructions in cases.items():
            with self.subTest(name), self.assertRaises(ValueError):
                place(entries(), *instructions)


if __name__ == "__main__":
    unittest.main()
