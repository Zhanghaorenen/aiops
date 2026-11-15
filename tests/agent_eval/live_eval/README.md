# 第一部分真实端到端评测

从 Spring Boot 仓库根目录执行，使用已有 `agent/.venv/Scripts/python.exe`。

1. `prepare.py` 创建一次性的独立MySQL副本 `aiops_eval_part1_20261113`；若已存在则拒绝覆盖。数据库和模型凭据只从Git忽略的 `data/local-run` 读取。
2. `start.ps1` 注入隔离环境配置，通过现有 `scripts/run.py backend/ai` 启动服务。`sitecustomize.py` 仅在指定AIOPS_EVAL_TRACE时启用观测，不改变工具结果或模型请求。Qdrant使用独立 `aiops-eval-part1` 容器和空存储，原知识库向量不复制。
3. `python tests/agent_eval/live_eval/live_eval.py` 创建专用账号和非敏感知识文档，自动发现资源并执行一次30题。存在结果文件时拒绝再次执行。先执行S01/S03/G01/G03/R01；没有JOB_ID的题仅记录阻塞。出现个别依赖错误时保留首次错误，继续独立可测题，不重试用例。
4. `report.py` 只读取首次结果和证据进行独立复核及报告汇总。它不会调用模型。首次记录保留在 `eval_results.first_pass.jsonl`，判定原因在最终结果的review字段。报告没有把未复核记录记为PASS。

源码未修改。报告输出位于 `tests/agent_eval/reports/part1/`。HTTP/SSE真实事件、源工具协程的参数/返回/耗时和模型请求计数分别保存，不以HTTP 200为成功标准。当前限额在模型单轮工具数超过3时整轮拒绝，无法从已有SSE或源工具封装取得被拒绝调用的具体参数，这些题相应指标缺测。

环境配置可能启动业务调度器；运行中的8080连接评测副本，Linux采集Agent也向该副本上报。切换回原业务环境前应停止本次服务并按常规配置重启。本次没有执行真实训练任务、修改训练进程或第二/第三部分测试。
