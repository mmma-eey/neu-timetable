# Run from any directory; JAVA_HOME and Android SDK must already be configured.
param([switch]$Offline)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectRoot
try {
    $gradleArgs=@(':app:assembleDebug',':app:testDebugUnitTest',':app:lintDebug','--console=plain')
    if($Offline){$gradleArgs+='--offline'}
    & './gradlew.bat' @gradleArgs
    if($LASTEXITCODE -ne 0){throw "Gradle failed with exit code $LASTEXITCODE"}
} finally { Pop-Location }
