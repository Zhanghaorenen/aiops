"""One first-pass real HTTP/SSE run, no scripted model, no synthetic workloads."""
import json,os,pathlib,time,uuid,secrets,datetime,re,hashlib,subprocess
import httpx
ROOT=pathlib.Path(__file__).resolve().parents[3]
OUT=ROOT/'tests/agent_eval/reports/part1'; OUT.mkdir(parents=True,exist_ok=True)
PRIV=ROOT/'data/local-run'
def save(name,v): (OUT/name).write_text(json.dumps(v,ensure_ascii=False,indent=2,default=str),encoding='utf-8')
def stamp():return datetime.datetime.now(datetime.timezone.utc).isoformat()
C=httpx.Client(base_url='http://127.0.0.1:8080',timeout=120)
A=httpx.Client(base_url='http://127.0.0.1:8000',timeout=180)
def api(method,path,body=None,client=C,**kw):
 r=client.request(method,path,json=body,**kw);r.raise_for_status();return r.json() if r.content else None
def setup():
 props=dict(x.split('=',1) for x in (PRIV/'dev-secrets.properties').read_text(encoding='utf-8-sig').splitlines() if '=' in x and not x.startswith('#'))
 admin=api('POST','/api/auth/login',{'username':props['aiops.admin.username'],'password':props['aiops.admin.password']})['accessToken']
 account='evalp1'+datetime.datetime.now().strftime('%H%M%S');password=secrets.token_urlsafe(24)
 api('POST','/api/auth/register',{'username':account,'password':password})
 api('POST','/api/workspace/members',{'username':account,'role':'ADMIN'},headers={'Authorization':'Bearer '+admin})
 token=api('POST','/api/auth/login',{'username':account,'password':password})['accessToken']
 C.headers['Authorization']=A.headers['Authorization']='Bearer '+token
 (PRIV/'part1-account.json').write_text(json.dumps({'username':account,'password':password}),encoding='utf-8')
 servers=api('GET','/api/servers'); env={'started_at':stamp(),'account':account,'servers':servers,'health':{'java':api('GET','/api/health'),'ai':api('GET','/api/ai/health',client=A)},'database':'aiops_eval_part1_20261113'}
 candidates=[]
 for s in servers:
  if s.get('status')=='ONLINE':
   try:
    g=api('GET',f"/api/servers/{s['id']}/gpus");candidates.append((s,g))
   except Exception:pass
 s,g=max(candidates,key=lambda x:len(x[1].get('gpuUuids',[]))) if candidates else (None,{})
 d={}
 if s:
  d['SERVER_ID']=s['id'];d['GPU_COUNT']=len(g.get('gpuUuids',[]));env['gpus']=g
  indices=g.get('gpuIndices',[])
  if indices:d['GPU_INDEX']=indices[0]
  jobs=api('GET',f"/api/servers/{s['id']}/training/jobs");env['job_count']=len(jobs)
  if jobs:d['JOB_ID']=jobs[0]['id']
  runs=api('GET',f"/api/servers/{s['id']}/training/workloads");env['active_run_count']=len(runs)
  for run in runs:
   run_id=run.get('id',run.get('run',{}).get('id'))
   if run_id:
    try:api('GET',f"/api/servers/{s['id']}/training/workloads/{run_id}/profile");d['RUN_ID']=run_id;break
    except httpx.HTTPError:continue
 kb=api('POST','/api/knowledge-bases',{'name':'Part1 live evaluation','description':'Isolated non-sensitive evaluation evidence'})
 text='# GPU训练运行规范（评测专用）\n\n## 启动前资源核查\n启动前应查询GPU空闲显存、利用率、温度及采样时间，并核查Available RAM、Swap、CPU PSI、内存PSI和IO PSI。空闲显存不等于最终准入，应扣除有效承诺和安全预留。只提供建议，不启动进程。\n\n## 进程身份\nPID会被操作系统复用，必须结合服务器、PID和进程启动时间识别同一次运行，必要时核对用户身份。仅有PID不能证明任务身份。\n\n## 证据边界\n历史采样不代表当前状态；训练时长因任务而异，本文没有统一完成时限。观察到资源压力不证明特定故障根因。\n'
 (OUT/'rag_ground_truth.md').write_text(text,encoding='utf-8')
 try:
  r=A.post(f"/api/rag/knowledge-bases/{kb['id']}/documents",files={'file':('part1_gpu_runbook.md',text.encode(),'text/markdown')});r.raise_for_status()
  docs=api('GET',f"/api/knowledge-bases/{kb['id']}/documents")
  env['kb_documents']=docs
  if any(x['status']=='READY' for x in docs):d['KB_ID']=kb['id']
 except Exception as e:env['rag_setup_error']=type(e).__name__+':'+str(e)[:160]
 session=api('POST','/api/ai/sessions',{'title':'Part1 first pass'});d['SESSION_ID']=session['id']
 env['discovered']=d
 config=json.loads((PRIV/'ai-service-secrets.json').read_text(encoding='utf-8-sig'));env['model']=config['AIOPS_LLM_MODEL']
 env['versions']={'fastapi':__import__('fastapi').__version__,'mysql':'8.0.46','qdrant':'1.13.6','spring_boot':'4.1.1','python':__import__('sys').version}
 env['commit']=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
 env['dirty_status']=subprocess.check_output(['git','status','--short'],cwd=ROOT,text=True)
 env['source_hashes']={str(f.relative_to(ROOT)):hashlib.sha256(f.read_bytes()).hexdigest() for folder in ['agent/ai-service/app','src/main/java'] for f in (ROOT/folder).rglob('*') if f.is_file() and f.suffix in ['.py','.java']}
 save('environment.json',env);return d,env

