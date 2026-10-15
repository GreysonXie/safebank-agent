param([switch]$ValidateOnly,[switch]$Background)
$ErrorActionPreference='Stop'
Set-Location -LiteralPath $PSScriptRoot
foreach($name in @('SAFEBANK_MYSQL_URL','SAFEBANK_MYSQL_USER','SAFEBANK_MYSQL_PASSWORD','SAFEBANK_MYSQL_MIGRATION_USER','SAFEBANK_MYSQL_MIGRATION_PASSWORD','SAFEBANK_AGENT_SERVICE_KEY','SAFEBANK_AGENT_DB_CONFIG','SAFEBANK_MODEL_BUDGET_PATH')){if([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))){throw "Missing configuration: $name"}}
if($env:SAFEBANK_MYSQL_URL -notmatch '^jdbc:mysql://127\.0\.0\.1:3307/safebank_bank_v07_m3\?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&useAffectedRows=false&useSSL=true&verifyServerCertificate=false$'){throw 'Only isolated M3 bank database allowed'}
if($env:SAFEBANK_MYSQL_USER -ne 'safebank_m3_bank' -or $env:SAFEBANK_MYSQL_MIGRATION_USER -ne 'safebank_m3_migrate'){throw 'Separate restricted runtime/migration users required'}
$agentConfig=$env:SAFEBANK_AGENT_DB_CONFIG|ConvertFrom-Json
if($agentConfig.user -ne 'safebank_m3_agent' -or $agentConfig.database -ne 'safebank_agent_v07_m3' -or $agentConfig.host -ne '127.0.0.1' -or $agentConfig.port -ne 3307){throw 'Restricted isolated agent database required'}
if($env:SAFEBANK_AGENT_SERVICE_KEY -notmatch '^[a-f0-9]{64}$'){throw 'Random service identity required'}
foreach($port in @(8134,8135)){if(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue){throw "Port $port occupied; refusing replacement"}}
if($ValidateOnly){Write-Output 'M3 isolated configuration accepted';return}
$java='C:/Program Files/Java/jdk-21/bin/java.exe';$python=Join-Path $PSScriptRoot '.superpowers/v07-checkpointer-probe/.venv/Scripts/python.exe';$jar=Join-Path $PSScriptRoot 'target/safebank-agent-0.6.0.jar'
foreach($file in @($java,$python,$jar)){if(-not(Test-Path -LiteralPath $file)){throw 'Existing runtime or packaged app missing'}}
$logs=Join-Path $PSScriptRoot '.superpowers/v07-langgraph-demo';New-Item -ItemType Directory -Force $logs|Out-Null
$saved=@{};$overrides=@{SPRING_PROFILES_ACTIVE='mysql,agent-gateway,langgraph-agent';SAFEBANK_PORT='8134';BANK_MODEL_API_KEY='';SAFEBANK_AGENT_BASE_URL='http://127.0.0.1:8135';SAFEBANK_BANK_URL='http://127.0.0.1:8134';SAFEBANK_AGENT_PORT='8135';SAFEBANK_FAULTS_ENABLED='false';LANGSMITH_TRACING='false';LANGCHAIN_TRACING_V2='false';PYTHONDONTWRITEBYTECODE='1';PYTHONUTF8='1'}
$agent=$null
try{
 foreach($name in $overrides.Keys){$saved[$name]=[Environment]::GetEnvironmentVariable($name);[Environment]::SetEnvironmentVariable($name,$overrides[$name])}
 $agent=Start-Process -FilePath $python -ArgumentList @('-m','safebank_agent.server') -WorkingDirectory (Join-Path $PSScriptRoot 'agent-service') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logs 'agent.log') -RedirectStandardError (Join-Path $logs 'agent-error.log') -PassThru
 if($Background){$bank=Start-Process -FilePath $java -ArgumentList @('-Dfile.encoding=UTF-8','-jar',('"'+$jar+'"')) -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logs 'server.log') -RedirectStandardError (Join-Path $logs 'server-error.log') -PassThru
  @{javaPid=$bank.Id;agentPid=$agent.Id;port=8134;agentPort=8135;bankDatabase='safebank_bank_v07_m3';agentDatabase='safebank_agent_v07_m3';worktree=$PSScriptRoot;jar=$jar;modelMode=$env:SAFEBANK_MODEL_MODE}|ConvertTo-Json|Set-Content (Join-Path $logs 'process.json') -Encoding utf8
  Write-Output 'M3 launched: http://127.0.0.1:8134/; readiness must be verified.'
 }else{try{& $java '-Dfile.encoding=UTF-8' '-jar' $jar}finally{if($agent -and -not $agent.HasExited){$agent.Kill()}}}
}catch{if($agent -and -not $agent.HasExited){$agent.Kill()};throw}finally{foreach($name in $saved.Keys){[Environment]::SetEnvironmentVariable($name,$saved[$name])}}
