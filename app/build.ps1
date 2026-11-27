# 存片 — APK 构建脚本（无 Gradle 手工管线）
# 用法: powershell -File build.ps1 [-Install]
param([switch]$Install)

$ErrorActionPreference = "Stop"

$App       = $PSScriptRoot
$Sdk       = $env:ANDROID_SDK_ROOT
if (-not $Sdk) { throw "请先设置环境变量 ANDROID_SDK_ROOT 指向 Android SDK 目录" }
$AndroidJar= "$Sdk\android-34\android.jar"
$BT        = "$Sdk\build-tools\android-14"

$Manifest = Join-Path $App "AndroidManifest.xml"
$manifestText = [System.IO.File]::ReadAllText($Manifest)
if (-not ($manifestText -match 'android:versionName="([^"]+)"')) { throw "AndroidManifest.xml 缺 versionName" }
$ApkName = "cunpian-v$($matches[1]).apk"

$Aapt2     = "$BT\aapt2.exe"
$D8        = "$BT\d8.bat"
$Zipalign  = "$BT\zipalign.exe"
$Apksigner = "$BT\apksigner.bat"

# 在临时目录构建，避免工具链对非 ASCII 路径兼容不佳
$BuildRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("cunpian_build_" + [guid]::NewGuid().ToString("N"))
$Out       = Join-Path $BuildRoot "out"
New-Item -ItemType Directory -Force -Path $Out, (Join-Path $Out "gen"), (Join-Path $Out "classes"), (Join-Path $Out "dex"), (Join-Path $App "build") | Out-Null

Copy-Item (Join-Path $App "src")      (Join-Path $BuildRoot "src")      -Recurse -Force
Copy-Item (Join-Path $App "res")      (Join-Path $BuildRoot "res")      -Recurse -Force
Copy-Item $Manifest                   (Join-Path $BuildRoot "AndroidManifest.xml") -Force

Write-Host "=== 1. aapt2 compile ==="
& $Aapt2 compile --dir (Join-Path $BuildRoot "res") -o (Join-Path $Out "res.zip")
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "=== 2. aapt2 link ==="
& $Aapt2 link -o (Join-Path $Out "base.apk") -I $AndroidJar `
    --manifest (Join-Path $BuildRoot "AndroidManifest.xml") -R (Join-Path $Out "res.zip") `
    --java (Join-Path $Out "gen") --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "=== 3. javac ==="
$argfile = Join-Path $Out "javac_args.txt"
$srcs = @(Get-ChildItem (Join-Path $BuildRoot "src") -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$gen  = @(Get-ChildItem (Join-Path $Out "gen") -Recurse -Filter *.java | ForEach-Object { $_.FullName })
@("-source","8","-target","8","-bootclasspath",$AndroidJar,"-classpath",$AndroidJar,
  "-d",(Join-Path $Out "classes"),"-encoding","UTF-8","-Xlint:-options") + $srcs + $gen |
    Set-Content -Path $argfile -Encoding Ascii
& javac "@$argfile"
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "=== 4. dex (r8) ==="
& jar cf (Join-Path $Out "classes.jar") -C (Join-Path $Out "classes") .
& java -cp "$Sdk\r8.jar" com.android.tools.r8.D8 --release --lib $AndroidJar --min-api 26 `
    --output (Join-Path $Out "dex") (Join-Path $Out "classes.jar")
if ($LASTEXITCODE -ne 0) { throw "dex failed" }

Write-Host "=== 5. pack dex into apk ==="
& jar uf (Join-Path $Out "base.apk") -C (Join-Path $Out "dex") classes.dex
if ($LASTEXITCODE -ne 0) { throw "apk pack failed" }

Write-Host "=== 6. zipalign ==="
& $Zipalign -f 4 (Join-Path $Out "base.apk") (Join-Path $Out "aligned.apk")
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "=== 7. sign ==="
$Ks = Join-Path $BuildRoot "debug.keystore"
if (-not (Test-Path -LiteralPath (Join-Path $App "debug.keystore"))) {
    & keytool -genkeypair -keystore $Ks -alias cunpian -storepass android -keypass android `
        -keyalg RSA -keysize 2048 -validity 10000 -storetype PKCS12 `
        -dname "CN=CunPian,O=Self,C=CN"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
    Copy-Item $Ks (Join-Path $App "debug.keystore")
} else {
    Copy-Item (Join-Path $App "debug.keystore") $Ks -Force
}
& $Apksigner sign --ks $Ks --ks-pass pass:android --ks-key-alias cunpian `
    --out (Join-Path $Out $ApkName) (Join-Path $Out "aligned.apk")
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

$Dest = Join-Path (Join-Path $App "build") $ApkName
Copy-Item (Join-Path $Out $ApkName) $Dest -Force
$apk = Get-Item $Dest
Write-Host ("DONE: {0}  {1:N2} MB" -f $apk.FullName, ($apk.Length/1MB))

if ($Install) {
    Write-Host "=== 8. adb install ==="
    & adb install -r $apk.FullName
}
