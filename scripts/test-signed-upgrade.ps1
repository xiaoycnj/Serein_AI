#Requires -Version 7.0
param(
    [Parameter(Mandatory)][ValidatePattern('^emulator-[0-9]+$')][string[]]$Serial,
    [Parameter(Mandatory)][string]$Baseline,
    [Parameter(Mandatory)][string]$Candidate,
    [string]$SdkDir = (Join-Path $env:LOCALAPPDATA 'Android/Sdk')
)

$ErrorActionPreference = 'Stop'
$adb = (Get-Command adb).Source
$tools = Join-Path $SdkDir 'build-tools/35.0.0'
$package = 'app.serein.chat'
$stamp = [guid]::NewGuid().ToString('N').Substring(0, 10)
$dump = "/data/local/tmp/serein-$stamp-upgrade.xml"

function Apk([string]$path) {
    $file = Get-Item -LiteralPath $path
    $signature = & (Join-Path $tools 'apksigner.bat') verify --print-certs $file.FullName
    if ($LASTEXITCODE -ne 0) { throw "Invalid APK signature for $($file.Name)" }
    $digest = @($signature | Select-String 'certificate SHA-256 digest: ([0-9a-f]{64})$')
    if ($digest.Count -ne 1) { throw 'Expected one verified signing certificate' }
    $badging = & (Join-Path $tools 'aapt.exe') dump badging $file.FullName
    if ($LASTEXITCODE -ne 0) { throw "Could not identify $($file.Name)" }
    $identity = $badging | Select-String "^package: name='app\.serein\.chat' versionCode='(\d+)' versionName='([^']+)'"
    if (!$identity -or $badging -contains 'application-debuggable' -or $file.Length -ge 64000000 -or
        $badging -notcontains "sdkVersion:'33'") { throw "Not an Android 13+ release APK below 64 MB: $($file.Name)" }
    @{Path=$file.FullName; Code=[int]$identity.Matches[0].Groups[1].Value; Name=$identity.Matches[0].Groups[2].Value;
      Signer=$digest[0].Matches[0].Groups[1].Value; Hash=(Get-FileHash -LiteralPath $file.FullName).Hash}
}

function Device {
    $result = & $adb -s $deviceSerial @args
    if ($LASTEXITCODE -ne 0) { throw "ADB failed on $deviceSerial ($($args[0]))" }
    $result
}

function Screen {
    for ($i = 0; $i -lt 3; $i++) {
        $receipt = & $adb -s $deviceSerial shell uiautomator dump $dump 2>&1
        if ($LASTEXITCODE -eq 0 -and ($receipt -join ' ').Contains($dump)) {
            try { return [xml]((Device shell cat $dump) -join "`n") } catch { if ($i -eq 2) { throw } }
        }
        Start-Sleep -Milliseconds 250
    }
    throw 'Could not read fresh UI snapshot'
}

function Find-Node([xml]$screen, [string]$label, [string]$class = '') {
    $screen.SelectNodes("//node[@package='$package']") | Where-Object {
        (!$class -or $_.class -eq $class) -and ($_.text -ceq $label -or $_.'content-desc' -ceq $label)
    } | Select-Object -Last 1
}

function Wait-Node([string]$label, [string]$class = '') {
    for ($i = 0; $i -lt 8; $i++) { if ($view = Find-Node (Screen) $label $class) { return $view } }
    throw "Expected $label on $deviceSerial"
}

