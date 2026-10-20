"""Task 3 standalone harness. Standard library only; never edits tracked team files."""
import argparse
import copy
import csv
import hashlib
import io
import json
import math
import os
import platform
import random
import statistics
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from dataclasses import asdict
from datetime import datetime, timedelta, timezone
from http.server import ThreadingHTTPServer
from pathlib import Path

COMMIT = 'd706ad2ea66aaed6faeebea1c21dc358ace0ecf2'
FMT = '%Y/%m/%d/%H/%M'
DAYS = ('monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday', 'sunday')
SECRET = ''

def clean(value):
    if isinstance(value, str):
        return value.replace(SECRET, '[REDACTED]') if SECRET else value
    if isinstance(value, dict):
        return {k: clean(v) for k, v in value.items()}
    if isinstance(value, list):
        return [clean(v) for v in value]
    return value

def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(clean(value), ensure_ascii=False, indent=2, allow_nan=False), encoding='utf-8')

def write_csv(path, rows, fields=None):
    if fields is None:
        fields = list(dict.fromkeys(k for row in rows for k in row))
    with path.open('w', encoding='utf-8-sig', newline='') as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        w.writerows(clean(rows))

def minutes(interval):
    return (interval[1] - interval[0]).total_seconds() / 60

def intersect(a, b):
    left, right = max(a[0], b[0]), min(a[1], b[1])
    return (left, right) if left < right else None

def merge(intervals):
    out = []
    for left, right in sorted(intervals):
        if left >= right:
            continue
        if out and left <= out[-1][1]:
            out[-1] = (out[-1][0], max(out[-1][1], right))
        else:
            out.append((left, right))
    return out

def subtract(window, busy):
    pieces = [window]
    for left, right in merge(busy):
        nxt = []
        for a, b in pieces:
            if right <= a or b <= left:
                nxt.append((a, b))
            else:
                if a < left:
                    nxt.append((a, left))
                if right < b:
                    nxt.append((right, b))
        pieces = nxt
    return pieces

def windows(request):
    start, end = (datetime.strptime(request[k], FMT) for k in ('preparation_start', 'exam_date'))
    busy = [(datetime.strptime(r['start_date'], FMT), datetime.strptime(r['end_date'], FMT))
            for r in request.get('busy_schedules', [])]
    current = start.date()
    out = []
    while current <= end.date():
        for index, w in enumerate(request['weekly_availability'].get(DAYS[current.weekday()], [])):
            a, b = (datetime.combine(current, datetime.strptime(w[k], '%H:%M').time()) for k in ('start', 'end'))
            clipped = intersect((a, b), (start, end))
            if clipped:
                pieces = subtract(clipped, busy)
                out.append((f'{current.isoformat()}-{index+1}', clipped, pieces))
        current += timedelta(days=1)
    return out

def pearson(x, y):
    if len(x) < 2:
        return None, 'fewer_than_two_assessed_topics'
    dx = [a-statistics.mean(x) for a in x]
    dy = [a-statistics.mean(y) for a in y]
    denominator = math.sqrt(sum(a*a for a in dx)*sum(b*b for b in dy))
    if denominator == 0:
        return None, 'zero_variance_scores_or_minutes'
    return sum(a*b for a, b in zip(dx, dy))/denominator, ''

def fixed_statistics(request, topic_map):
    values = {}
    for q in request['assessment_results']['results']:
        topic = topic_map[str(q['problem_id'])]
        row = values.setdefault(topic, {'topic': topic, 'possible_score': 0, 'earned_score': 0})
        row['possible_score'] += q['possible_score']
        row['earned_score'] += q['possible_score'] * q['is_correct']
    return list(values.values())

