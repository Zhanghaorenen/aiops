# AIOps Python Agent

Python 3.10+ Agent for Windows and Linux. It samples actual host CPU, memory, and root disk usage with `psutil`, then sends HTTPS/HTTP JSON requests to the backend.

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

Optional configuration: `AIOPS_NODE_KEY`, `AIOPS_AGENT_KEY`, `AIOPS_AGENT_VERSION`, `AIOPS_AGENT_INTERVAL_SECONDS` (default 15), `AIOPS_AGENT_STATE_FILE` (default `~/.aiops-agent/state.json`). The state file contains the Agent credential and should be protected like a password. The enrollment token expires after 30 minutes and is consumed once. To rotate a lost or revoked Agent credential, remove the state file and enroll with a fresh token; the same node and Agent identifiers are re-used and the credential is rotated.

The process retries temporary network failures with capped exponential backoff. HTTP 4xx responses stop the process so configuration or credentials can be corrected; Ctrl+C exits cleanly.
