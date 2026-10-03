# 复制最新 HTML 进 assets 并打 debug APK
# 用法：在 android-app 目录下执行  powershell -ExecutionPolicy Bypass -File .\build-apk.ps1
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$src = Join-Path $root '..\image-browser.html'
$assets = Join-Path $root 'app\src\main\assets'
New-Item -ItemType Directory -Force -Path $assets | Out-Null
Copy-Item $src (Join-Path $assets 'index.html') -Force
Write-Host '已同步 index.html'

# PowerShell 5.1 在 Stop 策略下会把原生命令的 stderr（Gradle 的 SDK 警告即走 stderr）误判为终止错误，
# 调用 Gradle 期间放宽为 Continue，只以退出码判定成败
# 非交互子进程（编辑器/CI 调起）没有有效控制台句柄，JVM 会启动即失败且退出码为空，
# 必须把输出重定向给 Gradle 一个有效句柄；Tee 同时写日志并回显
$prevEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$gradleCode = 0
$buildLog = Join-Path $root 'last-build.log'
$gradleArgs = @('-p', $root, 'assembleDebug', '--no-daemon', '--console=plain')
if (Test-Path (Join-Path $root 'gradlew.bat')) {
    # no-daemon 让构建在客户端 JVM 内跑完即退，避免常驻 daemon 持有控制台句柄导致脚本挂住
    # 所有流合并后接 Tee，给非交互子进程中的 JVM 提供有效输出句柄（缺失会零输出直接失败）
    & (Join-Path $root 'gradlew.bat') @gradleArgs *>&1 |
        Tee-Object -FilePath $buildLog | Out-Null
    $gradleCode = $LASTEXITCODE
} else {
    & (Join-Path $root '..\.tools\gradle-8.13\bin\gradle.bat') @gradleArgs *>&1 |
        Tee-Object -FilePath $buildLog | Out-Null
    $gradleCode = $LASTEXITCODE
}
$ErrorActionPreference = $prevEap
if ($gradleCode -ne 0) { throw "Gradle 构建失败，详见 $buildLog" }

$apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
if (Test-Path $apk) {
    Write-Host ('APK 已生成：' + $apk)
} else {
    throw '构建结束但未找到 APK'
}
