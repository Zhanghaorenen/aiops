# 自主故障调查

故障页选择事件后点击「AI 深度调查」。训练页从已发现且有权查看的任务点击同名按钮，自动关联运行记录。告警页沿用「调查」入口，一条告警复用一个事件。调查由现有数据库任务和租约在后台执行，报告仅发起人可读，页面每五秒恢复持久化进度。

调查图依次执行对象识别、初始证据、候选原因、补查计划、只读工具和证据复核。每轮最多三个补查，最多三个候选原因，工具名称和参数通过 Pydantic 验证。工具只访问固定服务器及实际发现的 GPU、运行和训练任务。重复参数查询不会再次执行。工具失败、过期和权限受限保留证据缺口。报告区分客观采样、候选解释、反对证据、缺口、建议与验证。

预算环境变量为 `AIOPS_INVESTIGATION_ROUNDS=4`、`AIOPS_INVESTIGATION_CALLS=12`、`AIOPS_INVESTIGATION_SECONDS=150`，分别允许 1–4、1–12、1–150。沿用同一 LLM、Embedding、Qdrant、JWT 与服务密钥配置。启动项目时 Flyway 应用 V17，扩展原任务进度和事件运行关联，并新增服务器级自动调查策略表。

管理员在故障详情的「告警自动调查设置」配置当前服务器。默认关闭。支持 WARNING/CRITICAL、60–86400 秒冷却和每天 1–20 次服务器总调查上限。策略以配置管理员身份执行，每次重新核验权限。仅针对五分钟内仍触发的新告警，每条告警自动调查一次。已有手工或自动调查会占用服务器的每日预算。未完成任务保留原租约过期恢复机制。

模拟场景可在仓库根目录运行（PowerShell）：

```powershell
$env:PYTHONPATH='agent/ai-service'
agent/.venv/Scripts/python.exe agent/ai-service/tests/simulate_investigation.py
```

该场景使用明确标注的模型及监控测试替身，验证低 GPU 与高 IO 证据先触发并发压力查询，再触发主机历史查询，最后因缺少 Step Time 保留性能下降判断缺口。真实模型调用效果需结合部署环境验证。

运行态竞争响应保留原字段，新增 summary、activeTasks、overlappingPairs、pressureSignals、performanceEvidence 和曲线数据。RESOURCE_OVERLAP 表示共用，RESOURCE_PRESSURE 表示主机或整卡压力，PERFORMANCE_DEGRADATION 只在关联任务同阶段、并发前后各至少三个对齐且去重的进度样本出现超过 30% 中位数变化时返回。训练性能来源为用户上报，阶段变化、缺测与旧采样返回 UNKNOWN。
