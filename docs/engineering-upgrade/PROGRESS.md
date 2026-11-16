# 工程升级进度

| 阶段 | 状态 | 验收依据 |
|---|---|---|
| 0 本地基线 | COMPLETED | Spring Boot 仓库 `7c3c845`，使用本地文件和依赖版本 |
| 1 跨节点追踪 | COMPLETED | Java agent、真实 Python SDK、同一 traceparent、模型用量、用户追踪界面与脱敏导出已验证。实际 Langfuse 租户待部署验收 |
| 2 混合检索 | COMPLETED | 真实 Qdrant 双路索引、游标重建、版本权限、本地 CPU CrossEncoder 及三组质量对比 |
| 3 节点恢复 | COMPLETED | MySQL 8.4 真实迁移、并发租约、FastAPI 与 JVM 跨进程恢复 |
| 4 MCP | COMPLETED | STDIO 六工具端到端、HTTP 签名验证、双用户身份检查与重连。实际远程 IdP 待部署验收 |
| 5 模型评估 | COMPLETED | 60 题 × 三种真实模型执行、原始指标与明细、单题 Judge、MySQL 汇总 |
| 6 集中验证 | COMPLETED | 见测试、故障、安全与性能记录；生产规模及部署约束单列 |

[交付与启动说明](README.md) · [集中测试结果](06-final-testing.md)
