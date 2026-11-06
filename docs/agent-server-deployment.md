# 采集服务器部署记录（2026-11-06）

## 已部署

- SSH：`hooasia@172.99.127.28`，Tailscale 地址 `100.100.193.35`；两地址返回的 ED25519 主机公钥一致，主机名 hooasia-Super-Server。
- 工作目录：`/media/hooasia/data/xz/aiops-agent/`，没有覆盖同目录下其他项目。
- Python 3.10.12 独立虚拟环境；安装 psutil 7.2.2、nvidia-ml-py 13.615.71、pip 25.3。没有改系统依赖，没有安装大模型或 AI Service。
- 用户级服务：`~/.config/systemd/user/aiops-collector.service`，已 enable；`Linger=yes`，支持退出 SSH 后及开机运行。
- 部署目录、state 目录权限 700；配置脚本生成的配置/凭据权限 600。SSH 密码未写入项目、部署文件或文档。
- 采样默认每 15 秒，上报目标为此 Windows 电脑的 Tailscale 地址 `http://100.108.161.64:8080`。SSH 用于部署/维护，不用于定时抓取指标。

## 已实际执行的检查

1. 真实采样 GPU 状态 AVAILABLE，CPU/内存/根分区指标有效。
2. GPU 0 UUID：`GPU-eac6f6b4-d4b5-893a-a582-b4d1dae8899f`。
3. GPU 1 UUID：`GPU-c715003e-1a9b-bb55-662b-fb2ce8c663bf`。
4. 两卡均为 NVIDIA GeForce RTX 4090 D，各 49140 MiB；UUID 与 nvidia-smi 一致。采样时 GPU 0 检测到 1 个计算进程、GPU 1 为 0，仅代表当时状态。
5. 在目标服务器执行采集端 8 项回归全部通过；pip check、Python 编译、bash -n、systemd 单元校验通过。
6. 后端当时未监听本机 8080，服务器访问健康检查超时。用户明确选择先部署，令牌稍后填写。

**初次部署时服务为 enabled / inactive，等待配置。后续已完成接入，最新状态见文末“真实上报联调完成”。**

## 接通后端

先在 Windows 启动 Spring Boot，确认不是仅绑定 127.0.0.1。服务器执行：

```bash
curl --connect-timeout 5 http://100.108.161.64:8080/api/health
```

如 Windows 防火墙尚无允许规则，可在管理员 PowerShell 中按需添加这一条，仅允许该服务器访问 Tailscale 地址的后端端口：

```powershell
New-NetFirewallRule -DisplayName 'AIOps Agent over Tailscale' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8080 -LocalAddress 100.108.161.64 -RemoteAddress 100.100.193.35
```

本次未自动修改 Windows 防火墙，也不开放 MySQL 端口。Windows 必须保持开机、后端运行且 Tailscale 在线，才能持续入库。

在 Vue 的 Agent 接入页，用 ADMIN 创建一次性注册令牌（有效期 30 分钟），然后登录服务器：

```bash
ssh hooasia@100.100.193.35
cd /media/hooasia/data/xz/aiops-agent
./.venv/bin/python configure.py --backend-url http://100.108.161.64:8080
```

在隐藏输入提示中粘贴令牌。脚本写入受限配置并启动服务。注册成功后 Agent 自动保存独立 Credential，此后无需重复签发令牌；可从 agent.env 删除已消费的 AIOPS_ENROLLMENT_TOKEN 行，其他行保持。

```bash
systemctl --user status aiops-collector --no-pager
journalctl --user -u aiops-collector -n 50 --no-pager
# 单次真实采样摘要；不写入平台，不打印敏感命令行。
./.venv/bin/python sample_check.py
```

最后核对前端出现 hooasia-Super-Server、采集时间持续更新、GPU 0/1 UUID 与上述一致、历史点每约 15 秒增加。未启动后端或未填令牌时不会出现这些数据。

停止采集：`systemctl --user stop aiops-collector`；取消自启动：`systemctl --user disable aiops-collector`。均不影响训练任务。

磁盘指标目前是根分区 `/`，部署所在 `/media/hooasia/data` 数据盘的独立指标不在现有上报协议内。


## 本机服务启动与接入检查（同日后续）

- Vue 已启动：`http://localhost:5173/`，HTTP 200，Vite 代理 `/api/health` 返回 UP。
- Spring Boot 已启动：`http://localhost:8080/`，激活 dev、cluster、operations；健康检查 UP。
- 修正 Git 忽略的本机数据库私有配置后，成功连接 MySQL。原库备份保存在 `data/local-run/aiops-before-agent-connect-20261106.sql`（含私有数据，不提交）。
- 实际库 Flyway 从 V6 成功执行 V7–V11。核实现有 admin 用户 ID=1 为 ACTIVE，通过部署引导绑定 default-cluster 的 ADMIN；没有改用户密码。
- 服务器实际访问 `http://100.108.161.64:8080/api/health` 返回 UP，Tailscale HTTP 已通，本次不需要增加防火墙规则。
- 现有私有配置的管理员密码及用户随后提供的 admin 密码均登录失败（401），账号本身为 ACTIVE、BCrypt 格式正常、成员权限 ADMIN。正在等待有效管理员密码或一次性注册令牌，没有绕过认证、重置密码或伪造采集数据。
- 此时 Agent 仍 enabled/inactive，等待授权后完成首次注册与真实入库。后端日志 `data/local-run/backend-agent-connect.log`；服务器日志通过 journalctl 查看。


## 真实上报联调完成

已在用户忘记平台密码后完成现有 admin 的本机恢复。仅更新该账户的 BCrypt Hash，保存旧 Hash 到 Git 忽略的本机备份文件，并记录 ADMIN_PASSWORD_RECOVERY 审计；未修改 MySQL root 密码，未新增账号或绕过登录。新的平台登录密码保存在 `data/local-run/admin-login-recovery.txt`，私有启动配置中的初始化密码也已同步，不在本文记录明文。

随后通过正常 HTTP 登录获得管理员身份，调用注册令牌接口，经 SSH 将令牌传给目标机器，Agent 完成首次注册：

- node/server ID：2，Agent ID：2，主机 hooasia-Super-Server，状态 ONLINE。
- GPU：真实 index 0、1，UUID 与本记录前述 nvidia-smi 核对结果一致。
- MySQL 的 metric_host_sample、metric_gpu_snapshot、metric_gpu_sample 已收到连续真实记录；每约 15 秒更新一次，不是模拟注入。
- 通过 Vue 的 localhost:5173 代理调用服务器列表、最新主机指标、GPU 快照与 GPU 进程接口均成功；进程接口当时返回 1 条记录。
- 不带登录凭据读取监控仍返回 401。
- 删除远端 agent.env 中已消费的注册令牌，仅保留 600 权限的 state/agent.json 独立凭据。重启服务后继续上报，未重新注册资产。
- 服务 enabled/active/running，Linger=yes。保留现有 Windows 离线节点 id=1 和其历史记录，没有删除旧数据。

访问 `http://localhost:5173/`，使用 admin 和本机恢复文件中的新密码登录，再打开 `#/servers/2` 查看真实监控。此次验证通过前端 HTTP 代理和真实数据库完成；未声称已完成人工浏览器视觉验收。AI Service / 模型 / RAG 不是此次启动联调范围。
