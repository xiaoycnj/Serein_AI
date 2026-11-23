param([string]$Serial, [switch]$ProviderControls, [switch]$OfflineModel, [string]$PickerScreenshot, [string]$RemoveTestProvider)

$ErrorActionPreference = 'Stop'
$adb = (Get-Command adb).Source
$package = 'app.serein.chat'
$device = @(if ($Serial) { '-s'; $Serial } else { '-d' })
$dump = '/data/local/tmp/serein-smoke.xml'
$testMessage = 'Alpha010-' + [Guid]::NewGuid().ToString('N').Substring(0, 6)
$samplePrompt = '帮我理顺今天的安排'
$sampleReply = '先写下今天非做不可的一件事'
$script:OriginalModelTitle = $null

function Invoke-Adb {
    $result = & $adb @device @args
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $args" }
    return $result
}

function Get-Screen {
    for ($attempt = 0; $attempt -lt 3; $attempt++) {
        $receipt = & $adb @device shell uiautomator dump $dump 2>&1
        $dumpExitCode = $LASTEXITCODE
        if ($dumpExitCode -eq 0 -and ($receipt -join ' ') -match [regex]::Escape($dump)) {
            try {
                $hierarchy = Invoke-Adb shell cat $dump
                return [xml]($hierarchy -join "`n")
            } catch {
                if ($attempt -eq 2) { throw }
            }
        }
        Start-Sleep -Milliseconds 300
    }
    throw "Could not read a fresh UI snapshot: $receipt"
}

function Find-Element([xml]$Screen, [string]$Label, [string]$Class) {
    foreach ($node in $Screen.SelectNodes('//node')) {
        if ($node.package -eq $package -and (!$Class -or $node.class -eq $Class) -and
            ($node.text -eq $Label -or $node.'content-desc' -eq $Label)) {
            return $node
        }
    }
    return $null
}

function Assert-Visible([string]$Label) {
    if (!(Find-Element (Get-Screen) $Label)) { throw "Expected to find '$Label' in Serein." }
}

function Find-ContainingText([xml]$Screen, [string]$Fragment) {
    foreach ($node in $Screen.SelectNodes('//node')) {
        if ($node.package -eq $package -and $node.text.Contains($Fragment)) { return $node }
    }
    return $null
}

function Wait-ContainingText([string]$Fragment) {
    for ($attempt = 0; $attempt -lt 12; $attempt++) {
        $node = Find-ContainingText (Get-Screen) $Fragment
        if ($node) { return $node }
        Start-Sleep -Milliseconds 300
    }
    throw "Expected to find '$Fragment' after sending."
}

function Select-OfflineModel {
    $screen = Get-Screen
    for ($attempt = 0; $attempt -lt 5; $attempt++) {
        if (Find-Element $screen '最近会话') {
            Tap-Label '关闭侧栏'
        } elseif (Find-Element $screen '返回') {
            Tap-Label '返回'
        } else {
            break
        }
        $screen = Get-Screen
    }
    $chooser = $screen.SelectNodes('//node') | Where-Object {
        $_.package -eq $package -and $_.'content-desc' -like '选择模型，当前 *'
    } | Select-Object -First 1
    if (!$chooser -or $chooser.'content-desc' -notmatch '^选择模型，当前 (.+)$') { throw 'Could not read the currently selected model.' }
    if ($Matches[1] -eq '离线演示') { $script:OriginalModelTitle = '离线演示'; return }
    $script:OriginalModelTitle = $Matches[1]
    Tap-Node $chooser '选择模型'
    $offline = (Get-Screen).SelectNodes('//node') | Where-Object {
        $_.package -eq $package -and $_.text -eq '离线演示'
    } | Select-Object -First 1
    Tap-Node $offline '离线演示'
    Wait-Visible '选择模型，当前 离线演示'
}

function Restore-SelectedModel {
    if (!$OfflineModel -or !$script:OriginalModelTitle -or $script:OriginalModelTitle -eq '离线演示') { return }
    $screen = Get-Screen
    $chooser = $screen.SelectNodes('//node') | Where-Object {
        $_.package -eq $package -and $_.'content-desc' -like '选择模型，当前 *'
    } | Select-Object -First 1
    if (!$chooser) { Write-Warning 'Could not restore the previous model because the test did not finish on the chat screen.'; return }
    Tap-Node $chooser '选择模型'
    Type-Text '搜索模型或服务商' $script:OriginalModelTitle
    Tap-Label $script:OriginalModelTitle
    Wait-Visible "选择模型，当前 $($script:OriginalModelTitle)"
    Write-Output "PASS: restored the previous model ($($script:OriginalModelTitle))"
}