def evaluate(request, schedules, topic_stats):
    """Independent evaluator: no scheduler placement helpers are reused."""
    study, invalid = [], 0
    for row in schedules:
        if not row['schedule_id'].startswith('study-'):
            continue
        a, b = datetime.strptime(row['start_date'], FMT), datetime.strptime(row['end_date'], FMT)
        if a >= b:
            invalid += 1
        else:
            study.append((row['topic'], (a, b)))
    intervals = [iv for _, iv in study]
    union = merge(intervals)
    window_rows = []
    free_all = []
    for name, clipped, pieces in windows(request):
        free_all.extend(pieces)
        available = sum(minutes(p) for p in pieces)
        used = sum(minutes(hit) for p in pieces for iv in union if (hit := intersect(p, iv)))
        window_rows.append({'window': name, 'available_minutes': available, 'study_minutes': used,
                            'ratio': used/available if available else None})
    ratios = [r['ratio'] for r in window_rows if r['ratio'] is not None]
    free_union = merge(free_all)
    total = sum(minutes(iv) for iv in intervals)
    in_free = sum(minutes(hit) for iv in union for p in free_union if (hit := intersect(iv, p)))
    available = sum(minutes(p) for p in free_union)
    busy = [(datetime.strptime(r['start_date'], FMT), datetime.strptime(r['end_date'], FMT))
            for r in request.get('busy_schedules', [])]
    topic_minutes = {}
    for topic, iv in study:
        topic_minutes[topic] = topic_minutes.get(topic, 0) + minutes(iv)
    topic_rows = []
    for row in topic_stats:
        if row['possible_score'] > 0:
            topic_rows.append({**row, 'accuracy': row['earned_score']/row['possible_score'],
                               'study_minutes': topic_minutes.get(row['topic'], 0)})
    corr, reason = pearson([r['accuracy'] for r in topic_rows], [r['study_minutes'] for r in topic_rows])
    outside = 0
    for iv in intervals:
        covered = sum(minutes(hit) for p in free_union if (hit := intersect(iv, p)))
        outside += covered < minutes(iv)-1e-8
    result = {
        'time_ratio_population_std': statistics.pstdev(ratios) if ratios else None,
        'window_count': len(ratios), 'zero_capacity_window_count': len(window_rows)-len(ratios),
        'score_minutes_pearson': corr, 'pearson_na_reason': reason,
        'total_study_minutes': total, 'available_minutes': available,
        'utilization': in_free/available if available else None,
        'study_overlap_pairs': sum(intersect(a, b) is not None for i, a in enumerate(intervals) for b in intervals[i+1:]),
        'busy_conflict_pairs': sum(intersect(a, b) is not None for a in intervals for b in busy),
        'outside_free_window_blocks': outside, 'invalid_duration_blocks': invalid,
        'duplicate_schedule_ids': len(schedules)-len({r['schedule_id'] for r in schedules}),
        'empty_study_plan': len(study) == 0,
        'unassessed_study_topics': sorted(set(topic_minutes)-{r['topic'] for r in topic_stats}),
    }
    return result, window_rows, topic_rows

