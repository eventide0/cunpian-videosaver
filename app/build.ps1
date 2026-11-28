# 存片 — APK 构建脚本（无 Gradle 手工管线）
# aapt2 对中文路径支持差：全部构建动作在 ASCII 临时目录进行，产物拷回项目。
# 用法: powershell -File build.ps1 [-Install] [-Clean]
param([switch]$Install, [switch]$Clean, [string]$Serial)

# 用 Continue + 每步显式检查 $LASTEXITCODE：
# PowerShell 5.1 在 Stop 模式下会把 native 命令的 stderr（如 apksigner 的 Java 警告）当终止错误
$ErrorActionPreference = "Continue"

$App       = $PSScriptRoot
$ApkName   = "cunpian-v0.5.3.apk"

# ============ SDK 探测（环境变量优先，兼容多种常见布局）============
# 优先级：$env:ANDROID_SDK_ROOT → $env:ANDROID_HOME → 常见安装路径 → 脚本同级 sdk/
function Resolve-Sdk {
    foreach ($p in @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME)) {
        if (-not [string]::IsNullOrWhiteSpace($p) -and (Test-Path -LiteralPath $p)) { return $p }
    }
    $cands = @(
        "$env:LOCALAPPDATA\Android\Sdk",
        "$env:USERPROFILE\AppData\Local\Android\Sdk",
        "C:\Android\Sdk",
        "D:\Android\Sdk",
        (Join-Path $PSScriptRoot "sdk")
    )
    foreach ($p in $cands) {
        if (-not [string]::IsNullOrWhiteSpace($p) -and (Test-Path -LiteralPath $p)) { return $p }
    }
    return $null
}
$Sdk = Resolve-Sdk
if (-not $Sdk) {
    throw "找不到 Android SDK。请设 ANDROID_SDK_ROOT 指向 SDK 根目录（内含 platforms\ 和 build-tools\），或把 SDK 放到 $PSScriptRoot\sdk"
}
Write-Host ("SDK: $Sdk")

# platform：优先 platforms\android-*（标准布局），再兼容根目录下直接放 android-* 的精简布局
$PlatformDir = $null
foreach ($base in @((Join-Path $Sdk "platforms"), $Sdk)) {
    if (-not (Test-Path -LiteralPath $base)) { continue }
    $dirs = Get-ChildItem -LiteralPath $base -Directory -Filter "android-*" -ErrorAction SilentlyContinue |
            Sort-Object { if ($_.Name -match 'android-(\d+)') { [int]$matches[1] } else { 0 } } -Descending
    foreach ($d in $dirs) {
        if (Test-Path -LiteralPath (Join-Path $d.FullName "android.jar")) { $PlatformDir = $d.FullName; break }
    }
    if ($PlatformDir) { break }
}
if (-not $PlatformDir) { throw "SDK 里找不到含 android.jar 的 platform（android-*）。请先安装 Android SDK Platform。" }
$AndroidJar = Join-Path $PlatformDir "android.jar"
$ApiLevel = if ($PlatformDir -match 'android-(\d+)') { [int]$matches[1] } else { 34 }
Write-Host ("Platform: $PlatformDir")

# build-tools：取版本号最高且 aapt2.exe 存在的一个
$BTDir = Get-ChildItem -LiteralPath (Join-Path $Sdk "build-tools") -Directory -ErrorAction SilentlyContinue |
         Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName "aapt2.exe") } |
         Sort-Object { if ($_.Name -match '(\d+)\.(\d+)\.(\d+)') { [int]$matches[1]*10000 + [int]$matches[2]*100 + [int]$matches[3] } else { 0 } } -Descending |
         Select-Object -First 1
if (-not $BTDir) { throw "SDK 里找不到含 aapt2.exe 的 build-tools。请先安装 Android SDK Build-Tools。" }
$BT = $BTDir.FullName
Write-Host ("Build-tools: $BT")

$Aapt2     = "$BT\aapt2.exe"
$D8        = "$BT\d8.bat"
$Zipalign  = "$BT\zipalign.exe"
$Apksigner = "$BT\apksigner.bat"
foreach ($tool in @($Aapt2, $Zipalign, $Apksigner)) {
    if (-not (Test-Path -LiteralPath $tool)) { throw "build-tools 缺文件：$tool" }
}

# ============ 构建前检查：清单与产物名自洽 ============
$SrcMain  = Join-Path $App "src\io\self\videosaver\MainActivity.java"
$Manifest = Join-Path $App "AndroidManifest.xml"
if (-not (Test-Path -LiteralPath $SrcMain))  { throw "源码不存在 $SrcMain" }
if (-not (Test-Path -LiteralPath $Manifest)) { throw "清单不存在 $Manifest" }

$mText = [System.IO.File]::ReadAllText($Manifest)
if ($mText -notmatch 'android:versionCode="(\d+)"')   { throw "清单缺 android:versionCode" }
$vCode = $matches[1]
if ($mText -notmatch 'android:versionName="([^"]+)"') { throw "清单缺 android:versionName" }
$vName = $matches[1]
if ($ApkName -ne "cunpian-v$vName.apk") {
    throw ("产物名 {0} 与清单版本 {1}（code {2}）不一致" -f $ApkName, $vName, $vCode)
}
Write-Host ("版本检查 OK：{0} / code {1}" -f $vName, $vCode)

