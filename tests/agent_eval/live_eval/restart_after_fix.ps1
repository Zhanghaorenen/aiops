$ErrorActionPreference='Stop'
$taskRoot='D:/work/aiops/backend'
$taskPid=(Get-NetTCPConnection -State Listen -LocalPort 8000 -ErrorAction SilentlyContinue).OwningProcess
if($taskPid){
 $taskProc=Get-CimInstance Win32_Process -Filter "ProcessId=$taskPid"
 if($taskProc.Name -ne 'python.exe' -or $taskProc.CommandLine -notmatch 'uvicorn app.main:app'){throw 'Unexpected port owner'}
 Stop-Process -Id $taskPid
}
$taskPrivate="$taskRoot/data/local-run"
$settings=Get-Content "$taskPrivate/ai-service-secrets.json" -Raw | ConvertFrom-Json
foreach($v in $settings.PSObject.Properties){[Environment]::SetEnvironmentVariable($v.Name,[string]$v.Value,'Process')}
$env:PYTHONUTF8='1';$env:HF_HUB_OFFLINE='1';$env:TRANSFORMERS_OFFLINE='1'
$env:AIOPS_RAG_STORAGE_DIR="$taskPrivate/part1-knowledge"
$env:AIOPS_EVAL_TRACE="$taskRoot/tests/agent_eval/reports/part1_after_fix/all_traces.jsonl"
$env:PYTHONPATH="$taskRoot/tests/agent_eval/live_eval;$taskRoot/agent/ai-service"
$p=Start-Process -FilePath "$taskRoot/agent/.venv/Scripts/python.exe" -ArgumentList 'scripts/run.py','ai' -WorkingDirectory $taskRoot -WindowStyle Hidden -RedirectStandardOutput "$taskPrivate/part1-after-fix-ai.log" -RedirectStandardError "$taskPrivate/part1-after-fix-ai-error.log" -PassThru
Write-Output "AI launcher $($p.Id)"