def http_json(url, payload=None):
    data = json.dumps(payload, ensure_ascii=False).encode() if payload is not None else None
    req = urllib.request.Request(url, data=data, headers={'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(req, timeout=310) as response:
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read())

def run(args):
    global SECRET
    repo = args.repo.resolve()
    backend = repo/'backend'
    head = subprocess.check_output(['git', '-C', str(repo), 'rev-parse', 'HEAD'], text=True).strip()
    if head != COMMIT:
        raise SystemExit('Wrong commit; use the documented detached checkout.')
    tracked = subprocess.check_output(['git', '-C', str(repo), 'diff', 'HEAD', '--name-only'], text=True).strip()
    if tracked:
        raise SystemExit('Tracked changes found. Use a clean separate checkout.')
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=False)  # never overwrite another run
    sys.path.insert(0, str(backend))
    import api_server
    from api_service import build_plan_request
    from license_planner import settings
    from license_planner.agent import GEMINI_MODEL, GeminiToolAgent
    from license_planner.tools import PlannerTools
    SECRET = settings.GEMINI_API_KEY
    cases = json.loads(args.cases.read_text(encoding='utf-8'))
    if args.case_id:
        cases = [c for c in cases if c['case_id'] in args.case_id]
        if not cases:
            raise SystemExit('No matching cases')
    for c in cases:
        build_plan_request(c['request'], require_assessment=True)
    write_json(out/'manifest.json', {
        'target_branch': 'iteration-1-demo', 'commit': head, 'mode': args.mode,
        'started_at_kst': datetime.now(timezone(timedelta(hours=9))).isoformat(),
        'python': sys.version, 'platform': platform.platform(), 'cpu': platform.processor(),
        'model': GEMINI_MODEL, 'key_present': bool(SECRET), 'cases': len(cases),
        'repeats_planned': args.repeats, 'seed': args.seed,
        'case_file_sha256': hashlib.sha256(args.cases.read_bytes()).hexdigest(),
        'timing_scope': 'local HTTP round trip including Gemini, tools, CSV saving; excludes server startup',
        'instrumentation': 'wrapper calls original tools and model method unchanged; captures accepted build statistics',
    })
    write_json(out/'cases_used.json', cases)
    if args.mode == 'offline':
        logs = []
        tempdir = out/'temp'
        tempdir.mkdir()
        env = dict(os.environ, TMP=str(tempdir), TEMP=str(tempdir), PYTHONIOENCODING='utf-8')
        for suite in ('license_planner_tests', 'schedule_adjuster_tests'):
            p = subprocess.run([sys.executable, '-m', 'unittest', 'discover', '-s', suite, '-v'],
                               cwd=backend, env=env, capture_output=True, text=True, encoding='utf-8', errors='replace')
            (out/f'{suite}.log').write_text(clean(p.stdout+p.stderr), encoding='utf-8')
            logs.append({'suite': suite, 'exit_code': p.returncode})
        validations = []
        for c in cases:
            req = build_plan_request(c['request'], require_assessment=True)
            tool = PlannerTools(req)
            mapping = [{'problem_id': q.problem_id, 'topics': [{'topic': c['fixed_topic_map'][str(q.problem_id)], 'weight': 1}]}
                       for q in req.assessment_items]
            result = tool.call('analyze_exam_results', {'problem_topics': mapping})
            if not isinstance(result, dict):
                raise ValueError(result)
            validations.append({'case_id': c['case_id'], 'question_count': len(req.assessment_items),
                                'input_valid': True, 'fixed_mapping_valid': True,
                                'available_minutes': sum(sum(minutes(p) for p in pieces) for _, _, pieces in windows(c['request']))})
        write_csv(out/'input_checks.csv', validations)
        write_json(out/'offline_summary.json', {'suites': logs, 'valid_inputs': len(validations),
                    'gemini_generation_attempts': 0, 'live_metrics': None,
                    'reason': 'GEMINI_API_KEY absent' if not SECRET else 'offline mode explicitly selected'})
    captures, model_calls = [], []
    original_call, original_ask = PlannerTools.call, GeminiToolAgent._ask_model
    def observed_call(self, name, arguments):
        t = time.perf_counter()
        result = original_call(self, name, arguments)
        captures.append({'tool': name, 'arguments': copy.deepcopy(arguments), 'result': copy.deepcopy(result),
                         'elapsed_seconds': time.perf_counter()-t,
                         'accepted_build_statistics': [asdict(s) for s in self.topic_statistics]
                            if name == 'build_study_schedule' and isinstance(result, dict) else None})
        return result
    def observed_ask(self, contents, schemas):
        t = time.perf_counter()
        record = {'model': self.model}
        try:
            result = original_ask(self, contents, schemas)
            record['success'] = True
            return result
        except Exception as e:
            record.update(success=False, error=clean(str(e)))
            raise
        finally:
            record['elapsed_seconds'] = time.perf_counter()-t
            model_calls.append(record)
    PlannerTools.call, GeminiToolAgent._ask_model = observed_call, observed_ask
    handler = api_server.make_handler(api_server.DEFAULT_API_CONFIG)
    class QuietHandler(handler):
        def log_message(self, *_):
            pass
    server = ThreadingHTTPServer(('127.0.0.1', 0), QuietHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    base = f'http://127.0.0.1:{server.server_port}/api/v1'
    records = []
    try:
        status, health = http_json(base+'/health')
        write_json(out/'health.json', {'http_status': status, 'body': health})
        if args.mode == 'offline':
            if not SECRET:
                t = time.perf_counter()
                status, body = http_json(base+'/study-plans', cases[0]['request'])
                write_json(out/'missing_key_http_probe.json', {'kind': 'offline_local_HTTP_probe',
                    'http_status': status, 'body': body, 'elapsed_seconds': time.perf_counter()-t,
                    'gemini_request_count': len(model_calls)})
            return
        stop_reason = 'GEMINI_API_KEY absent' if not SECRET else ''
        jobs = [(c, r) for c in cases for r in range(1, args.repeats+1)]
        random.Random(args.seed).shuffle(jobs)
        for order, (c, r) in enumerate(jobs, 1):
            row = {'case_id': c['case_id'], 'run_id': r, 'order': order, 'commit': head,
                   'model': GEMINI_MODEL, 'mode': 'live', 'question_count': len(c['request']['assessment_results']['results'])}
            if stop_reason:
                row.update(outcome='skipped', error=stop_reason)
                records.append(row)
                continue
            captures.clear()
            model_calls.clear()
            t = time.perf_counter()
            response = {}
            try:
                status, response = http_json(base+'/study-plans', c['request'])
                row.update(http_status=status, elapsed_seconds=time.perf_counter()-t,
                           model_request_count=len(model_calls), outcome='success' if status == 200 else 'failure')
                row['error'] = response.get('error', {}).get('message', '')
            except Exception as e:
                row.update(outcome='failure', elapsed_seconds=time.perf_counter()-t, error=clean(str(e)))
            stem = f"{c['case_id']}-run{r:02d}"
            write_json(out/f'{stem}-raw.json', {'request': c['request'], 'response': response,
                                              'tool_observations': captures, 'model_calls': model_calls, 'record': row})
            if row['outcome'] == 'success':
                (out/f'{stem}-schedule.csv').write_text(clean(response['schedule_csv']), encoding='utf-8')
                accepted = [x for x in captures if x['accepted_build_statistics'] is not None]
                if not accepted:
                    row.update(outcome='measurement_error', error='No accepted build observation')
                else:
                    try:
                        stats = accepted[-1]['accepted_build_statistics']
                        metrics, wr, tr = evaluate(c['request'], response['schedules'], stats)
                        row.update({k: v for k, v in metrics.items() if k != 'unassessed_study_topics'})
                        _, _, fixed = evaluate(c['request'], response['schedules'], fixed_statistics(c['request'], c['fixed_topic_map']))
                        fixed_corr, fixed_reason = pearson([s['accuracy'] for s in fixed], [s['study_minutes'] for s in fixed])
                        row.update(fixed_score_minutes_pearson=fixed_corr, fixed_pearson_na_reason=fixed_reason)
                        row['constraint_valid'] = not any(metrics[k] for k in ('study_overlap_pairs', 'busy_conflict_pairs', 'outside_free_window_blocks', 'invalid_duration_blocks', 'duplicate_schedule_ids', 'unassessed_study_topics', 'empty_study_plan'))
                        write_json(out/f'{stem}-metrics.json', metrics)
                        write_csv(out/f'{stem}-windows.csv', wr)
                        write_csv(out/f'{stem}-topics.csv', tr)
                        write_csv(out/f'{stem}-fixed-topics.csv', fixed)
                    except Exception as e:
                        row.update(outcome='measurement_error', error=clean(str(e)))
            if any(s in row.get('error', '').lower() for s in ('http 401', 'http 403', 'http 404', 'http 429', 'api key', 'quota', 'not found')):
                stop_reason = row['error']  # no repeated billing/quota errors
            records.append(row)
            write_csv(out/'runs.csv', records)
            print(c['case_id'], r, row['outcome'], flush=True)
        write_csv(out/'runs.csv', records)
        summaries = []
        for c in cases:
            group = [r for r in records if r['case_id'] == c['case_id']]
            attempted = [r for r in group if r['outcome'] != 'skipped']
            success = [r for r in attempted if r['outcome'] == 'success']
            summary = {'case_id': c['case_id'], 'planned': len(group), 'attempted': len(attempted),
                       'success': len(success), 'skipped': len(group)-len(attempted),
                       'constraint_valid_success': sum(r.get('constraint_valid') is True for r in success),
                       'constraint_invalid_success': sum(r.get('constraint_valid') is False for r in success),
                       'http_or_transport_failure_rate': sum(r.get('http_status') != 200 for r in attempted)/len(attempted) if attempted else None,
                       'measurement_errors': sum(r['outcome'] == 'measurement_error' for r in attempted)}
            for metric in ('elapsed_seconds', 'time_ratio_population_std', 'score_minutes_pearson', 'fixed_score_minutes_pearson', 'utilization'):
                vals = [r[metric] for r in success if r.get(metric) is not None]
                summary[metric+'_n'] = len(vals)
                summary[metric+'_mean'] = statistics.mean(vals) if vals else None
                summary[metric+'_repeat_std'] = statistics.pstdev(vals) if vals else None
                summary[metric+'_min'] = min(vals) if vals else None
                summary[metric+'_max'] = max(vals) if vals else None
            durations = [r['elapsed_seconds'] for r in attempted if 'elapsed_seconds' in r]
            summary['all_attempt_latency_mean'] = statistics.mean(durations) if durations else None
            summary['over_android_120_seconds'] = sum(x > 120 for x in durations)
            summaries.append(summary)
        write_csv(out/'summary.csv', summaries)
        write_json(out/'execution_summary.json', {'generation_attempts': sum(r['outcome'] != 'skipped' for r in records),
                                                 'stop_reason': stop_reason or None})
    finally:
        server.shutdown()
        server.server_close()
        thread.join()
        PlannerTools.call, GeminiToolAgent._ask_model = original_call, original_ask
        final_diff = subprocess.check_output(['git', '-C', str(repo), 'diff', 'HEAD', '--name-only'], text=True)
        write_json(out/'repository_check.json', {'tracked_changes_after': final_diff.strip(), 'pushed': False})

if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--repo', type=Path, required=True)
    p.add_argument('--cases', type=Path, default=Path(__file__).with_name('cases.json'))
    p.add_argument('--out', type=Path, required=True)
    p.add_argument('--mode', choices=['offline', 'live'], default='offline')
    p.add_argument('--repeats', type=int, default=5)
    p.add_argument('--seed', type=int, default=20261014)
    p.add_argument('--case-id', action='append')
    args = p.parse_args()
    if args.repeats < 1:
        p.error('--repeats must be positive')
    run(args)