function Wait-Visible([string]$Label) {
    for ($attempt = 0; $attempt -lt 4; $attempt++) {
        if (Find-Element (Get-Screen) $Label) { return }
        Start-Sleep -Milliseconds 300
    }
    throw "Expected to find '$Label' after startup."
}

function Tap-Label([string]$Label) {
    $class = if ($Label -eq '关闭侧栏') { 'android.widget.ImageButton' }
    for ($attempt = 0; $attempt -lt 3; $attempt++) {
        $node = Find-Element (Get-Screen) $Label $class
        if ($node) { Tap-Node $node $Label; return }
        Start-Sleep -Milliseconds 300
    }
    throw "Could not tap '$Label' in Serein."
}

function Tap-Node($node, [string]$Label, [switch]$NearEnd) {
    if (!$node -or $node.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') {
        throw "Could not tap '$Label' in Serein."
    }
    $x = ([int]$Matches[1] + [int]$Matches[3]) -shr 1
    if ($NearEnd) { $x = [int]$Matches[3] - 22 }
    $y = ([int]$Matches[2] + [int]$Matches[4]) -shr 1
    Invoke-Adb shell input tap $x $y | Out-Null
}

function Type-Text([string]$Field, [string]$Value) {
    $input = (Get-Screen).SelectNodes('//node') | Where-Object {
        $_.package -eq $package -and $_.class -eq 'android.widget.EditText' -and ($_.text -eq $Field -or $_.hint -eq $Field)
    } | Select-Object -First 1
    Tap-Node $input $Field
    $existing = [string]$input.text
    if ($existing -and $existing -ne $Field) {
        Invoke-Adb shell input keyevent KEYCODE_MOVE_END | Out-Null
        for ($index = 0; $index -lt $existing.Length; $index++) {
            Invoke-Adb shell input keyevent KEYCODE_DEL | Out-Null
        }
    }
    if ($Value) { Invoke-Adb shell input text ($Value -replace ' ', '%s') | Out-Null }
}

