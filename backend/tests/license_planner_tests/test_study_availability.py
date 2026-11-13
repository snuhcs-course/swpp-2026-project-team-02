import json
import tempfile
import unittest
from datetime import date, datetime, time
from pathlib import Path

from study_availability import (
    AvailabilityWindow, context_payload, load_availability_profile, parse_availability_context,
    save_availability_profile,
)


def monday_windows(*windows):
    return parse_availability_context({"monday": list(windows)})


class WindowFormatTests(unittest.TestCase):
    def test_legacy_end_and_duration_forms_normalize_to_the_same_window(self):
        legacy = monday_windows({"start": "18:00", "end": "20:00"})
        canonical = monday_windows({"start": "18:00", "duration_minutes": 120})
        dual = monday_windows({"start": "23:00", "duration_minutes": 90, "end": "00:30"})

        self.assertEqual(legacy, canonical)
        self.assertEqual(legacy.base.for_weekday(0), (AvailabilityWindow(time(18), 120),))
        self.assertEqual(dual.base.for_weekday(0)[0].duration_minutes, 90)
        self.assertEqual(dual.base.for_weekday(0)[0].end_time, time(0, 30))

    def test_dual_fields_must_agree(self):
        with self.assertRaisesRegex(ValueError, "end does not match"):
            monday_windows({"start": "23:00", "duration_minutes": 90, "end": "00:15"})

    def test_legacy_end_only_window_must_end_after_start_on_the_same_day(self):
        for end in ("18:00", "17:00", "00:30"):
            with self.subTest(end=end), self.assertRaises(ValueError):
                monday_windows({"start": "18:00", "end": end})

    def test_duration_boundaries_and_types(self):
        for duration in (1, 1439):
            with self.subTest(duration=duration):
                window = monday_windows({"start": "00:00", "duration_minutes": duration}).base.for_weekday(0)[0]
                self.assertEqual(window.duration_minutes, duration)
        for duration in (0, 1440, -1, True, 1.5, None, "60"):
            with self.subTest(duration=duration), self.assertRaisesRegex(ValueError, "duration_minutes"):
                monday_windows({"start": "00:00", "duration_minutes": duration})

    def test_malformed_times_weekdays_and_keys_are_rejected(self):
        invalid = (
            {"monday": [{"start": "24:00", "duration_minutes": 30}]},
            {"monday": [{"start": "9:00", "duration_minutes": 30}]},
            {"monday": [{"start": "09:00", "end": "24:00"}]},
            {"monday": [{"start": "09:00"}]},
            {"monday": [{"start": "09:00", "duration_minutes": 30, "label": "x"}]},
            {"funday": []},
            {"monday": {"start": "09:00", "duration_minutes": 30}},
            {"weekly_availability": {}, "extra": 1},
        )
        for value in invalid:
            with self.subTest(value=value), self.assertRaises(ValueError):
                parse_availability_context(value)

    def test_window_ending_exactly_at_midnight_stays_owned_by_its_start_day(self):
        context = monday_windows({"start": "22:00", "duration_minutes": 120})
        window = context.base.for_weekday(0)[0]

        self.assertEqual(window.end_time, time(0, 0))
        self.assertEqual(context.windows_for(date(2026, 11, 23)),
                         ((datetime(2026, 11, 23, 22), datetime(2026, 11, 24, 0)),))
        self.assertEqual(context.windows_for(date(2026, 11, 24)), ())
        self.assertEqual(context_payload(context, include_end=True)["weekly_availability"]["monday"],
                         [{"start": "22:00", "duration_minutes": 120, "end": "00:00"}])

    def test_omitted_weekdays_normalize_and_windows_sort(self):
        context = parse_availability_context({"saturday": [
            {"start": "14:00", "duration_minutes": 30}, {"start": "09:00", "duration_minutes": 60}]})
        payload = context_payload(context, include_end=False)["weekly_availability"]

        self.assertEqual(list(payload), ["monday", "tuesday", "wednesday", "thursday",
                                         "friday", "saturday", "sunday"])
        self.assertEqual(payload["saturday"], [{"start": "09:00", "duration_minutes": 60},
                                               {"start": "14:00", "duration_minutes": 30}])


