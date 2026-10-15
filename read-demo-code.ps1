param([Parameter(Mandatory=$true)][string]$ChallengeId)
$ErrorActionPreference='Stop'
if($ChallengeId -notmatch '^[A-Za-z0-9-]{1,80}$'){throw 'Invalid challenge identifier.'}
$demoInfo=Get-Content -LiteralPath (Join-Path $PSScriptRoot '.superpowers/risk-demo/operator.json') -Raw | ConvertFrom-Json
$demoResult=Invoke-RestMethod -Uri ('http://127.0.0.1:'+ $demoInfo.operatorPort+'/challenges/'+$ChallengeId) -Headers @{Authorization=('Bearer '+$demoInfo.operatorToken)}
# Intended for the local human operator; do not execute in agent logs or capture in reports.
$demoResult.code
