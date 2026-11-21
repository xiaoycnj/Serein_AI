param(
    [string]$SdkDir = $env:ANDROID_HOME,
    [string]$BuildToolsVersion = '35.0.0'
)

$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
if (!$SdkDir) { $SdkDir = $env:ANDROID_SDK_ROOT }
if (!$SdkDir) { $SdkDir = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }

$tools = Join-Path $SdkDir "build-tools\$BuildToolsVersion"
$androidJar = Join-Path $SdkDir 'platforms\android-36\android.jar'
if (!(Test-Path -LiteralPath $androidJar)) {
    throw "Install Android SDK Platform 36 in $SdkDir."
}
$aapt2 = Join-Path $tools 'aapt2.exe'
$d8 = Join-Path $tools 'd8.bat'
$zipalign = Join-Path $tools 'zipalign.exe'
$apksigner = Join-Path $tools 'apksigner.bat'
foreach ($tool in @($aapt2, $d8, $zipalign, $apksigner)) {
    if (!(Test-Path -LiteralPath $tool)) { throw "Install Android SDK Build-Tools $BuildToolsVersion in $SdkDir." }
}
if ($env:JAVA_HOME) {
    $javac = Join-Path $env:JAVA_HOME 'bin\javac.exe'
    $jartool = Join-Path $env:JAVA_HOME 'bin\jar.exe'
    $keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
} else {
    $javac = (Get-Command javac.exe).Source
    $jartool = (Get-Command jar.exe).Source
    $keytool = (Get-Command keytool.exe).Source
}

function Invoke-BuildTool([string]$Command, [string[]]$Arguments) {
    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Command exited with code $LASTEXITCODE." }
}

$app = Join-Path $project 'app\src\main'
$build = Join-Path $project 'app\build\sdk-alpha'
$work = Join-Path $build ([Guid]::NewGuid().ToString('N'))
$gen = Join-Path $work 'generated'
$classes = Join-Path $work 'classes'
$dex = Join-Path $work 'dex'
$out = Join-Path $project 'outputs'
New-Item -ItemType Directory -Path $gen,$classes,$dex,$out -Force | Out-Null

# Keep the source manifest compatible with AGP. Standalone AAPT2 needs the package inline.
$manifest = Join-Path $work 'AndroidManifest.xml'
[xml]$document = Get-Content -LiteralPath (Join-Path $app 'AndroidManifest.xml') -Raw
$document.DocumentElement.SetAttribute('package', 'app.serein.chat')
$document.Save($manifest)

$compiled = Join-Path $work 'resources.zip'
$resourcesApk = Join-Path $work 'resources.apk'
Invoke-BuildTool $aapt2 @('compile', '--dir', (Join-Path $app 'res'), '-o', $compiled)
Invoke-BuildTool $aapt2 @('link', '-o', $resourcesApk, '-I', $androidJar, '--manifest', $manifest,
    '--java', $gen, '--min-sdk-version', '33', '--target-sdk-version', '36', '--version-code', '3',
    '--version-name', '0.0.3-alpha', '-A', (Join-Path $app 'assets'), $compiled)

$sources = @(Get-ChildItem -LiteralPath (Join-Path $app 'java'),$gen -Recurse -File -Filter '*.java' | ForEach-Object FullName)
Invoke-BuildTool $javac (@('--release', '17', '-encoding', 'UTF-8', '-classpath', $androidJar, '-d', $classes) + $sources)
$classesJar = Join-Path $work 'classes.jar'
Invoke-BuildTool $jartool @('--create', '--file', $classesJar, '-C', $classes, '.')
Invoke-BuildTool $d8 @('--release', '--min-api', '33', '--lib', $androidJar, '--output', $dex, $classesJar)

$unsigned = Join-Path $work 'unsigned.apk'
Copy-Item -LiteralPath $resourcesApk -Destination $unsigned
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::Open($unsigned, [IO.Compression.ZipArchiveMode]::Update)
try {
    foreach ($file in Get-ChildItem -LiteralPath $dex -File -Filter '*.dex') {
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $file.FullName, $file.Name) | Out-Null
    }
} finally { $archive.Dispose() }
$aligned = Join-Path $work 'aligned.apk'
Invoke-BuildTool $zipalign @('-f', '4', $unsigned, $aligned)

$keyDir = Join-Path $env:USERPROFILE '.android'
$key = Join-Path $keyDir 'debug.keystore'
if (!(Test-Path -LiteralPath $key)) {
    New-Item -ItemType Directory -Path $keyDir -Force | Out-Null
    Invoke-BuildTool $keytool @('-genkeypair', '-keystore', $key, '-storepass', 'android', '-keypass', 'android',
        '-alias', 'androiddebugkey', '-dname', 'CN=Android Debug,O=Android,C=US', '-keyalg', 'RSA', '-validity', '10000')
}
$signed = Join-Path $work 'Serein-Alpha-0.0.3.apk'
Invoke-BuildTool $apksigner @('sign', '--ks', $key, '--ks-key-alias', 'androiddebugkey', '--ks-pass', 'pass:android',
    '--key-pass', 'pass:android', '--v4-signing-enabled', 'false', '--out', $signed, $aligned)
Invoke-BuildTool $apksigner @('verify', $signed)
$artifact = Join-Path $out 'Serein-Alpha-0.0.3.apk'
Copy-Item -LiteralPath $signed -Destination $artifact -Force
Write-Output $artifact
