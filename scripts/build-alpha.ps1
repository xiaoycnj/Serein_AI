param(
    [string]$SdkDir = $env:ANDROID_HOME,
    [string]$BuildToolsVersion = '35.0.0',
    [string]$GradlePath,
    [switch]$RunChatTests,
    [switch]$Release
)

$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
if (!$SdkDir) { $SdkDir = $env:ANDROID_SDK_ROOT }
if (!$SdkDir) { $SdkDir = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$SdkDir = (Resolve-Path -LiteralPath $SdkDir).Path
if (!(Test-Path -LiteralPath (Join-Path $SdkDir 'platforms\android-36\android.jar'))) {
    throw "Install Android SDK Platform 36 in $SdkDir."
}
$apksigner = Join-Path $SdkDir "build-tools\$BuildToolsVersion\apksigner.bat"
if (!(Test-Path -LiteralPath $apksigner -PathType Leaf)) {
    throw "Install Android SDK Build-Tools $BuildToolsVersion in $SdkDir."
}
if ($Release -and (!$env:SEREIN_RELEASE_KEYSTORE -or !$env:SEREIN_RELEASE_STORE_PASS -or
        !(Test-Path -LiteralPath $env:SEREIN_RELEASE_KEYSTORE -PathType Leaf))) {
    throw 'Release signing requires SEREIN_RELEASE_KEYSTORE and SEREIN_RELEASE_STORE_PASS.'
}
if (!$GradlePath) { $GradlePath = Join-Path $project 'gradlew.bat' }

$oldHome = $env:ANDROID_HOME
$oldRoot = $env:ANDROID_SDK_ROOT
$oldKey = $env:SEREIN_RELEASE_KEYSTORE
try {
    $env:ANDROID_HOME = $SdkDir
    $env:ANDROID_SDK_ROOT = $SdkDir
    if ($Release) { $env:SEREIN_RELEASE_KEYSTORE = (Resolve-Path -LiteralPath $oldKey).Path }
    if ($RunChatTests) {
        & $GradlePath -p $project --console=plain :app:chatChecks
        if ($LASTEXITCODE -ne 0) { throw "Chat checks failed (exit $LASTEXITCODE)." }
    }
    $variant = if ($Release) { 'release' } else { 'debug' }
    $task = if ($Release) { ':app:assembleRelease' } else { ':app:assembleDebug' }
    & $GradlePath -p $project --console=plain $task
    if ($LASTEXITCODE -ne 0) { throw "APK build failed (exit $LASTEXITCODE)." }
} finally {
    $env:ANDROID_HOME = $oldHome
    $env:ANDROID_SDK_ROOT = $oldRoot
    $env:SEREIN_RELEASE_KEYSTORE = $oldKey
}

$apkDir = Join-Path $project "app\build\outputs\apk\$variant"
$metadata = Get-Content -Raw -LiteralPath (Join-Path $apkDir 'output-metadata.json') | ConvertFrom-Json
if (@($metadata.elements).Count -ne 1) { throw 'Expected one universal APK.' }
$apk = Join-Path $apkDir $metadata.elements[0].outputFile
$size = (Get-Item -LiteralPath $apk).Length
if ($size -ge 64000000) { throw "APK must be smaller than 64 MB; got $size bytes." }
& $apksigner verify $apk
if ($LASTEXITCODE -ne 0) { throw "APK signature check failed (exit $LASTEXITCODE)." }
$out = Join-Path $project 'outputs'
if ($Release) { $out = Join-Path $out 'release' }
New-Item -ItemType Directory -Path $out -Force | Out-Null
$version = $metadata.elements[0].versionName -replace '-alpha$', ''
$artifact = Join-Path $out "Serein-Alpha-$version.apk"
Copy-Item -LiteralPath $apk -Destination $artifact -Force
Write-Output $artifact
