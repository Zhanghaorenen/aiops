"""Independent evidence review and aggregate comparison. Does not call any model."""
import json,pathlib,hashlib,shutil,collections,datetime
ROOT=pathlib.Path(__file__).resolve().parents[3]
P=ROOT/'tests/agent_eval/reports/part1_rerun_20261113_1734'
def read(p):return json.loads(p.read_text(encoding='utf-8'))
raw=P/'eval_results.first_pass.jsonl'
if not raw.exists():shutil.copy2(P/'eval_results.jsonl',raw)
rows=[json.loads(x) for x in raw.read_text(encoding='utf-8').splitlines()]
assert len(rows)==30 and len({r['case_id'] for r in rows})==30
reviews={
'S01':('PASS','两个授权服务器、ID和ONLINE/OFFLINE状态与list_servers相符；明确离线样本陈旧，没有错误容量换算。'),
'S02':('PASS','ONLINE、心跳09:35:31.93Z、指标时间及附带资产指标与当次get_server_status一致。'),
'S03':('PASS','CPU47.25%、核心17/64/71、容量MB/MiB/GB/GiB与程序字段相符；没有编造磁盘聚合或负载持续时间。'),
'S04':('NEEDS_REVIEW','实际可用21.61GiB、Swap占满且PSI缺失；回答先断言“不紧张”，后限定不能判断竞争/完全健康，整体压力定性仍需独立专家复核。'),
'S05':('PASS','真实心跳/采集/接收时间与FRESH字段相符，限制为单点快照、不推断持续时间；没有把未来采集时间解释成正向延迟。约8秒采集时钟偏差仍作为环境限制记录。'),
'G01':('PASS','真实GPU数量2、索引0/1、型号、UUID及附带指标匹配；本次不含型号标称容量猜测。判定依据当次回答和证据，不因修改Prompt直接通过。'),
'G02':('PASS','两卡完整列出，已用/空闲显存、50%平均利用率、30.94%容量占比与overview一致；NVML活动率未当作容量比。'),
'G03':('PASS','总49140MiB、已用25332.06MiB、空闲23807.94MiB及51.55%容量比正确，区分控制器活动66%与计算100%。'),
'G04':('PASS','限定GPU0，两个真实PID及显存字节值正确；合计25870467072B约25.87GB正确，没有命令行或整卡利用率归因。'),
'G05':('PASS','GPU0最高98%、GPU1为0%、极差98、阈值30及imbalanced与compare_gpu_load相符，没有推测持续时长或故障根因。'),
'G06':('PASS','8589934592字节和gpu_count=1正确，推荐GPU1与真实工具一致；说明历史证据不可用、未预留/启动且启动前复查。'),
'G07':('PASS','normal、65°C、51.55%容量占比与规则一致；本次明确不覆盖降频、换页和长期健康，已不含Baseline的无依据否定结论。'),
'G08':('NEEDS_REVIEW','正确查询平台UTC近30分钟、65样本、首36/末98/最小0/最大100，且识别两段缺口；“近7分钟持续高位”对含0/23/26/57低值的采样仍有过度概括风险，需专家复核趋势措辞。'),
'T01':('PASS','真实登记列表为空，明确无登记不等于无进程，没有创建虚假任务。'),
'T02':('PASS','3个RUNNING和2个MISSING区分正确，确认数0、UID_UNAVAILABLE、job_id=null均有依据；说明派生总计包含MISSING。'),
'W01':('FAIL','RUNNING三条RSS合计17404747776B=17.40GB、GPU显存30509367296B=30.51GB；回答16.97GB和29.90GB错误。CPU2590.1%正确，全部5条合计口径有说明。'),
'W02':('PASS','LOW_CONFIDENCE及无已确认同配置画像与API一致，未编造P50/P95、峰值或样本数。'),
'W03':('FAIL','预测工具要求至少三次时长“超过当前运行时长”的成功历史，回答表格却说“短于”；后文又说长于，前后矛盾。虽正确拒绝精确ETA，预测依据仍不忠实。'),
'W04':('PASS','实际调用容量工具，10240MiB/8192MiB和gpu_index=0正确；安全预算23295.94/19957.66MiB、allowed=true、UNAVAILABLE与缺失项均对应证据；只读建议并要求重新准入。'),
'W05':('FAIL','未执行必需estimate_available_capacity，因未给需求而停止；标题称CPU/IO确实紧张，却承认PSI缺失不能判断IO，且引用包含MISSING的总CPU描述当前活跃负载。'),
'R01':('PASS','真实READY文档的GPU/主机核查清单、准入和证据边界均有检索片段支持，文件/ID/章节匹配。'),
'R02':('PASS','PID复用和服务器+PID+启动时间匹配文档；真实引用doc6-v1-00002，没有声称snapshot_id+pid退化实现。run_id仅作为建议并声明文档无实现细节。'),
'R03':('PASS','真实片段明确无统一完成时限，未把20分钟伪造成规则；引用真实文件和章节。'),
'R04':('FAIL','已完成RAG+实时+预算链路，但43548.0625MiB安全VRAM约42.53GiB，回答按约43.5GiB规划超过预算；还把整卡5.33GB混作PID981029的进程显存，后者实际4638900224B约4.64GB。'),
'C01':('PASS','真实session/request API，GPU信息及单位正确，Java消息API有2条顺序正确记录且保存的回答一致；会话上下文与永久私人记忆有区别。'),
'C02':('FAIL','没有重新调用工具，沿用C01；且把46200.32MB换成45.12GiB（应43.03GiB），24964.43MB换成24.38GiB（应23.25GiB）。排序正确不等于任务通过。'),
'C03':('FAIL','原会话4条消息成功恢复，结束后6条顺序正确，且说明需重取；但继续重复C02的45.12/24.38GiB错误，传播错误历史，事实忠实性不合格。')}
for r in rows:
 r['http_attempted']=r['latency_ms'] is not None
 if r['case_id'] in reviews:
  status,note=reviews[r['case_id']]
  r['status']=status;r['review']={'evaluator':'Independent Codex evidence review, not tested DeepSeek self-judge or human signoff','reason':note}
  r['failure_reason']=None if status=='PASS' else note
  r['task_success']=status=='PASS' if status in ['PASS','FAIL'] else None
  r['answer_faithful']=r['evidence_match']=status=='PASS' if status in ['PASS','FAIL'] else None
  if r['case_id'] in ['C02','C03']:
   r['evidence_basis']='Historical actual tools in C01 plus Java session records; absence of fresh calls is itself checked for C02'
