$ErrorActionPreference='Stop'
$toolsPath=Join-Path $PSScriptRoot '.tools'
New-Item -ItemType Directory -Force -Path $toolsPath | Out-Null
$archivePath=Join-Path $toolsPath 'maven.zip'
$mavenPath=Join-Path $toolsPath 'apache-maven-3.9.9\bin\mvn.cmd'
if (-not (Test-Path -LiteralPath $mavenPath)) {
    Invoke-WebRequest -Uri 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip' -OutFile $archivePath
    $expected=(Invoke-WebRequest -Uri 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip.sha512').Content.Trim().Split(' ')[0]
    if ((Get-FileHash -LiteralPath $archivePath -Algorithm SHA512).Hash -ne $expected.ToUpperInvariant()) { throw 'Maven 下载校验失败' }
    Expand-Archive -LiteralPath $archivePath -DestinationPath $toolsPath -Force
}
Write-Host 'Maven 已就绪。请运行 .\run.ps1 test。'
