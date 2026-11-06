# 双 GPU 场景评测

cases.json 包含 30 条可核对问题、参考事实来源、期望工具与人工检查项，不预填虚构数值。运行目录 agent/ai-service：

```powershell
python evaluation/evaluate.py --dry-run
# 在进程环境设置 AIOPS_EVALUATION_TOKEN（有效用户 JWT），不要提交凭据。
python evaluation/evaluate.py --server-id 1 --output evaluation/results.local.json
```

可选 AIOPS_EVALUATION_AI_URL。真实服务器编号需从平台获取，不要照抄示例。结果记录实测耗时、工具次数、实际来源和回答；自动检查只覆盖工具选择/是否返回来源，不能视为召回准确率或事实正确率。结果包含私有知识片段，应留在本机人工审核。

当前状态：NOT_EXECUTED。未配置真实双 GPU / 生成模型 / 用户本地 Embedding / Qdrant，不能产生或声称真实评测成功结果。
