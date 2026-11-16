# 外部 MCP 客户端

安装工程化依赖，将以下配置中的路径改为项目绝对路径。客户端父进程提供个人登录后的短期 `AIOPS_MCP_USER_JWT`，到期重新获取。标准输入输出只承载协议，审计写 stderr。

```json
{"mcpServers":{"aiops":{"command":"/absolute/backend/agent/.venv/bin/python","args":["-m","app.mcp_server.server"],"env":{"PYTHONPATH":"/absolute/backend/agent/ai-service","AIOPS_MCP_ENABLED":"true","AIOPS_BACKEND_URL":"http://127.0.0.1:8080"}}}}
```

远程模式需要既有 OAuth 授权服务器、面向本资源且含 aiops.read scope 的签名 access token、可信 HTTPS issuer/JWKS 和 HTTPS 反向代理。运行 `python -m app.mcp_server.server --transport streamable-http`，仅监听回环，代理保留外部 Host，路径为 /mcp。资源元数据由 SDK 提供。设置 AIOPS_MCP_ISSUER、AIOPS_MCP_RESOURCE（含 /mcp）、AIOPS_MCP_JWKS、AIOPS_MCP_DELEGATIONS。最后一项为受 ACL 保护的本地 JSON 路径，格式是 OAuth sub 到 {"user_id":整数,"user_jwt":"该用户短期平台凭据"} 的映射，由运维轮换，不能共用管理员身份。每次调用重新核验 Java /auth/me 及具体资源权限。MCP bearer 不进入 Java。未配置映射默认拒绝。
