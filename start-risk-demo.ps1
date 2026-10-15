param([int]$Port=8100,[int]$OperatorPort=8101,[switch]$Online,[string]$KeyFile='',
 [string]$JarFileName='safebank-agent-0.5.0.jar',[string]$Profile='risk-demo',[string]$BudgetPath='')
$ErrorActionPreference='Stop'
Set-Location -LiteralPath $PSScriptRoot
if($JarFileName -notmatch '^safebank-agent-[0-9]+\.[0-9]+\.[0-9]+\.jar$' -or $Profile -notmatch '^[a-z,-]+$'){throw 'Invalid candidate filename or profile.'}
$riskJar=Join-Path $PSScriptRoot ('target/'+$JarFileName)
if(-not(Test-Path -LiteralPath $riskJar)){throw 'Build the selected candidate JAR first.'}
if($Port -eq $OperatorPort){throw 'Business and operator ports must differ.'}
foreach($riskPort in @($Port,$OperatorPort)){if(Get-NetTCPConnection -LocalPort $riskPort -State Listen -ErrorAction SilentlyContinue){throw "Port $riskPort already in use; no process was stopped."}}
$riskFolder=Join-Path $PSScriptRoot '.superpowers/risk-demo'
New-Item -ItemType Directory -Force -Path $riskFolder | Out-Null
$riskRunFolder=Join-Path $riskFolder ('runs/'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $riskRunFolder | Out-Null
$riskRunJar=Join-Path $riskRunFolder $JarFileName
Copy-Item -LiteralPath $riskJar -Destination $riskRunJar
$riskToken=[guid]::NewGuid().ToString('N')+[guid]::NewGuid().ToString('N')
$riskPepper=[guid]::NewGuid().ToString('N')+[guid]::NewGuid().ToString('N')
$riskPrevious=@{}
foreach($riskName in @('BANK_MODEL_API_KEY','SPRING_APPLICATION_JSON','BANK_DEMO_OPERATOR_TOKEN','BANK_DEMO_PEPPER','BANK_DEMO_CONSOLE_PORT')){$riskPrevious[$riskName]=[Environment]::GetEnvironmentVariable($riskName,'Process')}
try{
 $env:BANK_MODEL_API_KEY='';$env:BANK_DEMO_OPERATOR_TOKEN=$riskToken;$env:BANK_DEMO_PEPPER=$riskPepper;$env:BANK_DEMO_CONSOLE_PORT=$OperatorPort.ToString()
 $env:SPRING_APPLICATION_JSON=''
 $riskSettings=@{}
 if($BudgetPath){if(-not[IO.Path]::IsPathRooted($BudgetPath)){throw 'Budget path must be absolute.'};$riskSettings['bank.model.debug-budget.path']=[IO.Path]::GetFullPath($BudgetPath);$riskSettings['bank.model.debug-budget.enabled']=$true;$riskSettings['bank.model.debug-budget.limit']=20}
 if($Online){
  if(-not $KeyFile){throw 'Online mode requires an explicit local key file path.'}
  $riskKeyText=[IO.File]::ReadAllText((Resolve-Path -LiteralPath $KeyFile).Path)
  $riskKeyMatches=[regex]::Matches($riskKeyText,'sk-[A-Za-z0-9_-]+')
  if($riskKeyMatches.Count -eq 1){$riskKey=$riskKeyMatches[0].Value}
  elseif($riskKeyMatches.Count -eq 0 -and $riskKeyText.Trim() -match '^[A-Za-z0-9_-]{20,}$'){$riskKey=$riskKeyText.Trim()}
  else{throw 'Expected exactly one unambiguous API key; value suppressed.'}
  $riskSettings['bank.model.api-key']=$riskKey
 }
 if($riskSettings.Count){$env:SPRING_APPLICATION_JSON=$riskSettings|ConvertTo-Json -Compress}
 $riskArgs=@('-Dfile.encoding=UTF-8','-jar',('"'+$riskRunJar+'"'),("--spring.profiles.active=$Profile"),("--server.port=$Port"))
 $riskProcess=Start-Process -FilePath 'C:/Program Files/Java/jdk-21/bin/java.exe' -ArgumentList $riskArgs -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $riskFolder 'stdout.log') -RedirectStandardError (Join-Path $riskFolder 'stderr.log')
 $riskInfo=@{pid=$riskProcess.Id;startedAt=$riskProcess.StartTime.ToUniversalTime().ToString('o');jar=(Resolve-Path -LiteralPath $riskRunJar).Path;jarSha256=(Get-FileHash -LiteralPath $riskRunJar -Algorithm SHA256).Hash;businessPort=$Port;operatorPort=$OperatorPort;operatorToken=$riskToken;pepper=$riskPepper;modelConfigured=[bool]$Online;scope='isolated synthetic memory demo'}
 [IO.File]::WriteAllText((Join-Path $riskFolder 'operator.json'),($riskInfo|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
 @{pid=$riskProcess.Id;url="http://127.0.0.1:$Port/";verificationMode='LOCAL_DEMO_ONLY';modelConfigured=[bool]$Online;modelRequests=0}|ConvertTo-Json
}finally{foreach($riskName in $riskPrevious.Keys){[Environment]::SetEnvironmentVariable($riskName,$riskPrevious[$riskName],'Process')}}
