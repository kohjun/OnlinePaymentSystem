[CmdletBinding()]
param(
    [switch] $SkipTests,
    [switch] $SkipDesktopPackage
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$gradle = Join-Path $repoRoot "gradlew.bat"
$desktopApp = Join-Path $repoRoot "desktop-app"
$frontendApp = Join-Path $repoRoot "frontend"

function New-TextFromCodePoints {
    param([Parameter(Mandatory = $true)] [int[]] $CodePoints)

    return -join ($CodePoints | ForEach-Object { [char] $_ })
}

function Invoke-Step {
    param(
        [Parameter(Mandatory = $true)] [string] $Name,
        [Parameter(Mandatory = $true)] [scriptblock] $Action
    )

    Write-Host ""
    Write-Host "==> $Name" -ForegroundColor Cyan
    & $Action
    Write-Host "PASS: $Name" -ForegroundColor Green
}

function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)] [string] $FilePath,
        [Parameter(Mandatory = $true)] [string[]] $Arguments
    )

    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed ($LASTEXITCODE): $FilePath $($Arguments -join ' ')"
    }
}

function Clear-GradleProblemsReport {
    foreach ($buildDirectory in @("build_sim", "build_sim_new")) {
        $problemReport = Join-Path $repoRoot "$buildDirectory\reports\problems\problems-report.html"
        if (Test-Path $problemReport) {
            try {
                Remove-Item -LiteralPath $problemReport -Force
            } catch {
                Write-Warning "Could not remove existing Gradle problems report; continuing with --no-problems-report."
            }
        }
    }
}

function Enable-Java {
    if (-not $env:JAVA_HOME) {
        $androidStudioJbr = "C:\Program Files\Android\Android Studio\jbr"
        if (Test-Path (Join-Path $androidStudioJbr "bin\java.exe")) {
            $env:JAVA_HOME = $androidStudioJbr
        }
    }

    if ($env:JAVA_HOME) {
        $javaExe = Join-Path $env:JAVA_HOME "bin\java.exe"
        if (-not (Test-Path $javaExe)) {
            throw "JAVA_HOME is set but java.exe was not found: $javaExe"
        }
        $env:Path = "$(Join-Path $env:JAVA_HOME 'bin');$env:Path"
        & $javaExe -version
        return
    }

    $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
    if (-not $javaCommand) {
        throw "Java 17+ was not found. Set JAVA_HOME before running the distribution quality gate."
    }
    & $javaCommand.Source -version
}

# rg recurses into directories; Select-String does not. Callers pass directory
# paths, so expand them before falling back or the scan silently matches nothing.
function Resolve-ScanPaths {
    param(
        [Parameter(Mandatory = $true)] [string[]] $Paths
    )

    $resolved = @()
    foreach ($path in $Paths) {
        if (Test-Path -LiteralPath $path -PathType Container) {
            $resolved += Get-ChildItem -LiteralPath $path -Recurse -File |
                Select-Object -ExpandProperty FullName
        } else {
            $resolved += $path
        }
    }
    return $resolved
}

# Reads UTF-8 explicitly instead of using Select-String -Encoding UTF8. Under
# Windows PowerShell 5.1 that switch still decodes these files wrongly and
# reports a replacement character on every non-ASCII line -- 164 false hits on
# desktop-app\splash.html alone, which is valid UTF-8 with no U+FFFD in it.
function Find-LiteralInFiles {
    param(
        [Parameter(Mandatory = $true)] [string] $Text,
        [Parameter(Mandatory = $true)] [string[]] $Paths
    )

    $utf8 = New-Object System.Text.UTF8Encoding($false, $false)
    $found = @()
    foreach ($path in (Resolve-ScanPaths $Paths)) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            continue
        }
        $content = [System.IO.File]::ReadAllText((Resolve-Path -LiteralPath $path).ProviderPath, $utf8)
        if ($content.Contains($Text)) {
            $found += $path
        }
    }
    return $found
}

