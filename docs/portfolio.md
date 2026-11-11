# 求职展示与源码讲解

## 项目描述（可按实际负责范围使用）

**AIOps 共享 GPU 运维平台｜个人项目**

技术栈：Spring Boot、Spring Security、MyBatis-Plus、MySQL、Vue 3、FastAPI、LangChain、LangGraph、Qdrant、psutil / NVML。

- 实现轻量 Agent 定时采样及主机/GPU 历史查询，使用 Flyway 管理数据库演进，区分缺测、不可用与真实零值。
- 实现共享训练任务资源准入：服务器行锁保护资源承诺，整机 RAM 与单卡显存联合检查，支持排队复查及 PID＋启动时间关联。
- 集成 LangGraph 只读工具调用、SSE 对话和带来源的 RAG 检索，沿用用户 JWT 校验数据权限，输出证据与待验证假设。
- 实现共享工作空间角色、预设告警与异步 Incident 调查，通过本地自动化覆盖权限、状态机和资源冲突等场景。

没有测量的数据不要填写：模型准确率、线上用户量、生产 QPS、性能提升比例和生产可用性。测试数量应说明版本、环境及跳过项，不能当成模型任务成功率。说明依赖框架的能力与自己的业务实现，并如实解释 AI 辅助开发方式；避免使用无法现场解释的表述。

## 面试阅读路线

| 问题 | 源码位置 | 讲解重点 |
|---|---|---|
| 为什么不同 GPU 也可能冲突？ | TrainingJobService.java | RAM 属于整机共享资源 |
| 如何避免并发超额预约？ | TrainingJobService.java | 节点行锁、事务隔离、空结果与当前读 |
| 为什么不能重复扣运行任务估计量？ | TrainingJobService.evaluate | 实际可用量已反映占用，远期承诺另算 |
| PID 为什么不能单独标识任务？ | collectors/process.py | PID 复用、启动时间、权限不足与未知退出 |
| 角色更改后 JWT 没过期怎么办？ | ClusterPermissionService.java | JWT 标识身份，数据库检查当前成员权限 |
| 模型幻觉如何限制？ | agents/graph_agent.py | 有限工具、只读、证据时间、缺失信息和引用 |
| 为什么使用数据库队列？ | InvestigationTaskService / Worker | 复用现有数据库，以幂等与租约控制异步执行 |
| RAG 失败怎样处理？ | rag/ingestion_service.py | 原文与索引分离、状态补偿、版本约束 |

Java 路径以 `src/main/java/com/aiops/backend` 为根；Python 路径以 `agent` 或 `agent/ai-service/app` 为根。

## 需要能解释的限制

当前只是资源准入与协同，不具备显存硬隔离；取消登记不会终止系统进程。规则只能给候选瓶颈，相关性不证明因果。未完成的真实 V0.4 验收应主动说明，不把历史截图或 Mock 回归描述成生产效果。

推荐按 [演示流程](project-demo.md) 准备一次录屏，展示界面、一次权限拒绝、一次资源冲突和一次带证据的工具调用，而不是逐页浏览所有功能。
