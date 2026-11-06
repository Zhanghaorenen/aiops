param(
    [string]$ConfigPath = (Join-Path $PSScriptRoot '../../data/local-run/ai-service-secrets.json'),
    [string]$PythonExe = (Join-Path $PSScriptRoot '../.venv/Scripts/python.exe')
)

$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $ConfigPath)) {
    throw '缺少本机私有 AI 配置，请参考 docs/deepseek-knowledge-setup.md。'
}
$settings = Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json
foreach ($setting in $settings.PSObject.Properties) {
    if ($setting.Name -notmatch '^AIOPS_[A-Z0-9_]+$') { throw '私有配置包含不支持的变量名' }
    [Environment]::SetEnvironmentVariable($setting.Name, [string]$setting.Value, 'Process')
}
# 模型已固定下载到本地，服务运行时不再访问 Hugging Face。
$env:PYTHONUTF8 = '1'
$env:HF_HUB_OFFLINE = '1'
$env:HF_HUB_DISABLE_TELEMETRY = '1'
$env:TRANSFORMERS_OFFLINE = '1'
Push-Location $PSScriptRoot
try {
    & $PythonExe -m uvicorn app.main:app --host 127.0.0.1 --port 8000
    if ($LASTEXITCODE -ne 0) { throw 'AI Service 启动失败，请检查端口和依赖。' }
} finally {
    Pop-Location
}
