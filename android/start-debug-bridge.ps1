param([ValidatePattern('^COM[0-9]+$')][string]$ComPort = 'COM9', [switch]$Install)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$pythonExe = Join-Path $projectRoot '.venv\Scripts\python.exe'
$adbExe = if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe' } else { (Get-Command adb).Source }
if ($Install) {
    & (Join-Path $PSScriptRoot 'gradlew.bat') -p $PSScriptRoot assembleDebug
    if ($LASTEXITCODE -ne 0) { throw 'Android build failed' }
    & $adbExe -d install -r (Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk')
    if ($LASTEXITCODE -ne 0) { throw 'Phone installation failed; check the phone prompt' }
}
& $adbExe -d reverse tcp:28732 tcp:28732
if ($LASTEXITCODE -ne 0) { throw 'Connect the phone by USB and authorize USB debugging first' }
$listener = Get-NetTCPConnection -State Listen -LocalPort 28732 -ErrorAction SilentlyContinue
if ($listener) {
    $owner = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener[0].OwningProcess)"
    if ($owner.CommandLine -notmatch 'serial_android_bridge.py') { throw 'Port 28732 is already used by another program' }
} else {
    $logDir = Join-Path $PSScriptRoot 'qa'
    New-Item -ItemType Directory -Force $logDir | Out-Null
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $scriptPath = Join-Path $projectRoot 'tools\serial_android_bridge.py'
    $child = Start-Process -FilePath $pythonExe -ArgumentList @('-u', ('"' + $scriptPath + '"'), '--port', $ComPort) -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logDir "bridge-$stamp.log") -RedirectStandardError (Join-Path $logDir "bridge-$stamp-error.log") -PassThru
    $child.Id | Set-Content (Join-Path $logDir 'bridge-service.pid')
}
& $adbExe -d shell am start -n com.echocraft.studio/.MainActivity --ez enable_bridge true
Write-Output 'Ready: ESP32 COM -> computer -> phone USB debugging -> EchoCraft camera recording'