(P/'eval_results.jsonl').write_text(''.join(json.dumps(x,ensure_ascii=False)+'\n' for x in rows),encoding='utf-8')
def ratio(n,d):return {'numerator':n,'denominator':d,'value':n/d if d else None}
def fmt(m):return f"{m['numerator']}/{m['denominator']} = {100*m['value']:.2f}%" if m['value'] is not None else 'N/A'
def quantile(a,q):
 a=sorted(a)
 if not a:return None
 k=(len(a)-1)*q;i=int(k);return round(a[i]+(a[min(i+1,len(a)-1)]-a[i])*(k-i),2)
counts={s:sum(r['status']==s for r in rows) for s in ['PASS','FAIL','ERROR','BLOCKED_ENV','NEEDS_REVIEW']}
executed=[r for r in rows if r['http_attempted']];completed=[r for r in executed if r['status']!='ERROR']
route=[r for r in executed if r['expected_tools'] and r['tool_selection_correct'] is not None]
arguments=[r for r in executed if r['actual_tools'] and r['tool_arguments_correct'] is not None]
facts=[r for r in completed if r['answer_faithful'] is not None]
units=[]
for r in rows:
 if not r['case_id'].startswith('R') or not r.get('agent_answer'):continue
 sources=[s for e in r.get('sse_events',[]) if e.get('type')=='sources' for s in e['results']]
 for s in sources:
  if s['document_title'] in r['agent_answer'] and s.get('section') in r['agent_answer']:
   unit=(r['case_id'],s['document_title'],s['section'])
   if unit not in units:units.append(unit)