function Assert-NoLiteralMatch {
    param(
        [Parameter(Mandatory = $true)] [string] $Text,
        [Parameter(Mandatory = $true)] [string[]] $Paths,
        [Parameter(Mandatory = $true)] [string] $Description
    )

    $rg = Get-Command rg.exe -ErrorAction SilentlyContinue
    if ($rg) {
        & $rg.Source -n -F $Text @Paths -S
        if ($LASTEXITCODE -eq 0) {
            throw "Unexpected match found: $Description"
        }
        if ($LASTEXITCODE -gt 1) {
            throw "Search failed while checking: $Description"
        }
        return
    }

    $hits = Find-LiteralInFiles -Text $Text -Paths $Paths
    if ($hits) {
        $hits | ForEach-Object { Write-Host $_ }
        throw "Unexpected match found: $Description"
    }
}

function Assert-LiteralMatch {
    param(
        [Parameter(Mandatory = $true)] [string] $Text,
        [Parameter(Mandatory = $true)] [string[]] $Paths,
        [Parameter(Mandatory = $true)] [string] $Description
    )

    $rg = Get-Command rg.exe -ErrorAction SilentlyContinue
    if ($rg) {
        & $rg.Source -n -F $Text @Paths -S
        if ($LASTEXITCODE -ne 0) {
            throw "Expected match was not found: $Description"
        }
        return
    }

    $hits = Find-LiteralInFiles -Text $Text -Paths $Paths
    if (-not $hits) {
        throw "Expected match was not found: $Description"
    }
}

Set-Location $repoRoot

Invoke-Step "Java runtime" {
    Enable-Java
}

Invoke-Step "Gradle compile" {
    Clear-GradleProblemsReport
    Invoke-Native $gradle @("compileJava", "compileTestJava", "--no-daemon", "--no-problems-report")
}

Invoke-Step "React marketplace quality gate" {
    $npm = Get-Command npm.cmd -ErrorAction SilentlyContinue
    if (-not $npm) {
        throw "npm was not found. Install Node.js before running the distribution quality gate."
    }
    $previousNpmCache = $env:npm_config_cache
    $env:npm_config_cache = Join-Path $repoRoot "build_sim_new\npm-cache"
    Push-Location $frontendApp
    try {
        Invoke-Native $npm.Source @("ci", "--no-audit", "--no-fund")
        Invoke-Native $npm.Source @("run", "verify")
    } finally {
        Pop-Location
        $env:npm_config_cache = $previousNpmCache
    }
}

# The whole unit suite, not a hand-picked list. The previous 20 --tests filters
# silently omitted 19 existing test classes and still referenced a test that had
# been deleted. build.gradle already excludes the integration and toss-sandbox
# tags, so a bare `test` needs no Docker and no credentials.
if (-not $SkipTests) {
    Invoke-Step "Unit test suite" {
        Clear-GradleProblemsReport
        Invoke-Native $gradle @("test", "--no-daemon", "--no-problems-report")
    }
}

# Enumerated rather than hardcoded: a hand-written list lets every new page
# component escape the branding and mojibake scans without anyone noticing.
$sourcePaths = @("frontend\index.html")
$sourcePaths += Get-ChildItem -LiteralPath "frontend\src" -Recurse -File -Include *.tsx, *.ts |
    Select-Object -ExpandProperty FullName
$sourcePaths += @(
    "desktop-app\main.js",
    "desktop-app\package.json",
    "desktop-app\package-lock.json",
    "desktop-app\splash.html"
)

