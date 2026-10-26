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
$env:AIOPS_DB_PASSWORD="123456"
$env:AIOPS_JWT_SECRET="YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE="
$env:AIOPS_ADMIN_USERNAME="admin"
$env:AIOPS_ADMIN_PASSWORD="change-this-local-admin-password"
.\mvnw.cmd spring-boot:run
```

Run tests:

```powershell
cmd.exe /d /c mvnw.cmd test
```

## Web console

Windows local launcher (selects free ports and connects the frontend proxy):

```powershell
.\start-local.ps1
```

It prompts for the MySQL password unless `AIOPS_DB_PASSWORD` is set. An existing
administrator account is required for real data; for a new database set
`AIOPS_ADMIN_USERNAME` and `AIOPS_ADMIN_PASSWORD` before launching. The login page
also provides an explicitly labelled demo environment with sample hosts and trends.
The launcher starts background processes and prints their ports and log paths.

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

## Python Agent

Install dependencies and follow [agent/README.md](agent/README.md). Sign in, request a one-time enrollment token with `POST /api/agent/enrollment-tokens`, then set it as `AIOPS_ENROLLMENT_TOKEN` before starting the Agent.

Flyway migrations V1-V4 create the V0.1 schema and preserve the earlier prototype tables. Existing prototype records are not transformed into the new node and metric tables.
