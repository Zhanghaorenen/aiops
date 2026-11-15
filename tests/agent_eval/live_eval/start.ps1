$ErrorActionPreference='Stop'
$taskRoot='D:/work/aiops/backend'
$taskPrivate="$taskRoot/data/local-run"
$taskConfig=Get-Content "$taskPrivate/ai-service-secrets.json" -Raw | ConvertFrom-Json
foreach($v in $taskConfig.PSObject.Properties){[Environment]::SetEnvironmentVariable($v.Name,[string]$v.Value,'Process')}
$taskProps=@{}
foreach($line in Get-Content "$taskPrivate/dev-secrets.properties"){if($line -match '^([^#=]+)=(.*)$'){$taskProps[$Matches[1]]=$Matches[2]}}
$env:AIOPS_DB_PASSWORD=$taskProps['spring.datasource.password']
$env:AIOPS_JWT_SECRET=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$env:AIOPS_DB_URL='jdbc:mysql://localhost:3306/aiops_eval_part1_20261113?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false'
$env:AIOPS_ADMIN_USERNAME=$taskProps['aiops.admin.username']
$env:AIOPS_ADMIN_PASSWORD=$taskProps['aiops.admin.password']
$env:AIOPS_BACKEND_URL='http://127.0.0.1:8080'
$env:PYTHONUTF8='1'
$env:HF_HUB_OFFLINE='1'
$env:TRANSFORMERS_OFFLINE='1'
$env:AIOPS_RAG_STORAGE_DIR="$taskPrivate/part1-knowledge"
$env:AIOPS_EVAL_TRACE="$taskRoot/tests/agent_eval/reports/part1/eval_traces.jsonl"
$env:PYTHONPATH="$taskRoot/tests/agent_eval/live_eval;$taskRoot/agent/ai-service"
foreach($svc in 'backend','ai'){
 if(Get-NetTCPConnection -State Listen -LocalPort $(if($svc -eq 'backend'){8080}else{8000}) -ErrorAction SilentlyContinue){continue}
 $p=Start-Process -FilePath "$taskRoot/agent/.venv/Scripts/python.exe" -ArgumentList 'scripts/run.py',$svc -WorkingDirectory $taskRoot -WindowStyle Hidden -RedirectStandardOutput "$taskPrivate/part1-$svc.log" -RedirectStandardError "$taskPrivate/part1-$svc-error.log" -PassThru
 Write-Output "$svc launcher PID $($p.Id)"
}
