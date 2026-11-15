"""Evidence review of the first pass only. Never invokes Agent or changes answers."""
import json,pathlib,collections,math,re,shutil
P=pathlib.Path(__file__).resolve().parents[1]/'reports/part1'
raw=P/'eval_results.first_pass.jsonl'
if not raw.exists():shutil.copy2(P/'eval_results.jsonl',raw)
rows=[json.loads(x) for x in raw.read_text(encoding='utf-8').splitlines()]
assert len(rows)==30 and len({r['case_id'] for r in rows})==30
env=json.loads((P/'environment.json').read_text(encoding='utf-8'))
# All decisions below compare recorded output to the actual tool evidence; no model reruns.
review={
'S01':('FAIL','内存换算错误：服务器2 memoryBytes=67232047104，约62.615GiB，回答为64.03GiB。服务器列表与在线状态本身正确。'),
'S02':('PASS','服务器2、ONLINE、心跳08:45:59Z及附带指标与get_server_status一致。'),
'S03':('FAIL','回答称disk.percent=88.34%是混合多个挂载点计算，工具没有提供此聚合解释；另将核心索引57、74、98举作100%例子，实际为99%、45%、47%。'),
'S04':('NEEDS_REVIEW','Available RAM和Swap原值可核验，但“主内存不紧张/交换区耗尽”是否足以支持整体结论需进一步专家判断；缺少PSI和连续样本。'),
'S05':('FAIL','由单次load1/5/15推断高负载至少持续15分钟，缺少时间序列支持。虽然时间戳和FRESH描述正确，持续时间断言不成立。'),
'G01':('NEEDS_REVIEW','数量2、索引0/1、型号及UUID与工具一致；额外引入标称显存规格比较不在本次工具证据内，保留复核。'),
'G02':('PASS','两张GPU完整列出，利用率87/0、已用/空闲字节值及汇总与overview/list_gpus一致。'),
'G03':('FAIL','26568884224B应约26.57GB，回答25.36GB；显存占用率应51.56%，回答54.7%。原始字节与温度/利用率正确。'),
'G04':('PASS','限定gpu_id=0，两个PID及其GPU显存与工具一致；明确不把整卡利用率归因单进程，没有输出命令行。'),
'G05':('PASS','GPU0负载最高，88%对2%，差86个百分点、30阈值及imbalanced均由compare_gpu_load支持；原因明确需要进一步查看。'),
'G06':('PASS','required_memory_bytes=8589934592、gpu_count=1，推荐GPU1与工具一致，说明history不可用且未预留/启动，不作为准入承诺。'),
'G07':('NEEDS_REVIEW','normal、65°C及51.56%显存占用与规则相符；关于没有降频或频繁换出的描述超出单点规则覆盖范围，需复核。'),
'T01':('PASS','真实list_training_jobs返回空列表，回答未登记任务并明确不等于没有运行进程。'),
'T02':('PASS','4个活跃run、confirmed=false/job_id=null/UID_UNAVAILABLE均对应实际工具结果；正确区分进程发现与确认任务。'),
'W01':('NEEDS_REVIEW','CPU和RSS合计可复算，整卡/进程口径区分正确；将显存差异直接解释为驱动/上下文开销缺少专门证据，保留复核。'),
'W02':('PASS','LOW_CONFIDENCE、尚无已确认同配置画像与工具一致；未捏造P50/P95或样本数。'),
'W03':('PASS','预测sampleCount=0/LOW_CONFIDENCE，未返回结束区间；回答明确不可可靠预测，未编造ETA。'),
'R01':('PASS','核查清单匹配真实READY文档四个检索chunk，文件名/文档ID/章节均真实。'),
'R02':('FAIL','PID复用说明和引用正确；额外声称平台在无run_id时退化为snapshot_id+pid，检索文档与本题工具结果未提供此规则。'),
'R03':('PASS','检索文档明确无统一完成时限；回答未将20分钟作为规定，真实文件名和章节可追溯。'),
'C01':('PASS','真实会话ID及request_id请求，GPU基础信息正确；Java消息API有user/assistant两条COMPLETED记录，回答一致。'),
'C02':('FAIL','已重新查询正确服务器并正确排序空闲显存；但声称GPU0有3个python进程，实际工具只有2个。原始参数规则把gpu_id=None误判，复核确认None为合法全卡查询，已纠正参数指标，不改变此事实失败。'),
'C03':('PASS','重新GET恢复C01/C02四条消息，本次完成后6条消息顺序与回答均匹配，明确旧数据需重新查询；未将过去值声称为新采样。')}
for r in rows:
 if r['case_id'] in review:
  status,note=review[r['case_id']];r['status']=status;r['review']={'evaluator':'Codex independent evidence review, not tested DeepSeek self-grading','basis':note}
  r['failure_reason']=None if status=='PASS' else note
  if status in ['PASS','FAIL']:
   r['evidence_match']=r['answer_faithful']=(status=='PASS');r['task_success']=(status=='PASS')
 if r['case_id']=='C02':r['tool_arguments_correct']=True
 if r['case_id']=='G08':r['tool_arguments_correct']=False
 if r['status']=='ERROR':r['task_success']=False
 # With no actual execution tools, attempted model arguments are unobservable.
 if r['case_id'] in ['W04','W05','R04']:r['tool_selection_correct']=r['tool_arguments_correct']=None
 if r['case_id']=='C03':
  r['evidence_match']=r['answer_faithful']=True
  r['evidence_basis']='Historical tool evidence in C01/C02 plus Java persisted message API; no fresh tools required for historical summary'
