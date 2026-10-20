"""Reproduce deterministic limitations with explicit scripted tool arguments, not AI output."""
import argparse
import json
import sys
from datetime import datetime
from pathlib import Path
from task3_measure import evaluate, fixed_statistics, write_json, write_csv

def main():
    p = argparse.ArgumentParser()
    p.add_argument('--repo', type=Path, required=True)
    p.add_argument('--out', type=Path, required=True)
    args = p.parse_args()
    args.out.mkdir(parents=True, exist_ok=False)
    sys.path.insert(0, str(args.repo.resolve()/'backend'))
    from api_service import build_plan_request
    from license_planner.tools import PlannerTools
    from schedule_adjuster.models import ScheduleEntry
    from schedule_adjuster.availability import parse_weekly_availability
    from schedule_adjuster.schedule_logic import move_study_entry
    cases = json.loads(Path(__file__).with_name('cases.json').read_text(encoding='utf-8'))
    c = next(c for c in cases if c['case_id'] == 'controlled5_weak_functions')
    req = build_plan_request(c['request'], require_assessment=True)
    mapping = [{'problem_id': q.problem_id, 'topics': [{'topic': c['fixed_topic_map'][str(q.problem_id)], 'weight': 1}]}
               for q in req.assessment_items]
    topics = ['스프레드시트 기본', '함수와 데이터 관리', '차트와 분석']
    scenarios = {
        'concentrated': [{'date': '2026/10/19', 'topic': topic, 'minutes': 30} for topic in topics],
        'distributed': [{'date': day, 'topic': topic, 'minutes': 5} for day in
                        ['2026/10/19', '2026/10/21', '2026/10/23', '2026/10/26', '2026/10/28', '2026/10/30'] for topic in topics],
        'strong_topic_more_time': [{'date': '2026/10/19', 'topic': topic, 'minutes': m}
                                  for topic, m in zip(topics, [40, 10, 40])],
        'over_capacity': [{'date': '2026/10/19', 'topic': topic, 'minutes': 41} for topic in topics],
    }
    rows = []
    for label, plan in scenarios.items():
        tools = PlannerTools(req)
        analysis = tools.call('analyze_exam_results', {'problem_topics': mapping})
        result = tools.call('build_study_schedule', {'daily_topic_minutes': plan})
        record = {'kind': 'OFFLINE_SCRIPTED_TOOL_PROBE_NOT_GEMINI', 'scenario': label,
                  'request': c['request'], 'mapping': mapping, 'scripted_minutes': plan,
                  'analysis': analysis, 'result': result}
        row = {'kind': record['kind'], 'scenario': label, 'accepted': isinstance(result, dict)}
        if isinstance(result, dict):
            metrics, wr, tr = evaluate(c['request'], result['schedule'], analysis['topic_statistics'])
            record['metrics'] = metrics
            row.update({k: v for k, v in metrics.items() if k != 'unassessed_study_topics'})
            write_csv(args.out/f'{label}-windows.csv', wr)
            write_csv(args.out/f'{label}-topics.csv', tr)
        else:
            row['error'] = result
        write_json(args.out/f'{label}.json', record)
        rows.append(row)
    write_csv(args.out/'scripted_probe_metrics.csv', rows)
    availability = parse_weekly_availability(c['request']['weekly_availability'])
    entries = (ScheduleEntry('study-1', datetime(2026, 10, 19, 18), datetime(2026, 10, 19, 19), topics[1]),
               ScheduleEntry('study-2', datetime(2026, 10, 21, 18), datetime(2026, 10, 21, 19), topics[1]))
    moved = move_study_entry(entries, 'study-1', availability)
    target = next(e for e in moved if e.schedule_id == 'study-1')
    exam = datetime(2026, 10, 21, 19)
    write_json(args.out/'task2_exam_deadline_probe.json', {
        'kind': 'OFFLINE_SCRIPTED_TASK2_PROBE_NOT_GEMINI', 'exam_date_for_evaluation': exam.isoformat(),
        'target_id': target.schedule_id, 'moved_start': target.start_datetime.isoformat(),
        'moved_end': target.end_datetime.isoformat(), 'past_exam': target.end_datetime > exam,
        'cause': 'AdjustmentRequest and move_study_entry accept no exam deadline'})
    long_entry = (ScheduleEntry('study-3', datetime(2026, 10, 19, 18), datetime(2026, 10, 19, 20, 30), topics[1]),)
    try:
        move_study_entry(long_entry, 'study-3', availability)
        outcome = 'unexpected_success'
    except ValueError as e:
        outcome = str(e)
    write_json(args.out/'task2_long_block_probe.json', {
        'kind': 'OFFLINE_SCRIPTED_TASK2_PROBE_NOT_GEMINI', 'session_minutes': 150,
        'max_window_minutes': 120, 'outcome': outcome,
        'note': '총 가용 시간 충분 여부와 별개로 한 창에 원래 길이를 유지해야 함; 부분 수행/분할 인터페이스 필요'})
    print('Scripted scheduler and Task 2 probes recorded; no Gemini calls.')

if __name__ == '__main__':
    main()
