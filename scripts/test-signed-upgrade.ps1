#Requires -Version 7.0
param(
    [Parameter(Mandatory)][ValidatePattern('^emulator-[0-9]+$')][string[]]$Serial,
    [Parameter(Mandatory)][string]$Baseline,
    [Parameter(Mandatory)][string]$Candidate,
    [string]$SdkDir = (Join-Path $env:LOCALAPPDATA 'Android/Sdk'),
    [ValidatePattern('^https://[a-zA-Z0-9.\-]+(?::[0-9]{1,5})?(?:/[a-zA-Z0-9._~/\-]*)?$')][string]$LiveBase,
    [ValidatePattern('^[a-zA-Z0-9][a-zA-Z0-9._:/\-]{0,127}$')][string]$LiveModel,
    [ValidateRange(2, 12)][int]$LiveRounds = 6
)

$ErrorActionPreference = 'Stop'
$adb = (Get-Command adb).Source
$tools = Join-Path $SdkDir 'build-tools/35.0.0'
$package = 'app.serein.chat'
$stamp = [guid]::NewGuid().ToString('N').Substring(0, 10)
$dump = "/data/local/tmp/serein-$stamp-upgrade.xml"
if ([bool]$LiveBase -ne [bool]$LiveModel) { throw 'LiveBase and LiveModel must be supplied together' }
if ($LiveBase) { $LiveBase = $LiveBase.TrimEnd('/') }

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

