param([switch]$Online,[string]$KeyFile='')
$ErrorActionPreference='Stop'
$candidateInfoFile=Join-Path $PSScriptRoot '.superpowers/risk-demo/operator.json'
if(-not(Test-Path -LiteralPath $candidateInfoFile)){throw 'No registered context candidate; no process was stopped.'}
$candidateInfo=Get-Content -LiteralPath $candidateInfoFile -Raw | ConvertFrom-Json
$candidateRegisteredAt=[DateTimeOffset]::Parse(([regex]::Match([IO.File]::ReadAllText($candidateInfoFile),'"startedAt"\s*:\s*"([^"]+)"')).Groups[1].Value).UtcDateTime
$candidateJar=[IO.Path]::GetFullPath($candidateInfo.jar)
$candidateRunRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '.superpowers/risk-demo/runs'))+[IO.Path]::DirectorySeparatorChar
if(-not $candidateJar.StartsWith($candidateRunRoot,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($candidateJar) -ne 'safebank-agent-0.6.0.jar' -or $candidateInfo.businessPort -ne 8110 -or $candidateInfo.operatorPort -ne 8111){throw 'Registration is not this isolated v0.6 candidate; no process was stopped.'}
$candidateProcess=Get-Process -Id $candidateInfo.pid -ErrorAction Stop
$candidateDetails=Get-CimInstance Win32_Process -Filter ('ProcessId='+$candidateInfo.pid)
if($candidateProcess.ProcessName -ne 'java' -or [Math]::Abs(($candidateProcess.StartTime.ToUniversalTime()-$candidateRegisteredAt).TotalSeconds) -gt 1 -or -not $candidateDetails.CommandLine.Replace('/','\').Contains($candidateJar.Replace('/','\')) -or (Get-FileHash -LiteralPath $candidateJar -Algorithm SHA256).Hash -ne $candidateInfo.jarSha256){throw 'Candidate PID/start time/command/hash mismatch; no process was stopped.'}
$candidateProcess.Kill()
if(-not $candidateProcess.WaitForExit(15000)){throw 'Registered candidate did not exit; restart aborted.'}
& (Join-Path $PSScriptRoot 'start-context-demo.ps1') -Online:$Online -KeyFile $KeyFile
