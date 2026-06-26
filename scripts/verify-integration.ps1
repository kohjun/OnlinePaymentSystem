[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$gradle = Join-Path $repoRoot "gradlew.bat"

Set-Location $repoRoot

function Clear-GradleProblemsReport {
    $problemReport = Join-Path $repoRoot "build_sim\reports\problems\problems-report.html"
    if (Test-Path $problemReport) {
        try {
            Remove-Item -LiteralPath $problemReport -Force
        } catch {
            Write-Warning "Could not remove existing Gradle problems report; continuing with --no-problems-report."
        }
    }
}

docker ps | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "Docker is required for integrationTest. Start Docker Desktop and retry."
}

if (-not $env:JAVA_HOME) {
    $androidStudioJbr = "C:\Program Files\Android\Android Studio\jbr"
    if (Test-Path (Join-Path $androidStudioJbr "bin\java.exe")) {
        $env:JAVA_HOME = $androidStudioJbr
    }
}
if ($env:JAVA_HOME) {
    $env:Path = "$(Join-Path $env:JAVA_HOME 'bin');$env:Path"
}

Clear-GradleProblemsReport
& $gradle "integrationTest" "--no-daemon" "--no-problems-report" "--rerun-tasks"
if ($LASTEXITCODE -ne 0) {
    throw "integrationTest failed."
}

Write-Host "EverySale integration verification completed successfully." -ForegroundColor Green
