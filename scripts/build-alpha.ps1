param(
    [string]$SdkDir = $env:ANDROID_HOME,
    [string]$BuildToolsVersion = '35.0.0',
    [switch]$RunChatTests,
    [switch]$Release
)

$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
if ($Release -and (!$env:SEREIN_RELEASE_KEYSTORE -or !$env:SEREIN_RELEASE_STORE_PASS -or
        !(Test-Path -LiteralPath $env:SEREIN_RELEASE_KEYSTORE -PathType Leaf))) {
    throw 'Release signing requires SEREIN_RELEASE_KEYSTORE and SEREIN_RELEASE_STORE_PASS.'
}
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
    $java = Join-Path $env:JAVA_HOME 'bin\java.exe'
    $javac = Join-Path $env:JAVA_HOME 'bin\javac.exe'
    $jartool = Join-Path $env:JAVA_HOME 'bin\jar.exe'
    $keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
} else {
    $java = (Get-Command java.exe).Source
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
if ($Release) { $out = Join-Path $out 'release' }
New-Item -ItemType Directory -Path $gen,$classes,$dex,$out -Force | Out-Null

$dependencyDir = Join-Path $build 'dependencies'
New-Item -ItemType Directory -Path $dependencyDir -Force | Out-Null
$commonmark = [ordered]@{
    'commonmark' = '679338E0B7FC15C02D275D598654B01A149893BC28A87992E90123C8D06AF25B'
    'commonmark-ext-gfm-strikethrough' = '7385CB637F04DC4CBDA4DDCA9C2FCD2AF7AC536A50E4C8D2C77F4748BB14BF41'
    'commonmark-ext-gfm-tables' = 'B54DC332F931E6D07C2766144C087B08F3693677E368151A67020B4E95BB4B99'
}
$dependencies = foreach ($package in $commonmark.Keys) {
    $name = "$package-0.24.0.jar"
    $file = Join-Path $dependencyDir $name
    if (!(Test-Path -LiteralPath $file) -or (Get-FileHash -LiteralPath $file).Hash -ne $commonmark[$package]) {
        $download = Join-Path $work $name
        Invoke-WebRequest -Uri "https://repo.maven.apache.org/maven2/org/commonmark/$package/0.24.0/$name" -OutFile $download -TimeoutSec 30
        if ((Get-FileHash -LiteralPath $download).Hash -ne $commonmark[$package]) { throw "Invalid checksum for $name." }
        Copy-Item -LiteralPath $download -Destination $file -Force
    }
    $file
}

# Keep the source manifest compatible with AGP. Standalone AAPT2 needs the package inline.
$manifest = Join-Path $work 'AndroidManifest.xml'
[xml]$document = Get-Content -LiteralPath (Join-Path $app 'AndroidManifest.xml') -Raw
$document.DocumentElement.SetAttribute('package', 'app.serein.chat')
$document.Save($manifest)

$compiled = Join-Path $work 'resources.zip'
$resourcesApk = Join-Path $work 'resources.apk'
Invoke-BuildTool $aapt2 @('compile', '--dir', (Join-Path $app 'res'), '-o', $compiled)
Invoke-BuildTool $aapt2 @('link', '-o', $resourcesApk, '-I', $androidJar, '--manifest', $manifest,
    '--java', $gen, '--min-sdk-version', '33', '--target-sdk-version', '36', '--version-code', '6',
    '--version-name', '0.0.6-alpha', $compiled)

$sources = @(Get-ChildItem -LiteralPath (Join-Path $app 'java'),$gen -Recurse -File -Filter '*.java' | ForEach-Object FullName)
Invoke-BuildTool $javac (@('--release', '17', '-encoding', 'UTF-8', '-classpath', ((@($androidJar) + $dependencies) -join ';'), '-d', $classes) + $sources)
if ($RunChatTests) {
    # android.jar contains compile-time stubs. Use the Android JSON implementation for the JVM test.
    $jsonName = 'android-json-0.0.20131108.vaadin1.jar'
    $jsonJar = Join-Path $dependencyDir $jsonName
    $jsonHash = 'DFB7BAE2F404CFE0B72B4D23944698CB716B7665171812A0A4D0F5926C0FAC79'
    if (!(Test-Path -LiteralPath $jsonJar) -or (Get-FileHash -LiteralPath $jsonJar).Hash -ne $jsonHash) {
        $download = Join-Path $work $jsonName
        Invoke-WebRequest -Uri "https://repo.maven.apache.org/maven2/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/$jsonName" -OutFile $download -TimeoutSec 30
        if ((Get-FileHash -LiteralPath $download).Hash -ne $jsonHash) { throw "Invalid checksum for $jsonName." }
        Copy-Item -LiteralPath $download -Destination $jsonJar -Force
    }
    $testClasses = Join-Path $work 'test-classes'
    $testDir = Join-Path $project 'app\src\test\java\app\serein\chat'
    $testSources = @(Get-ChildItem -LiteralPath $testDir -File -Filter '*.java' | ForEach-Object FullName)
    $testClasspath = @($testClasses, $classes, $jsonJar, $androidJar) -join ';'
    Invoke-BuildTool $javac (@('--release', '17', '-encoding', 'UTF-8', '-classpath', $testClasspath, '-d', $testClasses) + $testSources)
    Invoke-BuildTool $java @('-classpath', $testClasspath, 'app.serein.chat.ModelApiStreamCheck')
    Invoke-BuildTool $java @('-classpath', $testClasspath, 'app.serein.chat.ChatFeatureCheck')
}
$classesJar = Join-Path $work 'classes.jar'
Invoke-BuildTool $jartool @('--create', '--file', $classesJar, '-C', $classes, '.')
Invoke-BuildTool $d8 (@('--release', '--min-api', '33', '--lib', $androidJar, '--output', $dex, $classesJar) + $dependencies)

$unsigned = Join-Path $work 'unsigned.apk'
Copy-Item -LiteralPath $resourcesApk -Destination $unsigned
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::Open($unsigned, [IO.Compression.ZipArchiveMode]::Update)
try {
    foreach ($file in Get-ChildItem -LiteralPath $dex -File -Filter '*.dex') {
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $file.FullName, $file.Name) | Out-Null
    }
    # Windows AAPT2 can put backslashes in nested asset paths.
    $assets = Join-Path $app 'assets'
    foreach ($file in Get-ChildItem -LiteralPath $assets -Recurse -File) {
        $entry = 'assets/' + $file.FullName.Substring($assets.Length + 1).Replace('\', '/')
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $file.FullName, $entry) | Out-Null
    }
    [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, (Join-Path $project 'LICENSE'), 'assets/licenses/serein.txt') | Out-Null
} finally { $archive.Dispose() }
$aligned = Join-Path $work 'aligned.apk'
Invoke-BuildTool $zipalign @('-f', '4', $unsigned, $aligned)

