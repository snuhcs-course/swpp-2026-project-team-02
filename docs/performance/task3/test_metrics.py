"""Synthetic arithmetic/interval tests only, never Gemini performance results."""
import copy
import math
import unittest
from datetime import datetime
from task3_measure import evaluate, pearson, subtract, minutes

def request():
    return {'preparation_start': '2026/10/05/18/00', 'exam_date': '2026/10/08/00/00',
            'weekly_availability': {'monday': [{'start': '18:00', 'end': '20:00'}],
                                    'tuesday': [{'start': '18:00', 'end': '19:00'}],
                                    'wednesday': [{'start': '18:00', 'end': '20:00'}]}, 'busy_schedules': []}

def block(i, start, end, topic='T'):
    return {'schedule_id': f'study-{i}', 'start_date': start, 'end_date': end, 'topic': topic}

class MetricTests(unittest.TestCase):
    def test_population_std_includes_unused_window(self):
        result, rows, _ = evaluate(request(), [block(1, '2026/10/05/18/00', '2026/10/05/19/00'),
                                              block(2, '2026/10/06/18/00', '2026/10/06/18/30')], [])
        self.assertEqual([r['ratio'] for r in rows], [.5, .5, 0])
        self.assertAlmostEqual(result['time_ratio_population_std'], math.sqrt(1/18))
        self.assertAlmostEqual(result['utilization'], .3)

    def test_pearson_negative_and_undefined(self):
        self.assertAlmostEqual(pearson([0, .5, 1], [90, 60, 30])[0], -1)
        self.assertIsNone(pearson([1, 1, 1], [90, 60, 30])[0])
        self.assertIsNone(pearson([0, .5, 1], [30, 30, 30])[0])
        self.assertIsNone(pearson([0], [60])[0])

    def test_busy_union_not_double_subtracted(self):
        dt = lambda t: datetime.strptime('2026/10/05/'+t, '%Y/%m/%d/%H/%M')
        pieces = subtract((dt('18/00'), dt('21/00')),
                          [(dt('18/30'), dt('19/30')), (dt('19/00'), dt('20/00'))])
        self.assertEqual(sum(minutes(p) for p in pieces), 90)

    def test_clip_start_exam_boundaries(self):
        req = request()
        req.update(preparation_start='2026/10/05/18/30', exam_date='2026/10/05/19/30')
        result, _, _ = evaluate(req, [block(1, '2026/10/05/18/30', '2026/10/05/19/30')], [])
        self.assertEqual(result['available_minutes'], 60)
        self.assertEqual(result['utilization'], 1)
        self.assertEqual(result['time_ratio_population_std'], 0)

    def test_conflicts_and_outside_and_duplicate(self):
        req = request()
        req['busy_schedules'] = [{'start_date': '2026/10/05/19/00', 'end_date': '2026/10/05/20/00'}]
        result, _, _ = evaluate(req, [block(1, '2026/10/05/18/30', '2026/10/05/19/30'),
                                     block(1, '2026/10/05/19/00', '2026/10/05/20/30')], [])
        self.assertEqual(result['study_overlap_pairs'], 1)
        self.assertEqual(result['busy_conflict_pairs'], 2)
        self.assertEqual(result['outside_free_window_blocks'], 2)
        self.assertEqual(result['duplicate_schedule_ids'], 1)

    def test_zero_capacity_excluded_and_missing_study_zero(self):
        req = request()
        req['busy_schedules'] = [{'start_date': '2026/10/05/18/00', 'end_date': '2026/10/05/20/00'}]
        result, rows, _ = evaluate(req, [], [])
        self.assertEqual(result['window_count'], 2)
        self.assertEqual(result['zero_capacity_window_count'], 1)
        self.assertEqual(result['time_ratio_population_std'], 0)
        self.assertEqual(result['utilization'], 0)
        self.assertTrue(result['empty_study_plan'])
        self.assertIsNone(rows[0]['ratio'])

    def test_assessed_topic_without_study_counts_zero(self):
        stats = [{'topic': 'T', 'possible_score': 1, 'earned_score': 0},
                 {'topic': 'U', 'possible_score': 1, 'earned_score': 1}]
        result, _, rows = evaluate(request(), [block(1, '2026/10/05/18/00', '2026/10/05/19/00')], stats)
        self.assertEqual(rows[1]['study_minutes'], 0)
        self.assertAlmostEqual(result['score_minutes_pearson'], -1)

    def test_half_open_touching_is_no_conflict(self):
        result, _, _ = evaluate(request(), [block(1, '2026/10/05/18/00', '2026/10/05/19/00'),
                                            block(2, '2026/10/05/19/00', '2026/10/05/20/00')], [])
        self.assertEqual(result['study_overlap_pairs'], 0)

    def test_no_available_time_is_na(self):
        req = request()
        req['weekly_availability'] = {}
        result, _, _ = evaluate(req, [], [])
        self.assertIsNone(result['time_ratio_population_std'])
        self.assertIsNone(result['utilization'])

if __name__ == '__main__':
    unittest.main(verbosity=2)
