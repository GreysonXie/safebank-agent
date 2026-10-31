# 真机一键联调：安装 APK → adb reverse → 预置服务器地址 → 启动 → 截图
# 前提：手机已用 USB 连接并开启 USB 调试；后端已在 127.0.0.1:8080 运行。
param(
    [string]$Apk = "",
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$ShotPath = "",
    [switch]$SkipInstall
)

$ErrorActionPreference = 'Stop'
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$adb = Join-Path $sdk "platform-tools\adb.exe"
if (-not $Apk) { $Apk = Join-Path $PSScriptRoot "app\build\outputs\apk\debug\app-debug.apk" }
if (-not $ShotPath) { $ShotPath = Join-Path $PSScriptRoot "docs\device-screenshot.png" }
$package = "cn.safebank.app"
$activity = "$package/.MainActivity"
$prefsDir = Join-Path $env:TEMP "safebank-prefs"
$prefsFile = Join-Path $prefsDir "safebank_settings.xml"

function Invoke-Adb { param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args) & $adb @Args }

$devices = & $adb devices
if ($devices -notmatch '(?m)^\S+\s+device') {
    throw "未检测到已授权的设备。请插上手机、开启 USB 调试，并在手机上允许调试授权。`n$($devices | Out-String)"
}

Write-Host "== 1/6 设备 =="
& $adb devices -l

if (-not $SkipInstall) {
    Write-Host "== 2/6 安装 APK =="
    & $adb install -r $Apk
} else {
    Write-Host "== 2/6 跳过安装 =="
}

Write-Host "== 3/6 adb reverse（手机 127.0.0.1:8080 -> 电脑 127.0.0.1:8080）=="
& $adb reverse tcp:8080 tcp:8080
& $adb reverse --list

Write-Host "== 4/6 预置服务器地址 $BaseUrl =="
& $adb shell am force-stop $package | Out-Null
New-Item -ItemType Directory -Force -Path $prefsDir | Out-Null
$xml = @"
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="base_url">$BaseUrl</string>
</map>
"@
[System.IO.File]::WriteAllText($prefsFile, $xml, (New-Object System.Text.UTF8Encoding($false)))
& $adb push $prefsFile "/data/local/tmp/safebank_settings.xml" | Out-Null
& $adb shell run-as $package mkdir -p shared_prefs
& $adb shell run-as $package cp /data/local/tmp/safebank_settings.xml shared_prefs/safebank_settings.xml
& $adb shell run-as $package cat shared_prefs/safebank_settings.xml

Write-Host "== 5/6 启动 App =="
& $adb shell am start -n $activity
Start-Sleep -Seconds 6

Write-Host "== 6/6 截图 =="
$tmp = Join-Path $env:TEMP "safebank-shot.png"
$bytes = & $adb exec-out screencap -p
[System.IO.File]::WriteAllBytes($tmp, $bytes)
Copy-Item $tmp $ShotPath -Force
Write-Host "截图已保存: $ShotPath"

Write-Host "== 完成：App 已连接 $BaseUrl =="