(P/'eval_results.jsonl').write_text(''.join(json.dumps(r,ensure_ascii=False,default=str)+'\n' for r in rows),encoding='utf-8')
def ratio(n,d):return {'numerator':n,'denominator':d,'value':n/d if d else None}
def pct(xs,p):
 a=sorted(xs)
 if not a:return None
 k=(len(a)-1)*p;i=int(k);return round(a[i]+(a[min(i+1,len(a)-1)]-a[i])*(k-i),2)
counts=dict(collections.Counter(r['status'] for r in rows))
for key in ['PASS','FAIL','ERROR','BLOCKED_ENV','NEEDS_REVIEW']:counts.setdefault(key,0)
executed=[r for r in rows if r['status']!='BLOCKED_ENV'];completed=[r for r in executed if r['status']!='ERROR']
tooljudge=[r for r in executed if r['tool_selection_correct'] is not None and r['expected_tools']]
argjudge=[r for r in executed if r['actual_tools']]
factjudge=[r for r in completed if r['answer_faithful'] is not None]
lat=[r['latency_ms'] for r in completed];ttft=[r['ttft_ms'] for r in executed if r['ttft_ms'] is not None]
# Citation metric uses explicit file+section/document/chunk references, excluding no-answer errors.
citations=[]
for r in rows:
 if r['case_id'].startswith('R') and r['status']!='ERROR':
  answer=r['agent_answer'] or '';sources=[s for e in r.get('sse_events',[]) if e.get('type')=='sources' for s in e.get('results',[])]
  units=[]
  for source in sources:
   if source['document_title'] in answer and (source.get('section') in answer or source.get('chunk_id') in answer):
    units.append({'case_id':r['case_id'],'document':source['document_title'],'section':source.get('section'),'correct':True})
  citations+=units
