# AIOps Backend

## MySQL

The application uses MySQL by default. Schema changes are managed by Flyway.

The default connection can create the database automatically. If you prefer to create it manually:

```sql
CREATE DATABASE aiops CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

Set these values in the shell before starting the backend. The application has no embedded database password or JWT signing key:

```properties
AIOPS_DB_URL=jdbc:mysql://localhost:3306/aiops?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false
AIOPS_DB_USERNAME=root
AIOPS_DB_PASSWORD=your-local-mysql-password
AIOPS_JWT_SECRET=base64-encoded-secret-of-at-least-32-bytes
AIOPS_ADMIN_USERNAME=admin
AIOPS_ADMIN_PASSWORD=choose-a-password-of-at-least-12-characters
```

You can override them with environment variables before running:

```powershell
$env:AIOPS_DB_USERNAME="root"
$mysqlPassword = Read-Host 'MySQL password' -AsSecureString
$env:AIOPS_DB_PASSWORD = [System.Net.NetworkCredential]::new('', $mysqlPassword).Password
$jwtBytes = New-Object byte[] 32
$random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$random.GetBytes($jwtBytes)
$random.Dispose()
$env:AIOPS_JWT_SECRET = [Convert]::ToBase64String($jwtBytes)
$env:AIOPS_ADMIN_USERNAME="admin"
$adminPassword = Read-Host 'Initial admin password (12-72 characters)' -AsSecureString
$env:AIOPS_ADMIN_PASSWORD = [System.Net.NetworkCredential]::new('', $adminPassword).Password
.\mvnw.cmd spring-boot:run
```

Run tests:

```powershell
cmd.exe /d /c mvnw.cmd test
```

## Web console

The console uses Vue 3 single-file components, Vue Router, Pinia, Ant Design Vue,
TypeScript and ECharts. See [frontend/README.md](frontend/README.md) for the Chinese
development guide and page/component map.

Windows local launcher (starts Java, FastAPI and Vue, selects free ports and connects both frontend proxies):

```powershell
.\start-local.ps1
```

Install AI dependencies using [AI Service setup](agent/ai-service/README.md) first.
It prompts for the MySQL password unless `AIOPS_DB_PASSWORD` is set. An existing
administrator account is required for real data; for a new database set
`AIOPS_ADMIN_USERNAME` and `AIOPS_ADMIN_PASSWORD` before launching. The login page
also provides an explicitly labelled demo environment with sample hosts and trends.
The launcher starts background processes, prints ports and `data/local-run/` log paths,
and checks health. Set `AIOPS_LLM_BASE_URL`, `AIOPS_LLM_MODEL`, `AIOPS_LLM_API_KEY`
to enable real AI chat. The AI service can start without those variables and then
returns an explicit configuration error after checking the user's JWT.

The console includes a fleet overview, searchable/filterable server inventory,
host details with 1/6/24-hour trends and CSV export, one-time enrollment tokens,
Windows/Linux Agent commands, and password changes. Real mode uses the existing
MySQL APIs. No sample hosts are inserted into the database.

```powershell
cd frontend
npm install
npm run dev
```

Open `http://localhost:5173`. By default Vite proxies `/api` to `http://localhost:8080`.
Set `AIOPS_PROXY_TARGET` before starting Vite when using another backend port and
`VITE_AGENT_BACKEND_URL` for the address in generated Agent commands. Production
hosting needs an `/api` reverse proxy, or an explicit `VITE_API_BASE_URL` and
matching `AIOPS_WEB_ALLOWED_ORIGIN` on the backend.

`/api/chat` and `/api/ai/health` proxy to FastAPI via `AIOPS_AI_PROXY_TARGET`
(default `http://127.0.0.1:8000`). Production can use `VITE_AI_BASE_URL` separately
from the Java API address. AI Tools query Java using the caller's user JWT and are
read-only; no SSH or Shell execution is registered.

Stage A audit, validation evidence and remaining work are recorded in
[the delivery report](docs/stage-a-report.md) and [source reading guide](docs/stage-a-source-guide.md).

## Python Agent

Install dependencies and follow [agent/README.md](agent/README.md). Sign in, request a one-time enrollment token with `POST /api/agent/enrollment-tokens`, then set it as `AIOPS_ENROLLMENT_TOKEN` before starting the Agent.

Flyway migrations V1-V4 create the V0.1 schema and preserve the earlier prototype tables. Existing prototype records are not transformed into the new node and metric tables.
