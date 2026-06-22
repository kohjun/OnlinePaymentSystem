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

    $matches = Select-String -Path $Paths -Pattern $Text -SimpleMatch -Encoding UTF8 -ErrorAction SilentlyContinue
    if ($matches) {
        $matches | ForEach-Object { Write-Host $_ }
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

    $matches = Select-String -Path $Paths -Pattern $Text -SimpleMatch -Encoding UTF8 -ErrorAction SilentlyContinue
    if (-not $matches) {
        throw "Expected match was not found: $Description"
    }
}

Set-Location $repoRoot

Invoke-Step "Java runtime" {
    Enable-Java
}

Invoke-Step "Gradle compile" {
    Invoke-Native $gradle @("compileJava", "compileTestJava", "--no-daemon")
}

if (-not $SkipTests) {
    Invoke-Step "Focused quality tests" {
        Invoke-Native $gradle @(
            "test",
            "--tests", "*DistributionReadinessServiceTest",
            "--tests", "*AuctionServiceTest",
            "--tests", "*MarketplaceQueryServiceTest",
            "--tests", "*MarketplaceCheckoutServiceTest",
            "--tests", "*MarketplaceOrderServiceTest",
            "--tests", "*RaffleServiceTest",
            "--tests", "*SellerMarketplaceServiceTest",
            "--tests", "*SellerPayoutServiceTest",
            "--tests", "*PaymentProcessingServiceTest",
            "--tests", "*CompleteReservationWorkflowTest",
            "--tests", "*OutboxPublisherTest",
            "--tests", "*InventoryReconciliationJobTest",
            "--no-daemon"
        )
    }
}

$sourcePaths = @(
    "src\main\resources\static\index.html",
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
        (New-TextFromCodePoints @(0xC5D0, 0xBE0C, 0xB9AC, 0xC138, 0xC77C, 0x0020, 0x007C, 0x0020, 0xC5D4, 0xD130, 0xD504, 0xB77C, 0xC774, 0xC988, 0x0020, 0xCEE4, 0xBA38, 0xC2A4, 0x0020, 0xC6B4, 0xC601, 0x0020, 0xD50C, 0xB7AB, 0xD3FC)),
        (New-TextFromCodePoints @(0xC5D0, 0xBE0C, 0xB9AC, 0xC138, 0xC77C, 0x0020, 0xC811, 0xC18D, 0x0020, 0xB300, 0xAE30, 0xC5F4)),
        (New-TextFromCodePoints @(0xD30C, 0xD2B8, 0xB108, 0x0020, 0xC6B4, 0xC601, 0x0020, 0xCF58, 0xC194, 0x0020, 0xB85C, 0xADF8, 0xC778))
    )

    foreach ($term in $expectedTerms) {
        Assert-LiteralMatch $term $sourcePaths "EverySale product copy"
    }
}

Invoke-Step "Toss payment configuration scan" {
    $configPaths = @("src\main\resources\application.yml", "src\main\resources\application-prod.yml")
    Assert-LiteralMatch "default-gateway: TOSS_PAYMENTS" $configPaths "Toss Payments default gateway"
    Assert-LiteralMatch "allow-gateway-fallback: false" $configPaths "payment gateway fallback disabled"
    Assert-NoLiteralMatch "default-gateway: MOCK_PAYMENT_GATEWAY" $configPaths "Mock gateway as production default"
    Assert-LiteralMatch 'client-key: ${TOSS_CLIENT_KEY:}' $configPaths "Toss client key environment binding"
    Assert-LiteralMatch 'secret-key: ${TOSS_SECRET_KEY:}' $configPaths "Toss secret key environment binding"
    Assert-LiteralMatch "public-complete-enabled: false" $configPaths "direct complete reservation API disabled"
    Assert-LiteralMatch "legacy-marketplace-enabled: false" $configPaths "legacy marketplace checkout disabled"
    Assert-LiteralMatch "legacy-api:" $configPaths "legacy payment API toggle"
    Assert-LiteralMatch "enabled: false" $configPaths "disabled legacy/mock defaults"
    Assert-LiteralMatch "mode: live" @("src\main\resources\application-prod.yml") "production Toss live mode"
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
