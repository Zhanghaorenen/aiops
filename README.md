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

```powershell
cd frontend
npm install
npm run dev
```

Open `http://localhost:5173`. Configure `VITE_API_BASE_URL` if the backend is not at `http://localhost:8080`; the backend CORS origin can be changed with `AIOPS_WEB_ALLOWED_ORIGIN`.

## Python Agent

Install dependencies and follow [agent/README.md](agent/README.md). Sign in, request a one-time enrollment token with `POST /api/agent/enrollment-tokens`, then set it as `AIOPS_ENROLLMENT_TOKEN` before starting the Agent.

Flyway migrations V1-V4 create the V0.1 schema and preserve the earlier prototype tables. Existing prototype records are not transformed into the new node and metric tables.
