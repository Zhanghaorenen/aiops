$ErrorActionPreference='Stop'
$taskRoot='D:/work/aiops/backend'
$taskOut="$taskRoot/tests/agent_eval/reports/part1_rerun_20261113_1734"
[void](New-Item -ItemType Directory -Force -Path $taskOut)
if(Test-Path "$taskOut/eval_results.jsonl"){throw 'Results exist; refusing restart for rerun'}
$taskPid=(Get-NetTCPConnection -State Listen -LocalPort 8000 -ErrorAction SilentlyContinue).OwningProcess
if($taskPid){
 $taskProc=Get-CimInstance Win32_Process -Filter "ProcessId=$taskPid"
 if($taskProc.Name -ne 'python.exe' -or $taskProc.CommandLine -notmatch 'uvicorn app.main:app'){throw 'Unexpected port owner'}
 Stop-Process -Id $taskPid
}
$taskPrivate="$taskRoot/data/local-run"
$taskSettings=Get-Content "$taskPrivate/ai-service-secrets.json" -Raw | ConvertFrom-Json
foreach($v in $taskSettings.PSObject.Properties){[Environment]::SetEnvironmentVariable($v.Name,[string]$v.Value,'Process')}
$env:PYTHONUTF8='1';$env:HF_HUB_OFFLINE='1';$env:TRANSFORMERS_OFFLINE='1'
$env:AIOPS_RAG_STORAGE_DIR="$taskPrivate/part1-knowledge"
$env:AIOPS_EVAL_TRACE="$taskOut/eval_traces.jsonl"
$env:PYTHONPATH="$taskRoot/tests/agent_eval/live_eval;$taskRoot/agent/ai-service"
$p=Start-Process -FilePath "$taskRoot/agent/.venv/Scripts/python.exe" -ArgumentList 'scripts/run.py','ai' -WorkingDirectory $taskRoot -WindowStyle Hidden -RedirectStandardOutput "$taskPrivate/rerun30-ai.log" -RedirectStandardError "$taskPrivate/rerun30-ai-error.log" -PassThru
Write-Output "AI launcher $($p.Id)"
$ready=$false
for($i=0;$i -lt 30;$i++){
 try{$h=Invoke-RestMethod 'http://127.0.0.1:8000/api/ai/health' -TimeoutSec 1; if($h.status -eq 'UP'){$ready=$true;break}}catch{}
 Start-Sleep -Seconds 1
}
if(-not $ready){throw 'AI health not ready; no evaluation submitted'}
Write-Output 'AI health ready'