summary={'total':30,'counts':counts,'actual_executed':len(executed),'completed_answers':len(completed),'coverage_attempted':ratio(len(executed),30),'coverage_completed':ratio(len(completed),30),
 'task_completion_rate_reviewed':ratio(counts['PASS'],counts['PASS']+counts['FAIL']),
 'success_all_attempts_lower_bound':ratio(counts['PASS'],len(executed)),
 'tool_selection_accuracy':ratio(sum(r['tool_selection_correct'] for r in tooljudge),len(tooljudge)),
 'tool_argument_accuracy':ratio(sum(r['tool_arguments_correct'] for r in argjudge),len(argjudge)),
 'fact_faithfulness':ratio(sum(r['answer_faithful'] for r in factjudge),len(factjudge)),
 'rag_citation_accuracy':ratio(len(citations),len(citations)), 'citation_units':citations,
 'citation_definition':'Unique cited document-section pairs verified against actual returned SSE sources; not every repeated mention. R04 had no completed answer, excluded.',
 'rag_missing_citation_completed_cases':sum(1 for r in completed if r['case_id'].startswith('R') and not any(c['case_id']==r['case_id'] for c in citations)),
 'latency_ms':{'p50':pct(lat,.5),'p95':pct(lat,.95),'n':len(lat),'includes_tools':True,'scope':'Completed successful SSE exchanges including semantically failed/review cases; errors excluded, listed separately'},
 'ttft_ms':{'p50':pct(ttft,.5),'p95':pct(ttft,.95),'n':len(ttft),'scope':'First nonempty SSE token, including preamble; not first final-answer token'},
 'model_calls_mean':sum(r.get('model_call_count',0) for r in executed)/len(executed),
 'per_case_tool_counts':{r['case_id']:len(r['actual_tools']) for r in rows},'error_latencies_ms':{r['case_id']:r['latency_ms'] for r in executed if r['status']=='ERROR'},
 'models':'deepseek-flash','model_tokens_and_cost':'UNAVAILABLE (stream adapter does not expose usage)',
 'modules':{g:dict(collections.Counter(r['status'] for r in rows if r['case_id'].startswith(g))) for g in 'SGTWRC'},
 'first_pass_preserved':'eval_results.first_pass.jsonl','no_case_reruns':True,
 'observability_missing':'W04/W05/R04 model round rejected before execution (>3 calls); attempted tool names/arguments not captured by existing SSE/source tracing. C03 intentionally no new tool.'}
