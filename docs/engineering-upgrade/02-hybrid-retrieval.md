# 混合检索

当前部署定义使用 Qdrant 1.13.6。原匿名 Dense 集合保持可读。同版本伴随集合后缀为 `_bm25_v1`，采用 Qdrant IDF、BM25 饱和与长度归一化。中文采用单字和相邻双字，英文保留带版本点号、下划线、斜线和短线的标识符。两个分支使用相同的授权 KB、READY、文档和索引版本过滤；召回后再经 Java 复核。

启用 `AIOPS_HYBRID_ENABLED=true` 后先启用所有索引写入实例，再从 ai-service 目录运行 `python -m app.rag.rebuild_sparse_index --cursor ../../data/sparse-cursor.json`。失败后复用游标，重新全量扫描加 `--restart`。每批确认后原子更新游标，重复 upsert 使用原 chunk UUID。每批写入后重新检查 Dense 源记录并清理并发删除的孤立向量；查询端始终复核 MySQL 文档版本。原集合不需要重建。关闭开关恢复 Dense 查询，再启用前重新扫描停用期间的文档。

默认 Dense/BM25 各取 20，RRF 常数为 60。启用 `AIOPS_RERANKER_ENABLED=true` 并设置本地 CrossEncoder 目录和设备后批量重排。加载或推理失败保留 RRF 并冷却一分钟。检索结果展开后可查看每路排名；跨检索模式的分值不可直接比较。