lat=[r['latency_ms'] for r in completed];ttft=[r['ttft_ms'] for r in executed if r['ttft_ms'] is not None]
summary={'total':30,'counts':counts,'actual_executed':len(executed),'completed_answers':len(completed),'coverage_attempted':ratio(len(executed),30),'coverage_completed':ratio(len(completed),30),
 'task_completion_rate_reviewed':ratio(counts['PASS'],counts['PASS']+counts['FAIL']),
 'success_all_attempts_lower_bound':ratio(counts['PASS'],len(executed)),
 'success_all_cases_lower_bound':ratio(counts['PASS'],30),
 'tool_selection_accuracy':ratio(sum(r['tool_selection_correct'] for r in route),len(route)),
 'tool_argument_accuracy':ratio(sum(r['tool_arguments_correct'] for r in arguments),len(arguments)),
 'fact_faithfulness':ratio(sum(r['answer_faithful'] for r in facts),len(facts)),
 'rag_citation_accuracy':ratio(len(units),len(units)),'citation_units':units,
 'citation_definition':'Unique explicit filename+section reference pairs supported by actual SSE sources; not claim accuracy or all reference mentions.',
 'rag_missing_citation_cases':[r['case_id'] for r in rows if r['case_id'].startswith('R') and r['http_attempted'] and not any(x[0]==r['case_id'] for x in units)],
 'latency_ms':{'p50':quantile(lat,.5),'p95':quantile(lat,.95),'n':len(lat),'scope':'Client end-to-end including SSE model/tools, trace reads and session API verification when applicable; ERROR excluded'},
 'ttft_ms':{'p50':quantile(ttft,.5),'p95':quantile(ttft,.95),'n':len(ttft),'scope':'First nonempty token, can be preamble'},
 'per_case_tool_counts':{r['case_id']:len(r['actual_tools']) for r in rows},
 'mean_tools_per_executed_case':sum(len(r['actual_tools']) for r in executed)/len(executed),
 'model_calls_mean':sum(r.get('model_call_count',0) for r in executed)/len(executed),
 'per_case_model_counts':{r['case_id']:r.get('model_call_count') for r in rows},
 'modules':{g:{s:sum(r['case_id'].startswith(g) and r['status']==s for r in rows) for s in counts} for g in 'SGTWRC'},
 'session_persistence':{'cases':3,'saved_message_counts':[len(r.get('messages_after',[])) for r in rows if r['case_id'].startswith('C')],
                        'passed_storage_checks':sum(r.get('session_persisted',False) for r in rows if r['case_id'].startswith('C'))},
 'failure_types':{'missing_required_realtime_or_budget_tool':['W05','C02'],'arithmetic_or_unit_or_scope_errors':['W01','R04','C02','C03'],'misstated_prediction_evidence':['W03']},
 'tokens_cost':'UNAVAILABLE','baseline_unchanged':True,'real_model':'deepseek-flash','no_case_retries':True}
