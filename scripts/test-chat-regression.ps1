#Requires -Version 7.0
param(
    [Parameter(Mandatory)][ValidatePattern('^emulator-[0-9]+$')][string[]]$Serial,
    [string]$SdkDir = $env:ANDROID_HOME,
    [switch]$CaptureUi
)

$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
if (!$SdkDir) { $SdkDir = $env:ANDROID_SDK_ROOT }
if (!$SdkDir) { $SdkDir = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$env:ANDROID_HOME = (Resolve-Path -LiteralPath $SdkDir).Path
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (!$adb) { $adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe' }
$nodeExe = (Get-Command node).Source
$keytool = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/keytool.exe' } else { (Get-Command keytool).Source }
$package = 'app.serein.chat.chatqa'
$component = "$package/app.serein.chat.MainActivity"
$runId = [guid]::NewGuid().ToString('N')
$dump = "/data/local/tmp/serein-$runId.xml"
$work = Join-Path $project "outputs/chat-regression/$runId"
$resources = Join-Path $work 'res'
$pfx = Join-Path $work 'fixture.p12'
$null = New-Item -ItemType Directory -Path (Join-Path $resources 'raw') -Force

function Device {
    $result = & $adb -s $deviceSerial @args
    if ($LASTEXITCODE -ne 0) { throw "ADB failed on $deviceSerial ($($args[0]))" }
    $result
}

function Screen {
    for ($attempt = 0; $attempt -lt 3; $attempt++) {
        $receipt = & $adb -s $deviceSerial shell uiautomator dump $dump 2>&1
        if ($LASTEXITCODE -eq 0 -and ($receipt -join ' ').Contains($dump)) {
            try { return [xml]((Device shell cat $dump) -join "`n") } catch {
                if ($attempt -eq 2) { throw }
            }
        }
        Start-Sleep -Milliseconds 300
    }
    throw "No fresh UI snapshot on $deviceSerial"
}

function Find-Node([xml]$ui, [string]$label, [string]$class = '') {
    $ui.SelectNodes('//node') | Where-Object {
        $_.package -eq $package -and (!$class -or $_.class -eq $class) -and
            ($_.text -ceq $label -or $_.'content-desc' -ceq $label)
    } | Select-Object -Last 1
}

function Wait-For([string]$description, [scriptblock]$check) {
    $timer = [Diagnostics.Stopwatch]::StartNew()
    do {
        $ui = Screen
        $value = & $check $ui
        if ($value) { return $value }
        Start-Sleep -Milliseconds 200
    } while ($timer.Elapsed.TotalSeconds -lt 20)
    $ui.SelectNodes("//node[@package='$package']") | ForEach-Object {
        $text = $_.text -replace '\s+', ' '
        if ($text.Length -gt 120) { $text = $text.Substring(0, 120) + '...' }
        if ($text -or $_.'content-desc') { "$text | $($_.'content-desc')" }
    } | Set-Content -LiteralPath (Join-Path $fixture 'last-ui.txt') -Encoding utf8
    throw "Expected $description on $deviceSerial"
}

function Tap-Node($element, [string]$label) {
    if (!$element -or $element.enabled -ne 'true' -or $element.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') {
        throw "Could not tap $label"
    }
    Device shell input tap (([int]$Matches[1] + [int]$Matches[3]) -shr 1) (([int]$Matches[2] + [int]$Matches[4]) -shr 1) | Out-Null
}

function Tap([string]$label, [string]$class = '') {
    $target = Wait-For $label { param($ui) Find-Node $ui $label $class }
    Tap-Node $target $label
}

function Back { Device shell input keyevent KEYCODE_BACK | Out-Null }

function Type-Field([string]$label, [string]$value, [switch]$KeepKeyboard) {
    Tap $label 'android.widget.EditText'
    Device shell input text ($value -replace ' ', '%s') | Out-Null
    if (!$KeepKeyboard) { Back }
}

function Start-App {
    $receipt = Device shell am start -W -n $component
    if ($receipt -match '^Error:') { throw 'Chat QA did not start' }
    Wait-For 'chat composer' { param($ui) $ui.SelectSingleNode("//node[@package='$package' and @class='android.widget.EditText']") } | Out-Null
}

function App-Process { ((Device shell pidof $package) -join '').Trim() }

function Capture([string]$name) {
    if (!$CaptureUi) { return }
    $image = "/data/local/tmp/serein-$runId.png"
    try {
        Device shell screencap -p $image | Out-Null
        Device pull $image (Join-Path $fixture "$name.png") | Out-Null
    } finally { Device shell rm -f $image | Out-Null }
}

function Assert-Process([string]$expected) {
    if (!$expected -or (App-Process) -ne $expected) { throw "Chat QA process exited during the scenario (PID $expected)" }
}

function App-Faults {
    Device logcat -b events -d -v threadtime -s 'am_anr:I' 'am_crash:I' | Where-Object {
        $_ -match ('\b' + [regex]::Escape($package) + '(?:,|\])')
    }
    Device shell dumpsys activity exit-info $package | Where-Object {
        $_ -match ('process=' + [regex]::Escape($package) + ' reason=(?:4|5|6) ')
    }
}

function Wait-Reply([string]$marker, [string]$state = '') {
    Wait-For "reply $marker $state" { param($ui)
        $texts = $ui.SelectNodes("//node[@package='$package' and @class='android.widget.TextView']") |
            ForEach-Object { $_.text }
        if (($texts -like "*$marker*") -and (!$state -or ($texts -like "*$state*")) -and (Find-Node $ui '发送')) {
            return $true
        }
    } | Out-Null
}

function Assert-Draft([string]$value) {
    Wait-For "draft $value" { param($ui) Find-Node $ui $value 'android.widget.EditText' } | Out-Null
}

function Assert-Requests([string[]]$expected) {
    $path = Join-Path $fixture 'requests.log'
    $actual = if (Test-Path $path) { (Get-Content -LiteralPath $path) -join ',' } else { '' }
    if ($actual -cne ($expected -join ',')) { throw "Unexpected requests: [$actual] instead of [$($expected -join ',')]" }
}

function Send([string]$prompt, [switch]$NewChat, [switch]$KeepKeyboard) {
    if ($NewChat) { Tap '新建聊天' }
    Type-Field '发消息' $prompt -KeepKeyboard:$KeepKeyboard
    if ($KeepKeyboard -and $CaptureUi) {
        Wait-For 'draft and send button with keyboard open' { param($ui)
            (Find-Node $ui $prompt 'android.widget.EditText') -and
                (Find-Node $ui '发送').enabled -eq 'true' -and
                (Device shell dumpsys input_method | Select-String 'mInputShown=true' -Quiet)
        } | Out-Null
        Capture '01-composer'
    }
    Tap '发送'
}

function Reopen {
    Device shell am force-stop $package | Out-Null
    Start-App
}

function Switch-Version([int]$number) {
    $target = Wait-For 'reply version chooser' { param($ui)
        $ui.SelectNodes("//node[@package='$package']") | Where-Object { $_.'content-desc' -like '切换版本，当前第 *' } | Select-Object -Last 1
    }
    Tap-Node $target 'reply version chooser'
    $item = Wait-For "reply version $number" { param($ui)
        $ui.SelectNodes("//node[@package='$package']") | Where-Object { $_.text -like "$number · Recover*" } | Select-Object -Last 1
    }
    Tap-Node $item "reply version $number"
}

function Configure-Provider([string]$base) {
    Tap '会话'
    Tap '设置'
    Tap '服务商连接'
    Tap '添加服务商'
    Type-Field '服务商名称' 'Local Fixture'
    Type-Field 'HTTPS 根地址（含 API 路径）' $base
    Tap '保存'
    Tap '拉取模型列表'
    Wait-For 'fixture model in provider settings' { param($ui) Find-Node $ui 'serein-fixture' } | Out-Null
    Tap '返回'
    Tap '返回'
    Tap '返回'
    Tap '关闭侧栏' 'android.widget.Button'
    Tap '选择模型，当前 离线演示'
    Tap 'serein-fixture'
    Wait-For 'selected fixture model' { param($ui) Find-Node $ui '选择模型，当前 serein-fixture' } | Out-Null
}

function Check-Delivery([string]$base) {
    Wait-For 'delivery confirmation for the local fixture' { param($ui)
        (Find-Node $ui '发送给 Local Fixture') -and $ui.OuterXml.Contains("$base/chat/completions")
    } | Out-Null
}

function Run-Scenarios([string]$base) {
    Start-App
    $initialProcess = App-Process
    Configure-Provider $base
    Send 'First' -KeepKeyboard
    Check-Delivery $base
    Tap '取消'
    Assert-Draft 'First'
    Assert-Requests @()
    Tap '发送'
    Check-Delivery $base
    Tap '发送'
    if ($CaptureUi) {
        Wait-For 'waiting state before fixture sends its first delta' { param($ui)
            (Find-Node $ui '停止生成') -and ($ui.SelectNodes("//node[@package='$package']") |
                Where-Object { $_.text -like '等待*回复*' })
        } | Out-Null
        Capture '02-waiting'
        $null = New-Item -ItemType File -Path (Join-Path $fixture 'First.resume')
    }
    Wait-Reply 'First complete.'
    Send 'Followup'
    Wait-Reply 'Followup complete.'
    Capture '03-transcript'
    Assert-Requests @('First', 'Followup')
    Assert-Process $initialProcess
    Write-Output 'PASS: cancellation kept the draft and sent nothing; approval and follow-up each sent once'

    Send 'Recover' -NewChat
    Wait-Reply 'Recover partial.' '回复未完成 · 已保留部分回复'
    Capture '04-interrupted'
    Type-Field '发消息' 'Unrelated draft'
    Tap '重试回复'
    Wait-Reply 'Recover partial.' '回复未完成 · 已保留部分回复'
    Assert-Draft 'Unrelated draft'
    Assert-Requests @('First', 'Followup', 'Recover', 'Recover')
    Tap '重试回复'
    Wait-Reply 'Recover complete.'
    Assert-Draft 'Unrelated draft'
    Assert-Process $initialProcess
    Reopen
    Wait-Reply 'Recover complete.'
    Switch-Version 1
    Wait-Reply 'Recover partial.' '回复未完成 · 已保留部分回复'
    Write-Output 'PASS: interruption and failed retry kept the partial reply and draft; both versions survived restart'

    $activeProcess = App-Process
    Send 'Stop' -NewChat
    Wait-For 'partial text while stop is available' { param($ui)
        (Find-Node $ui '停止生成') -and (Find-Node $ui 'Stop partial.')
    } | Out-Null
    Capture '05-streaming'
    Tap '停止生成'
    Wait-Reply 'Stop partial.' '已停止 · 已保留部分回复'
    $null = New-Item -ItemType File -Path (Join-Path $fixture 'Stop.resume')
    Assert-Process $activeProcess
    Reopen
    Wait-Reply 'Stop partial.' '已停止 · 已保留部分回复'
    Write-Output 'PASS: stopping had no extra send and the partial reply survived restart'

    $activeProcess = App-Process
    Send 'Background' -NewChat
    Wait-For 'background case started streaming' { param($ui) Find-Node $ui 'Background partial.' } | Out-Null
    Device shell input keyevent KEYCODE_HOME | Out-Null
    if (Device shell dumpsys activity activities | Select-String ('topResumedActivity=.*' + [regex]::Escape($package))) {
        throw 'Chat QA did not move to the background'
    }
    $null = New-Item -ItemType File -Path (Join-Path $fixture 'Background.resume')
    Start-App
    Wait-Reply 'Background complete.'
    Assert-Process $activeProcess
    Write-Output 'PASS: streaming finished across Home and return without restarting the process'

    Send 'Dense' -NewChat
    Wait-Reply 'Dense complete.'
    Tap '消息更多操作'
    Wait-For 'dense reply export action' { param($ui) Find-Node $ui '导出完整回复' } | Out-Null
    Back
    Assert-Process $activeProcess
    Reopen
    Wait-Reply 'Dense complete.'
    Assert-Requests @('First', 'Followup', 'Recover', 'Recover', 'Recover', 'Stop', 'Background', 'Dense')
    Write-Output 'PASS: 18,000 dense Markdown blocks remained actionable and readable after a cold restart'
}

try {
    & $keytool -genkeypair -alias fixture -keyalg RSA -keysize 2048 -sigalg SHA256withRSA -validity 2 `
        -dname 'CN=Serein Chat QA' -ext 'SAN=IP:10.0.2.2' -storetype PKCS12 -keystore $pfx -storepass only-local-qa
    if ($LASTEXITCODE -ne 0) { throw 'Could not create temporary fixture certificate' }
    & $keytool -exportcert -alias fixture -keystore $pfx -storepass only-local-qa -file (Join-Path $resources 'raw/chat_qa_cert.der')
    if ($LASTEXITCODE -ne 0) { throw 'Could not export fixture certificate' }
    & (Join-Path $project 'gradlew.bat') -p $project --console=plain "-PsereinChatQaResources=$resources" :app:assembleDebug
    if ($LASTEXITCODE -ne 0) { throw 'Could not build the isolated chat QA package' }
    $apkFolder = Join-Path $project 'app/build/outputs/apk/debug'
    $metadata = Get-Content -LiteralPath (Join-Path $apkFolder 'output-metadata.json') -Raw | ConvertFrom-Json
    if ($metadata.applicationId -ne $package -or $metadata.elements.Count -ne 1) { throw 'Unexpected QA APK identifier' }
    $apk = Join-Path $apkFolder $metadata.elements[0].outputFile

    foreach ($deviceSerial in $Serial) {
        if ((Device shell getprop ro.kernel.qemu) -ne '1') { throw "$deviceSerial is not an emulator" }
        if ((Device shell pm list packages $package) -contains "package:$package") { throw 'QA package is already installed; refusing to overwrite it' }
        $beforeFaults = @(App-Faults)
        $fixture = Join-Path $work $deviceSerial
        $null = New-Item -ItemType Directory -Path $fixture
        Copy-Item -LiteralPath $pfx -Destination $fixture
        $server = $null
        $installed = $false
        try {
            $serverArgs = @('"' + (Join-Path $PSScriptRoot 'qa/chat-fixture.cjs') + '"', '"' + $fixture + '"')
            if ($CaptureUi) { $serverArgs += 'capture' }
            $server = Start-Process -FilePath $nodeExe -ArgumentList $serverArgs `
                -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $fixture 'server.stdout.log') `
                -RedirectStandardError (Join-Path $fixture 'server.stderr.log')
            $portFile = Join-Path $fixture 'port.txt'
            for ($attempt = 0; $attempt -lt 50 -and !(Test-Path $portFile) -and !$server.HasExited; $attempt++) {
                Start-Sleep -Milliseconds 100
            }
            if (!(Test-Path $portFile) -or $server.HasExited) { throw 'Local HTTPS fixture did not start' }
            $port = (Get-Content -LiteralPath $portFile -Raw).Trim()
            if ($port -notmatch '^[0-9]{1,5}$') { throw 'Invalid fixture port' }
            Write-Output "Running on $deviceSerial (API $(Device shell getprop ro.build.version.sdk))"
            Device install $apk
            $installed = $true
            Run-Scenarios "https://10.0.2.2:$port/v1"
            $newFaults = @(App-Faults | Where-Object { $_ -notin $beforeFaults })
            if ($newFaults.Count) { throw "New crash or ANR for $package on $deviceSerial" }
            Write-Output "PASS: exact request sequence and no new app crash or ANR on $deviceSerial"
        } finally {
            if ($server -and !$server.HasExited) { Stop-Process -Id $server.Id }
            Remove-Item -LiteralPath (Join-Path $fixture 'fixture.p12') -ErrorAction SilentlyContinue
            if ($installed) { Device uninstall $package | Out-Null }
            Device shell rm -f $dump | Out-Null
        }
    }
} finally {
    Remove-Item -LiteralPath $pfx -ErrorAction SilentlyContinue
    Write-Output "Fixture receipts: $work"
}
