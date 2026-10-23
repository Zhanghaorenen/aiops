# AIOps Python Agent

Python 3.10+ Agent for Windows and Linux. It samples host CPU, memory, and the system drive/root disk with `psutil`, then sends JSON to the backend over HTTP or HTTPS. NVIDIA GPU collection is available locally when the NVIDIA driver is installed; GPU data is not part of the current backend metrics API.

## Setup

```powershell
py -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
$env:AIOPS_BACKEND_URL = "http://localhost:8080"
$env:AIOPS_ENROLLMENT_TOKEN = "令牌从已登录的 POST /api/agent/enrollment-tokens 获取"
py -m aiops_agent
```

Linux/macOS:

```sh
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
export AIOPS_BACKEND_URL=http://localhost:8080
export AIOPS_ENROLLMENT_TOKEN='令牌从已登录的 POST /api/agent/enrollment-tokens 获取'
python -m aiops_agent
```

Configuration: `AIOPS_BACKEND_URL` (HTTP/HTTPS URL, default `http://localhost:8080`), `AIOPS_NODE_KEY`, `AIOPS_AGENT_KEY`, `AIOPS_AGENT_VERSION`, `AIOPS_AGENT_INTERVAL_SECONDS` (default 15; minimum 5), and `AIOPS_AGENT_STATE_FILE` (default `~/.aiops-agent/state.json`). `AIOPS_ENROLLMENT_TOKEN` is only needed on first enrollment or credential rotation. The state file contains the Agent credential and should be protected like a password; on Linux/macOS it is written with owner-only permissions. The enrollment token expires after 30 minutes and is consumed once. To rotate a lost or revoked Agent credential, remove the state file and enroll with a fresh token; the same node and Agent identifiers are re-used and the credential is rotated.

The process retries temporary network failures and HTTP 429/5xx responses with capped exponential backoff. HTTP 4xx responses stop the process so configuration or credentials can be corrected. Ctrl+C and SIGTERM stop retry waits promptly.

Run the Agent tests from this directory:

```sh
python -m unittest discover -s tests -v
```
