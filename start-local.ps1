param(
    [string]$DatabasePassword = $env:AIOPS_DB_PASSWORD,
    [int]$BackendPort = 8080,
    [int]$FrontendPort = 5173
)

$ErrorActionPreference = 'Stop'
function Find-FreePort([int]$FirstPort) {
    for ($port = $FirstPort; $port -lt $FirstPort + 100; $port++) {
        $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Any, $port)
        try { $listener.Start(); return $port }
        catch [System.Net.Sockets.SocketException] { }
        finally { $listener.Stop() }
    }
    throw 'No free local port found.'
}

if (-not (Test-Path -LiteralPath "$PSScriptRoot/frontend/node_modules/vite/bin/vite.js")) {
    throw 'Install frontend dependencies first: cd frontend; npm install'
}
if (-not $DatabasePassword) {
    $securePassword = Read-Host 'MySQL password' -AsSecureString
    $DatabasePassword = [System.Net.NetworkCredential]::new('', $securePassword).Password
}
$backendPort = Find-FreePort $BackendPort
$frontendPort = Find-FreePort $FrontendPort
$env:AIOPS_DB_PASSWORD = $DatabasePassword
if (-not $env:AIOPS_DB_USERNAME) { $env:AIOPS_DB_USERNAME = 'root' }
if (-not $env:AIOPS_JWT_SECRET) {
    $secretBytes = New-Object byte[] 32
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($secretBytes) } finally { $generator.Dispose() }
    $env:AIOPS_JWT_SECRET = [Convert]::ToBase64String($secretBytes)
}
$env:AIOPS_WEB_ALLOWED_ORIGIN = "http://localhost:$frontendPort"
$env:AIOPS_PROXY_TARGET = "http://localhost:$backendPort"
$env:VITE_AGENT_BACKEND_URL = "http://localhost:$backendPort"
$env:VITE_API_BASE_URL = ''
$backendProcess = Start-Process -FilePath 'cmd.exe' -ArgumentList "/d /c mvnw.cmd clean spring-boot:run -Dspring-boot.run.arguments=--server.port=$backendPort" -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -RedirectStandardOutput "$PSScriptRoot/backend-dev.log" -RedirectStandardError "$PSScriptRoot/backend-dev-error.log" -PassThru
$nodePath = (Get-Command node.exe).Source
$frontendProcess = Start-Process -FilePath $nodePath -ArgumentList 'node_modules/vite/bin/vite.js','--host','0.0.0.0','--port',"$frontendPort",'--strictPort' -WorkingDirectory "$PSScriptRoot/frontend" -WindowStyle Hidden -RedirectStandardOutput "$PSScriptRoot/frontend/dev.log" -RedirectStandardError "$PSScriptRoot/frontend/dev-error.log" -PassThru
Write-Host "Console: http://localhost:$frontendPort"
Write-Host "Backend: http://localhost:$backendPort"
Write-Host "Started backend launcher PID $($backendProcess.Id), frontend PID $($frontendProcess.Id)."
Write-Host 'Startup logs: backend-dev.log, backend-dev-error.log, frontend/dev.log'
Write-Host 'Use the demo environment on the login page to explore without signing in.'
