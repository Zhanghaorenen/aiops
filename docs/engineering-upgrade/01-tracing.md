# 调用追踪

安装 `agent/ai-service/requirements-engineering.txt`，设置 `OTEL_ENABLED=true`。Java 进程通过 `JAVA_TOOL_OPTIONS=-javaagent:/绝对路径/opentelemetry-javaagent.jar` 加载官方 agent，并使用 OTLP HTTP/protobuf。为 Java 设置独立的 OTEL_SERVICE_NAME，禁用数据库语句、HTTP 头和查询串采集。调查任务入队保存 W3C 父上下文，领取后恢复父子关系。Python 只导出操作名称、验证后的关联 ID、耗时、异常类型、模型配置和供应商返回的 token 计数。完整提示词和证据正文不外发。Langfuse 使用原生 OTLP 接收端，由后台批量发送。调查报告保留本次执行时间线，可通过故障页面查询。普通聊天通过响应头 X-Trace-ID 与 OTLP 平台关联。FastAPI 在响应结束后以用户 JWT 和独立服务密钥投递脱敏的时间线，MySQL V22 存七天。故障页按 ID 可查看本人的对话或调查执行记录。Java API 为 GET /api/ai/traces/{traceId} 与受服务密钥限制的 POST /api/ai/traces。

Java 的 HTTP、JDBC、Redis 自动探针及安装方式参见 [OpenTelemetry Java agent](https://opentelemetry.io/docs/zero-code/java/agent/)。Langfuse 对模型、token 和错误的 OTLP 字段映射见 [Langfuse OpenTelemetry](https://langfuse.com/integrations/native/opentelemetry)。