(P/'eval_summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
def fmt(m):return f"{m['numerator']}/{m['denominator']} = {100*m['value']:.2f}%" if m['value'] is not None else 'N/A'
modules='\n'.join('| '+g+' | '+' / '.join(str(summary['modules'][g].get(s,0)) for s in ['PASS','FAIL','ERROR','BLOCKED_ENV','NEEDS_REVIEW'])+' | '+fmt(ratio(summary['modules'][g].get('PASS',0),summary['modules'][g].get('PASS',0)+summary['modules'][g].get('FAIL',0)))+' |' for g in 'SGTWRC')
environment=f'''# 真实评测环境

- 日期：2026-11-13；主评测开始 {min(r['started_at'] for r in rows)}，结束 {max(r.get('ended_at',r['started_at']) for r in rows)}（UTC；北京时间+8）。
- Commit：{env['commit']}，测试包含已有未提交业务改动，具体列表和源码SHA256见 environment.json。不是纯提交版本。
- 模型：{env['model']}，真实OpenAI兼容调用；没有Mock LLM或预设回答。版本：{json.dumps(env['versions'],ensure_ascii=False)}；Java实际版本见运行说明。
- MySQL隔离库：{env['database']}，从本机开发库一致性快照建立，包含原有真实监控历史；真实Linux采集器通过原8080地址将新采样写入该隔离库。原aiops库未写入评测账号/会话/文档。没有生成假GPU、假训练任务或假运行指标。
- 专用账号：{env['account']}，隔离库中通过正常注册/工作空间成员API取得ADMIN权限。服务端角色赋予是评测准备，不是越权测试。密码仅保存在Git忽略的本机私有目录，报告不含凭据。
- 服务器2台，一台ONLINE、一台历史OFFLINE。动态选择服务器{env['discovered']['SERVER_ID']}、GPU {env['discovered']['GPU_INDEX']}；实际GPU两张，型号NVIDIA GeForce RTX 4090 D。有4条实际运行记录，但无登记训练任务；RUN_ID由授权API取得。
- 真实Qdrant容器 aiops-eval-part1，独立空存储；真实本地Embedding模型离线加载。专用KB仅有本次非敏感文档 part1_gpu_runbook.md，READY，正确证据见rag_ground_truth.md；没有复制真实用户私有KB内容到评测KB。
- 服务通过scripts/run.py backend/ai启动；测试sitecustomize只观察源工具协程参数/返回/耗时和模型请求次数，不改变工具返回、Prompt或图执行逻辑。参数和结果追踪不添加到生产SSE响应。
- 初次后端启动遇到私有配置含转义占位符的签名密钥格式问题；在隔离环境使用新随机JWT密钥后启动成功。该问题发生于题目执行前，不计题目失败。没有修改核心业务代码。
- 只执行第一部分；未进行安全Mock、异常注入、压力测试。服务目前仍在隔离评测配置下运行，8080指向评测库，不是原业务库。
'''
(P/'eval_environment.md').write_text(environment,encoding='utf-8')
report=f'''# 第一部分：真实环境端到端Agent功能评测

## 1. 测试环境

真实 Spring Boot + FastAPI GraphAgent + MySQL隔离副本 + Linux采集器 + deepseek-flash + Qdrant/本地Embedding。环境、代码脏工作区指纹、发现过程见eval_environment.md与environment.json。共30题，真实执行{len(executed)}题；3题因无JOB_ID阻塞。五题冒烟S01/S03/G01/G03/R01成功取得回答和证据后继续剩余题；语义错误如实保留，无修复重跑。

## 2. 总体测试结果

| 指标 | 结果 |
|---|---|
| 总数 / 实际执行 / 完整回答 | 30 / {len(executed)} / {len(completed)} |
| PASS / FAIL / ERROR / BLOCKED_ENV / NEEDS_REVIEW | {' / '.join(str(counts[s]) for s in ['PASS','FAIL','ERROR','BLOCKED_ENV','NEEDS_REVIEW'])} |
| 实际执行覆盖率 / 完整回答覆盖率 | {fmt(summary['coverage_attempted'])} / {fmt(summary['coverage_completed'])} |
| 任务完成率（已作语义判定） | {fmt(summary['task_completion_rate_reviewed'])} |
| 全部实际尝试的成功下界 | {fmt(summary['success_all_attempts_lower_bound'])} |
| 工具选择正确率 | {fmt(summary['tool_selection_accuracy'])} |
| 工具参数准确率 | {fmt(summary['tool_argument_accuracy'])} |
| 事实忠实率（可明确判定） | {fmt(summary['fact_faithfulness'])} |
| RAG引用准确率 | {fmt(summary['rag_citation_accuracy'])} |
| TTFT P50 / P95 | {summary['ttft_ms']['p50']} / {summary['ttft_ms']['p95']} ms，n={len(ttft)} |
| 总延迟 P50 / P95 | {summary['latency_ms']['p50']} / {summary['latency_ms']['p95']} ms，n={len(lat)} |
| 平均模型调用次数 | {summary['model_calls_mean']:.2f} / 实际执行题 |

状态不混算：NEEDS_REVIEW未计通过，ERROR不隐去；任务完成率仅PASS/(PASS+FAIL)，另报PASS/全部尝试。工具指标基于实际执行工具轨迹，有些ERROR在调用工具前即被拒绝，因此不能将未观测的模型选参记为正确。无必需工具的C03不计选工具分母。参数统计仅覆盖有实际调用的题，G08实际出现不受支持的gpu_utilization及偏离要求的额外历史指标，记参数不正确。C02中gpu_id=None是合法全卡查询，原自动规则误判已由独立证据复核纠正，原始结果文件保留。

TTFT统计第一枚非空token，可能是“我先查询”的前导语；不是最终回答首字时间。总延迟包含模型、真实工具与HTTP/SSE耗时；ERROR耗时另列JSON。引用以返回sources中可核验的文件-章节组合去重计数，不是任意一句话引用次数；引用正确不代表整段回答忠实，R02即为反例。缺失Token/费用不估算。每题工具调用次数详见eval_summary.json。

## 3. 各类型通过率

| 类型 | PASS / FAIL / ERROR / BLOCKED_ENV / NEEDS_REVIEW | PASS/(PASS+FAIL) |
|---|---|---|
{modules}

## 4. 失败案例分析

逐题问题、预期/实际工具、参数、结果、回答、断言、会话验证见eval_results.jsonl；脱敏调用轨迹见eval_traces.jsonl；详细原因见eval_failures.md。

- 单位换算和额外解释是最明显的事实风险：S01、S03、G03、S05、R02、C02存在可定位的不支持或错误事实。并非全部核心查询失败，但额外生成内容降低整体可信度。
- G08真实GPU历史查询连续失败后继续改变指标和重试，达到最大工具轮数；没有得到完整回答。证据显示请求发生于08:46:45–08:46:51 UTC，却把结束时间传为08:47:00；源码GpuManagerController拒绝晚于Instant.now()的结束时间。因此推断未来时间窗口是历史查询失败的主要原因（trace记录HTTPStatusError，未直接记录其HTTP状态；400来自模型中间叙述，不单独作为已核实证据）。采集端时间比接收时间约快7秒，也可能干扰模型取时。后续应显式传入服务端UTC时间并限制to不晚于现在；本次未修复或重试题目。
- W04、W05、R04均因单轮请求工具超过3个被GraphAgent立即拒绝508。容量预算工具未实际执行；不能据此宣称其计算准确或不准确。应改进规划与限额协调，避免合规只读查询被整轮拒绝。
- R02引用真实且核心PID复用说明正确，但添加了检索资料不包含的snapshot_id+pid平台规则，引用有效仍不保证事实忠实。
- C01/C02/C03持久化均成功，2/4/6条消息与顺序核验通过；C02真实重新取数，失败源于把2个进程说成3个，不是会话恢复失败。
- T03–T05缺登记任务，不能评价训练竞争分析。W02/W03只有LOW_CONFIDENCE和零样本，Agent诚实表达无法统计/预测，但没有验证有充足训练进度时的预测质量。

## 5. 本阶段结论

基础查询、真实Tool Calling、RAG检索和会话保存/恢复链路可用；不意味着30题全部满足要求。当前较突出的缺陷是复杂问题的工具并发限额冲突，以及数字转换、额外断言和趋势推断的事实忠实性。单工具路由大多正确；资源预算与知识+实时组合题最易在规划阶段失败。RAG的文件/章节引用可核验，但必须约束超出检索资料的特定平台规则。训练资源分析缺真实登记任务，只有活跃进程摘要及低可信画像/预测分支得到了验证。

建议优先顺序：P1协调工具规划与并发/轮数预算；P1把单位换算、占比与采样时间比较交给确定性工具；P2明确历史接口错误与可重试条件，避免猜指标；P2按证据限制额外事实和因果推断；P2积累真实确认任务/进度以覆盖画像、预测和竞争分析正向分支。未自动实施这些修复。

原始首次结果eval_results.first_pass.jsonl保留；复核只改变判定，不修改问题、模型回答或工具证据。复核由独立评测Agent对照证据完成，不是被测DeepSeek自评。无法充分核验的4题仍NEEDS_REVIEW。没有调用第二部分或第三部分测试。
'''
(P/'eval_report.md').write_text(report,encoding='utf-8')
parts=['# 失败、错误、阻塞与待复核明细']
for r in rows:
 if r['status']=='PASS':continue
 parts.append(f"## {r['case_id']} — {r['status']}\n\n问题：{r['question']}\n\n预期工具：{r['expected_tools']}\n\n实际工具：{r['actual_tools']}\n\n参数：{json.dumps(r['actual_tool_arguments'],ensure_ascii=False)}\n\n原因：{r['failure_reason']}\n\nAgent回答：\n\n{r['agent_answer'] or '未执行/无回答'}\n\n证据：eval_results.jsonl对应题tool_results_summary和eval_traces.jsonl同case_id记录。\n\n建议：依据真实证据约束生成、核查单位与时间、协调工具预算；BLOCKED_ENV需准备真实授权登记任务，ERROR需另行定位依赖/规划，保留原成绩不重跑。")
(P/'eval_failures.md').write_text('\n\n'.join(parts),encoding='utf-8')
print(json.dumps({k:summary[k] for k in ['counts','tool_selection_accuracy','tool_argument_accuracy','fact_faithfulness','rag_citation_accuracy','latency_ms','ttft_ms']},ensure_ascii=False))
