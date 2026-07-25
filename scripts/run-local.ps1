[CmdletBinding()]
param(
    [switch] $StartInfrastructure,
    [switch] $SkipInfrastructureCheck,
    [string] $Profile = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$gradle = Join-Path $repoRoot "gradlew.bat"
$envFile = Join-Path $repoRoot ".env"

function Import-DotEnv {
    if (-not (Test-Path -LiteralPath $envFile)) {
        Write-Host "No .env file found; using the current process environment." -ForegroundColor Yellow
        return
    }

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

    Write-Host "Loaded local environment variables from .env (values hidden)." -ForegroundColor DarkGray
}

function Resolve-JavaHome {
    $candidates = @()
    if ($env:JAVA_HOME) {
        $candidates += $env:JAVA_HOME
    }
    $candidates += @(
        "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot",
        "C:\Program Files\Android\Android Studio\jbr"
    )
    $adoptiumRoot = "C:\Program Files\Eclipse Adoptium"
    if (Test-Path -LiteralPath $adoptiumRoot) {
        $candidates += Get-ChildItem -LiteralPath $adoptiumRoot -Directory -ErrorAction SilentlyContinue |
                Sort-Object Name -Descending |
                Select-Object -ExpandProperty FullName
    }

    foreach ($candidate in $candidates | Select-Object -Unique) {
        if ($candidate -and (Test-Path -LiteralPath (Join-Path $candidate "bin\java.exe"))) {
            $env:JAVA_HOME = $candidate
            $env:Path = "$(Join-Path $candidate 'bin');$env:Path"
            return
        }
    }
    throw "Java 17+ was not found. Install Temurin JDK 17 or set JAVA_HOME to a directory containing bin\java.exe."
}

function Assert-Infrastructure {
    & docker info | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Docker Desktop Linux engine is not available. Start Docker Desktop and wait until the engine is running."
    }

    if ($StartInfrastructure) {
        Write-Host "Starting EverySale local infrastructure..." -ForegroundColor Cyan
        & docker compose -f (Join-Path $repoRoot "docker-compose.yml") up -d
        if ($LASTEXITCODE -ne 0) {
            throw "docker compose up failed."
        }
    }

    $requiredServices = @("postgres", "redis", "kafka", "temporal")
    $runningServices = @(& docker compose -f (Join-Path $repoRoot "docker-compose.yml") ps --status running --services)
    $missing = @($requiredServices | Where-Object { $_ -notin $runningServices })
    if ($missing.Count -gt 0) {
        throw "Required Docker services are not running: $($missing -join ', '). Run this script with -StartInfrastructure."
    }
}

Set-Location $repoRoot
Import-DotEnv
Resolve-JavaHome

Write-Host "Using JAVA_HOME=$env:JAVA_HOME" -ForegroundColor DarkGray
& (Join-Path $env:JAVA_HOME "bin\java.exe") -version
if ($LASTEXITCODE -ne 0) {
    throw "Java runtime validation failed."
}

if (-not $SkipInfrastructureCheck) {
    Assert-Infrastructure
}

if ($Profile) {
    $env:SPRING_PROFILES_ACTIVE = $Profile
    Write-Host "Starting EverySale with Spring profile '$Profile'." -ForegroundColor Cyan
} else {
    Write-Host "Starting EverySale with the default local profile." -ForegroundColor Cyan
}
Write-Host "Application URL: http://localhost:8080/app/" -ForegroundColor Green
Write-Host "Temporal UI: http://localhost:8088" -ForegroundColor Green

& $gradle "bootRun" "--no-daemon" "--no-problems-report"
exit $LASTEXITCODE