function Test-ProviderControls {
    $provider = 'SereinQA-' + [Guid]::NewGuid().ToString('N').Substring(0, 6)
    Tap-Label '会话'
    $history = Get-Screen
    if ($history.SelectNodes('//node') | Where-Object { $_.package -eq $package -and $_.text -like 'Alpha001-*' }) {
        Write-Output 'PASS: pre-upgrade history is visible'
    }
    Tap-Label '设置'
    Tap-Label '服务商连接'
    if ($RemoveTestProvider) {
        if ($RemoveTestProvider -cnotmatch '^SereinQA-[0-9a-f]{6}$') { throw 'Expected a synthetic test provider name.' }
        $target = Find-Element (Get-Screen) $RemoveTestProvider
        if ($target) {
            Tap-Node $target $RemoveTestProvider
            Tap-Label '移除服务商'
            Tap-Label '移除'
        }
    }
    Tap-Label '添加服务商'
    Type-Text '服务商名称' $provider
    Type-Text 'HTTPS 根地址（含 API 路径）' 'https://api.openai.com/v1'
    Tap-Label '保存'
    foreach ($key in 'QAKeyOne', 'QAKeyTwo') {
        Tap-Label '添加 Key'
        Type-Text 'Key 名称' $key
        Type-Text 'API Key' "synthetic-$key"
        Tap-Label '保存'
    }
    Tap-Label 'QAKeyTwo'
    Tap-Label '使用这把 Key'
    Assert-Visible '使用中'
    Tap-Label '手动添加模型'
    Type-Text '模型 ID' 'vendor/gpt-5-mini'
    Assert-Visible '本地目录未匹配'
    $modelId = (Get-Screen).SelectNodes('//node') | Where-Object {
        $_.package -eq $package -and $_.class -eq 'android.widget.EditText' -and $_.text -eq 'vendor/gpt-5-mini'
    } | Select-Object -First 1
    Tap-Node $modelId 'vendor/gpt-5-mini'
    Invoke-Adb shell input keyevent KEYCODE_MOVE_HOME | Out-Null
    for ($i = 0; $i -lt 7; $i++) { Invoke-Adb shell input keyevent KEYCODE_FORWARD_DEL | Out-Null }
    Assert-Visible '已匹配本地目录'
    Assert-Visible 'GPT-5 mini'
    Assert-Visible 'OpenAI'
    Tap-Label 'GPT-5 mini'
    Invoke-Adb shell input keyevent KEYCODE_MOVE_END | Out-Null
    Invoke-Adb shell input text '%sQA' | Out-Null
    Invoke-Adb shell input keyevent KEYCODE_BACK | Out-Null
    $mode = Find-Element (Get-Screen) '思考参数 · reasoning_effort'
    if (!$mode -or $mode.checked -ne 'true') { throw 'GPT-5 mini did not preselect reasoning_effort on the official endpoint.' }
    $glyphRow = Find-Element (Get-Screen) '模型图标 · 跟随模型目录'
    Tap-Node $glyphRow '模型图标'
    Tap-Label 'OpenAI'
    Assert-Visible '模型图标 · OpenAI'
    Tap-Label '保存'
    Write-Output 'PASS: provider setup, two Key entries, exact offline prefill, unknown ID and manual override'
    Tap-Label '手动添加模型'
    Type-Text '模型 ID' 'anthropic/claude-sonnet-4.6'
    Assert-Visible '已匹配本地目录'
    Assert-Visible 'Claude Sonnet 4.6'
    Invoke-Adb shell input keyevent KEYCODE_BACK | Out-Null
    Tap-Label '保存'
    Tap-Label '返回'
    Tap-Label '返回'
    Tap-Label '返回'
    Tap-Label '关闭侧栏'
    $chooser = (Get-Screen).SelectNodes('//node') | Where-Object { $_.package -eq $package -and $_.'content-desc' -like '选择模型，当前 *' } | Select-Object -First 1
    Tap-Node $chooser '选择模型'
    Type-Text '搜索模型或服务商' 'gpt-5-mini'
    Assert-Visible 'GPT-5 mini QA'
    if ($PickerScreenshot) {
        $remote = '/data/local/tmp/serein-picker.png'
        try {
            Invoke-Adb shell screencap -p $remote | Out-Null
            Invoke-Adb pull $remote $PickerScreenshot | Out-Null
        } finally { Invoke-Adb shell rm -f $remote | Out-Null }
    }
    Type-Text '搜索模型或服务商' 'no-match-serein'
    Assert-Visible '没有匹配的模型'
    Invoke-Adb shell input keyevent KEYCODE_MOVE_END | Out-Null
    for ($i = 0; $i -lt 'no-match-serein'.Length; $i++) { Invoke-Adb shell input keyevent KEYCODE_DEL | Out-Null }
    Invoke-Adb shell input text 'gpt-5-mini' | Out-Null
    Assert-Visible 'GPT-5 mini QA'
    if (Find-Element (Get-Screen) 'Claude Sonnet 4.6') { throw 'Search did not filter the model list.' }
    Tap-Label 'GPT-5 mini QA'
    Assert-Visible '选择模型，当前 GPT-5 mini QA'
    if (Find-Element (Get-Screen) '思考强度') { throw 'Reasoning slider visible without the keyboard.' }
    Tap-Label '发消息'
    $slider = Find-Element (Get-Screen) '思考强度'
    Tap-Node $slider '思考强度' -NearEnd
    Assert-Visible '思考强度 · 高'
    Invoke-Adb shell input keyevent KEYCODE_BACK | Out-Null
    if (Find-Element (Get-Screen) '思考强度') { throw 'Reasoning slider stayed visible after Back.' }
    Write-Output 'PASS: model selection and keyboard-controlled reasoning slider'
    Invoke-Adb shell am force-stop $package | Out-Null
    Start-Serein
    Wait-Visible '选择模型，当前 GPT-5 mini QA'
    Tap-Label '会话'
    Tap-Label '设置'
    Tap-Label '服务商连接'
    Tap-Label $provider
    Assert-Visible 'QAKeyTwo'
    Assert-Visible 'GPT-5 mini QA'
    Tap-Label 'GPT-5 mini QA'
    Assert-Visible '模型图标 · OpenAI'
    Tap-Label '取消'
    Tap-Label '移除服务商'
    Tap-Label '移除'
    if (Find-Element (Get-Screen) $provider) { throw 'Synthetic test provider was not removed.' }
    Write-Output 'PASS: model and provider settings survived restart; test configuration removed'
    Tap-Label '返回'
    Tap-Label '外观'
    Assert-Visible '自定义主题色'
    Tap-Label '聊天材质'
    Assert-Visible '磨砂'
    Tap-Label '取消'
    Tap-Label '背景'
    Assert-Visible '选择本机图片'
    Assert-Visible '主题渐变'
    Tap-Label '取消'
    Tap-Label '返回'
    Tap-Label '返回'
    Tap-Label '关闭侧栏'
    Write-Output 'PASS: appearance controls and navigation'
}

