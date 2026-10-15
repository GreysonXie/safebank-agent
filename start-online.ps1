param([int]$Port=8093,[switch]$IncludeEvaluation,[switch]$UpdateExisting,[switch]$ReplaceOwned,[string]$KeyFile='')
$ErrorActionPreference='Stop'
Set-Location -LiteralPath $PSScriptRoot
$onlineJar=Join-Path $PSScriptRoot 'target\safebank-agent-0.4.1.jar'
if(-not (Test-Path -LiteralPath $onlineJar)){throw 'Build the v0.4.1 JAR first with run.ps1 package.'}
if(-not $KeyFile){$KeyFile=Join-Path (Split-Path $PSScriptRoot -Parent) 'apikey.txt'}
$onlineSecretText=Get-Content -LiteralPath $KeyFile -Raw
$onlineMatches=[regex]::Matches($onlineSecretText,'sk-[A-Za-z0-9_-]+')
if($onlineMatches.Count -eq 1){$onlineKey=$onlineMatches[0].Value}
elseif($onlineMatches.Count -eq 0 -and $onlineSecretText.Trim() -match '^[A-Za-z0-9_-]+$'){$onlineKey=$onlineSecretText.Trim()}
else{throw 'The key file must contain one unambiguous API key.'}
if($onlineKey.Length -lt 20){throw 'The key appears incomplete.'}
$onlinePreviousKey=$env:BANK_MODEL_API_KEY
$env:BANK_MODEL_API_KEY=$onlineKey
try{
    $onlinePorts=@($Port)
    if($IncludeEvaluation){if($Port -eq 8094){throw 'The evaluation port must differ from the demo port.'};$onlinePorts+=8094}
    if($UpdateExisting){$onlinePorts+=8092}
    $onlinePorts=@($onlinePorts|Select-Object -Unique)
    $onlineKnown=@{}
    foreach($onlineMetadata in @('target\v03-online-instances.json','target\deepseek-online-instance.json','target\online-instances.json')){
        if(Test-Path -LiteralPath $onlineMetadata){
            $onlineRows=Get-Content -LiteralPath $onlineMetadata -Raw|ConvertFrom-Json
            foreach($onlineRecorded in $onlineRows){
                $onlineKnown[[int]$onlineRecorded.port]=$onlineRecorded
            }
        }
    }
    $onlineInstances=@()
    foreach($onlinePort in $onlinePorts){
        if($onlinePort -lt 1024 -or $onlinePort -gt 65535){throw 'Use a localhost port between 1024 and 65535.'}
        $onlineOldFile=Join-Path $PSScriptRoot 'target\deepseek-online-instance.json'
        if($UpdateExisting -or $ReplaceOwned){
            $onlineOld=$onlineKnown[$onlinePort]
            if($onlineOld){
                $onlineOldProcess=Get-Process -Id $onlineOld.pid -ErrorAction SilentlyContinue
                if($onlineOldProcess -and $onlineOldProcess.ProcessName -eq 'java' -and [math]::Abs(($onlineOldProcess.StartTime-[datetime]$onlineOld.started).TotalSeconds) -lt 2){
                    Stop-Process -Id $onlineOld.pid -Force
                    if(-not $onlineOldProcess.WaitForExit(5000)){throw 'Owned instance did not stop within five seconds.'}
                }
            }
        }
        $onlineListeners=[System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()
        if(@($onlineListeners|Where-Object {$_.Port -eq $onlinePort}).Count){throw ('Port '+$onlinePort+' is occupied; no unknown process will be stopped.')}
        $onlineRunFolder=Join-Path $PSScriptRoot ('target\run\'+[guid]::NewGuid().ToString())
        New-Item -ItemType Directory -Path $onlineRunFolder -Force|Out-Null
        foreach($onlineLogName in @(('online-'+$onlinePort+'.log'),('online-'+$onlinePort+'.err.log'))){
            $onlinePreviousLog=Join-Path $PSScriptRoot ('target\'+$onlineLogName)
            if(Test-Path -LiteralPath $onlinePreviousLog){Copy-Item -LiteralPath $onlinePreviousLog -Destination (Join-Path $onlineRunFolder ('previous-'+$onlineLogName))}
        }
        $onlineRunJar=Join-Path $onlineRunFolder 'safebank-agent-0.4.1.jar'
        Copy-Item -LiteralPath $onlineJar -Destination $onlineRunJar
        $onlineArguments=@('-Xmx256m','-jar',('"'+$onlineRunJar+'"'),('--server.port='+$onlinePort),'--bank.simulation.faults-enabled=false')
        if($onlinePort -eq 8092 -and $UpdateExisting){$onlineArguments+='--spring.datasource.url=jdbc:h2:file:./data/deepseek-online-20261007;DB_CLOSE_ON_EXIT=FALSE'}
        else{$onlineArguments+=@(('--spring.datasource.url=jdbc:h2:mem:v03-'+$onlinePort+';DB_CLOSE_DELAY=-1'),'--spring.sql.init.schema-locations=classpath:schema.sql','--spring.sql.init.data-locations=file:fixtures/v03/v03-synthetic-bank.sql','--spring.sql.init.continue-on-error=false')}
        $onlineProcess=Start-Process -FilePath 'C:\Program Files\Java\jdk-21\bin\java.exe' -ArgumentList $onlineArguments -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -RedirectStandardOutput ('target\online-'+$onlinePort+'.log') -RedirectStandardError ('target\online-'+$onlinePort+'.err.log') -PassThru
        $onlineInstance=@{port=$onlinePort;pid=$onlineProcess.Id;started=$onlineProcess.StartTime.ToString('o');runJar=$onlineRunJar}
        $onlineInstances+=$onlineInstance
        $onlineKnown[$onlinePort]=$onlineInstance
        @($onlineKnown.Values)|ConvertTo-Json|Set-Content -LiteralPath target/online-instances.json -Encoding UTF8
        if($onlinePort -eq 8092 -and $UpdateExisting){$onlineInstance|ConvertTo-Json|Set-Content -LiteralPath $onlineOldFile -Encoding UTF8}
    }
    $onlineInstances|ForEach-Object{[pscustomobject]@{port=$_.port;pid=$_.pid}}|ConvertTo-Json
}finally{
    if($null -eq $onlinePreviousKey){Remove-Item Env:BANK_MODEL_API_KEY -ErrorAction SilentlyContinue}else{$env:BANK_MODEL_API_KEY=$onlinePreviousKey}
    Remove-Variable onlineSecretText,onlineMatches,onlineKey,onlinePreviousKey -ErrorAction SilentlyContinue
}