SPEC=[
('S01','列出我当前有权限管理或查看的全部服务器，并告诉我哪些在线、哪些离线。','list_servers'),
('S02','帮我检查服务器 {SERVER_ID} 当前是否在线，最后一次心跳是什么时候？','get_server_status'),
('S03','查询服务器 {SERVER_ID} 的 CPU、内存和磁盘资源使用情况，给出具体数值。','get_server_resource_usage'),
('S04','服务器 {SERVER_ID} 当前内存紧张吗？请根据实际资源指标判断，不要只给一般性建议。','get_server_resource_usage'),
('S05','服务器 {SERVER_ID} 的监控数据是最新的吗？现在能否可靠地判断它的资源状态？','get_server_status get_server_resource_usage'),
('G01','服务器 {SERVER_ID} 有几张 GPU？分别是什么型号、编号和 UUID？','list_gpus'),
('G02','汇总服务器 {SERVER_ID} 所有 GPU 的当前利用率、显存占用和空闲显存。','get_gpu_overview'),
('G03','查询服务器 {SERVER_ID} 的 GPU {GPU_INDEX}，告诉我它的显存、利用率和温度。','get_gpu_status'),
('G04','GPU {GPU_INDEX} 当前有哪些计算进程？它们分别占用多少 GPU 显存？','get_gpu_processes'),
('G05','服务器 {SERVER_ID} 哪张 GPU 当前负载最高？是否存在明显负载不均衡？','compare_gpu_load'),
('G06','我现在准备运行一个预计需要 8 GiB 显存的训练任务，服务器 {SERVER_ID} 推荐使用哪张 GPU？','recommend_gpu_placement'),
('G07','检查服务器 {SERVER_ID} 的 GPU {GPU_INDEX} 是否存在温度或显存方面的健康风险。','analyze_gpu_health'),
('G08','分析服务器 {SERVER_ID} 的 GPU {GPU_INDEX} 最近 30 分钟利用率变化情况。','get_gpu_history'),
('T01','服务器 {SERVER_ID} 当前有哪些已登记的训练任务？它们分别处于什么状态？','list_training_jobs'),
('T02','服务器 {SERVER_ID} 当前有哪些活跃运行任务？哪些任务已确认关联进程？','get_active_workloads'),
('T03','帮我分析服务器 {SERVER_ID} 的训练任务 {JOB_ID} 是否存在资源竞争，并告诉我判断依据。','analyze_training_contention'),
('T04','训练任务 {JOB_ID} 是否因为内存压力或磁盘 IO 竞争而变慢？请区分已确认事实和可能原因。','analyze_training_contention'),
('T05','查询任务 {JOB_ID} 是否存在 Step Time 增长或训练吞吐下降的证据。','analyze_training_contention'),
('W01','服务器 {SERVER_ID} 当前的活跃任务占用了多少 CPU、RAM 和 GPU 资源？','get_active_workloads'),
('W02','查询运行任务 {RUN_ID} 对应的历史资源画像，重点说明显存 P50、P95、峰值和历史样本数。','get_workload_profile'),
('W03','运行任务 {RUN_ID} 大概还需要多久结束？请说明预测依据、时间范围和可信程度。','predict_workload_completion'),
('W04','服务器 {SERVER_ID} 的 GPU {GPU_INDEX}，现在是否适合新增一个需要 10 GiB 显存和 8 GiB RAM 的训练任务？','estimate_available_capacity'),
('W05','服务器 {SERVER_ID} 的 GPU 可能还有空闲显存，但 CPU 或 IO 已经很忙，这时候是否应该继续启动训练？','estimate_available_capacity'),
('R01','根据知识库，GPU 训练任务启动前应该核查哪些资源？','search_knowledge'),
('R02','根据知识库说明，为什么训练进程不能只使用 PID 识别？请提供实际文档来源。','search_knowledge'),
('R03','知识库是否明确规定所有 GPU 训练任务都必须在 20 分钟内完成？','search_knowledge'),
('R04','根据知识库中的 GPU 资源使用建议，结合服务器 {SERVER_ID} 当前实际情况，判断是否适合启动新的训练任务。','search_knowledge'),
('C01','帮我查询服务器 {SERVER_ID} 当前的 GPU 基础信息，并记住我们正在讨论这台服务器。','list_gpus'),
('C02','那它现在空闲显存最多的是哪张卡？','list_gpus'),
('C03','总结一下我们刚才对这台服务器的查询结果，并说明哪些资源数据需要重新获取。','')]

