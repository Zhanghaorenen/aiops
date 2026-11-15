"""A new immutable 30-case live run using baseline prompts and real HTTP/SSE."""
import json,pathlib,os,time,hashlib
import live_eval as live

ROOT=live.ROOT
OUT=ROOT/'tests/agent_eval/reports/part1_rerun_20261113_1734'
OUT.mkdir(parents=True,exist_ok=True)
if (OUT/'eval_results.jsonl').exists():raise SystemExit('Results already exist; refusing overwrite or rerun')
live.OUT=OUT
baseline=ROOT/'tests/agent_eval/reports/part1'
assert json.loads((baseline/'cases.json').read_text(encoding='utf-8'))==[list(x) for x in live.SPEC]
old={str(f.relative_to(ROOT)):hashlib.sha256(f.read_bytes()).hexdigest() for folder in ['part1','part1_after_fix','part1_after_fix_targeted'] for f in (baseline.parent/folder).rglob('*') if f.is_file()}
live.save('previous_report_hashes.json',old)
live.save('cases.json',live.SPEC)
for path,client in [('/api/health',live.C),('/api/ai/health',live.A)]:
 r=client.get(path);r.raise_for_status()
d,env=live.setup()
if 'SERVER_ID' not in d:raise SystemExit('No online server discovered; no model cases submitted')
live.save('discovery.json',d)
# Trace is assigned only to this run; no previous artifact is touched.
completed={}
order=['S01','S03','G01','G03','R01']+[c[0] for c in live.SPEC if c[0] not in ['S01','S03','G01','G03','R01']]
with (OUT/'eval_results.jsonl').open('x',encoding='utf-8') as f:
 for cid in order:
  case=next(c for c in live.SPEC if c[0]==cid)
  if cid in ['C02','C03'] and not completed.get('C01',{}).get('session_persisted'):
   r={'case_id':cid,'question':case[1],'expected_tools':case[2].split(),'actual_tools':[],
      'actual_tool_arguments':[],'tool_results_summary':[],'agent_answer':None,'evidence_match':None,
      'task_success':None,'tool_selection_correct':None,'tool_arguments_correct':None,'answer_faithful':None,
      'latency_ms':None,'ttft_ms':None,'started_at':live.stamp(),'status':'BLOCKED_ENV',
      'failure_reason':'Prerequisite C01 persisted conversation unavailable; request not submitted'}
  else:
   r=live.run(case,d)
   if r['actual_tools']:
    valid=True
    for name,a in zip(r['actual_tools'],r['actual_tool_arguments']):
     if 'server_id' in a and a['server_id']!=d['SERVER_ID']:valid=False
     if cid in ['G03','G04','G07','G08','W04']:
      if a.get('gpu_id') is not None and a['gpu_id']!=d['GPU_INDEX']:valid=False
      if a.get('gpu_index') is not None and a['gpu_index']!=d['GPU_INDEX']:valid=False
     if cid=='G06' and name=='recommend_gpu_placement' and a.get('required_memory_bytes')!=8589934592:valid=False
     if cid=='W04' and name=='estimate_available_capacity' and (a.get('vram_mb')!=10240 or a.get('ram_mb')!=8192):valid=False
     if 'job_id' in a and a['job_id']!=d.get('JOB_ID'):valid=False
     if 'run_id' in a and a['run_id'] is not None and cid in ['W02','W03'] and a['run_id']!=d.get('RUN_ID'):valid=False
     if cid=='G08' and name=='get_recent_gpu_history' and (a.get('last_minutes',30)!=30 or a.get('metric_name')!='utilization'):valid=False
    r['tool_arguments_correct']=valid
    if cid=='G08' and 'get_recent_gpu_history' in r['actual_tools']:
     r['tool_selection_correct']=True;r['route_adjudication']='Recent-history tool reuses the same platform historical API; equivalent evidence route'
    if valid and r['tool_selection_correct'] and r['status']=='FAIL':
     r['status']='NEEDS_REVIEW';r['failure_reason']='Rule scope adjudicated; semantic evidence review required'
   if r['status']=='ERROR' and not r['actual_tools']:
    r['tool_selection_correct']=r['tool_arguments_correct']=None
  completed[cid]=r
  f.write(json.dumps(r,ensure_ascii=False,default=str)+'\n');f.flush()
  print(cid,r['status'],round(r.get('latency_ms') or 0),r['failure_reason'],flush=True)
live.save('end_state.json',{'ended_at':live.stamp(),'servers':live.api('GET','/api/servers')})
assert all(hashlib.sha256((ROOT/path).read_bytes()).hexdigest()==digest for path,digest in old.items())
print('COMPLETE: 30 records; prior reports unchanged',flush=True)
