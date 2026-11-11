"""读取本地 .env，在当前终端启动一个开发服务，不修改系统环境。"""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def load_env(path):
    values = {}
    if not path.exists():
        return values
    for number, line in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        key, value = key.strip(), value.strip()
        if not separator or not re.fullmatch(r"(?:AIOPS|VITE)_[A-Z0-9_]+", key):
            raise ValueError(f".env 第 {number} 行格式或变量名无效")
        if value.startswith(("\"", "'")):
            if len(value) < 2 or value[-1] != value[0]:
                raise ValueError(f".env 第 {number} 行引号不完整")
            value = value[1:-1]
        values[key] = value
    return values


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("service", choices=["backend", "ai", "frontend"])
    args = parser.parse_args()
    try:
        env = {**load_env(ROOT / ".env"), **os.environ}
    except ValueError as error:
        parser.error(str(error))
    cwd = ROOT
    if args.service == "backend":
        missing = [k for k in ["AIOPS_DB_PASSWORD", "AIOPS_JWT_SECRET"] if not env.get(k)]
        if missing:
            parser.error("请在 .env 或终端设置 " + ", ".join(missing))
        wrapper = ROOT / ("mvnw.cmd" if os.name == "nt" else "mvnw")
        command = [str(wrapper), "spring-boot:run", "-Dspring-boot.run.profiles=cluster,operations"]
        if os.name != "nt":
            command = ["sh", *command]
    elif args.service == "ai":
        command = [sys.executable, "-m", "uvicorn", "app.main:app", "--host", "127.0.0.1", "--port", "8000"]
        cwd = ROOT / "agent/ai-service"
    else:
        npm = shutil.which("npm.cmd" if os.name == "nt" else "npm")
        if not npm:
            parser.error("未找到 npm，请先安装 Node.js")
        command = [npm, "run", "dev"]
        cwd = ROOT / "frontend"
    if os.name == "nt" and command[0].lower().endswith(".cmd"):
        # 固定开发命令，不把 .env 内容拼接为 Shell 指令。
        command = [os.environ.get("COMSPEC", "cmd.exe"), "/d", "/c", *command]
    try:
        return subprocess.call(command, cwd=cwd, env=env)
    except KeyboardInterrupt:
        return 130
    except OSError:
        parser.error("开发命令无法启动，请检查 JDK、Node.js 或虚拟环境")


if __name__ == "__main__":
    raise SystemExit(main())