function Test-AccountControls {
    Tap-Label '账户'
    $screen = Get-Screen
    $fields = $screen.SelectNodes('//node') | Where-Object {
        $_.package -eq $package -and $_.class -eq 'android.widget.EditText'
    }
    $nameField = $fields | Where-Object { $_.hint -eq '本机账户' } | Select-Object -First 1
    $bioField = $fields | Where-Object { $_.hint -eq '个人简介' } | Select-Object -First 1
    $shareNode = Find-Element $screen '随聊天发送账户资料' 'android.widget.Switch'
    if (!$nameField -or !$bioField -or !$shareNode) { throw 'Account profile controls were not exposed in the UI tree.' }
    Write-Output 'PASS: account controls are available (read-only check)'
    Tap-Label '返回'
}

function Start-Serein {
    $status = Invoke-Adb shell am start -W -n "$package/.MainActivity"
    if (($status -join "`n") -notmatch 'Status: ok') { throw ($status -join "`n") }
}

try {
    Start-Serein
    if ($ProviderControls) {
        Test-ProviderControls
        return
    }
    if ($OfflineModel) { Select-OfflineModel }
    Assert-Visible '选择模型，当前 离线演示'
    Tap-Label '新建聊天'
    Assert-Visible '今天想聊什么？'
    Tap-Label $samplePrompt
    Tap-Label '发送'
    Wait-ContainingText $sampleReply | Out-Null
    Write-Output 'PASS: send the sample prompt and render the offline reply'

    Tap-Label '重新生成'
    $versions = $null
    for ($attempt = 0; $attempt -lt 12 -and !$versions; $attempt++) {
        $versions = (Get-Screen).SelectNodes('//node') | Where-Object {
            $_.package -eq $package -and $_.'content-desc' -match '^切换版本，当前第 2 个，共 2 个$'
        } | Select-Object -First 1
        if (!$versions) { Start-Sleep -Milliseconds 300 }
    }
    if (!$versions) { throw 'Regenerating a reply did not create a second selectable version.' }
    $version = $versions
    Tap-Node $version '切换版本'
    $firstVersion = Find-ContainingText (Get-Screen) '1 · 帮我理顺今天的安排'
    if (!$firstVersion) { throw 'The original message version was not offered.' }
    Tap-Node $firstVersion 'Original message version'
    Assert-Visible '切换版本，当前第 1 个，共 2 个'
    Write-Output 'PASS: regenerate a reply and switch back to the original version'

    Tap-Label '编辑提问'
    Assert-Visible '编辑提问'
    if (!(Find-Element (Get-Screen) $samplePrompt)) { throw 'The edit dialog did not preserve the selected prompt.' }
    Tap-Label '取消'
    Write-Output 'PASS: open and cancel the prompt editor without changing history'

    Tap-Label '新建聊天'
    Tap-Label '发消息'
    Invoke-Adb shell input text $testMessage | Out-Null
    Tap-Label '发送'
    Assert-Visible $testMessage
    Invoke-Adb shell am force-stop $package | Out-Null
    Start-Serein
    Assert-Visible $testMessage
    Write-Output 'PASS: type and send with the keyboard open, then restore after process restart'

    Tap-Label '会话'
    Tap-Label $samplePrompt
    Wait-ContainingText $sampleReply | Out-Null
    Tap-Label '会话'
    Assert-Visible '最近会话'
    Invoke-Adb shell input keyevent KEYCODE_BACK | Out-Null
    $chat = Get-Screen
    if ((Find-Element $chat '最近会话') -or !(Find-Element $chat '会话')) {
        throw 'System Back did not return from the drawer to the chat screen.'
    }
    Write-Output 'PASS: revisit history and dismiss drawer with Back'

    Tap-Label '会话'
    Test-AccountControls
    Tap-Label '设置'
    $settings = Get-Screen
    $sections = '服务商连接', '外观', '自定义插件', '关于', '相关政策', '开源声明'
    foreach ($section in $sections) {
        if (!(Find-Element $settings $section)) { throw "Settings is missing '$section'." }
    }
    Tap-Label '外观'
    Assert-Visible '减少动效'
    Tap-Label '返回'
    Tap-Label '关于'
    Assert-Visible 'Alpha 0.0.10 · Android 13+'
    Tap-Label '返回'
    Tap-Label '自定义插件'
    $extensions = Get-Screen
    foreach ($label in '自动压缩上下文', '自动模型路由') {
        if (!(Find-Element $extensions $label 'android.widget.Switch')) { throw "Missing extension switch '$label'." }
    }
    Tap-Label '返回'
    Tap-Label '返回'
    Tap-Label '关闭侧栏'
    Write-Output 'PASS: account, all six settings entries, appearance and version'
    if ($OfflineModel) { Restore-SelectedModel }
} finally {
    Invoke-Adb shell rm -f $dump | Out-Null
}