function Tap([string]$label, [string]$class = '') {
    $view = Wait-Node $label $class
    if ($view.enabled -ne 'true' -or $view.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { throw "Cannot tap $label" }
    Device shell input tap (([int]$Matches[1]+[int]$Matches[3]) -shr 1) (([int]$Matches[2]+[int]$Matches[4]) -shr 1) | Out-Null
}

function Type-Field([string]$label, [string]$value) {
    Tap $label 'android.widget.EditText'
    Device shell input text ($value -replace ' ', '%s') | Out-Null
    Device shell input keyevent KEYCODE_BACK | Out-Null
}

function Start-App {
    Device shell am start -W -n "$package/.MainActivity" | Out-Null
    Wait-Node '发消息' 'android.widget.EditText' | Out-Null
}

function Send-Offline([string]$text) {
    Wait-Node '选择模型，当前 离线演示' | Out-Null
    Type-Field '发消息' $text
    Tap '发送'
    Wait-Node $text | Out-Null
    Wait-Node '收到。这是一条离线演示回复。' | Out-Null
}

function Open-ProviderSettings {
    Tap '会话'
    Tap '设置'
    Tap '服务商连接'
}

function PackageState {
    $info = Device shell dumpsys package $package
    $uid = ($info | Select-String '^\s+(?:userId|appId)=(\d+)' | Select-Object -First 1).Matches[0].Groups[1].Value
    $code = ($info | Select-String '^\s+versionCode=(\d+)' | Select-Object -First 1).Matches[0].Groups[1].Value
    $first = ($info | Select-String 'firstInstallTime=(.+)' | Select-Object -First 1).Matches[0].Groups[1].Value
    @{Uid=$uid; Code=[int]$code; FirstInstall=$first}
}

$from = Apk $Baseline
$to = Apk $Candidate
if ($from.Signer -ne $to.Signer -or $from.Code -ge $to.Code) { throw 'Upgrade must use the same signer and increase versionCode' }
"Baseline: $($from.Name) SHA-256 $($from.Hash)"
"Candidate: $($to.Name) SHA-256 $($to.Hash)"
"Verified signer SHA-256 $($to.Signer)"

foreach ($deviceSerial in $Serial) {
    $name = Device emu avd name | Select-Object -First 1
    if ($name -notmatch '^SereinBeta(?:33|37)$' -or (Device shell getprop ro.kernel.qemu) -ne '1' -or
        (Device shell pm list packages $package)) { throw 'Use a fresh disposable SereinBeta33 or SereinBeta37 emulator; will not touch an existing installation' }
    $marker = "Upgrade-$stamp"
    $provider = 'Serein Upgrade Fixture'
    $started = $false
    try {
        $beforeFaults = Device logcat -b events -d -v threadtime -s 'am_anr:I' 'am_crash:I' | Where-Object { $_ -match '\bapp\.serein\.chat(?:,|\])' }
        Device shell input keyevent KEYCODE_WAKEUP | Out-Null
        Device shell wm dismiss-keyguard | Out-Null
        Device install $from.Path
        $started = $true
        $installed = PackageState
        if ($installed.Code -ne $from.Code) { throw 'Wrong baseline installed' }
        Start-App
        Send-Offline $marker
        Send-Offline "$marker-followup"

        Open-ProviderSettings
        Tap '添加服务商'
        Type-Field '服务商名称' $provider
        Type-Field 'HTTPS 根地址（含 API 路径）' 'https://example.invalid/v1'
        Tap '保存'
        Tap '添加 Key'
        Type-Field 'Key 名称' 'Synthetic upgrade key'
        Type-Field 'API Key' 'synthetic-upgrade-value'
        Tap '保存'
        Wait-Node 'Synthetic upgrade key' | Out-Null
        Tap '手动添加模型'
        Type-Field '模型 ID' 'serein-upgrade-model'
        Tap '保存'
        Wait-Node 'serein-upgrade-model' | Out-Null
        Device shell am force-stop $package | Out-Null

        Device install -r $to.Path
        $updated = PackageState
        if ($updated.Code -ne $to.Code -or $updated.Uid -ne $installed.Uid -or $updated.FirstInstall -ne $installed.FirstInstall) {
            throw 'APK did not update the existing installation in place'
        }
        Start-App
        Wait-Node $marker | Out-Null
        Wait-Node "$marker-followup" | Out-Null
        Wait-Node '收到。这是一条离线演示回复。' | Out-Null
        Open-ProviderSettings
        Tap $provider
        Wait-Node 'https://example.invalid/v1' | Out-Null
        Wait-Node 'Synthetic upgrade key' | Out-Null
        Wait-Node 'serein-upgrade-model' | Out-Null
        Tap '返回'
        Tap '返回'
        Tap '返回'
        Tap '关闭侧栏' 'android.widget.Button'
        Send-Offline "$marker-after"
        Device shell am force-stop $package | Out-Null
        Start-App
        Wait-Node "$marker-after" | Out-Null
        $faults = Device logcat -b events -d -v threadtime -s 'am_anr:I' 'am_crash:I' | Where-Object {
            $_ -match '\bapp\.serein\.chat(?:,|\])' -and $_ -notin $beforeFaults
        }
        if ($faults) { throw 'New application crash or ANR during the upgrade' }
        "PASS: $deviceSerial API $(Device shell getprop ro.build.version.sdk) kept install identity, existing chat, provider/model and key entry; new reply survived restart"
    } finally {
        if ($started) { Device uninstall $package | Out-Null }
        Device shell rm -f $dump | Out-Null
    }
}
