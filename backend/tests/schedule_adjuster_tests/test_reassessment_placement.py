from datetime import datetime, timedelta
import unittest

from schedule_adjuster.reassessment_placement import place, PlacementSearchLimit


def intervals(lengths, adjacent=False):
    result = []
    cursor = datetime(2026, 12, 3, 18)
    for i, n in enumerate(lengths):
        end = cursor + timedelta(minutes=n)
        result.append({"schedule_id": f"study-{i+1}", "start_date": cursor.strftime("%Y/%m/%d/%H/%M"),
                       "end_date": end.strftime("%Y/%m/%d/%H/%M"), "minutes": n})
        cursor = end if adjacent else cursor + timedelta(days=1)
    return result


class ReviewPlacementTests(unittest.TestCase):
    def test_exact_budgets_fit_without_changing_interval_totals(self):
        result = place(intervals([40, 20]), ["A", "B"], [35, 25], [35, 25])
        self.assertIsNotNone(result)
        self.assertEqual([sum(p["minutes"] for p in r["pieces"]) for r in result], [40, 20])
        self.assertTrue(all(p["minutes"] >= 15 for r in result for p in r["pieces"]))
        self.assertEqual(sum(p["minutes"] for r in result for p in r["pieces"] if p["topic"] == "A"), 35)

    def test_total_capacity_alone_does_not_prove_placement(self):
        self.assertIsNone(place(intervals([40, 20]), ["A", "B"], [30, 30], [30, 30]))

    def test_continuous_review_can_cross_only_adjacent_original_boundaries(self):
        self.assertIsNotNone(place(intervals([40, 20], adjacent=True), ["A"], [60], [60], single=["A"], review_runs={"A": 60}))
        self.assertIsNone(place(intervals([40, 20]), ["A"], [60], [60], single=["A"], review_runs={"A": 60}))

    def test_lower_bounds_allow_free_time_but_still_need_a_full_review_run(self):
        self.assertIsNotNone(place(intervals([40, 20]), ["A", "B"], [20, 20], [60, 60], review_runs={"A": 20, "B": 20}))
        self.assertIsNone(place(intervals([40, 20]), ["A", "B"], [35, 25], [35, 25], review_runs={"A": 30}))

    def test_search_limit_is_unknown_not_a_proof_of_impossibility(self):
        with self.assertRaises(PlacementSearchLimit):
            place(intervals([60]), ["A"], [60], [60], limit=0)
