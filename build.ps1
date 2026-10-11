param([Parameter(ValueFromRemainingArguments = $true)][string[]]$GradleTasks)

if (-not $GradleTasks) { $GradleTasks = @('testDebugUnitTest', 'assembleDebug') }

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location -LiteralPath $projectRoot

$localJdk = Get-ChildItem -LiteralPath (Join-Path $projectRoot '.toolchain/jdk') -Directory -ErrorAction SilentlyContinue |
    Where-Object { Test-Path (Join-Path $_.FullName 'bin/java.exe') } | Select-Object -First 1
if ($localJdk) { $env:JAVA_HOME = $localJdk.FullName }

$localSdk = Join-Path $projectRoot '.toolchain/sdk'
if (Test-Path (Join-Path $localSdk 'platforms/android-35/android.jar')) { $env:ANDROID_HOME = $localSdk }

$env:GRADLE_USER_HOME = Join-Path $projectRoot '.gradle'
& (Join-Path $projectRoot 'gradlew.bat') @GradleTasks --console=plain
exit $LASTEXITCODE