# ASCII 临时构建区：用系统临时目录，避免中文路径导致 aapt2 出错；每次构建独立目录
$ScratchRoot = Join-Path ([System.IO.Path]::GetTempPath()) "cunpian_build"
$BuildRoot   = Join-Path $ScratchRoot (Get-Date -Format yyyyMMdd_HHmmss)
$Out         = "$BuildRoot\out"

# -Clean 把旧构建树移到一边（可复原），不原地删除
if ($Clean -and (Test-Path -LiteralPath $ScratchRoot)) {
    $TrashDir = Join-Path ([System.IO.Path]::GetTempPath()) "cunpian_build_old_$(Get-Date -Format yyyyMMdd_HHmmss)"
    Move-Item -LiteralPath $ScratchRoot -Destination $TrashDir
}
New-Item -ItemType Directory -Force -Path $BuildRoot | Out-Null

# 阶段：拷源码到全新 ASCII 区
Copy-Item "$App\src"      "$BuildRoot\src"      -Recurse -Force
Copy-Item "$App\res"      "$BuildRoot\res"      -Recurse -Force
Copy-Item "$App\AndroidManifest.xml" "$BuildRoot\AndroidManifest.xml" -Force
New-Item -ItemType Directory -Force -Path "$Out\gen","$Out\classes","$Out\dex","$App\build" | Out-Null

Write-Host "=== 1. aapt2 compile ==="
& $Aapt2 compile --dir "$BuildRoot\res" -o "$Out\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "=== 2. aapt2 link ==="
& $Aapt2 link -o "$Out\base.apk" -I $AndroidJar `
    --manifest "$BuildRoot\AndroidManifest.xml" -R "$Out\res.zip" `
    --java "$Out\gen" --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "=== 3. javac ==="
$argfile = "$Out\javac_args.txt"
$srcs = @(Get-ChildItem "$BuildRoot\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$gen  = @(Get-ChildItem "$Out\gen" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
@("-source","8","-target","8","-bootclasspath",$AndroidJar,"-classpath",$AndroidJar,
  "-d","$Out\classes","-encoding","UTF-8","-Xlint:-options") + $srcs + $gen |
    Set-Content -Path $argfile -Encoding Ascii
& javac "@$argfile"
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "=== 4. d8 (dex) ==="
& jar cf "$Out\classes.jar" -C "$Out\classes" .
# 优先用 R8/D8 的 jar（build-tools 里的 d8.bat 某些版本有 NPE bug）；找不到再退回 d8.bat
$R8Candidates = @(
    (Join-Path $Sdk "r8.jar"),
    (Join-Path $Sdk "cmdline-tools\latest\lib\r8.jar"),
    (Join-Path $BT "lib\r8.jar"),
    (Join-Path $BT "lib\d8.jar")
)
$R8Jar = $R8Candidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if ($R8Jar) {
    Write-Host ("D8 via jar: $R8Jar")
    & java -cp $R8Jar com.android.tools.r8.D8 --release --lib $AndroidJar --min-api 26 --output "$Out\dex" "$Out\classes.jar"
} else {
    Write-Host ("D8 via bat: $D8")
    & $D8 --release --lib $AndroidJar --min-api 26 --output "$Out\dex" "$Out\classes.jar"
}
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "=== 5. pack dex into apk ==="
& jar uf "$Out\base.apk" -C "$Out\dex" classes.dex
if ($LASTEXITCODE -ne 0) { throw "apk pack failed" }

Write-Host "=== 6. zipalign ==="
& $Zipalign -f 4 "$Out\base.apk" "$Out\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "=== 7. sign ==="
$Ks = "$BuildRoot\debug.keystore"
if (-not (Test-Path "$App\debug.keystore")) {
    & keytool -genkeypair -keystore $Ks -alias cunpian -storepass android -keypass android `
        -keyalg RSA -keysize 2048 -validity 10000 -storetype PKCS12 `
        -dname "CN=CunPian,O=Self,C=CN"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
    Copy-Item $Ks "$App\debug.keystore"
} else {
    Copy-Item "$App\debug.keystore" $Ks -Force
}
& $Apksigner sign --ks $Ks --ks-pass pass:android --ks-key-alias cunpian `
    --out "$Out\$ApkName" "$Out\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

Copy-Item "$Out\$ApkName" "$App\build\$ApkName" -Force
$apk = Get-Item "$App\build\$ApkName"
Write-Host ("DONE: {0}  {1:N2} MB" -f $apk.FullName, ($apk.Length/1MB))

if ($Install) {
    Write-Host "=== 8. adb install ==="
    # adb：优先 PATH，其次 SDK/platform-tools
    $Adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
    if (-not $Adb) {
        $AdbCandidates = @(
            (Join-Path $Sdk "platform-tools\adb.exe"),
            (Join-Path $Sdk "platform-tools\adb")
        )
        $Adb = $AdbCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    }
    if (-not $Adb) { throw "找不到 adb。请把 platform-tools 加入 PATH，或设 ANDROID_SDK_ROOT 包含 platform-tools" }
    $adbArgs = @()
    if ($Serial) { $adbArgs += @("-s", $Serial) }
    $adbArgs += @("install", "-r", $apk.FullName)
    & $Adb @adbArgs
    if ($LASTEXITCODE -ne 0) { throw "adb install failed" }
}
