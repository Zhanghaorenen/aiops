#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
root="$(pwd -P)"
umask 077
mkdir -p state
chmod 700 state
# Ubuntu 有时缺少 ensurepip；用宿主 pip 管理独立 venv，不安装系统软件包。
python3 -m venv --without-pip .venv
python3 -m pip --python "$root/.venv/bin/python" install --disable-pip-version-check 'pip>=23,<26' -r requirements.txt
.venv/bin/python -m compileall -q aiops_agent configure.py sample_check.py
mkdir -p "$HOME/.config/systemd/user"
unit="$HOME/.config/systemd/user/aiops-collector.service"
if [ -e "$unit" ]; then
    echo "已有同名服务，停止安装以便核对，未覆盖：$unit" >&2
    exit 1
fi
sed "s|@ROOT@|$root|g" aiops-collector.service.in > "$unit"
chmod 600 "$unit"
systemctl --user daemon-reload
systemctl --user enable aiops-collector.service
echo "服务已安装；运行 configure.py 填写地址和注册令牌后启动。"