class OverlapInvariantTests(unittest.TestCase):
    def test_same_day_overlap_is_rejected_and_adjacency_accepted(self):
        with self.assertRaisesRegex(ValueError, "overlap"):
            monday_windows({"start": "18:00", "duration_minutes": 120},
                           {"start": "19:00", "duration_minutes": 120})
        adjacent = monday_windows({"start": "18:00", "duration_minutes": 60},
                                  {"start": "19:00", "duration_minutes": 60})
        self.assertEqual(len(adjacent.base.for_weekday(0)), 2)

    def test_next_day_spill_overlap_is_rejected_until_adjacent(self):
        spill = {"start": "23:00", "duration_minutes": 90}
        for first, second in (("monday", "tuesday"), ("sunday", "monday")):
            with self.subTest(first=first), self.assertRaisesRegex(ValueError, "overlap"):
                parse_availability_context({first: [spill], second: [{"start": "00:15", "duration_minutes": 60}]})
            with self.subTest(first=first, adjacent=True):
                context = parse_availability_context(
                    {first: [spill], second: [{"start": "00:30", "duration_minutes": 60}]})
                self.assertTrue(context.has_windows())

    def test_override_week_collides_with_prior_base_sunday_spill(self):
        with self.assertRaisesRegex(ValueError, "overlap"):
            parse_availability_context({
                "weekly_availability": {"sunday": [{"start": "23:00", "duration_minutes": 90}]},
                "availability_overrides": {"2026/11/30": {"monday": [{"start": "00:15", "duration_minutes": 45}]}},
            })

    def test_override_sunday_spill_collides_with_following_week(self):
        cases = (
            # Override spill into a base week.
            {"weekly_availability": {"monday": [{"start": "00:00", "duration_minutes": 60}]},
             "availability_overrides": {"2026/11/23": {"sunday": [{"start": "23:30", "duration_minutes": 60}]}}},
            # Override spill into a neighbouring override week.
            {"weekly_availability": {},
             "availability_overrides": {
                 "2026/11/23": {"sunday": [{"start": "23:30", "duration_minutes": 60}]},
                 "2026/11/30": {"monday": [{"start": "00:00", "duration_minutes": 60}]}}},
        )
        for value in cases:
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, "overlap"):
                parse_availability_context(value)

    def test_unshown_later_window_in_a_list_is_still_checked(self):
        with self.assertRaisesRegex(ValueError, "overlap"):
            parse_availability_context({"tuesday": [
                {"start": "08:00", "duration_minutes": 30},
                {"start": "12:00", "duration_minutes": 60},
                {"start": "12:30", "duration_minutes": 60},
            ]})

    def test_invalid_override_keys_and_values(self):
        for overrides in ({"2026/11/24": {}}, {"2026/11/2": {}}, {"2026-11-23": {}},
                          {"2026/02/30": {}}, {"2026/11/23": None}, {"2026/11/23": []}):
            with self.subTest(overrides=overrides), self.assertRaises(ValueError):
                parse_availability_context({"weekly_availability": {}, "availability_overrides": overrides})
        with self.assertRaises(ValueError):
            parse_availability_context({"weekly_availability": {}, "availability_overrides": None})


class ProfileFileTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.path = Path(self.directory.name) / "user_availability.json"

    def tearDown(self):
        self.directory.cleanup()

    def test_failed_saves_leave_existing_bytes_unchanged(self):
        self.path.write_text(json.dumps({"weekly_availability": {
            "wednesday": [{"start": "19:00", "end": "20:00"}]}}), encoding="utf-8")
        before = self.path.read_bytes()
        invalid = (
            {"monday": [{"start": "23:00", "duration_minutes": 90}],
             "tuesday": [{"start": "00:15", "duration_minutes": 60}]},
            {"monday": [{"start": "23:00", "duration_minutes": 90, "end": "00:15"}]},
            {"monday": [{"start": "23:00", "duration_minutes": 0}]},
            {"weekly_availability": {"sunday": [{"start": "23:00", "duration_minutes": 90}]},
             "availability_overrides": {"2026/11/30": {"monday": [{"start": "00:15", "duration_minutes": 45}]}}},
        )
        for value in invalid:
            with self.subTest(value=value), self.assertRaises(ValueError):
                save_availability_profile(value, self.path)
            self.assertEqual(self.path.read_bytes(), before)
        self.assertEqual(list(self.path.parent.iterdir()), [self.path])

    def test_base_save_that_collides_with_a_retained_override_is_rejected(self):
        save_availability_profile({"weekly_availability": {}, "availability_overrides": {
            "2026/11/30": {"monday": [{"start": "00:15", "duration_minutes": 45}]}}}, self.path)
        before = self.path.read_bytes()

        with self.assertRaisesRegex(ValueError, "overlap"):
            save_availability_profile({"sunday": [{"start": "23:00", "duration_minutes": 90}]}, self.path)
        self.assertEqual(self.path.read_bytes(), before)

    def test_legacy_profile_reads_do_not_rewrite_the_file(self):
        self.path.write_text('{"weekly_availability": {"monday": [{"start": "18:00", "end": "20:00"}]}}',
                             encoding="utf-8")
        before = self.path.read_bytes()

        first = load_availability_profile(self.path)
        second = load_availability_profile(self.path)

        self.assertEqual(first, second)
        self.assertEqual(first.base.for_weekday(0), (AvailabilityWindow(time(18), 120),))
        self.assertEqual(self.path.read_bytes(), before)

    def test_malformed_profile_is_reported_without_fallback(self):
        for content in ("{not json", "[]", '{"monday": [{"start": "18:00", "end": "17:00"}]}',
                        '{"weekly_availability": {}, "availability_overrides": {"2026/11/24": {}}}'):
            with self.subTest(content=content):
                self.path.write_text(content, encoding="utf-8")
                with self.assertRaisesRegex(ValueError, "saved"):
                    load_availability_profile(self.path)
                self.assertEqual(self.path.read_text(encoding="utf-8"), content)

    def test_missing_profile_is_absent(self):
        self.assertIsNone(load_availability_profile(self.path))


if __name__ == "__main__":
    unittest.main()
