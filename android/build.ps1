# 构建 K5Web 安卓 APK（不依赖 Gradle / AndroidStudio，只用 JDK + build-tools + android.jar）
# 用法：
#   powershell -ExecutionPolicy Bypass -File .\android\build.ps1
#   powershell -ExecutionPolicy Bypass -File .\android\build.ps1 -VersionName 1.0.0 -VersionCode 1
param(
    [string]$VersionName = "1.0.0",
    [int]$VersionCode = 1,
    [switch]$SkipWeb
)

$ErrorActionPreference = "Stop"
$android = $PSScriptRoot
$repo = Split-Path -Parent $android
$build = Join-Path $android "build"
$tools = Join-Path $repo "tools"

function Find-FirstFile($root, $filter) {
    if (-not (Test-Path $root)) { return $null }
    $f = Get-ChildItem $root -Recurse -Filter $filter -File -ErrorAction SilentlyContinue |
        Sort-Object FullName | Select-Object -First 1
    if ($f) { return $f.FullName }
    return $null
}

# ---------------------------------------------------------------- 工具定位
$javac = Find-FirstFile (Join-Path $tools "jdk") "javac.exe"
if (-not $javac) { throw "未找到 JDK（tools\jdk）" }
$jdkHome = Split-Path (Split-Path $javac -Parent) -Parent

$aapt2 = Find-FirstFile (Join-Path $tools "build-tools") "aapt2.exe"
if (-not $aapt2) { throw "未找到 aapt2（tools\build-tools）" }
$bt = Split-Path $aapt2 -Parent

$androidJar = Find-FirstFile (Join-Path $tools "platform") "android.jar"
if (-not $androidJar) { throw "未找到 android.jar（tools\platform）" }

$env:JAVA_HOME = $jdkHome
$env:PATH = "$jdkHome\bin;$bt;$env:PATH"

Write-Host "JDK        : $jdkHome"
Write-Host "build-tools: $bt"
Write-Host "android.jar: $androidJar"

# ---------------------------------------------------------------- 前端资源
$assetsWww = Join-Path $android "assets\www"
if (-not $SkipWeb) {
    $dist = Join-Path $repo "dist"
    if (-not (Test-Path (Join-Path $dist "index.html"))) {
        throw "找不到 dist\index.html —— 请先构建前端（pnpm run build）"
    }
    if (Test-Path $assetsWww) { Remove-Item $assetsWww -Recurse -Force }
    New-Item -ItemType Directory -Path $assetsWww -Force | Out-Null
    Copy-Item (Join-Path $dist "*") $assetsWww -Recurse -Force
    Copy-Item (Join-Path $android "bridge\k5-bridge.js") (Join-Path $assetsWww "k5-bridge.js") -Force
    $count = (Get-ChildItem $assetsWww -Recurse -File | Measure-Object).Count
    Write-Host "assets/www : $count 个文件"
} elseif (-not (Test-Path (Join-Path $assetsWww "index.html"))) {
    throw "assets\www 不存在，不能使用 -SkipWeb"
}

# ---------------------------------------------------------------- 图标
$icon = Join-Path $android "res\drawable\app_icon.png"
if (-not (Test-Path $icon)) {
    New-Item -ItemType Directory -Path (Split-Path $icon -Parent) -Force | Out-Null
    Copy-Item (Join-Path $repo "public\k5web.png") $icon -Force
}

# ---------------------------------------------------------------- 编译资源
foreach ($d in @("res", "gen", "classes", "dex")) {
    $p = Join-Path $build $d
    if (Test-Path $p) { Remove-Item $p -Recurse -Force }
}
New-Item -ItemType Directory -Path $build -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $build "gen") -Force | Out-Null

Write-Host "==> aapt2 compile"
& $aapt2 compile --dir (Join-Path $android "res") -o (Join-Path $build "res.zip")
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile 失败" }