(P/'eval_summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
e=read(P/'environment.json');end=read(P/'end_state.json')
old=read(P/'previous_report_hashes.json');assert all(hashlib.sha256((ROOT/f).read_bytes()).hexdigest()==h for f,h in old.items())
assert all(hashlib.sha256((ROOT/f).read_bytes()).hexdigest()==h for f,h in e['source_hashes'].items())
baseline=read(P.parent/'part1/eval_summary.json');bad=read(P.parent/'part1_after_fix/eval_summary.json');targeted=read(P.parent/'part1_after_fix_targeted/eval_summary.json')
base_rows={r['case_id']:r for r in map(json.loads,(P.parent/'part1/eval_results.jsonl').read_text(encoding='utf-8').splitlines())}
target_rows={r['case_id']:r for r in map(json.loads,(P.parent/'part1_after_fix_targeted/eval_results.jsonl').read_text(encoding='utf-8').splitlines())}
case_table='\n'.join(f"| {r['case_id']} | {base_rows[r['case_id']]['status']} | {target_rows.get(r['case_id'],{}).get('status','—')} | {r['status']} | {len(r['actual_tools'])} | {r.get('failure_reason') or '工具/事实证据核验通过'} |" for r in rows)
module_table='\n'.join(f"| {g} | {' / '.join(str(summary['modules'][g][s]) for s in counts)} | {fmt(ratio(summary['modules'][g]['PASS'],summary['modules'][g]['PASS']+summary['modules'][g]['FAIL']))} |" for g in 'SGTWRC')
comparisons='\n'.join(f"| {label} | {fmt(baseline[key])} | {fmt(summary[key])} |" for label,key in [('任务完成率（已判定）','task_completion_rate_reviewed'),('全部实际尝试成功比例','success_all_attempts_lower_bound'),('工具选择准确率','tool_selection_accuracy'),('工具参数准确率','tool_argument_accuracy'),('事实忠实率','fact_faithfulness'),('RAG引用准确率','rag_citation_accuracy')])
report=f'''# AIOps Agent 30题真实端到端综合评测报告

## 结论

本次在服务器在线、真实采集恢复的条件下完成一次30题重新评测：**实际执行27题，PASS {counts['PASS']}、FAIL {counts['FAIL']}、ERROR {counts['ERROR']}、BLOCKED_ENV {counts['BLOCKED_ENV']}、NEEDS_REVIEW {counts['NEEDS_REVIEW']}**。3题仍因没有登记训练任务JOB_ID而未执行；没有创建虚假任务补分。所有27次SSE请求正常完成，但完成请求不等于完成任务。

基础监控、GPU查询、真实RAG和持久化存储链路可用；多工具组合及近期历史查询本轮未再出现508。主要剩余风险是容量换算/合计与证据表述错误，以及多轮“现在”问题不重新调用工具。C01/C02/C03的数据库保存与恢复均通过，但C02未重新取数且C03传播了其单位错误。

## 1. 环境、范围与执行方法

- 时间：{min(r['started_at'] for r in rows)} 至 {end['ended_at']}，UTC；北京时间加8小时。目录名是运行标识，精确时间以上述时间戳为准。
- 仓库 `D:/work/aiops/backend`，Commit `{e['commit']}`，包含既有未提交改动；源码SHA256和脏工作区清单保存在environment.json。本次没有修改业务代码或判分标准。
- 真实模型 `{e['model']}`，通过原OpenAI兼容适配器调用；GraphAgent、Tool Calling、Java API、MySQL、Qdrant和本地Embedding均为真实实现。没有预设模型回答。
- 复用独立MySQL库 `{e['database']}`，本轮专用账号 `{e['account']}` 通过正常注册与工作空间成员API授权。只创建测试账号、测试会话和非敏感知识文档；原业务库不写入本轮测试数据。
- 授权服务器2台，动态选择在线服务器{e['discovered']['SERVER_ID']}；两张GPU，实际索引0/1，平台上报NVIDIA GeForce RTX 4090 D。本次GPU总容量约47.99GiB/卡按真实上报记录，不按型号修改数据，也不视为已完成硬件规格审计。
- Linux采集器开始前active，连续收到真实采样，结束状态见end_state.json。采集时间比接收时间约快8秒；模型应区分采集时钟和服务端时间，不把偏差当成真实负载变化。
- 实际运行记录包括3条RUNNING和2条MISSING；无登记训练任务，故T03/T04/T05阻塞。历史画像和预测仅覆盖LOW_CONFIDENCE/样本不足分支。
- 本次专用KB为{e['discovered']['KB_ID']}，文档part1_gpu_runbook.md已READY。文档和正确知识证据见rag_ground_truth.md，未读取其他用户私有文档作为本轮标准答案。
- 先执行S01/S03/G01/G03/R01冒烟，再执行剩余题；每题只提交一次。C01创建真实会话，C02复用会话并要求实时取数，C03重新读取历史后追问。测试级封装只记录源工具和模型调用，不改变回答或生产SSE协议。SSE原始事件保存在逐题记录中。
- 复核由独立Codex评测Agent对照真实工具结果完成，不是被测DeepSeek自评，也不是人工专家签核；2道语义定性题仍NEEDS_REVIEW。所有首次自动判定和原始回答另存eval_results.first_pass.jsonl。

## 2. 本次总体指标

| 指标 | 本次结果 | 分母与限制 |
|---|---|---|
| 总数 / 实际执行 / 完整回答 | 30 / {len(executed)} / {len(completed)} | 未执行题不计成功 |
| PASS / FAIL / ERROR / BLOCKED_ENV / NEEDS_REVIEW | {' / '.join(str(counts[s]) for s in counts)} | 状态互斥 |
| 有效执行覆盖率 | {fmt(summary['coverage_attempted'])} | 真实HTTP执行/30 |
| 任务完成率 | {fmt(summary['task_completion_rate_reviewed'])} | PASS/(PASS+FAIL)，排除待复核和阻塞 |
| 全部尝试成功比例 | {fmt(summary['success_all_attempts_lower_bound'])} | PASS/27，包含待复核的不确定性 |
| 全题成功下界 | {fmt(summary['success_all_cases_lower_bound'])} | PASS/30 |
| 工具选择准确率 | {fmt(summary['tool_selection_accuracy'])} | 有工具要求且可判定题；C03不要求新工具 |
| 参数准确率 | {fmt(summary['tool_argument_accuracy'])} | 实际有调用且可检查参数的题，不为漏调工具补分 |
| 事实忠实率 | {fmt(summary['fact_faithfulness'])} | 可明确核验题级关键事实，不是逐句全覆盖统计 |
| RAG引用准确率 | {fmt(summary['rag_citation_accuracy'])} | 可验证文件-章节引用组合去重；不代表回答全正确 |
| 缺失引用 | {len(summary['rag_missing_citation_cases'])}题 | 需引用且执行的RAG题 |
| 总延迟P50 / P95 | {summary['latency_ms']['p50']} / {summary['latency_ms']['p95']} ms | 客户端端到端，含SSE/工具、轨迹读取及适用的会话API核验，n={len(lat)} |
| TTFT P50 / P95 | {summary['ttft_ms']['p50']} / {summary['ttft_ms']['p95']} ms | 第一枚非空token，可能为前导语，n={len(ttft)} |
| 平均工具调用 / 模型调用 | {summary['mean_tools_per_executed_case']:.2f} / {summary['model_calls_mean']:.2f} | 每道实际执行题 |
| Token与费用 | UNAVAILABLE | 原stream适配器不提供真实usage，不估造 |

参数正确与工具选择是独立维度：W05没有调用必需预算工具、C02没有调用实时工具，选择判错；其他已执行工具的合法参数仍可正确。C03无需新工具，事实核对可追溯到C01和恢复的会话内容。R04引用真实但预算换算错误，因此引用通过与事实失败可以同时发生。

## 3. 分类结果

| 类别 | PASS / FAIL / ERROR / BLOCKED_ENV / NEEDS_REVIEW | 已判定通过率 |
|---|---|---|
{module_table}

## 4. 与历史结果对比

| 指标 | 原始Baseline | 本次 |
|---|---|---|
{comparisons}
| 运行异常 | 4 | {counts['ERROR']} |
| 完整响应P95 | {baseline['latency_ms']['p95']/1000:.2f}s，n={baseline['latency_ms']['n']} | {summary['latency_ms']['p95']/1000:.2f}s，n={len(lat)} |

Baseline为13PASS/6FAIL/4ERROR/3BLOCKED_ENV/4NEEDS_REVIEW，真实执行27题；本次也执行27题且相同3题阻塞，但已判定分母由19变为25，新增可完成的复杂题、不同回答和待复核范围均影响百分比。总体任务完成率变化不能简单视为同一19题的精确提升。

工具选择分母从23变为26：Baseline中工具未执行即出错的复杂题当时不可判；本次这些题可判，且W05/C02漏调，因而本次百分比下降不等价于所有路由普遍退化。引用组合数从9变为{len(units)}，是实际引用内容变化，不是固定九条逐一复测。

中间的修复后30题复测曾遇服务器离线，仅4题有效；其3PASS/26BLOCKED_ENV/1NEEDS_REVIEW不适合当质量比较基线。修复后14题专项为5PASS/5FAIL/4NEEDS_REVIEW，覆盖不同，单列于下表，不能混入本次30题。每轮均保留，没有覆盖历史低分或阻塞记录。

| 题号 | Baseline | 14题专项 | 本次 | 本次工具次数 | 判定依据/不足 |
|---|---|---|---|---:|---|
{case_table}

## 5. 主要失败模式及证据

1. **确定性数值仍被模型自行重算（4题）**：W01把RUNNING三条RSS17.40GB、显存30.51GB算成16.97/29.90GB；R04把43548.0625MiB约42.53GiB说成43.5GiB并用于规划；C02把MB按MiB换成GiB；C03重复历史错误。程序提供部分派生字段并不保证最终答案只使用这些字段。
2. **必需工具漏调（2题）**：W05未调用estimate_available_capacity；C02直接沿用上一轮，虽排序GPU1正确，仍未满足“现在重新取数”。工具选择本轮失误集中在这两题。
3. **预测依据被反向转述（1题）**：W03工具要求历史时长超过当前已运行时长，回答表格说“短于”，后文又说“长于”。拒绝编造ETA本身正确，但依据矛盾依然不通过。
4. **语义结论仍需专家复核（2题）**：S04在PSI缺失时对内存“不紧张”的定性边界；G08对混有低值及缺口的序列称“近7分钟持续高位”。不因底层工具正常就自动通过。

以上类型存在重叠，不相加为FAIL总数。当前FAIL为W01、W03、W05、R04、C02、C03。详细原题、实际工具、参数、回答及差异见eval_failures.md和eval_results.jsonl。

## 6. 综合结论与优化优先级

- **基础功能可用**：S01/S02/S03/S05及GPU基础、总览、进程、负载、选卡、健康查询均有真实工具证据；本轮未再因合理多工具计划直接508。
- **历史链路恢复但趋势表述需约束**：G08使用程序计算窗口取得65条真实样本，正确说明两段缺口；不能据此承诺所有未来历史查询或趋势判断可靠。
- **RAG来源可追溯，答案仍要单独核验**：所有RAG题都检索到专用READY文档，R02不再虚构snapshot_id+pid实现；R04的容量建议仍因数值错误失败。
- **会话存储正常，实时性与错误传播未解决**：C01/C02/C03分别保存2/4/6条消息并恢复正确；C02漏调实时工具，C03继承错误数值，不能说多轮任务整体通过。
- **训练证据覆盖有限**：有真实运行进程，但没有登记训练任务和足量同配置成功历史；竞争分析、充分进度ETA和预测准确率仍未验证。

优先改进：P1对“现在/当前”的续问强制验证新鲜工具证据；P1对容量预算、RUNNING合计和历史摘要提供完整确定性结果并做受限数值核对；P1区分RUNNING与MISSING集合以免旧值污染当前合计；P2对预测原因采用结构化方法字段而非自由改写；P2继续约束缺PSI/历史缺口时的定性结论。本轮只评测，没有实施这些修复或为了提高分数调整题目。

本报告是单次真实模型结果，不是稳定性概率、并发测试或安全评测。监控负载在变化，记录以每题当次工具响应为准；没有拿不同时间的API值否定当时回答。当前服务仍连接独立评测库。
'''
(P/'eval_report.md').write_text(report,encoding='utf-8')
(P/'comprehensive_report.md').write_text(report,encoding='utf-8')
failures=['# 本次失败、阻塞和待复核明细']
for r in rows:
 if r['status']=='PASS':continue
 failures.append(f"## {r['case_id']} · {r['status']}\n\n问题：{r['question']}\n\n预期工具：{r['expected_tools']}\n\n实际工具：{r['actual_tools']}\n\n参数：{json.dumps(r['actual_tool_arguments'],ensure_ascii=False)}\n\n原因：{r['failure_reason']}\n\nAgent回答：\n\n{r['agent_answer'] or '未执行，无模型回答'}\n\n证据：eval_results.jsonl同题tool_results_summary，以及eval_traces.jsonl对应case_id。C02/C03还应对照C01工具和会话消息。")
(P/'eval_failures.md').write_text('\n\n'.join(failures),encoding='utf-8')
environment=f"# 本次运行环境\n\nCommit：{e['commit']}（包含既有未提交修改，哈希见environment.json）。\n\n模型：{e['model']}；版本：{json.dumps(e['versions'],ensure_ascii=False)}。JDK运行版本沿用本地21.0.12.1。\n\n数据库：{e['database']}（隔离副本）；专用账号：{e['account']}，权限通过正常API授予。凭据未写入报告。\n\n动态对象：{json.dumps(e['discovered'],ensure_ascii=False)}。发现5条运行记录，其中3RUNNING/2MISSING；无JOB_ID。\n\nQdrant独立容器、真实Embedding离线加载、专用KB单文档READY；知识文档不变。Linux采集active，数据实时更新，采集端约快8秒。\n\n结束服务器状态见end_state.json；本轮及历史报告和业务源码哈希均核验不变。完整请求一次/题，无修复重算、无Mock、安全异常或压力测试。"
(P/'eval_environment.md').write_text(environment,encoding='utf-8')
(P/'README.md').write_text('综合报告见 comprehensive_report.md（同 eval_report.md）。首次机器记录保存在eval_results.first_pass.jsonl，独立证据复核见最终eval_results.jsonl的review字段。所有先前报告未改动。\n',encoding='utf-8')
print(json.dumps({k:summary[k] for k in ['counts','task_completion_rate_reviewed','success_all_attempts_lower_bound','tool_selection_accuracy','tool_argument_accuracy','fact_faithfulness','rag_citation_accuracy','latency_ms','ttft_ms']},ensure_ascii=False))
