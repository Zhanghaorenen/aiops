# 模型评估

从仓库根目录使用已经安装 AI 服务及工程化依赖的 Python 运行 `python -m evaluation.runner`。默认读取 evaluation/configs/evaluation.yaml，可用 --config 指定其他配置，命令行参数优先。默认执行 60 题 × A/B/C，使用指定模型与人工标注的模拟观测。A 是 GraphAgent，B 是原 Supervisor（原实现已有证据校验），C 在 B 上启用真实召回逻辑和同用户、已验证的模拟偏好。实验专用 --validation off 可关闭校验做消融。参数用 --help 查看。使用 --embedding local 运行已配置的本地语义模型；默认特征哈希用于可重复验收。可指定 --rag dense/hybrid/rerank、--judge 与按百万 token 输入/输出单价。未配置价格或供应商用量时成本留空。

默认生成独立目录、逐样本 JSONL、汇总 JSON/CSV/Markdown。--mysql 使用 PyMySQL 将汇总写入 V21 表，账号由 AIOPS_EVAL_DB_HOST/PORT/NAME/USER/PASSWORD 提供，仅授予该表 INSERT 权限。各模式共享题目、工具 schema、语料与模型；记录内容哈希和实际配置。每题单独生成 Trace ID，可通过 Langfuse 的 OTLP 入口关联。

路由、工具选择按集合精确匹配；工具成功率为成功调用数/实际调用数。覆盖率为返回的必需证据项/标注项。诊断和完成要求取得全部必需证据、命中标注发现、避免禁断断言并在需保留判断时表达不确定。引用指标检查正文证据 ID 是否存在；过期使用统计已确认发现支持引用中标记过期的比例。标注发现用正则进行保守匹配，精确率为命中发现/(命中发现+命中禁断断言)，召回率为命中发现/标注数。未标注的自然语言推断需人工复核。可选 --trace-content 将有界、脱敏的模拟评测提示和模型输出附加到 OTLP；业务 Trace 始终不收集内容。可选 Judge 单独留档，不替代这些分数。样本无适用分母时留空，汇总显示有效样本数。错误与未运行单列。延迟采用实际耗时的最近秩分位；平均 token 取有完整供应商用量的调用。检索使用独立查询的前 10 个唯一 chunk，Recall@5、MRR@10 与二元相关性 nDCG@10。重排改善比较同一候选集重排前后 nDCG。
