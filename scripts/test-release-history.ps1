#Requires -Version 7.3
param(
    [Parameter(Mandatory)][ValidatePattern('^emulator-[0-9]+$')][string]$Serial,
    [Parameter(Mandatory)][string]$Candidate,
    [ValidateRange(65, 1200)][int]$Turns = 1200,
    [string]$SdkDir = (Join-Path $env:LOCALAPPDATA 'Android/Sdk')
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$adb = (Get-Command adb).Source
$tools = Join-Path $SdkDir 'build-tools/35.0.0'
$platform = Join-Path $SdkDir 'platforms/android-36/android.jar'
$source = Join-Path $PSScriptRoot 'qa/release-history'
$app = 'app.serein.chat'
$helper = 'app.serein.historyqa'
$stamp = [guid]::NewGuid().ToString('N')
$filename = "serein-history-$stamp.md"
$remote = "/sdcard/Download/$filename"
$out = Join-Path $PSScriptRoot "../outputs/release-history/$stamp"
$candidatePath = (Get-Item -LiteralPath $Candidate).FullName

function Device { & $adb -s $Serial @args }

if ((Device emu avd name | Select-Object -First 1) -notmatch '^SereinBeta(?:33|37)$' -or
    (Device shell getprop ro.kernel.qemu) -ne '1' -or
    (Device shell getprop sys.boot_completed) -ne '1' -or
    (Device shell settings get secure accessibility_enabled) -eq '1' -or
    (Device shell pm list packages $app) -or (Device shell pm list packages $helper)) {
    throw 'Use a fresh and fully booted SereinBeta33 or SereinBeta37 AVD with accessibility services off.'
}
& (Join-Path $tools 'apksigner.bat') verify $candidatePath
$badging = & (Join-Path $tools 'aapt.exe') dump badging $candidatePath
if (!($badging -match "^package: name='app\.serein\.chat'") -or
    $badging -contains 'application-debuggable') {
    throw 'Candidate must be the non-debuggable Serein APK.'
}
$digest = (Get-FileHash -LiteralPath $candidatePath -Algorithm SHA256).Hash
$classes = Join-Path $out 'classes'
$dex = Join-Path $out 'dex'
New-Item -ItemType Directory -Path $classes, $dex | Out-Null
$unsigned = Join-Path $out 'helper-unsigned.apk'
$signed = Join-Path $out 'helper.apk'
$buildKey = Join-Path $out 'fixture.p12'

& javac --release 11 -encoding UTF-8 -cp $platform -d $classes (Join-Path $source 'ReleaseHistory.java')
$classFiles = @(Get-ChildItem -LiteralPath $classes -Recurse -Filter '*.class' | ForEach-Object FullName)
& (Join-Path $tools 'd8.bat') --min-api 33 --lib $platform --output $dex @classFiles
& (Join-Path $tools 'aapt.exe') package -f -M (Join-Path $source 'AndroidManifest.xml') -I $platform -F $unsigned
Push-Location -LiteralPath $dex
try { & (Join-Path $tools 'aapt.exe') add $unsigned classes.dex }
finally { Pop-Location }
& keytool -genkeypair -alias fixture -keyalg RSA -keysize 2048 -validity 2 -dname 'CN=Serein History QA' `
    -storetype PKCS12 -keystore $buildKey -storepass only-local-qa
& (Join-Path $tools 'apksigner.bat') sign --ks $buildKey --ks-pass pass:only-local-qa --out $signed $unsigned

$installed = $false
$helperInstalled = $false
try {
    $faultPattern = '\bapp\.serein\.chat(?:,|\])'
    $before = @(Device logcat -b events -d -v threadtime -s 'am_anr:I' 'am_crash:I' | Where-Object { $_ -match $faultPattern })
    Device shell input keyevent KEYCODE_WAKEUP | Out-Null
    Device shell wm dismiss-keyguard | Out-Null
    Device install $candidatePath
    $installed = $true
    $packagePath = (Device shell pm path $app) -replace '^package:', ''
    if ($packagePath -notmatch '^/data/app/.+/base\.apk$' -or
        ((Device shell sha256sum $packagePath) -split '\s+')[0] -ne $digest) {
        throw 'Installed APK does not match the candidate.'
    }
    "Candidate SHA-256: $digest; API $(Device shell getprop ro.build.version.sdk); turns: $Turns"
    Device install $signed
    $helperInstalled = $true
    $runLog = Join-Path $out 'instrumentation.txt'
    Device shell am instrument -w -e turns $Turns -e filename $filename "$helper/.ReleaseHistory" |
        Tee-Object -FilePath $runLog
    if (!((Get-Content -LiteralPath $runLog -Raw).Contains('RELEASE_HISTORY_UI_PASS'))) {
        throw "Release-history UI checks failed. See $runLog."
    }
    $local = Join-Path $out $filename
    $exists = $false
    for ($i = 0; $i -lt 30; $i++) {
        if ((Device shell ls /sdcard/Download) -contains $filename) { $exists = $true; break }
        Start-Sleep -Milliseconds 250
    }
    if (!$exists) { throw "No Markdown export found in Downloads. See $runLog." }
    Device pull $remote $local
    $lines = Get-Content -LiteralPath $local -Encoding utf8
    $messages = @($lines | Where-Object { $_ -match '^ReleaseHistory [0-9]{4}$' -or $_ -ceq '收到。这是一条离线演示回复。' })
    if ($messages.Count -ne 2 * $Turns) { throw 'Export omitted or duplicated a prompt or reply.' }
    for ($i = 0; $i -lt $Turns; $i++) {
        if ($messages[2 * $i] -cne ('ReleaseHistory {0:D4}' -f ($i + 1)) -or
            $messages[2 * $i + 1] -cne '收到。这是一条离线演示回复。') { throw "Export order differs at index $i." }
    }
    $faults = @(Device logcat -b events -d -v threadtime -s 'am_anr:I' 'am_crash:I' |
        Where-Object { $_ -match $faultPattern -and $_ -notin $before })
    if ($faults) { throw 'Android logged a new Serein crash or ANR.' }
    "PASS: $Turns prompts and replies exported in order, $((Get-Item -LiteralPath $local).Length) bytes; no new crash or ANR."
    "Evidence: $out"
} finally {
    Device shell rm -f $remote | Out-Null
    if ($helperInstalled) { Device uninstall $helper | Out-Null }
    if ($installed) { Device uninstall $app | Out-Null }
}