def trace_for(cid):
 p=OUT/'eval_traces.jsonl'
 return [json.loads(x) for x in p.read_text(encoding='utf-8').splitlines() if json.loads(x)['case_id']==cid] if p.exists() else []
def run(case,d):
 cid,q,tools=case;missing=[k for k in re.findall(r'\{(\w+)\}',q) if k not in d]
 if cid.startswith('R') and 'KB_ID' not in d:missing.append('READY_KB')
 if cid=='G05' and d.get('GPU_COUNT',0)<2:missing.append('TWO_GPUS')
 q=q.format_map({k:d.get(k,'{'+k+'}') for k in re.findall(r'\{(\w+)\}',q)})
 # Explicit server context for standalone questions that omit it; no answer hints added.
 history=[] if cid.startswith('C') else ([{'role':'user','content':f"本次查询服务器 {d['SERVER_ID']}。"}] if 'SERVER_ID' in d else [])
 r={'case_id':cid,'question':q,'expected_tools':tools.split(),'actual_tools':[],'actual_tool_arguments':[],'tool_results_summary':[],'agent_answer':None,'evidence_match':None,'task_success':None,'tool_selection_correct':None,'tool_arguments_correct':None,'answer_faithful':None,'latency_ms':None,'ttft_ms':None,'status':'BLOCKED_ENV','failure_reason':None,'started_at':stamp()}
 if missing:r['failure_reason']='Missing '+','.join(missing);return r
 payload={'question':q,'history':history}
 if cid.startswith('C'):
  payload={'question':q,'session_id':d['SESSION_ID'],'request_id':str(uuid.uuid4())}
  r['messages_before']=api('GET',f"/api/ai/sessions/{d['SESSION_ID']}/messages")
 events=[];answer='';t=time.perf_counter()
 try:
  with A.stream('POST','/api/chat/stream',json=payload,headers={'X-Eval-Case':cid}) as response:
   response.raise_for_status()
   for line in response.iter_lines():
    if not line.startswith('data:'):continue
    e=json.loads(line[5:]);events.append(e)
    if e.get('type')=='token' and e.get('text'):
     if r['ttft_ms'] is None:r['ttft_ms']=(time.perf_counter()-t)*1000
     answer+=e['text']
  r['agent_answer']=answer
  tr=trace_for(cid);results=[x for x in tr if x['event']=='tool_result'];starts=[x for x in tr if x['event']=='tool_start']
  r['actual_tools']=[x['name'] for x in starts];r['actual_tool_arguments']=[x['arguments'] for x in starts];r['tool_results_summary']=results
  r['model_call_count']=sum(x['event']=='model_call' for x in tr)
  r['sse_events']=events
  r['tool_selection_correct']=set(tools.split()).issubset(r['actual_tools'])
  if cid=='R04':r['tool_selection_correct'] &= bool(set(r['actual_tools']) & {'get_gpu_status','get_gpu_overview','list_gpus','estimate_available_capacity'})
  r['tool_arguments_correct']=True
  for x in starts:
   a=x['arguments']
   if 'server_id' in a and a['server_id']!=d.get('SERVER_ID'):r['tool_arguments_correct']=False
   if 'gpu_id' in a and a['gpu_id']!=d.get('GPU_INDEX'):r['tool_arguments_correct']=False
   if x['name']=='recommend_gpu_placement' and cid=='G06' and a.get('required_memory_bytes')!=8589934592:r['tool_arguments_correct']=False
   if cid=='W04' and x['name']=='estimate_available_capacity' and (a.get('vram_mb')!=10240 or a.get('ram_mb')!=8192):r['tool_arguments_correct']=False
   if cid=='G08' and x['name']=='get_gpu_history':
    end=datetime.datetime.fromisoformat(a['end_time'].replace('Z','+00:00'));start=datetime.datetime.fromisoformat(a['start_time'].replace('Z','+00:00'))
    if a.get('metric_name')!='utilization' or abs((end-start).total_seconds()-1800)>120 or abs((datetime.datetime.now(datetime.timezone.utc)-end).total_seconds())>300:r['tool_arguments_correct']=False
  if cid.startswith('C'):
   messages=api('GET',f"/api/ai/sessions/{d['SESSION_ID']}/messages");r['messages_after']=messages
   r['session_persisted']=len(messages)==int(cid[-1])*2 and messages[-1].get('content')==answer
  errs=[e for e in events if e.get('type')=='error']
  if errs:r['status']='ERROR';r['failure_reason']=json.dumps(errs,ensure_ascii=False)
  elif not any(e.get('type')=='done' for e in events) or not answer:r['status']='ERROR';r['failure_reason']='No complete answer/done'
  elif not r['tool_selection_correct'] or not r['tool_arguments_correct']:r['status']='FAIL';r['task_success']=False;r['failure_reason']='Tool route or argument assertion failed'
  elif cid.startswith('C') and not r['session_persisted']:r['status']='FAIL';r['task_success']=False;r['failure_reason']='Session persistence mismatch'
  else:r['status']='NEEDS_REVIEW';r['failure_reason']='Requires evidence-based semantic review; not auto-passed'
 except Exception as e:r['status']='ERROR';r['failure_reason']=type(e).__name__+':'+str(e)[:300]
 r['latency_ms']=(time.perf_counter()-t)*1000;r['ended_at']=stamp();return r

if __name__=='__main__':
 if (OUT/'eval_results.jsonl').exists():raise SystemExit('First pass results exist; refusing rerun')
 save('cases.json',SPEC)
 d,env=setup();smoke=['S01','S03','G01','G03','R01'];order=[next(x for x in SPEC if x[0]==s) for s in smoke]+[x for x in SPEC if x[0] not in smoke]
 with (OUT/'eval_results.jsonl').open('x',encoding='utf-8') as f:
  for c in order:
   r=run(c,d);f.write(json.dumps(r,ensure_ascii=False,default=str)+'\n');f.flush();print(r['case_id'],r['status'],round(r.get('latency_ms') or 0),r['failure_reason'],flush=True)
   if len([x for x in smoke if x==c[0]]) and r['status']=='ERROR':print('Smoke dependency error recorded; independent cases continue without retry',flush=True)
 print('FIRST_PASS_COMPLETE',flush=True)
