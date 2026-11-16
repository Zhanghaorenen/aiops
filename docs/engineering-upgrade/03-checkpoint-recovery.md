# 调查节点恢复

Java 与 FastAPI 同时设置 `AIOPS_CHECKPOINT_ENABLED=true`。Redis Compose 默认关闭 AOF 与 RDB，本功能使用 MySQL，节点写入与当前任务租约位于同一事务。V20 新增 checkpoint 和 pending writes 表。LangGraph 1.2 的原生 saver 接口保存各通道版本、metadata、父 checkpoint 及 pending writes；同步耐久模式在进入下一节点前等待保存。thread_id 使用调查运行表主键（不同于训练工作负载 UUID）。

Worker 只能使用当前用户、服务密钥和本次领取的租约读写。Worker 对原队列中的候选行执行 SELECT FOR UPDATE SKIP LOCKED，在同一事务内直接换租；过期运行最多可重新领取三次。切换后的 Worker 拿新 token，在原调查线程恢复，复核服务器权限。阶段、证据、预算、去重键和已发现的目标一起恢复。已完成的节点可复用；在写入前中断的只读查询可能重试。调查工具名单不包含资源调整。历史观测保留原采样时间。保留期默认成功 7 天、失败 14 天。MySQL 备份包含节点数据，应按监控数据控制访问。关闭开关后用原始无 checkpoint 流程处理新任务。
