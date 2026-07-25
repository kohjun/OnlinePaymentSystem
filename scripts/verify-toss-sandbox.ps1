[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $repoRoot ".env"
$gradle = Join-Path $repoRoot "gradlew.bat"

if (Test-Path -LiteralPath $envFile) {
    foreach ($rawLine in Get-Content -LiteralPath $envFile) {
        $line = $rawLine.Trim()
        if (-not $line -or $line.StartsWith("#") -or -not $line.Contains("=")) {
            continue
        }
        $parts = $line.Split("=", 2)
        $name = $parts[0].Trim()
        if (-not $name -or $name -ieq "PATH" -or $name -ieq "JAVA_HOME") {
            continue
        }
        $value = $parts[1].Trim()
        if (($value.StartsWith('"') -and $value.EndsWith('"')) -or
            ($value.StartsWith("'") -and $value.EndsWith("'"))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        [Environment]::SetEnvironmentVariable($name, $value, "Process")
    }
    Write-Host "Loaded Toss sandbox environment from .env (values hidden)." -ForegroundColor DarkGray
}

if (-not $env:TOSS_CLIENT_KEY -or -not $env:TOSS_SECRET_KEY) {
    throw "TOSS_CLIENT_KEY and TOSS_SECRET_KEY are required. Values were not printed."
}
if (-not $env:TOSS_CLIENT_KEY.StartsWith("test_") -or -not $env:TOSS_SECRET_KEY.StartsWith("test_")) {
    throw "Only Toss test keys are allowed by this sandbox verification."
}

Push-Location $repoRoot
try {
    & $gradle tossSandboxTest --no-daemon --no-problems-report
    if ($LASTEXITCODE -ne 0) {
        throw "tossSandboxTest failed."
    }
    Write-Host "EverySale Toss sandbox preflight completed without creating a payment." -ForegroundColor Green
} finally {
    Pop-Location
}
