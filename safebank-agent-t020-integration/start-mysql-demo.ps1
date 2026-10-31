param([ValidateRange(1024,65535)][int]$Port=8130,[switch]$Background,[switch]$ValidateOnly)
$ErrorActionPreference='Stop'
Set-Location -LiteralPath $PSScriptRoot
foreach($name in @('SAFEBANK_MYSQL_URL','SAFEBANK_MYSQL_USER','SAFEBANK_MYSQL_PASSWORD','SAFEBANK_MYSQL_MIGRATION_USER','SAFEBANK_MYSQL_MIGRATION_PASSWORD')) {
    if(-not [Environment]::GetEnvironmentVariable($name)){throw "Missing required local environment: $name"}
}
if($env:SAFEBANK_MYSQL_URL -notmatch '^jdbc:mysql://127\.0\.0\.1:3307/safebank_bank_v07\?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&useAffectedRows=false(?:&useSSL=true&verifyServerCertificate=false)?$') {
    throw 'Only explicit local v0.7 candidate database with UTC and found-row semantics is allowed'
}
if($env:SAFEBANK_MYSQL_USER -eq 'root' -or $env:SAFEBANK_MYSQL_USER -eq $env:SAFEBANK_MYSQL_MIGRATION_USER){throw 'Candidate requires a separate restricted application account'}
if($Port -in @(8096,8110,8111,3307)){throw 'Original service/database ports are protected'}
if(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue){throw "Port $Port is already in use"}
if($ValidateOnly){Write-Output 'MySQL candidate configuration accepted; no database or server started';return}
$jar=Join-Path $PSScriptRoot 'target/safebank-agent-0.6.0.jar'
if(-not(Test-Path -LiteralPath $jar)){throw 'Build the candidate JAR before starting'}
$java=Join-Path 'C:/Program Files/Java/jdk-21' 'bin/java.exe'
if(-not(Test-Path -LiteralPath $java)){throw 'Java 21 is required'}
$saved=@{};$overrides=@{SPRING_PROFILES_ACTIVE='mysql';SAFEBANK_PORT=[string]$Port;BANK_MODEL_API_KEY='';BANK_DEBUG_BUDGET_PATH='';SAFEBANK_FAULTS_ENABLED='false'}
try {
    foreach($name in $overrides.Keys){$saved[$name]=[Environment]::GetEnvironmentVariable($name);[Environment]::SetEnvironmentVariable($name,$overrides[$name])}
    if($Background) {
        $logs=Join-Path $PSScriptRoot '.superpowers/v07-mysql-demo';New-Item -ItemType Directory -Force $logs | Out-Null
        $process=Start-Process -FilePath $java -ArgumentList @('-Dfile.encoding=UTF-8','-jar',('"'+$jar+'"')) -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logs 'server.log') -RedirectStandardError (Join-Path $logs 'server-error.log') -PassThru
        $registration=@{pid=$process.Id;port=$Port;worktree=$PSScriptRoot;jar=$jar;database='safebank_bank_v07'}
        $registration | ConvertTo-Json | Set-Content (Join-Path $logs 'process.json') -Encoding utf8
        Write-Output ("Candidate launched with PID {0}; verify readiness at http://127.0.0.1:{1}/api/session" -f $process.Id,$Port)
    } else {& $java '-Dfile.encoding=UTF-8' '-jar' $jar;if($LASTEXITCODE -ne 0){throw 'MySQL candidate startup failed'}}
}finally{foreach($name in $saved.Keys){[Environment]::SetEnvironmentVariable($name,$saved[$name])}}