Invoke-Step "Brand and encoding regression scan" {
    $legacyTerms = @(
        "SaaS B2B Simulator",
        "SaaS Simulator Platform",
        "B2B SaaS",
        "b2b-saas",
        "B2BSaaS",
        ("N " + (New-TextFromCodePoints @(0xC608, 0xC57D))),
        (New-TextFromCodePoints @(0xCD08, 0xACE0, 0xC131, 0xB2A5)),
        (New-TextFromCodePoints @(0xB85C, 0xCEEC, 0x0020, 0xC11C, 0xBE44, 0xC2A4, 0x0020, 0xC5D4, 0xC9C4)),
        (New-TextFromCodePoints @(0xC2DC, 0xBBAC, 0xB808, 0xC774, 0xD130, 0x0020, 0xD50C, 0xB7AB, 0xD3FC)),
        (New-TextFromCodePoints @(0xD30C, 0xD2B8, 0xB108, 0x0020, 0xC13C, 0xD130)),
        (New-TextFromCodePoints @(0xAD6C, 0xB9E4, 0x0020, 0xC13C, 0xD130))
    )

    foreach ($term in $legacyTerms) {
        Assert-NoLiteralMatch $term $sourcePaths "legacy simulator branding: $term"
    }

    $mojibakeTerms = @(
        (New-TextFromCodePoints @(0xFFFD)),
        (New-TextFromCodePoints @(0x003F, 0xBA2E)),
        (New-TextFromCodePoints @(0x003F, 0xB349)),
        (New-TextFromCodePoints @(0x003F, 0xC496)),
        (New-TextFromCodePoints @(0x003F, 0xAFA9))
    )

    foreach ($term in $mojibakeTerms) {
        Assert-NoLiteralMatch $term $sourcePaths "mojibake or replacement characters"
    }

    $expectedTerms = @(
        (New-TextFromCodePoints @(0xC5D0, 0xBE0C, 0xB9AC, 0xC138, 0xC77C)),
        (New-TextFromCodePoints @(0x0043, 0x0032, 0x0043, 0x0020, 0xB9C8, 0xCF13, 0xD50C, 0xB808, 0xC774, 0xC2A4)),
        (New-TextFromCodePoints @(0xC5D0, 0xBE0C, 0xB9AC, 0xC138, 0xC77C, 0x0020, 0xB9C8, 0xCF13, 0xD50C, 0xB808, 0xC774, 0xC2A4))
    )

    foreach ($term in $expectedTerms) {
        Assert-LiteralMatch $term $sourcePaths "EverySale product copy"
    }
}

Invoke-Step "Frontend checkout flow scan" {
    $frontendPaths = @(
        "frontend\src\api.ts",
        "frontend\src\EventDetail.tsx",
        "frontend\src\TicketPanel.tsx"
    )

    Assert-NoLiteralMatch "/api/reservations/complete" $frontendPaths "public frontend direct complete reservation call"
    Assert-NoLiteralMatch "/api/payments/process" $frontendPaths "public frontend legacy payment process call"
    Assert-NoLiteralMatch "/api/simulation/events/active" $frontendPaths "marketplace UI simulation event selection call"
    Assert-NoLiteralMatch "/api/simulation/products" $frontendPaths "marketplace UI simulation product lookup"
    Assert-LiteralMatch "/api/payments/toss/intents" $frontendPaths "Toss payment intent endpoint"
    Assert-LiteralMatch "/api/payments/toss/confirm" $frontendPaths "Toss payment confirm endpoint"
    # Built from code points like every other Korean literal here. This file has
    # no BOM, so Windows PowerShell 5.1 reads a raw literal in the ANSI codepage
    # and the negative assertion would search for a string that cannot occur.
    Assert-NoLiteralMatch ("MOCK " + (New-TextFromCodePoints @(0xACB0, 0xC81C))) $frontendPaths "user-visible mock payment window"
    Assert-LiteralMatch "/auction/stream" $frontendPaths "auction SSE endpoint"
    Assert-LiteralMatch "/raffle/stream" $frontendPaths "raffle SSE endpoint"
}

