# Linux 采集端部署文件

仅部署 aiops_agent 和 requirements.txt，不复制 AI Service 或模型。

将本目录的 configure.py、sample_check.py、install.sh、服务模板，与采集包放在同一级目录，执行 `bash install.sh`。使用独立虚拟环境，不修改服务器已有 Python 包。首次安装遇到同名服务会停止，由管理员核对，避免覆盖其他部署。

```bash
cd /media/hooasia/data/xz/aiops-agent
./.venv/bin/python sample_check.py
./.venv/bin/python configure.py --backend-url http://100.108.161.64:8080
# 根据提示输入平台 ADMIN 创建的一次性注册令牌，不把令牌放入命令行。
systemctl --user status aiops-collector --no-pager
journalctl --user -u aiops-collector -n 50 --no-pager
```

Windows 上启动真实后端并使 `100.108.161.64:8080` 可达；防火墙入站规则只允许采集服务器的 Tailscale IP `100.100.193.35` 访问该端口。服务器测试 `curl --connect-timeout 5 http://100.108.161.64:8080/api/health`。不要将 MySQL 端口开放给采集端。Tailscale 为 HTTP 提供加密内网传输；跨公网改用 HTTPS。

采样默认每 15 秒，使用 `/api/agent/enroll` 首次注册，再通过独立 Credential 调用 `/api/agent/metrics` 和 `/api/agent/heartbeat`。`agent.env` 与 `state/agent.json` 权限为 600，后者含凭据，不应复制或提交。首次注册后可从 agent.env 移除已消费的 AIOPS_ENROLLMENT_TOKEN 行，保留其他配置。

服务默认尚无 agent.env，ConditionPathExists 使其等待配置。只有配置完成才启动，不会生成虚假资产。`configure.py --rotate --backend-url ...` 可用新令牌轮换凭据，保留原节点和 Agent 标识，旧状态备份在受限目录。

保持退出 SSH 后运行、开机启动需要 `loginctl enable-linger hooasia`。停止使用 `systemctl --user stop aiops-collector`，取消自启动使用 `systemctl --user disable aiops-collector`，不会停止训练进程。

采集磁盘指标沿用现有协议，表示根分区 `/`，不是部署目录所在数据盘；数据盘明细尚未上报。
