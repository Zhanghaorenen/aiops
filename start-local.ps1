param(
    [string]$DatabasePassword = $env:AIOPS_DB_PASSWORD,
    [int]$BackendPort = 8080,
    [int]$FrontendPort = 5173,
    [int]$AiPort = 8000,
    [string]$PythonExe = ''
)

$ErrorActionPreference = 'Stop'
$reservedPorts = [System.Collections.Generic.HashSet[int]]::new()
function Find-FreePort([int]$FirstPort) {
    for ($port = $FirstPort; $port -lt $FirstPort + 100; $port++) {
        if ($reservedPorts.Contains($port)) { continue }
        $inUse = [System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() | Where-Object { $_.Port -eq $port }
        if ($inUse) { continue }
        $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Any, $port)
        $listener.Server.ExclusiveAddressUse = $true
        try { $listener.Start(); [void]$reservedPorts.Add($port); return $port }
        catch [System.Net.Sockets.SocketException] { }
        finally { $listener.Stop() }
    }
    throw 'No free local port found.'
}

if (-not (Test-Path -LiteralPath "$PSScriptRoot/frontend/node_modules/vite/bin/vite.js")) {
    throw 'Install frontend dependencies first: cd frontend; npm install'
}
if (-not $PythonExe) {
    $candidate = "$PSScriptRoot/agent/ai-service/.venv/Scripts/python.exe"
    if (-not (Test-Path -LiteralPath $candidate)) { $candidate = "$PSScriptRoot/agent/.venv/Scripts/python.exe" }
    if (Test-Path -LiteralPath $candidate) { $PythonExe = $candidate }
    else { $PythonExe = (Get-Command python.exe).Source }
}
& $PythonExe -c 'import fastapi, httpx, openai, uvicorn'
if ($LASTEXITCODE -ne 0) { throw 'Install AI dependencies: python -m pip install -r agent/ai-service/requirements.txt' }
if (-not $DatabasePassword) {
    $securePassword = Read-Host 'MySQL password' -AsSecureString
    $DatabasePassword = [System.Net.NetworkCredential]::new('', $securePassword).Password
}
$backendPort = Find-FreePort $BackendPort
$frontendPort = Find-FreePort $FrontendPort
$aiPort = Find-FreePort $AiPort
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
$env:AIOPS_BACKEND_URL = "http://127.0.0.1:$backendPort"
$env:AIOPS_AI_PROXY_TARGET = "http://127.0.0.1:$aiPort"
$env:VITE_AGENT_BACKEND_URL = "http://localhost:$backendPort"
$env:VITE_API_BASE_URL = ''
$env:VITE_AI_BASE_URL = ''
$logRoot = Join-Path $PSScriptRoot 'data/local-run'
[void](New-Item -ItemType Directory -Force -Path $logRoot)
$backendProcess = Start-Process -FilePath 'cmd.exe' -ArgumentList "/d /c mvnw.cmd spring-boot:run -Dspring-boot.run.arguments=--server.port=$backendPort" -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -RedirectStandardOutput "$logRoot/backend-$backendPort.log" -RedirectStandardError "$logRoot/backend-$backendPort-error.log" -PassThru
$aiProcess = Start-Process -FilePath $PythonExe -ArgumentList '-m','uvicorn','app.main:app','--host','127.0.0.1','--port',"$aiPort" -WorkingDirectory "$PSScriptRoot/agent/ai-service" -WindowStyle Hidden -RedirectStandardOutput "$logRoot/ai-$aiPort.log" -RedirectStandardError "$logRoot/ai-$aiPort-error.log" -PassThru
$nodePath = (Get-Command node.exe).Source
$frontendProcess = Start-Process -FilePath $nodePath -ArgumentList 'node_modules/vite/bin/vite.js','--host','0.0.0.0','--port',"$frontendPort",'--strictPort' -WorkingDirectory "$PSScriptRoot/frontend" -WindowStyle Hidden -RedirectStandardOutput "$logRoot/frontend-$frontendPort.log" -RedirectStandardError "$logRoot/frontend-$frontendPort-error.log" -PassThru
Write-Host "Console: http://localhost:$frontendPort"
Write-Host "Backend: http://localhost:$backendPort"
Write-Host "AI Service: http://127.0.0.1:$aiPort"
Write-Host "Started backend launcher PID $($backendProcess.Id), AI PID $($aiProcess.Id), frontend PID $($frontendProcess.Id)."
Write-Host "Startup logs: $logRoot"
Write-Host 'AI chat needs AIOPS_LLM_BASE_URL, AIOPS_LLM_MODEL and AIOPS_LLM_API_KEY; health remains available without them.'
Write-Host 'Use the demo environment on the login page to explore without signing in.'
$healthChecks = @(
    @{ Name = 'Backend'; Url = "http://127.0.0.1:$backendPort/api/health"; Ready = $false },
    @{ Name = 'AI'; Url = "http://127.0.0.1:$aiPort/api/ai/health"; Ready = $false },
    @{ Name = 'Frontend'; Url = "http://127.0.0.1:$frontendPort/"; Ready = $false }
)
$deadline = [DateTime]::UtcNow.AddSeconds(35)
do {
    foreach ($check in $healthChecks) {
        if ($check.Ready) { continue }
        try { [void](Invoke-WebRequest -Uri $check.Url -TimeoutSec 1 -UseBasicParsing); $check.Ready = $true }
        catch { }
    }
    if (@($healthChecks | Where-Object { -not $_.Ready }).Count -eq 0) { break }
    Start-Sleep -Milliseconds 500
} while ([DateTime]::UtcNow -lt $deadline)
foreach ($check in $healthChecks) {
    if ($check.Ready) { Write-Host "$($check.Name): health check passed" }
    else { Write-Warning "$($check.Name) did not become ready. Check $logRoot" }
}
