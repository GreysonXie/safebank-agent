param([string]$Tests='MySqlMigrationTest,MySqlSchemaComparisonTest,MySqlTimeMappingTest,MySqlMapperParityTest,MySqlBankBoundaryTest')
$ErrorActionPreference='Stop'
Set-Location -LiteralPath $PSScriptRoot
if($Tests -notmatch '^[A-Za-z0-9_,]+$') {throw 'Invalid test selection'}
foreach($name in @('SAFEBANK_MYSQL_TEST_URL','SAFEBANK_MYSQL_TEST_USER','SAFEBANK_MYSQL_TEST_PASSWORD')) {
    if([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {throw "Missing $name; refusing H2 fallback"}
}
if($env:SAFEBANK_MYSQL_TEST_URL -notmatch '^jdbc:mysql://127\.0\.0\.1:3307/safebank_v07_test_[A-Za-z0-9_]+(?:\?.*)?$') {throw 'Only isolated MySQL test database is allowed'}
$common=git -c "safe.directory=$PSScriptRoot" rev-parse --path-format=absolute --git-common-dir
if($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($common)) {throw 'Cannot resolve shared project directory'}
$shared=Split-Path -Parent $common.Trim()
$maven=Join-Path $shared '.tools/apache-maven-3.9.9/bin/mvn.cmd'
if(-not (Test-Path -LiteralPath $maven)) {throw 'Maven runtime not found'}
$oldKey=$env:BANK_MODEL_API_KEY;$oldProfiles=$env:SPRING_PROFILES_ACTIVE
$oldJava=$env:JAVA_HOME;$oldOptions=$env:JAVA_TOOL_OPTIONS;$oldPath=$env:PATH
try {
    $env:JAVA_HOME='C:/Program Files/Java/jdk-21'
    $env:PATH=(Join-Path $env:JAVA_HOME 'bin')+';'+$env:PATH
    $env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'
    $env:BANK_MODEL_API_KEY='';$env:SPRING_PROFILES_ACTIVE='mysql,mysql-test'
    & $maven ('-Dmaven.repo.local='+(Join-Path $shared '.m2')) ('-Dtest='+$Tests) test
    $testExit=$LASTEXITCODE
} finally {
    $env:BANK_MODEL_API_KEY=$oldKey;$env:SPRING_PROFILES_ACTIVE=$oldProfiles
    $env:JAVA_HOME=$oldJava;$env:JAVA_TOOL_OPTIONS=$oldOptions;$env:PATH=$oldPath
}
exit $testExit
