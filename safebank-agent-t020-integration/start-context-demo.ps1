param([int]$Port=8110,[int]$OperatorPort=8111,[switch]$Online,[string]$KeyFile='')
$ErrorActionPreference='Stop'
$contextCommon=git -c ('safe.directory='+$PSScriptRoot.Replace('\','/')) -C $PSScriptRoot rev-parse --path-format=absolute --git-common-dir
if($LASTEXITCODE -ne 0){throw 'Cannot locate shared project root for the single debug budget.'}
$contextRoot=Split-Path -Parent $contextCommon.Trim()
$contextBudget=Join-Path $contextRoot '.superpowers/deepseek-v06-debug/20261010-v06-context.json'
& (Join-Path $PSScriptRoot 'start-risk-demo.ps1') -Port $Port -OperatorPort $OperatorPort -Online:$Online -KeyFile $KeyFile -JarFileName 'safebank-agent-0.6.0.jar' -Profile 'risk-demo,context-demo' -BudgetPath $contextBudget
