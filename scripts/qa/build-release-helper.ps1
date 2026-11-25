function New-ReleaseQaHelper([string]$SdkDir, [string]$Out) {
    $PSNativeCommandUseErrorActionPreference = $true
    $tools = Join-Path $SdkDir 'build-tools/35.0.0'
    $platform = Join-Path $SdkDir 'platforms/android-36/android.jar'
    $source = Join-Path $PSScriptRoot 'release-history'
    $classes = Join-Path $Out 'classes'
    $dex = Join-Path $Out 'dex'
    New-Item -ItemType Directory -Path $classes, $dex | Out-Null
    $unsigned = Join-Path $Out 'helper-unsigned.apk'
    $signed = Join-Path $Out 'helper.apk'
    $buildKey = Join-Path $Out 'fixture.p12'
    $java = @(Get-ChildItem -LiteralPath $source -Filter '*.java' -File | ForEach-Object FullName)
    & javac --release 11 -encoding UTF-8 -cp $platform -d $classes @java | Out-Host
    $classFiles = @(Get-ChildItem -LiteralPath $classes -Recurse -Filter '*.class' | ForEach-Object FullName)
    & (Join-Path $tools 'd8.bat') --min-api 33 --lib $platform --output $dex @classFiles | Out-Host
    & (Join-Path $tools 'aapt.exe') package -f -M (Join-Path $source 'AndroidManifest.xml') -I $platform -F $unsigned | Out-Host
    Push-Location -LiteralPath $dex
    try { & (Join-Path $tools 'aapt.exe') add $unsigned classes.dex | Out-Host }
    finally { Pop-Location }
    & keytool -genkeypair -alias fixture -keyalg RSA -keysize 2048 -validity 2 -dname 'CN=Serein Release UI QA' `
        -storetype PKCS12 -keystore $buildKey -storepass only-local-qa | Out-Host
    & (Join-Path $tools 'apksigner.bat') sign --ks $buildKey --ks-pass pass:only-local-qa --out $signed $unsigned | Out-Host
    return $signed
}
