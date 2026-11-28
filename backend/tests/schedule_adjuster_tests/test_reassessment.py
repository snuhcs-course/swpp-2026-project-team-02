"""Shared Android/Python parity cases and reassessment boundary validation."""
import json
import random
import unittest
from collections import Counter
from datetime import datetime, timedelta
from pathlib import Path

from schedule_adjuster.csv_io import parse_schedule_rows
from schedule_adjuster.reassessment import ReassessmentPreviewExpired, resplit_study
from schedule_adjuster.models import ScheduleEntry


def date(value):
    return datetime.strptime(value, "%Y/%m/%d/%H/%M")


CASES = json.loads((Path(__file__).parent / "fixtures/reassessment_parity.json").read_text(encoding="utf-8"))["cases"]


class ReassessmentTests(unittest.TestCase):
    def test_captured_real_generated_plan_matches_both_live_api_results(self):
        from schedule_adjuster.reassessment_agent import build_context, validate_proposal
        capture = json.loads((Path(__file__).parent / "fixtures/generated_plan_reassessment.json").read_text(encoding="utf-8"))
        original = parse_schedule_rows(capture["generated"]["schedules"])
        next_id = max(int(r.schedule_id[6:]) for r in original if r.schedule_type == "study") + 1
        cutoff = date(capture["replan_from"])
        for profile in capture["profiles"]:
            with self.subTest(profile=profile["label"]):
                results = [(q["topic"], q["possible_score"], profile["answers"][str(q["id"])] == q["correct_answer"])
                           for q in capture["questions"]]
                reports = {key: {"status": value["status"].lower(), "actual_minutes": value["actualMinutes"]}
                           for key, value in capture["check_ins"].items()}
                context = build_context(original, set(), results, replan_from=cutoff, now=cutoff,
                                        first_new_study_id=f"study-{next_id}", reports=reports)
                actual = validate_proposal(context, profile["preview"]["tool_calls"][0]["arguments"])
                expected = parse_schedule_rows(profile["preview"]["schedules"])
                self.assertEqual(actual, expected)
                self.assertEqual(profile["preview"]["schedules"], profile["saved"]["schedules"])
                for key in capture["check_ins"]:
                    self.assertIn(next(r for r in original if r.schedule_id == key), actual)
                for row in actual:
                    if row.schedule_type == "study":
                        self.assertGreaterEqual((row.end_datetime-row.start_datetime).total_seconds()/60, 15)

    def test_no_new_short_sessions_and_exact_interval_coverage(self):
        rng = random.Random(28)
        for _ in range(200):
            durations = [rng.randint(15, 180) for _ in range(8)] + [7]
            start = datetime(2026, 12, 3, 19)
            rows = tuple(ScheduleEntry(f"study-{i+1}", start + timedelta(days=i),
                                      start + timedelta(days=i, minutes=duration), "old")
                         for i, duration in enumerate(durations))
            answers = [(f"topic-{i}", 1.0, rng.choice([True, False])) for i in range(8)]
            revised = self.run_case(CASES[0], schedule=rows, keep=set(), results=answers,
                                    first_new_study_id="study-10")
            self.assertEqual(revised, self.run_case(CASES[0], schedule=rows, keep=set(),
                             results=list(reversed(answers)), first_new_study_id="study-10"))
            self.assertEqual({r.topic for r in revised}, {r[0] for r in answers})
            totals = Counter()
            for row in revised:
                totals[row.topic] += int((row.end_datetime-row.start_datetime).total_seconds()/60)
            for weak, _, correct in answers:
                if not correct:
                    for strong, _, other_correct in answers:
                        if other_correct:
                            self.assertGreaterEqual(totals[weak], totals[strong])
            for source, duration in zip(rows, durations):
                inside = [r for r in revised if source.start_datetime <= r.start_datetime < source.end_datetime]
                self.assertEqual(inside[0].start_datetime, source.start_datetime)
                self.assertEqual(inside[-1].end_datetime, source.end_datetime)
                for a, b in zip(inside, inside[1:]):
                    self.assertEqual(a.end_datetime, b.start_datetime)
                self.assertTrue(all((r.end_datetime-r.start_datetime).total_seconds()/60 >= min(15, duration)
                                    for r in inside))
                if duration < 15:
                    self.assertEqual(len(inside), 1)

    def test_two_answer_sets_change_which_topics_receive_more_time(self):
        start = datetime(2026, 12, 3, 19)
        rows = tuple(ScheduleEntry(f"study-{i+1}", start + timedelta(days=i),
                                  start + timedelta(days=i, minutes=60), "old") for i in range(8))
        totals = []
        for weak in ({"topic-0", "topic-1", "topic-2", "topic-3"},
                     {"topic-4", "topic-5", "topic-6", "topic-7"}):
            revised = self.run_case(CASES[0], schedule=rows, keep=set(),
                                    results=[(f"topic-{i}", 1.0, f"topic-{i}" not in weak) for i in range(8)],
                                    first_new_study_id="study-9")
            minutes = Counter()
            for row in revised:
                minutes[row.topic] += int((row.end_datetime-row.start_datetime).total_seconds()/60)
            self.assertEqual(sum(minutes.values()), 480)
            self.assertGreater(min(minutes[t] for t in weak), max(m for t, m in minutes.items() if t not in weak))
            totals.append(minutes)
        for topic in totals[0]:
            if topic < "topic-4":
                self.assertGreater(totals[0][topic], totals[1][topic])
            else:
                self.assertLess(totals[0][topic], totals[1][topic])

    def test_infeasible_topic_coverage_does_not_create_short_sessions(self):
        start = datetime(2026, 12, 3, 19)
        with self.assertRaisesRegex(ValueError, "NOT_ENOUGH_TIME"):
            self.run_case(CASES[0], schedule=(ScheduleEntry("study-1", start, start + timedelta(minutes=29), "old"),),
                          keep=set(), results=[("A", 1.0, False), ("B", 1.0, True)], first_new_study_id="study-2")

    def test_valid_exact_targets_are_not_rounded_unnecessarily(self):
        start = datetime(2026, 12, 3, 19)
        revised = self.run_case(CASES[0], schedule=(ScheduleEntry("study-1", start, start + timedelta(minutes=60), "old"),),
                                keep=set(), results=[("A", 1.0, False), ("B", 1.0, True)], first_new_study_id="study-2")
        self.assertEqual([(r.topic, int((r.end_datetime-r.start_datetime).total_seconds()/60)) for r in revised],
                         [("A", 40), ("B", 20)])

    def run_case(self, case, **overrides):
        options = dict(schedule=parse_schedule_rows(case["rows"]), keep=set(case["keep_schedule_ids"]),
                       results=[(row["topic"], row["possible_score"], row["is_correct"]) for row in case["results"]],
                       replan_from=date(case["replan_from"]), now=date(case["now"]),
                       first_new_study_id=case["first_new_study_id"])
        return resplit_study(**(options | overrides))

    def test_shared_android_parity_cases(self):
        for case in CASES:
            with self.subTest(case=case["name"]):
                if "expected_rows" in case:
                    expected = {row.schedule_id: row for row in parse_schedule_rows(case["expected_rows"])}
                    self.assertEqual({row.schedule_id: row for row in self.run_case(case)}, expected)
                elif case["expected_code"] == "REASSESSMENT_PREVIEW_EXPIRED":
                    with self.assertRaises(ReassessmentPreviewExpired):
                        self.run_case(case)
                else:
                    with self.assertRaisesRegex(ValueError, "NOT_ENOUGH_TIME" if case["expected_code"] == "NOT_ENOUGH_TIME" else "no future"):
                        self.run_case(case)

    def test_fresh_ids_cannot_collide_with_current_rows(self):
        for value in ("study-1", "study-0", "external-9", "study-05", "study--1", "study-1.5"):
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, "first_new_study_id"):
                self.run_case(CASES[0], first_new_study_id=value)

    def test_only_future_study_rows_may_be_reported(self):
        for keep in ({"study-1"}, {"external-1"}, {"study-999"}):
            with self.subTest(keep=keep), self.assertRaisesRegex(ValueError, "keep_schedule_ids"):
                self.run_case(CASES[0], keep=keep)

    def test_unreported_row_at_current_minute_is_still_revisable(self):
        result = self.run_case(CASES[0], now=date("2026/12/03/19/00"))
        self.assertTrue(result)


if __name__ == "__main__":
    unittest.main()