$signed = Join-Path $work 'Serein-Alpha-0.0.6.apk'
if ($Release) {
    $signing = @('--ks', $env:SEREIN_RELEASE_KEYSTORE, '--ks-key-alias', 'serein-release',
        '--ks-pass', 'env:SEREIN_RELEASE_STORE_PASS', '--key-pass', 'env:SEREIN_RELEASE_STORE_PASS')
} else {
    $keyDir = Join-Path $env:USERPROFILE '.android'
    $key = Join-Path $keyDir 'debug.keystore'
    if (!(Test-Path -LiteralPath $key)) {
        New-Item -ItemType Directory -Path $keyDir -Force | Out-Null
        Invoke-BuildTool $keytool @('-genkeypair', '-keystore', $key, '-storepass', 'android', '-keypass', 'android',
            '-alias', 'androiddebugkey', '-dname', 'CN=Android Debug,O=Android,C=US', '-keyalg', 'RSA', '-validity', '10000')
    }
    $signing = @('--ks', $key, '--ks-key-alias', 'androiddebugkey', '--ks-pass', 'pass:android', '--key-pass', 'pass:android')
}
Invoke-BuildTool $apksigner (@('sign') + $signing + @('--v4-signing-enabled', 'false', '--out', $signed, $aligned))
Invoke-BuildTool $apksigner @('verify', $signed)
$artifact = Join-Path $out 'Serein-Alpha-0.0.6.apk'
Copy-Item -LiteralPath $signed -Destination $artifact -Force
Write-Output $artifact
