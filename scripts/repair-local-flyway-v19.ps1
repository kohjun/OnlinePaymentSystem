[CmdletBinding()]
param(
    [switch] $Apply
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$container = "payment-postgres"
$database = "payment"
$databaseUser = "payment"
$expectedAppliedChecksum = "504217666"
$resolvedChecksum = "67518303"
$requiredConstraintCount = 11

function Invoke-Docker {
    param([Parameter(Mandatory = $true)] [string[]] $Arguments)

    $output = @(& docker @Arguments)
    if ($LASTEXITCODE -ne 0) {
        throw "Docker command failed: docker $($Arguments -join ' ')"
    }
    return $output
}

function Invoke-ScalarQuery {
    param([Parameter(Mandatory = $true)] [string] $Sql)

    $output = Invoke-Docker @(
        "exec", $container,
        "psql", "-U", $databaseUser, "-d", $database,
        "-t", "-A", "-v", "ON_ERROR_STOP=1", "-c", $Sql
    )
    return ($output -join "`n").Trim()
}

$running = Invoke-Docker @("inspect", "-f", "{{.State.Running}}", $container)
if (($running -join "").Trim() -ne "true") {
    throw "$container is not running. Start the local Docker Compose stack first."
}

$checksum = Invoke-ScalarQuery "SELECT checksum FROM flyway_schema_history WHERE version='19' AND success=true;"
if ($checksum -ne $expectedAppliedChecksum -and $checksum -ne $resolvedChecksum) {
    throw "Unexpected V19 checksum '$checksum'. Expected '$expectedAppliedChecksum' or '$resolvedChecksum'. No changes were made."
}

$constraintCountSql = @"
SELECT count(*)
FROM pg_constraint
WHERE conname IN (
  'uq_auction_settlement_sale_event',
  'uq_raffle_winner_sale_event_customer',
  'chk_auction_bid_amount_positive',
  'chk_auction_bid_status',
  'chk_auction_settlement_amount_nonnegative',
  'chk_auction_settlement_status',
  'chk_raffle_entry_status',
  'chk_raffle_winner_checkout_status',
  'chk_sale_event_realtime_price_nonnegative',
  'chk_sale_event_realtime_stock_positive',
  'chk_sale_event_realtime_status'
);
"@
$constraintCount = [int](Invoke-ScalarQuery $constraintCountSql)
if ($constraintCount -ne $requiredConstraintCount) {
    throw "V19 schema verification failed: found $constraintCount of $requiredConstraintCount required constraints. No changes were made."
}

$indexDefinition = Invoke-ScalarQuery "SELECT indexdef FROM pg_indexes WHERE indexname='idx_sale_events_due_auction';"
if (-not $indexDefinition) {
    throw "V19 schema verification failed: idx_sale_events_due_auction is missing. No changes were made."
}

Write-Host "V19 history and schema preconditions passed." -ForegroundColor Green
Write-Host "Current checksum: $checksum" -ForegroundColor DarkGray
Write-Host "Required constraints: $constraintCount/$requiredConstraintCount" -ForegroundColor DarkGray
Write-Host "Apply requested: $Apply" -ForegroundColor DarkGray

if (-not $Apply) {
    Write-Host "Dry run completed. Re-run with -Apply to create a backup and repair the local schema history." -ForegroundColor Yellow
    exit 0
}

if ($checksum -eq $resolvedChecksum -and $indexDefinition -notmatch "\sWHERE\s") {
    Write-Host "Local V19 repair is already complete." -ForegroundColor Green
    exit 0
}

$backupDirectory = Join-Path $repoRoot "backups\local-db"
New-Item -ItemType Directory -Path $backupDirectory -Force | Out-Null
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$containerBackup = "/tmp/everysale-before-v19-repair-$timestamp.dump"
$hostBackup = Join-Path $backupDirectory "everysale-before-v19-repair-$timestamp.dump"

Invoke-Docker @(
    "exec", $container,
    "pg_dump", "-U", $databaseUser, "-d", $database,
    "--format=custom", "--file=$containerBackup"
) | Out-Null
Invoke-Docker @("cp", "${container}:${containerBackup}", $hostBackup) | Out-Null

if (-not (Test-Path -LiteralPath $hostBackup) -or (Get-Item -LiteralPath $hostBackup).Length -le 0) {
    throw "Database backup verification failed. No schema history changes were attempted."
}
Write-Host "Backup created: $hostBackup" -ForegroundColor Green

$repairSql = @'
BEGIN;
DROP INDEX IF EXISTS idx_sale_events_due_auction;
CREATE INDEX idx_sale_events_due_auction
    ON sale_events(sale_type, status, ends_at);
UPDATE flyway_schema_history
SET checksum = 67518303
WHERE version = '19'
  AND checksum = 504217666
  AND success = true;
COMMIT;
'@

Invoke-Docker @(
    "exec", $container,
    "psql", "-U", $databaseUser, "-d", $database,
    "-v", "ON_ERROR_STOP=1", "-c", $repairSql
) | Out-Null

$updatedChecksum = Invoke-ScalarQuery "SELECT checksum FROM flyway_schema_history WHERE version='19' AND success=true;"
$updatedIndex = Invoke-ScalarQuery "SELECT indexdef FROM pg_indexes WHERE indexname='idx_sale_events_due_auction';"
if ($updatedChecksum -ne $resolvedChecksum -or $updatedIndex -match "\sWHERE\s") {
    throw "Post-repair verification failed. Restore from $hostBackup before continuing."
}

Write-Host "Local Flyway V19 repair completed and verified." -ForegroundColor Green
