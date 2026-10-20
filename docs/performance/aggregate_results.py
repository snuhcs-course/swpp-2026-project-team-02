"""Recompute supplied Task 3 results without API calls. Python standard library only."""
import argparse,csv,json,math,statistics,sys,hashlib,collections
from pathlib import Path

def readcsv(path):
 with path.open(encoding='utf-8-sig',newline='') as f:return list(csv.DictReader(f))
def writecsv(path,rows):
 fields=list(dict.fromkeys(k for r in rows for k in r))
 with path.open('w',encoding='utf-8-sig',newline='') as f:
  w=csv.DictWriter(f,fieldnames=fields);w.writeheader();w.writerows(rows)
def quant(values):
 v=sorted(values)
 return {'n':len(v),'mean':statistics.mean(v) if v else None,'median':statistics.median(v) if v else None,'p95_nearest_rank':v[math.ceil(.95*len(v))-1] if v else None,'max':max(v) if v else None}
def same(a,b):
 if a is None:return b in (None,'')
 if isinstance(a,bool):return str(a)==str(b)
 if isinstance(a,(float,int)):
  try:return math.isclose(a,float(b),abs_tol=1e-9,rel_tol=1e-9)
  except (TypeError,ValueError):return False
 return a==b
def main():
 base=Path(__file__).resolve().parent
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--logs',type=Path,default=base/'original');p.add_argument('--out',type=Path,required=True);args=p.parse_args()
 if args.out.exists():p.error('Output path exists; choose a new path.')
 manifest=readcsv(base/'case_manifest.csv')
 sys.path.insert(0,str(base/'task3'))
 import task3_measure as m
 cases=json.loads((base/'task3/cases.json').read_text(encoding='utf-8'));byid={c['case_id']:c for c in cases}
 rows=[];issues=[];checks=collections.Counter();model_seconds=0.;tool_seconds=0.;corr_diffs=[];failures=[];case_summaries=[]
 for item in manifest:
  folder=args.logs/item['folder'];rs=readcsv(folder/'runs.csv');used=json.loads((folder/'cases_used.json').read_text(encoding='utf-8'))
  if used!=[byid[item['case_id']]]:issues.append(item['folder']+': cases_used mismatch')
  if len(rs)!=int(item['repeats']):issues.append(item['folder']+': run count mismatch')
  if sorted(int(r['run_id']) for r in rs)!=list(range(1,int(item['repeats'])+1)):issues.append(item['folder']+': duplicate/missing run_id')
  metadata=json.loads((folder/'manifest.json').read_text(encoding='utf-8'))
  input_hash=hashlib.sha256((base/'task3/cases.json').read_bytes()).hexdigest()
  if metadata['case_file_sha256']!=input_hash:issues.append(item['folder']+': input hash mismatch')
  for r in rs:
   r.update(folder=item['folder'],group=item['group']);stem=f"{r['case_id']}-run{int(r['run_id']):02d}"
   raw=json.loads((folder/(stem+'-raw.json')).read_text(encoding='utf-8'));checks['raw_files']+=1
   for k in ('case_id','run_id','order','http_status','model_request_count','outcome'):
    if str(raw['record'].get(k))!=str(r.get(k)):issues.append(stem+': raw record '+k)
   if r['case_id']!=item['case_id']:issues.append(stem+': unexpected case_id')
   if raw['request']!=byid[r['case_id']]['request']:issues.append(stem+': raw request mismatch')
   if len(raw['model_calls'])!=int(r['model_request_count']):issues.append(stem+': model count mismatch')
   model_seconds+=sum(x['elapsed_seconds'] for x in raw['model_calls'])
   tool_seconds+=sum(x['elapsed_seconds'] for x in raw['tool_observations'])
   checks['tool_calls']+=len(raw['tool_observations'])
   if r['outcome']=='success':
    accepted=[x for x in raw['tool_observations'] if x.get('accepted_build_statistics') is not None]
    metrics,_,_=m.evaluate(raw['request'],raw['response']['schedules'],accepted[-1]['accepted_build_statistics'])
    saved=json.loads((folder/(stem+'-metrics.json')).read_text(encoding='utf-8'))
    for k,v in metrics.items():
     if not same(v,saved.get(k)):issues.append(stem+': saved metrics '+k)
     if k!='unassessed_study_topics' and not same(v,r.get(k)):issues.append(stem+': runs metrics '+k)
    _,_,fixed=m.evaluate(raw['request'],raw['response']['schedules'],m.fixed_statistics(raw['request'],byid[r['case_id']]['fixed_topic_map']))
    corr,reason=m.pearson([x['accuracy'] for x in fixed],[x['study_minutes'] for x in fixed])
    if not same(corr,r.get('fixed_score_minutes_pearson')):issues.append(stem+': fixed correlation')
    cv=not any(metrics[k] for k in ('study_overlap_pairs','busy_conflict_pairs','outside_free_window_blocks','invalid_duration_blocks','duplicate_schedule_ids','unassessed_study_topics','empty_study_plan'))
    if not same(cv,r['constraint_valid']):issues.append(stem+': constraint_valid')
    if corr is not None and metrics['score_minutes_pearson'] is not None and not math.isclose(corr,metrics['score_minutes_pearson'],abs_tol=1e-9):corr_diffs.append({'folder':r['folder'],'run_id':r['run_id'],'agent_r':metrics['score_minutes_pearson'],'fixed_r':corr})
    checks['recomputed_success']+=1
   elif r['outcome']=='failure':
    errors=[x['result'] for x in raw['tool_observations'] if isinstance(x['result'],str)]
    accepted=[x for x in raw['tool_observations'] if x.get('accepted_build_statistics') is not None]
    failures.append({'folder':r['folder'],'case_id':r['case_id'],'run_id':r['run_id'],'http_status':r['http_status'],'elapsed_seconds':r['elapsed_seconds'],'model_request_count':r['model_request_count'],'error_class':r['error'].split(';')[0],'accepted_build_before_failure':bool(accepted),'tool_errors_json':json.dumps(errors,ensure_ascii=False)})
   else:issues.append(stem+': unexpected outcome '+r['outcome'])
  rows+=rs
  ok=[r for r in rs if r['outcome']=='success']
  summary={'folder':item['folder'],'group':item['group'],'case_id':item['case_id'],'runs':len(rs),'success':len(ok),'failure':sum(r['outcome']=='failure' for r in rs),'constraint_valid_success':sum(r.get('constraint_valid')=='True' for r in ok)}
  for col in ('time_ratio_population_std','score_minutes_pearson','fixed_score_minutes_pearson','utilization','elapsed_seconds','model_request_count'):
   vals=[float(r[col]) for r in ok if r.get(col)!='' and r.get(col) is not None];summary[col+'_n']=len(vals);summary[col+'_mean']=statistics.mean(vals) if vals else None
  summary['all_attempt_latency_mean']=statistics.mean(float(r['elapsed_seconds']) for r in rs)
  case_summaries.append(summary)
 group_summaries=[]
 for group in dict.fromkeys(r['group'] for r in rows):
  gr=[r for r in rows if r['group']==group];n=len(gr);ok=sum(r['outcome']=='success' for r in gr)
  group_summaries.append({'group':group,'runs':n,'success':ok,'failure':n-ok,'success_rate':ok/n})
 result={'row_count':len(rows),'outcomes':dict(collections.Counter(r['outcome'] for r in rows)),'checks':dict(checks),'issues':issues,'timing':{key:quant([float(r['elapsed_seconds']) for r in rows if key=='all' or r['outcome']==key]) for key in ('all','success','failure')},'model_request_count':sum(int(r['model_request_count']) for r in rows),'model_seconds_sum':model_seconds,'tool_seconds_sum':tool_seconds,'http_seconds_sum':sum(float(r['elapsed_seconds']) for r in rows),'fixed_corr_difference_runs':len(corr_diffs),'input_sha256':input_hash,'p95_method':'nearest rank: sorted_values[ceil(0.95*n)-1]'}
 args.out.mkdir(parents=True)
 for name,data in [('runs_combined.csv',rows),('case_summary.csv',case_summaries),('group_summary.csv',group_summaries),('failure_details.csv',failures),('correlation_differences.csv',corr_diffs)]:writecsv(args.out/name,data)
 (args.out/'verification.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
 print(json.dumps({k:result[k] for k in ('row_count','outcomes','checks','issues','model_request_count')},ensure_ascii=False,indent=2))
 if issues:raise SystemExit(1)
if __name__=='__main__':main()
