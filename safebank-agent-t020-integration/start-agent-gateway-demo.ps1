param([switch]$ValidateOnly,[switch]$Background)
$ErrorActionPreference='Stop'
Set-Location -LiteralPath $PSScriptRoot
foreach($name in @('SAFEBANK_MYSQL_URL','SAFEBANK_MYSQL_USER','SAFEBANK_MYSQL_PASSWORD','SAFEBANK_MYSQL_MIGRATION_USER','SAFEBANK_MYSQL_MIGRATION_PASSWORD','SAFEBANK_AGENT_SERVICE_KEY')) {
    if([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))){throw "Missing local configuration: $name"}
}
if($env:SAFEBANK_MYSQL_URL -notmatch '^jdbc:mysql://127\.0\.0\.1:3307/safebank_bank_v07_m2\?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&useAffectedRows=false(?:&useSSL=true&verifyServerCertificate=false)?$'){throw 'Only the isolated M2 database is allowed'}
if($env:SAFEBANK_MYSQL_USER -eq 'root' -or $env:SAFEBANK_MYSQL_USER -eq $env:SAFEBANK_MYSQL_MIGRATION_USER){throw 'Separate restricted runtime and migration users required'}
if($env:SAFEBANK_AGENT_SERVICE_KEY -notmatch '^[a-f0-9]{64}$'){throw 'Generate a random 256-bit internal service key'}
foreach($port in @(8132,8133)){if(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue){throw "Port $port already occupied; refusing replacement"}}
if($ValidateOnly){Write-Output 'M2 isolated configuration accepted; no services started';return}
$java='C:/Program Files/Java/jdk-21/bin/java.exe'
$python=Join-Path $PSScriptRoot '.superpowers/v07-checkpointer-probe/.venv/Scripts/python.exe'
$jar=Join-Path $PSScriptRoot 'target/safebank-agent-0.6.0.jar'
foreach($file in @($java,$python,$jar)){if(-not(Test-Path -LiteralPath $file)){throw 'Required existing Java/Python/JAR runtime missing'}}
$logs=Join-Path $PSScriptRoot '.superpowers/v07-agent-gateway-demo';New-Item -ItemType Directory -Force $logs | Out-Null
$saved=@{};$overrides=@{SPRING_PROFILES_ACTIVE='mysql,agent-gateway';SAFEBANK_PORT='8132';BANK_MODEL_API_KEY='';SAFEBANK_AGENT_BASE_URL='http://127.0.0.1:8133';SAFEBANK_AGENT_JAVA_URL='http://127.0.0.1:8132';SAFEBANK_FAKE_AGENT_PORT='8133';SAFEBANK_FAULTS_ENABLED='false'}
$fake=$null
try{
    foreach($name in $overrides.Keys){$saved[$name]=[Environment]::GetEnvironmentVariable($name);[Environment]::SetEnvironmentVariable($name,$overrides[$name])}
    $fixture=Join-Path $PSScriptRoot 'tools/fake-agent-server.py'
    $fake=Start-Process -FilePath $python -ArgumentList ('"'+$fixture+'"') -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logs 'fake.log') -RedirectStandardError (Join-Path $logs 'fake-error.log') -PassThru
    if($Background){
        $bank=Start-Process -FilePath $java -ArgumentList @('-Dfile.encoding=UTF-8','-jar',('"'+$jar+'"')) -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logs 'server.log') -RedirectStandardError (Join-Path $logs 'server-error.log') -PassThru
        @{javaPid=$bank.Id;fakePid=$fake.Id;port=8132;fakePort=8133;database='safebank_bank_v07_m2';jar=$jar;worktree=$PSScriptRoot} | ConvertTo-Json | Set-Content (Join-Path $logs 'process.json') -Encoding utf8
        Write-Output 'M2 candidate launched at http://127.0.0.1:8132/; readiness and synthetic seed must be verified separately.'
    }else{try{& $java '-Dfile.encoding=UTF-8' '-jar' $jar;if($LASTEXITCODE -ne 0){throw 'M2 startup failed'}}finally{if($fake -and -not $fake.HasExited){$fake.Kill()}}}
}catch{if($fake -and -not $fake.HasExited){$fake.Kill()};throw}finally{foreach($name in $saved.Keys){[Environment]::SetEnvironmentVariable($name,$saved[$name])}}