Write-Host "==> aapt2 link"
& $aapt2 link `
    -o (Join-Path $build "base.apk") `
    -I $androidJar `
    --manifest (Join-Path $android "AndroidManifest.xml") `
    -A (Join-Path $android "assets") `
    -R (Join-Path $build "res.zip") `
    --java (Join-Path $build "gen") `
    --min-sdk-version 26 `
    --target-sdk-version 34 `
    --version-code $VersionCode `
    --version-name $VersionName `
    --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw "aapt2 link 失败" }

# aapt2（Windows）会把 assets 条目名写成反斜杠，设备端 AssetManager 按 "/" 查找会失败，
# 这里统一规范成 "/"（保留 resources.arsc 的 STORED 压缩方式）。
Write-Host "==> ZipFix (规范化 assets 条目路径)"
$fixedApk = Join-Path $build "base-fixed.apk"
& (Join-Path $jdkHome "bin\java.exe") (Join-Path $repo "tools\ZipFix.java") (Join-Path $build "base.apk") $fixedApk
if ($LASTEXITCODE -ne 0) { throw "ZipFix 失败" }

# ---------------------------------------------------------------- 编译 Java
Write-Host "==> javac"
$sources = @()
$sources += (Get-ChildItem (Join-Path $build "gen") -Recurse -Filter *.java -File | ForEach-Object { $_.FullName })
$sources += (Get-ChildItem (Join-Path $android "java") -Recurse -Filter *.java -File | ForEach-Object { $_.FullName })
& $javac --release 11 -nowarn -encoding UTF-8 -classpath $androidJar -d (Join-Path $build "classes") @sources
if ($LASTEXITCODE -ne 0) { throw "javac 失败" }

# ---------------------------------------------------------------- dex
Write-Host "==> d8"
$dexDir = Join-Path $build "dex"
New-Item -ItemType Directory -Path $dexDir -Force | Out-Null
$classes = (Get-ChildItem (Join-Path $build "classes") -Recurse -Filter *.class -File | ForEach-Object { $_.FullName })
& (Join-Path $bt "d8.bat") --lib $androidJar --min-api 26 --release --output $dexDir @classes
if ($LASTEXITCODE -ne 0) { throw "d8 失败" }

# ---------------------------------------------------------------- 打包
Write-Host "==> package"
$unsigned = Join-Path $build "app-unsigned.apk"
Copy-Item $fixedApk $unsigned -Force
& (Join-Path $jdkHome "bin\jar.exe") uf $unsigned -C (Join-Path $build "dex") classes.dex
if ($LASTEXITCODE -ne 0) { throw "jar 追加 classes.dex 失败" }

$aligned = Join-Path $build "app-aligned.apk"
& (Join-Path $bt "zipalign.exe") -f -p 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw "zipalign 失败" }

# ---------------------------------------------------------------- 签名
$ks = Join-Path $build "k5web.keystore"
# 签名密码不入库：从环境变量 K5WEB_KS_PASS 或本地文件 android/build/ks.pass.txt
# 读取（该文件已被 .gitignore 排除）。克隆仓库后首次构建请自行设置密码，
# keytool 会用该密码自动生成本地签名证书。
$ksPass = $env:K5WEB_KS_PASS
if (-not $ksPass -and (Test-Path (Join-Path $build "ks.pass.txt"))) {
    $ksPass = (Get-Content (Join-Path $build "ks.pass.txt") -Raw).Trim()
}
if (-not $ksPass) {
    throw "未找到签名密码：请设置环境变量 K5WEB_KS_PASS，或在 android\build\ks.pass.txt 中保存（该文件不入库）"
}
if (-not (Test-Path $ks)) {
    Write-Host "==> keytool (生成签名证书)"
    & (Join-Path $jdkHome "bin\keytool.exe") -genkeypair -v `
        -keystore $ks -alias k5web -keyalg RSA -keysize 2048 -validity 10950 `
        -storepass $ksPass -keypass $ksPass `
        -dname "CN=K5Web, OU=HAM, O=K5Web Android, L=Beijing, ST=Beijing, C=CN"
    if ($LASTEXITCODE -ne 0) { throw "keytool 失败" }
}

$release = Join-Path $repo "release"
New-Item -ItemType Directory -Path $release -Force | Out-Null
$outApk = Join-Path $release ("K5Web-android-{0}.apk" -f $VersionName)

Write-Host "==> apksigner"
& (Join-Path $bt "apksigner.bat") sign `
    --ks $ks --ks-pass "pass:$ksPass" --key-pass "pass:$ksPass" `
    --v1-signing-enabled true --v2-signing-enabled true `
    --out $outApk $aligned
if ($LASTEXITCODE -ne 0) { throw "apksigner sign 失败" }

& (Join-Path $bt "apksigner.bat") verify --print-certs $outApk
if ($LASTEXITCODE -ne 0) { throw "apksigner verify 失败" }

$size = [math]::Round((Get-Item $outApk).Length / 1MB, 2)
Write-Host ""
Write-Host "APK 构建完成: $outApk ($size MB)" -ForegroundColor Green
