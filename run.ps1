param([ValidateSet('test','start','package')][string]$Task='start',[string]$Tests='')
$ErrorActionPreference='Stop'
Set-Location -LiteralPath $PSScriptRoot
if (-not $env:JAVA_HOME) {
    if (Test-Path 'C:\Program Files\Java\jdk-21\bin\java.exe') { $env:JAVA_HOME='C:\Program Files\Java\jdk-21' }
    else { throw '请先设置 JAVA_HOME 为 Java 21 JDK 目录。' }
}
$env:PATH=(Join-Path $env:JAVA_HOME 'bin')+';'+$env:PATH
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'
$runPreviousProfile=$env:SPRING_PROFILES_ACTIVE
$runPreviousKey=$env:BANK_MODEL_API_KEY
$runSharedRoot=$PSScriptRoot
$runGitCommon=git rev-parse --path-format=absolute --git-common-dir 2>$null
if($LASTEXITCODE -eq 0){$runSharedRoot=Split-Path -Parent $runGitCommon.Trim()}
$mavenPath=Join-Path $runSharedRoot '.tools\apache-maven-3.9.9\bin\mvn.cmd'
if (-not (Test-Path -LiteralPath $mavenPath)) {
    $mavenCommand=Get-Command mvn -ErrorAction SilentlyContinue
    if ($mavenCommand) { $mavenPath=$mavenCommand.Source }
    else { throw '找不到 Maven。请运行 setup.ps1，或安装 Maven 3.9+。' }
}
$runRepoCache=Join-Path $runSharedRoot '.m2'
$arguments=@("-Dmaven.repo.local=$runRepoCache")
if ($Tests) { $arguments += ('-Dtest='+$Tests) }
switch ($Task) {
    'test' { $arguments += 'test' }
    'package' { $arguments += 'package' }
    'start' { $arguments += 'spring-boot:run' }
}
try{
 if($Task -in @('test','package')){$env:SPRING_PROFILES_ACTIVE='test';$env:BANK_MODEL_API_KEY=''}
 & $mavenPath @arguments
 $runExit=$LASTEXITCODE
}finally{
 $env:SPRING_PROFILES_ACTIVE=$runPreviousProfile
 $env:BANK_MODEL_API_KEY=$runPreviousKey
}
if ($runExit -ne 0) { exit $runExit }
