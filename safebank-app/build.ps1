# SafeBank App 构建脚本（Windows / PowerShell）
# 说明：
# 1) DSH 沙箱只允许写工作区，因此 Gradle 用户目录被重定向到工作区内的 .gradle-home；
# 2) 子 JVM（Gradle/Kotlin daemon）对系统 %TEMP% 的写入可能被沙箱拒绝，
#    这里把 TEMP/TMP 指到工作区 .tmp，保证 compileDebugKotlin 不报 AccessDeniedException。
param(
    [Parameter(Position = 0)]
    [ValidateSet('assembleDebug', 'test', 'lint', 'clean', 'assembleRelease')]
    [string]$Task = 'assembleDebug',
    [switch]$NoDaemon
)

$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$workspaceRoot = Split-Path $projectRoot -Parent
$gradleHome = Join-Path $workspaceRoot '.gradle-home'
$tempDir = Join-Path $workspaceRoot '.tmp'

foreach ($dir in @($gradleHome, $tempDir)) {
    if (-not (Test-Path $dir)) {
        Write-Host "创建目录: $dir"
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
    }
}

$env:GRADLE_USER_HOME = $gradleHome
$env:TEMP = $tempDir
$env:TMP = $tempDir
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $env:JAVA_HOME = 'E:\Develop\java17'
}

$gradlew = Join-Path $projectRoot 'gradlew.bat'
$args = @($Task, '--console=plain')
if ($NoDaemon) { $args += '--no-daemon' }

Push-Location $projectRoot
try {
    & $gradlew @args
    $code = $LASTEXITCODE
} finally {
    Pop-Location
}
exit $code
