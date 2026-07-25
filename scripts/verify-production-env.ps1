[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$requiredVariables = @(
    "DATABASE_URL",
    "DATABASE_USERNAME",
    "DATABASE_PASSWORD",
    "REDIS_HOST",
    "KAFKA_BOOTSTRAP_SERVERS",
    "TEMPORAL_TARGET",
    "OIDC_ISSUER_URI",
    "OIDC_AUDIENCE",
    "EVERYSALE_TENANT_ID",
    "CORS_ALLOWED_ORIGINS",
    "TOSS_CLIENT_KEY",
    "TOSS_SECRET_KEY",
    "TOSS_WEBHOOK_PATH_TOKEN",
    "PAYOUT_TRANSFER_PROVIDER",
    "PAYOUT_TRANSFER_ADAPTER_ENABLED"
)

$errors = [System.Collections.Generic.List[string]]::new()

function Get-RequiredEnvironmentValue {
    param([Parameter(Mandatory = $true)] [string] $Name)

    $value = [Environment]::GetEnvironmentVariable($Name, "Process")
    if ([string]::IsNullOrWhiteSpace($value)) {
        $errors.Add("$Name is required.")
        return ""
    }
    return $value.Trim()
}

function Assert-ExternalEndpoint {
    param(
        [Parameter(Mandatory = $true)] [string] $Name,
        [Parameter(Mandatory = $true)] [string] $Value
    )

    if ($Value -match '(?i)(localhost|127\.0\.0\.1|host\.docker\.internal)') {
        $errors.Add("$Name must not point to a local endpoint in production.")
    }
}

$values = @{}
foreach ($name in $requiredVariables) {
    $values[$name] = Get-RequiredEnvironmentValue -Name $name
}

foreach ($name in @("DATABASE_URL", "REDIS_HOST", "KAFKA_BOOTSTRAP_SERVERS", "TEMPORAL_TARGET", "OIDC_ISSUER_URI", "CORS_ALLOWED_ORIGINS")) {
    if ($values[$name]) {
        Assert-ExternalEndpoint -Name $name -Value $values[$name]
    }
}

if ($values["DATABASE_URL"] -and $values["DATABASE_URL"] -notmatch '^jdbc:postgresql://') {
    $errors.Add("DATABASE_URL must be a PostgreSQL JDBC URL.")
}
if ($values["OIDC_ISSUER_URI"] -and $values["OIDC_ISSUER_URI"] -notmatch '^https://') {
    $errors.Add("OIDC_ISSUER_URI must use HTTPS.")
}
if ($values["TOSS_CLIENT_KEY"] -and $values["TOSS_CLIENT_KEY"] -notmatch '^live_') {
    $errors.Add("TOSS_CLIENT_KEY must be a Toss live key in production.")
}
if ($values["TOSS_SECRET_KEY"] -and $values["TOSS_SECRET_KEY"] -notmatch '^live_') {
    $errors.Add("TOSS_SECRET_KEY must be a Toss live key in production.")
}
if ($values["TOSS_WEBHOOK_PATH_TOKEN"] -and $values["TOSS_WEBHOOK_PATH_TOKEN"].Length -lt 32) {
    $errors.Add("TOSS_WEBHOOK_PATH_TOKEN must contain at least 32 characters.")
}
if ($values["PAYOUT_TRANSFER_ADAPTER_ENABLED"] -and $values["PAYOUT_TRANSFER_ADAPTER_ENABLED"] -ne "true") {
    $errors.Add("PAYOUT_TRANSFER_ADAPTER_ENABLED must be true in production.")
}
if ($values["PAYOUT_TRANSFER_PROVIDER"] -match '(?i)(LEDGER_ONLY|IMPLEMENTED_PROVIDER_NAME|replace)') {
    $errors.Add("PAYOUT_TRANSFER_PROVIDER must name an implemented external gateway bean.")
}

if ($values["CORS_ALLOWED_ORIGINS"]) {
    foreach ($origin in $values["CORS_ALLOWED_ORIGINS"].Split(",")) {
        $trimmedOrigin = $origin.Trim()
        if ($trimmedOrigin -eq "*" -or $trimmedOrigin -notmatch '^https://') {
            $errors.Add("CORS_ALLOWED_ORIGINS must contain explicit HTTPS origins only.")
            break
        }
    }
}

if ($errors.Count -gt 0) {
    Write-Host "EverySale production environment preflight failed:" -ForegroundColor Red
    foreach ($errorMessage in $errors) {
        Write-Host " - $errorMessage" -ForegroundColor Red
    }
    exit 1
}

Write-Host "EverySale production environment preflight passed. Secret values were not printed." -ForegroundColor Green