Invoke-Step "Toss payment configuration scan" {
    $configPaths = @("src\main\resources\application.yml", "src\main\resources\application-prod.yml")
    Assert-LiteralMatch "default-gateway: TOSS_PAYMENTS" $configPaths "Toss Payments default gateway"
    Assert-LiteralMatch "allow-gateway-fallback: false" $configPaths "payment gateway fallback disabled"
    Assert-NoLiteralMatch "default-gateway: MOCK_PAYMENT_GATEWAY" $configPaths "Mock gateway as production default"
    Assert-LiteralMatch 'client-key: ${TOSS_CLIENT_KEY:}' $configPaths "Toss client key environment binding"
    Assert-LiteralMatch 'secret-key: ${TOSS_SECRET_KEY:}' $configPaths "Toss secret key environment binding"
    Assert-LiteralMatch "public-complete-enabled: false" $configPaths "direct complete reservation API disabled"
    Assert-LiteralMatch "enabled: false" $configPaths "disabled legacy/mock defaults"
    Assert-NoLiteralMatch '@PostMapping("/process")' @("src\main\java\com\example\payment\presentation\controller\PaymentController.java") "removed legacy payment process endpoint"
    Assert-NoLiteralMatch '@PostMapping("/events/{eventId}/checkout")' @("src\main\java\com\example\payment\presentation\controller\MarketplaceController.java") "removed non-Toss marketplace checkout endpoint"
    Assert-LiteralMatch "mode: live" @("src\main\resources\application-prod.yml") "production Toss live mode"
    Assert-NoLiteralMatch "/api/simulation" @("src\main\java", "frontend\src") "removed simulation API exposure"
    Assert-LiteralMatch "webhook:" $configPaths "Toss webhook configuration block"
    Assert-LiteralMatch 'path-token: ${TOSS_WEBHOOK_PATH_TOKEN' $configPaths "Toss webhook path token environment binding"
    Assert-LiteralMatch "toss-webhook-configured" @("src\main\java\com\example\payment\application\service\DistributionReadinessService.java") "Toss webhook readiness check"
    Assert-LiteralMatch 'audience: ${OIDC_AUDIENCE}' @("src\main\resources\application-prod.yml") "production JWT audience binding"
    Assert-LiteralMatch "bind-token-claims: true" @("src\main\resources\application-prod.yml") "production tenant token binding"
    Assert-LiteralMatch "mode: SINGLE_TENANT" @("src\main\resources\application-prod.yml") "production single-tenant deployment boundary"
    Assert-LiteralMatch 'allowed-tenant-id: ${EVERYSALE_TENANT_ID}' @("src\main\resources\application-prod.yml") "production allowed tenant binding"
    Assert-LiteralMatch "MULTI_TENANT_RLS" @("src\main\java\com\example\payment\application\service\DistributionReadinessService.java") "multi-tenant RLS readiness blocker"
    Assert-LiteralMatch "verify-toss-sandbox.ps1" @("README.md") "Toss sandbox preflight documentation"
    Assert-LiteralMatch "temporal-worker-enabled" @("src\main\java\com\example\payment\application\service\DistributionReadinessService.java") "Temporal worker readiness check"
    Assert-LiteralMatch "inventory-reconciliation-enabled" @("src\main\java\com\example\payment\application\service\DistributionReadinessService.java") "inventory reconciliation readiness check"
    Assert-LiteralMatch "toss-reconciliation-enabled" @("src\main\java\com\example\payment\application\service\DistributionReadinessService.java") "Toss reconciliation readiness check"
    Assert-LiteralMatch "auction-auto-close-enabled" @("src\main\java\com\example\payment\application\service\DistributionReadinessService.java") "auction auto-close readiness check"
    Assert-LiteralMatch "marketplace-realtime-broadcast-enabled" @("src\main\java\com\example\payment\application\service\DistributionReadinessService.java") "distributed marketplace realtime readiness check"
    Assert-LiteralMatch "MarketplaceConcurrencyIntegrationTest" @("README.md", "src\test\java\com\example\payment\MarketplaceConcurrencyIntegrationTest.java") "commercial concurrency integration gate"
    Assert-LiteralMatch "redis-broadcast-enabled: true" @("src\main\resources\application-prod.yml") "production distributed marketplace realtime broadcast"
    Assert-LiteralMatch "seller-payout-transfer-provider" @("src\main\java\com\example\payment\application\service\DistributionReadinessService.java") "seller payout transfer readiness check"
    Assert-LiteralMatch "seller-payout-reconciliation-enabled" @("src\main\java\com\example\payment\application\service\DistributionReadinessService.java") "seller payout reconciliation readiness check"
    Assert-LiteralMatch 'provider: ${PAYOUT_TRANSFER_PROVIDER:}' @("src\main\resources\application-prod.yml") "production payout transfer provider binding"
    Assert-LiteralMatch "verify-production-env.ps1" @("README.md") "production environment preflight documentation"
    Assert-LiteralMatch "TOSS_SECRET_KEY" @(".env.prod.example", "scripts\verify-production-env.ps1") "production secret-key inventory"
    Assert-LiteralMatch "ddl-auto: validate" @("src\main\resources\application-prod.yml") "production schema validation mode"
}

if (-not $SkipDesktopPackage) {
    Invoke-Step "Electron desktop package" {
        Push-Location $desktopApp
        try {
            Invoke-Native "npm.cmd" @("run", "package")
        } finally {
            Pop-Location
        }

        $exe = Join-Path $desktopApp "dist\EverySale-win32-x64\EverySale.exe"
        if (-not (Test-Path $exe)) {
            throw "Expected packaged executable was not found: $exe"
        }
    }
}

Write-Host ""
Write-Host "EverySale distribution quality gate completed successfully." -ForegroundColor Green
