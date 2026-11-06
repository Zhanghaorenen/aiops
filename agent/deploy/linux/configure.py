"""首次接入或更换后端地址。令牌交互输入，不进入命令历史。"""

import argparse
import getpass
import os
from pathlib import Path
import subprocess
from urllib.parse import urlsplit


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--backend-url", required=True)
    parser.add_argument("--rotate", action="store_true", help="使用新令牌轮换凭据")
    args = parser.parse_args()
    url = args.backend_url.rstrip("/")
    parsed = urlsplit(url)
    if (parsed.scheme not in {"http", "https"} or not parsed.hostname
            or parsed.username or parsed.password or parsed.query or parsed.fragment
            or any(c.isspace() or c in '\"\\' for c in url)):
        parser.error("后端地址必须是无凭据的 HTTP/HTTPS 地址")
    root = Path(__file__).resolve().parent
    state = root / "state" / "agent.json"
    token = ""
    if not state.exists() or args.rotate:
        token = getpass.getpass("管理员创建的一次性注册令牌：").strip()
        if not token or any(c.isspace() or c in '\"\\' for c in token):
            parser.error("注册令牌不能为空或包含空白、引号、反斜线")
    # Agent 会自行持久化独立 Credential；后续启动不再依赖一次性令牌。
    content = (f'AIOPS_BACKEND_URL="{url}"\n'
               f'AIOPS_AGENT_STATE_FILE="{state}"\n'
               'AIOPS_AGENT_INTERVAL_SECONDS=15\n'
               'PYTHONDONTWRITEBYTECODE=1\n')
    if token:
        content += f'AIOPS_ENROLLMENT_TOKEN="{token}"\n'
    os.umask(0o077)
    temp = root / "agent.env.tmp"
    temp.write_text(content, encoding="utf-8")
    temp.chmod(0o600)
    if args.rotate and state.exists():
        # 保留节点/Agent 标识，只清掉旧凭据，避免产生重复资产。
        import json
        saved = json.loads(state.read_text(encoding="utf-8"))
        saved.pop("credential", None)
        backup = state.with_suffix(".json.previous")
        backup.write_bytes(state.read_bytes())
        backup.chmod(0o600)
        state.write_text(json.dumps(saved), encoding="utf-8")
    temp.replace(root / "agent.env")
    subprocess.run(["systemctl", "--user", "restart", "aiops-collector.service"], check=True)
    print("配置已保存。用 systemctl --user status aiops-collector 检查状态。")


if __name__ == "__main__":
    main()
