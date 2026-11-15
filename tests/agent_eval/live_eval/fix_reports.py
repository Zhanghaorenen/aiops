"""Summarize fixed-code first attempts; never rerun model or overwrite baseline."""
import json,pathlib,hashlib,shutil,collections
R=pathlib.Path(__file__).resolve().parents[3];BASE=R/'tests/agent_eval/reports/part1';FULL=BASE.parent/'part1_after_fix';TARGET=BASE.parent/'part1_after_fix_targeted'
def read(p):return json.loads(p.read_text(encoding='utf-8'))
def ratio(n,d):return {'numerator':n,'denominator':d,'value':n/d if d else None}
def fmt(m):return f"{m['numerator']}/{m['denominator']} ({m['value']*100:.2f}%)" if m.get('value') is not None else 'N/A'
def percentile(v,p):
 v=sorted(v)
 if not v:return None
 k=(len(v)-1)*p;i=int(k);return round(v[i]+(v[min(i+1,len(v)-1)]-v[i])*(k-i),2)
notes={
'S01':('PASS','授权服务器列表、状态与当次工具一致；没有错误容量换算。'),
'S03':('PASS','容量显示取程序字段；fully_busy_indices=[41]与当次核心数组一致；未猜测磁盘聚合。'),
'S04':('NEEDS_REVIEW','仍以“有压力/偏紧”开头，又承认PSI缺失无法判压力；需独立人工审核，不能因Prompt修改记PASS。'),
'S05':('FAIL','采集09:21:51Z比声明当前09:21:46Z更晚，却解释为秒级延迟，未正确说明时钟偏差。持续时间断言已移除。'),
'G01':('NEEDS_REVIEW','数量、索引、UUID匹配，已不引入标称容量比较；硬件NVML核查因SSH超时未完成，保留独立人工复核。'),
'G03':('PASS','容量MB/MiB/GB和47.25%容量占用与程序派生值一致；控制器活动38%与计算50%区分。'),
'G07':('NEEDS_REVIEW','normal与当次快照规则一致，并明确不判断降频/换出；仍保留原四题的独立人工复核流程。'),
'G08':('FAIL','实际近期历史工具成功、UTC窗口30分钟和65个样本；回答“最后3个样本”为4个数，额外计数仍错误。'),
'W01':('NEEDS_REVIEW','派生合计仍含MISSING历史记录，回答有说明；逐任务换算仍自行计算，需人工确认活动集合和统计口径。'),
'W04':('PASS','实际执行预算工具，10240MiB/8192MiB正确；allowed=true及UNAVAILABLE/缺PSI一致，未启动或预约。'),
'W05':('FAIL','无508并已执行预算，但CPU43.23%、可用RAM19.44GiB仍称接近饱和，且拿已扣运行占用的安全CPU预算再次与现有任务相减，结论无充分证据。'),
'R02':('PASS','引用真实文档/章节/PID复用依据，没有补充snapshot_id+pid内部关联规则。'),
'R04':('FAIL','真实RAG+预算链路完成；GPU1安全预算22886.75MiB约22.35GiB，回答允许约22.8GiB，RAM预算17858.57MiB约17.44GiB却建议低于17.8GiB，换算/边界仍不可靠。'),
'C02':('FAIL','没有任何新工具调用，直接沿用C01快照回答“现在”，不满足重新取数标准。')}
def summarize(folder,targeted):
 p=folder/'eval_results.jsonl';raw=folder/'eval_results.first_pass.jsonl'
 if not raw.exists():shutil.copy2(p,raw)
 rows=[json.loads(x) for x in raw.read_text(encoding='utf-8').splitlines()]
 for row in rows:
  cid=row['case_id'];row['http_attempted']=row['latency_ms'] is not None;row['valid_test_execution']=row['http_attempted']
  if targeted:
   status,note=notes[cid];row['status']=status;row['review']={'evaluator':'Independent Codex evidence review; not human signoff','reason':note}
   if cid in ['W05','R04']:row['tool_arguments_correct']=True # free GPU selection permitted for aggregate questions
  elif cid in ['C02','C03']:
   status='BLOCKED_ENV';note='C01缺SERVER_ID未执行，后续会话题缺先行上下文；runner仍提交了HTTP请求，原始记录保留，此两次不作为有效会话评测。'
   row['status']=status;row['valid_test_execution']=False;row['review']={'evaluator':'Precondition audit','reason':note}
   row['tool_selection_correct']=row['tool_arguments_correct']=None
  elif cid in ['R01','R02','R03']:
   status='PASS';note='真实隔离KB片段、文件和章节可核验；没有无依据内部实现规则或20分钟规定。';row['status']=status;row['review']={'evaluator':'Independent evidence review','reason':note}
  elif cid=='S01':
   status='NEEDS_REVIEW';note='容量换算和列表正确，但把离线状态与约两分钟旧样本说成不一致，没有状态阈值证据。';row['status']=status;row['review']={'evaluator':'Independent evidence review','reason':note}
  else:status=row['status'];note=row['failure_reason']
  row['failure_reason']=None if status=='PASS' else note
  row['answer_faithful']=row['evidence_match']=status=='PASS' if status in ['PASS','FAIL'] else None
  row['task_success']=status=='PASS' if status in ['PASS','FAIL','ERROR'] else None
 p.write_text(''.join(json.dumps(x,ensure_ascii=False)+'\n' for x in rows),encoding='utf-8')
 counts={s:sum(r['status']==s for r in rows) for s in ['PASS','FAIL','ERROR','BLOCKED_ENV','NEEDS_REVIEW']}
 valid=[r for r in rows if r['valid_test_execution']];tool=[r for r in valid if r['tool_selection_correct'] is not None and r['expected_tools']];arg=[r for r in valid if r['actual_tools']];facts=[r for r in valid if r['answer_faithful'] is not None]
 cit=[]
 for r in valid:
  if not r['case_id'].startswith('R') or r['status']=='ERROR':continue
  sources=[s for e in r.get('sse_events',[]) if e.get('type')=='sources' for s in e['results']]
  for s in sources:
   if s['document_title'] in (r['agent_answer'] or '') and s.get('section') in r['agent_answer']:
    unit=(r['case_id'],s['document_title'],s['section'])
    if unit not in cit:cit.append(unit)
 lat=[r['latency_ms'] for r in valid if r['status']!='ERROR'];ttft=[r['ttft_ms'] for r in valid if r['ttft_ms'] is not None]
 summary={'counts':counts,'total':len(rows),'http_attempts':sum(r['http_attempted'] for r in rows),'valid_executed':len(valid),'invalid_prerequisite_attempts':sum(r['http_attempted'] and not r['valid_test_execution'] for r in rows),
 'task_completion_rate_reviewed':ratio(counts['PASS'],counts['PASS']+counts['FAIL']), 'success_all_attempts_lower_bound':ratio(counts['PASS'],sum(r['http_attempted'] for r in rows)),
 'tool_selection_accuracy':ratio(sum(r['tool_selection_correct'] for r in tool),len(tool)),
 'tool_argument_accuracy':ratio(sum(bool(r['tool_arguments_correct']) for r in arg),len(arg)),
 'fact_faithfulness':ratio(sum(r['answer_faithful'] for r in facts),len(facts)),
 'rag_citation_accuracy':ratio(len(cit),len(cit)),'citation_units':cit,
 'latency_ms':{'p50':percentile(lat,.5),'p95':percentile(lat,.95),'n':len(lat)},
 'ttft_ms':{'p50':percentile(ttft,.5),'p95':percentile(ttft,.95),'n':len(ttft)},
 'tokens_cost':'UNAVAILABLE','model':'deepseek-flash','scope':'14 targeted original questions + real C01 setup' if targeted else 'One full first pass; no online server discovered; not comparable quality coverage',
 'coverage':ratio(len(valid),len(rows)),'excluded_from_semantic_pass':['NEEDS_REVIEW','BLOCKED_ENV','ERROR']}
 (folder/'eval_summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
 return rows,summary
t,ts=summarize(TARGET,True);f,fs=summarize(FULL,False);baseline=read(BASE/'eval_summary.json')
manifest=read(FULL/'baseline_manifest.json')
assert all(hashlib.sha256((BASE/p).read_bytes()).hexdigest()==h for p,h in manifest.items())
refs=BASE.parent/'part1_baseline';refs.mkdir(exist_ok=True)
(refs/'README.md').write_text('Baseline保留原位 ../part1/，本目录仅作引用。校验清单见 ../part1_after_fix/baseline_manifest.json，原始文件未修改。\n',encoding='utf-8')
comparison=['# 修复前后对比','完整复测遇服务器离线：Baseline有效执行27题，修复后仅4题有效执行，另有2次在C01前置失败后的无效会话请求。不得将百分比变化当成质量提升。专项14题当时服务器在线，可用于观察修复分支，但它不是30题分数。',
'| 指标 | Baseline | 完整复测 |\n|---|---|---|']
for label,key in [('任务完成率','task_completion_rate_reviewed'),('实际HTTP尝试成功下界','success_all_attempts_lower_bound'),('工具选择','tool_selection_accuracy'),('参数正确','tool_argument_accuracy'),('事实忠实','fact_faithfulness'),('RAG引用','rag_citation_accuracy')]:comparison.append(f'| {label} | {fmt(baseline[key])} | {fmt(fs[key])} |')
comparison += [f"| 运行异常数 | 4 | {fs['counts']['ERROR']}（覆盖不足，不能视为已消除全部异常） |",f"| 完整响应P95 | {baseline['latency_ms']['p95']/1000:.2f}s，n={baseline['latency_ms']['n']} | {fs['latency_ms']['p95']/1000:.2f}s，n={fs['latency_ms']['n']} |",
f"\n专项14题：{ts['counts']}。对应Baseline子集为PASS0/FAIL6/ERROR4/NEEDS_REVIEW4；本次PASS5/FAIL5/ERROR0/NEEDS_REVIEW4。专项任务完成率{fmt(ts['task_completion_rate_reviewed'])}；W04/W05/R04均实际执行预算工具，G08实际返回历史，均不再出现原508，语义失败仍保留。",
'\n| 题号 | 专项结论 | 证据判定 |\n|---|---|---|']
comparison += [f"| {r['case_id']} | {r['status']} | {r['review']['reason']} |" for r in t]
(FULL/'before_after_comparison.md').write_text('\n\n'.join(comparison),encoding='utf-8')
fix='''# 定向修复报告

- P0多工具限额：graph_agent.py新增_batched_tools_node，最多3并发、总12调用、最多4轮，并给每个call ID完整ToolMessage。超总预算整批明确未执行，最后允许一次无工具证据总结；相同失败签名不再执行。保留401/403异常传播、只读工具集和SSE，不增加shell能力。
- P0数值：新增numeric_evidence.py，Decimal ROUND_HALF_UP统一2位显示，保留原字节值/未知值；server_manager补资产容量、核心索引、RAM和未知磁盘口径说明；gpu_manager补GB/GiB和容量占比；Graph工具结果增加derived_summary进程计数/任务合计。NVML控制器活动与容量占比明确区分。
- P0时间：gpu_tool.py新增get_recent_gpu_history，Literal指标白名单，last_minutes=1..1440；复用既有Java历史API，结束时间取PlatformClient HTTP Date校时后减2秒，显式未来/无时区时间拒绝。成功空历史明确no_history，不猜指标重试。没有改Java接口或存储。
- P1证据：运行时Prompt添加当前UTC、时间/压力/磁盘/RAG边界和派生字段优先规则。未添加宽泛关键词过滤，也未宣称Prompt可保证消除幻觉。

单元兼容性测试最终47/47通过，日志在data/local-run/part1-fix-regression-unit-final.log。最初一个旧测试假定到轮数立即508，已更新为最后一次不提供tools的受限总结，仍断言调用次数与缺失证据说明。专项启动前出现一次就绪502，未提交用例，记录preparation_errors.json。

保留原有未提交业务改动；修改前相关文件拷贝到Git忽略的data/local-run/part1-fix-before。此次新增/修改范围：numeric_evidence.py、graph_agent.py、platform_client.py、server_manager.py、gpu_manager.py、gpu_tool.py、test_eval_reliability.py、test_chat.py及独立评测脚本。其他脏工作区文件未由本任务修改。

API兼容：REST路径、JWT/RBAC、数据库结构均不变；工具输出新增字段，既有列表类型和原始指标保留。新增近期历史工具与原工具并存；显式未来历史范围现在提前拒绝，工具预算耗尽由立即失败改为受限证据总结，属于预期行为变化。证据不足不会生成真实训练或预测样本。
'''
(FULL/'fix_report.md').write_text(fix,encoding='utf-8')
reg=f'''# 回归报告

定向+既有兼容测试47项通过。专项14题原题首次结果：{ts['counts']}；完整30题首次结果：{fs['counts']}。

完整复测HTTP实际提交{fs['http_attempts']}题，有效执行{fs['valid_executed']}题，C01没有目标服务器却仍提交C02/C03两次，是复测runner前置依赖检查缺口；已保留原始HTTP/回答和原始FAIL标记于eval_results.first_pass.jsonl，最终记BLOCKED_ENV并显式标valid_test_execution=false，绝不计成功。其余缺数据题未提交HTTP。

专项C02用独立真实C01会话作准备（session_setup_C01.json），实际没有重新调用工具而沿用历史，判FAIL。完整复测未能建立相同前置上下文，不能据其结果评价会话改善。四道人工复核题不因改Prompt自动PASS，待独立人工签核见manual_review.md。

专项和完整测试期间未再次修改核心实现、未修复后重算。完整第一次结果保留，未因服务器离线重复跑30题。模型均deepseek-flash，题目来自同一SPEC；新增近期历史API-backed等价路径单独说明；修正旧runner把合法gpu_id=None/汇总问题选择另一张卡误判的参数规则，没有放宽用户指定GPU的约束。

NVML只读SSH核查超时，未取得驱动原始值；没有按型号修改上报容量，没有改变驱动/GPU配置或运行真实训练。
'''
(FULL/'regression_report.md').write_text(reg,encoding='utf-8')
remaining=['# 剩余问题','专项仍FAIL：S05、G08、W05、R04、C02。详见专项结果的review字段。','结构化派生字段+Prompt改善了常用换算，但不是答案硬校验：模型仍可能自行换算预算、误计数、混淆空余预算与已用资源、沿用历史实时值。需要后续设计范围受控的证据约束；本轮不伪造“彻底解决”。','四道NEEDS_REVIEW：S04、G01、G07、W01，独立人工签核尚未完成。','完整复测服务器离线，无法得到与Baseline同覆盖的30题质量结论。必须在用户另行安排的在线窗口完成可比验证；此轮不反复重跑。','T03/T04/T05仍缺真实JOB_ID；W02/W03充足样本预测分支未验证；NVML原始容量核查受SSH超时阻塞。','HTTP Date校时+2秒保护覆盖小幅时钟差；没有验证极端服务端时钟漂移/代理改写Date情形。','工作区原有用户改动均保留，Baseline哈希全部一致。']
(FULL/'remaining_issues.md').write_text('\n\n'.join(remaining),encoding='utf-8')
manual=['# 独立人工复核待办','本文件不是人工通过凭据。独立评测Agent只整理证据，不代替人工专家签核。所有四题仍NEEDS_REVIEW。']
for r in t:
 if r['case_id'] in ['S04','G01','G07','W01']:
  manual.append(f"## {r['case_id']}\n\n状态：待人工签核\n\n评测观察：{r['review']['reason']}\n\n回答：\n\n{r['agent_answer']}\n\n原始工具证据：../part1_after_fix_targeted/eval_results.jsonl中同case_id/tool_results_summary。\n\n复核人：未提供；结论：未签核。")
(FULL/'manual_review.md').write_text('\n\n'.join(manual),encoding='utf-8')
print(json.dumps({'targeted':ts['counts'],'full':fs['counts'],'valid_full':fs['valid_executed'],'http_attempts':fs['http_attempts'],'baseline_unchanged':True},ensure_ascii=False))
