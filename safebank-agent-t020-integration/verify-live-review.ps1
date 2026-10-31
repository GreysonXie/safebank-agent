param([string]$KeyFile='')
$ErrorActionPreference='Stop'
Set-Location -LiteralPath $PSScriptRoot
if(-not $KeyFile){$KeyFile=Join-Path (Split-Path $PSScriptRoot -Parent) 'apikey.txt'}
$reviewSecret=[IO.File]::ReadAllText($KeyFile)
$reviewMatches=[regex]::Matches($reviewSecret,'sk-[A-Za-z0-9_-]+')
if($reviewMatches.Count -ne 1){throw 'The key file must contain one unambiguous API key.'}
$reviewPreviousKey=$env:BANK_MODEL_API_KEY
$reviewPreviousEnabled=$env:SAFEBANK_LIVE_REVIEW_TEST
try {
    $env:BANK_MODEL_API_KEY=$reviewMatches[0].Value
    $env:SAFEBANK_LIVE_REVIEW_TEST='true'
    $reviewShell='C:\Users\21310\.cache\codex-runtimes\codex-primary-runtime\dependencies\native\powershell\pwsh.exe'
    if(-not (Test-Path -LiteralPath $reviewShell)){throw 'PowerShell 7 is required for the UTF-8 project scripts; adjust reviewShell for this machine.'}
    $reviewArguments=@('-NoProfile','-File',('"'+(Join-Path $PSScriptRoot 'run.ps1')+'"'),'test','-Tests','LiveBusinessAnswerReviewTest')
    $reviewProcess=Start-Process -FilePath $reviewShell -ArgumentList $reviewArguments -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -Wait -PassThru -RedirectStandardOutput (Join-Path $PSScriptRoot 'target/v041-live-review.log') -RedirectStandardError (Join-Path $PSScriptRoot 'target/v041-live-review.err.log')
    $reviewExit=$reviewProcess.ExitCode
} finally {
    if($null -eq $reviewPreviousKey){Remove-Item Env:BANK_MODEL_API_KEY -ErrorAction SilentlyContinue}else{$env:BANK_MODEL_API_KEY=$reviewPreviousKey}
    if($null -eq $reviewPreviousEnabled){Remove-Item Env:SAFEBANK_LIVE_REVIEW_TEST -ErrorAction SilentlyContinue}else{$env:SAFEBANK_LIVE_REVIEW_TEST=$reviewPreviousEnabled}
    Remove-Variable reviewSecret,reviewMatches,reviewPreviousKey,reviewPreviousEnabled -ErrorAction SilentlyContinue
}
if($reviewExit -ne 0){throw 'Live read-only review failed; inspect target/eval/v041-live-review.json and target/v041-live-review.log.'}
Write-Output 'Live review completed; report: target/eval/v041-live-review.json'
