param([switch]$Running,[switch]$Help,[string]$Report='')
$ErrorActionPreference='Stop'
$doctorArgs=@((Join-Path $PSScriptRoot 'doctor.py'))
if($Running){$doctorArgs+='--running'}
if($Help){$doctorArgs+='--help'}
if($Report){$doctorArgs+=@('--report',$Report)}
if(Get-Command python -ErrorAction SilentlyContinue){& python @doctorArgs}
elseif(Get-Command py -ErrorAction SilentlyContinue){& py -3 @doctorArgs}
else{Write-Output 'FAIL PYTHON_HOST: Install Python 3.10+ and reopen PowerShell.';exit 1}
exit $LASTEXITCODE