function Tap-View($view, [string]$label) {
    if ($view.enabled -ne 'true' -or $view.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { throw "Cannot tap $label" }
    Device shell input tap (([int]$Matches[1]+[int]$Matches[3]) -shr 1) (([int]$Matches[2]+[int]$Matches[4]) -shr 1) | Out-Null
}

function Tap([string]$label, [string]$class = '') { Tap-View (Wait-Node $label $class) $label }

function Type-Field([string]$label, [string]$value) {
    Tap $label 'android.widget.EditText'
    Device shell input text ($value -replace ' ', '%s') | Out-Null
    Device shell input keyevent KEYCODE_BACK | Out-Null
}

function Save-LiveKey {
    $ui = Screen
    $field = Find-Node $ui 'API Key' 'android.widget.EditText'
    $save = Find-Node $ui '保存'
    if (!$field -or $field.password -ne 'true' -or !$save) { throw 'Expected a masked Key field' }
    $value = [Net.NetworkCredential]::new('', $liveKey).Password
    try {
        if ($value -cnotmatch '^[a-zA-Z0-9_.\-]{12,256}$') { throw 'Unsupported test Key input format' }
        Tap-View $field 'masked Key field'
        # Pass the Key over stdin and avoid UI dumps until the password field is gone.
        @("input text '$value'", 'exit') | & $adb -s $deviceSerial shell -T sh *> $null
        if ($LASTEXITCODE -ne 0) { throw 'Could not enter the masked test Key' }
        Device shell input keyevent KEYCODE_BACK | Out-Null
        Start-Sleep -Milliseconds 350
        Tap-View $save '保存'
    } finally { $value = $null }
}

function Start-App {
    Device shell am start -W -n "$package/.MainActivity" | Out-Null
    Wait-Node '发消息' 'android.widget.EditText' | Out-Null
}

function Wait-OfflineReply([string]$text) {
    for ($i = 0; $i -lt 8; $i++) {
        $ui = Screen
        $prompt = Find-Node $ui $text 'android.widget.TextView'
        $composer = Find-Node $ui '发消息' 'android.widget.EditText'
        if ($prompt -and $composer.enabled -eq 'true' -and !(Find-Node $ui '停止生成') -and
            $prompt.SelectSingleNode("following::node[@package='$package' and @class='android.widget.TextView' and @text='收到。这是一条离线演示回复。']")) { return }
    }
    throw "Expected a complete offline reply after $text on $deviceSerial"
}

function Send-Offline([string]$text) {
    Wait-Node '选择模型，当前 离线演示' | Out-Null
    Type-Field '发消息' $text
    Tap '发送'
    Wait-OfflineReply $text
}

function Open-ProviderSettings {
    Tap '会话'
    Tap '设置'
    Tap '服务商连接'
}

function Wait-LiveReply([string]$expected, [string]$pidBefore, [switch]$Rich) {
    $observedPartial = $false
    $timer = [Diagnostics.Stopwatch]::StartNew()
    do {
        $ui = Screen
        $texts = @($ui.SelectNodes("//node[@package='$package' and @class='android.widget.TextView']") |
            ForEach-Object text)
        $failure = $texts | Where-Object {
            $_ -match '^(服务商(拒绝访问|返回 HTTP|限流|暂时繁忙)|无法验证 HTTPS|无法找到服务商|连接超时|请求失败|回复未完成|模型.*上限|模型没有返回)'
        } | Select-Object -First 1
        if ($failure) { throw "Online reply on $deviceSerial did not complete: $failure" }
        $reply = $texts | Where-Object { $_ -ceq $expected -or ($Rich -and $_ -like "*$expected*" -and $_ -notlike 'Write a Markdown*') }
        $busy = Find-Node $ui '停止生成'
        if ($busy -and $reply) { $observedPartial = $true }
        $composer = Find-Node $ui '发消息' 'android.widget.EditText'
        if ($reply -and $composer.enabled -eq 'true' -and !$busy) {
            if ((Device shell pidof $package).Trim() -ne $pidBefore) { throw 'Serein process changed during live chat' }
            return $observedPartial
        }
        Start-Sleep -Milliseconds 250
    } while ($timer.Elapsed.TotalSeconds -lt 90)
    throw "No complete online reply on $deviceSerial"
}

function Send-LiveBatch([string]$provider, [string]$modelTitle) {
    Tap '选择模型，当前 离线演示'
    Tap $modelTitle
    Wait-Node "选择模型，当前 $modelTitle" | Out-Null
    $appPid = (Device shell pidof $package).Trim()
    $streamObserved = $false
    $last = ''
    for ($turn = 1; $turn -le $LiveRounds; $turn++) {
        $last = "SEREIN-QA-$stamp-$turn"
        $rich = $turn -eq 3
        $prompt = if ($rich) {
            "Write a Markdown list of 35 tiny test items. Start with the heading $last and use no links."
        } else {
            "Reply with exactly $last and nothing else."
        }
        Type-Field '发消息' $prompt
        Tap '发送'
        if ($turn -eq 1) {
            Wait-Node "发送给 $provider" | Out-Null
            if (!(Screen).OuterXml.Contains($LiveBase + '/chat/completions')) { throw 'Unexpected disclosed recipient' }
            Tap '取消'
            Wait-Node $prompt 'android.widget.EditText' | Out-Null
            Tap '发送'
            Wait-Node "发送给 $provider" | Out-Null
            Tap '发送'
        }
        if ($rich) {
            Device shell input keyevent KEYCODE_HOME | Out-Null
            Start-Sleep -Milliseconds 800
            Device shell am start -W -n "$package/.MainActivity" | Out-Null
        }
        if (Wait-LiveReply $last $appPid -Rich:$rich) { $streamObserved = $true }
        "PASS: $deviceSerial live reply $turn/$LiveRounds"
    }
    Device shell am force-stop $package | Out-Null
    Start-App
    Wait-Node "选择模型，当前 $modelTitle" | Out-Null
    $null = Wait-LiveReply $last (Device shell pidof $package).Trim() -Rich:($LiveRounds -eq 3)
    "PASS: $deviceSerial completed $LiveRounds real replies with the upgraded Key and retained the last reply after restart; UI partial observed: $streamObserved"
}

function PackageState {
    $info = Device shell dumpsys package $package
    $uid = ($info | Select-String '^\s+(?:userId|appId)=(\d+)' | Select-Object -First 1).Matches[0].Groups[1].Value
    $code = ($info | Select-String '^\s+versionCode=(\d+)' | Select-Object -First 1).Matches[0].Groups[1].Value
    $first = ($info | Select-String 'firstInstallTime=(.+)' | Select-Object -First 1).Matches[0].Groups[1].Value
    @{Uid=$uid; Code=[int]$code; FirstInstall=$first}
}

function Test-Upgrade([string]$deviceSerial) {
    $name = Device emu avd name | Select-Object -First 1
    if ($name -notmatch '^SereinBeta(?:33|37)$' -or (Device shell getprop ro.kernel.qemu) -ne '1' -or
        (Device shell pm list packages $package)) { throw 'Use a fresh disposable SereinBeta33 or SereinBeta37 emulator; will not touch an existing installation' }
    $marker = "Upgrade-$stamp"
    $provider = 'Serein Upgrade Fixture'
    $fixtureBase = if ($LiveBase) { $LiveBase } else { 'https://example.invalid/v1' }
    $fixtureModel = if ($LiveModel) { $LiveModel } else { 'serein-upgrade-model' }
    $keyLabel = if ($LiveBase) { 'Disposable online QA Key' } else { 'Synthetic upgrade key' }
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
        Type-Field 'HTTPS 根地址（含 API 路径）' $fixtureBase
        Tap '保存'
        Tap '添加 Key'
        Type-Field 'Key 名称' $keyLabel
        if ($LiveBase) { Save-LiveKey }
        else {
            Type-Field 'API Key' 'synthetic-upgrade-value'
            Tap '保存'
        }
        Wait-Node $keyLabel | Out-Null
        Tap '手动添加模型'
        Type-Field '模型 ID' $fixtureModel
        Tap '保存'
        $modelRow = Wait-Node $fixtureModel 'android.widget.TextView'
        $modelTitle = $modelRow.ParentNode.SelectSingleNode('node[@class="android.widget.TextView"][1]').text
        if (!$modelTitle) { throw 'Could not identify the saved model display name' }
        Device shell am force-stop $package | Out-Null

        Device install -r $to.Path
        $updated = PackageState
        if ($updated.Code -ne $to.Code -or $updated.Uid -ne $installed.Uid -or $updated.FirstInstall -ne $installed.FirstInstall) {
            throw 'APK did not update the existing installation in place'
        }
        $apkPath = (Device shell pm path $package) -replace '^package:', ''
        if ($apkPath -notmatch '^/data/app/.+/base\.apk$' -or ((Device shell sha256sum $apkPath) -split '\s+')[0] -ne $to.Hash) {
            throw 'Installed build does not match the candidate APK'
        }
        Start-App
        Wait-Node $marker 'android.widget.TextView' | Out-Null
        Wait-OfflineReply "$marker-followup"
        Open-ProviderSettings
        Tap $provider
        Wait-Node $fixtureBase | Out-Null
        Wait-Node $keyLabel | Out-Null
        Wait-Node $fixtureModel | Out-Null
        Tap '返回'
        Tap '返回'
        Tap '返回'
        Tap '关闭侧栏' 'android.widget.Button'
        Send-Offline "$marker-after"
        Device shell am force-stop $package | Out-Null
        Start-App
        Wait-OfflineReply "$marker-after"
        if ($LiveBase) { Send-LiveBatch $provider $modelTitle }
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

$from = Apk $Baseline
$to = Apk $Candidate
if ($from.Signer -ne $to.Signer -or $from.Code -ge $to.Code) { throw 'Upgrade must use the same signer and increase versionCode' }
"Baseline: $($from.Name) SHA-256 $($from.Hash)"
"Candidate: $($to.Name) SHA-256 $($to.Hash)"
"Verified signer SHA-256 $($to.Signer)"

$liveKey = if ($LiveBase) { Read-Host 'Disposable QA provider Key' -AsSecureString }
try {
    foreach ($deviceSerial in $Serial) { Test-Upgrade $deviceSerial }
} finally { if ($liveKey) { $liveKey.Dispose() } }
