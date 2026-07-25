[CmdletBinding()]
param(
    [string] $BaseUrl = "http://localhost:8080",
    [string] $TenantId = "everysale-demo",
    [string] $PartnerId = "demo-partner",
    [ValidateSet("FIXED_PRICE", "AUCTION", "RAFFLE")]
    [string] $SaleType = "FIXED_PRICE",
    [switch] $SkipPaymentIntent,
    [switch] $DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$runId = ([guid]::NewGuid().ToString("N")).Substring(0, 10).ToUpperInvariant()
$sellerUserId = "USER-SMOKE-$runId"
$sellerCustomerId = "CUST-SMOKE-$runId"
$adminUserId = "ADMIN-SMOKE"

function New-Headers {
    param(
        [Parameter(Mandatory = $true)] [string] $UserId,
        [Parameter(Mandatory = $true)] [string] $CustomerId,
        [Parameter(Mandatory = $true)] [string] $Roles
    )

    return @{
        "X-Tenant-Id" = $TenantId
        "X-Partner-Id" = $PartnerId
        "X-Correlation-Id" = "SMOKE-$runId"
        "X-EverySale-User-Id" = $UserId
        "X-EverySale-Customer-Id" = $CustomerId
        "X-EverySale-Roles" = $Roles
    }
}

function New-DryRunResponse {
    param([string] $Path)

    if ($Path -eq "/api/me/seller-profile") {
        return [pscustomobject]@{ sellerId = "SELLER-DRYRUN" }
    }
    if ($Path -eq "/api/c2c/seller/payout-account") {
        return [pscustomobject]@{ payoutAccountId = "PACCT-DRYRUN"; sellerId = "SELLER-DRYRUN" }
    }
    if ($Path -eq "/api/c2c/listings") {
        return [pscustomobject]@{ listingId = "LIST-DRYRUN"; productId = "PROD-DRYRUN" }
    }
    if ($Path -like "/api/c2c/listings/*/sale-events") {
        return [pscustomobject]@{ saleEventId = "EVT-DRYRUN" }
    }
    if ($Path -like "*/checkout/toss/intents") {
        return [pscustomobject]@{ intentId = "INTENT-DRYRUN"; orderId = "ORDER-DRYRUN"; status = "READY" }
    }
    if ($Path -like "/api/admin/operations/queues?*") {
        return [pscustomobject]@{ totalOpen = 0; queues = @() }
    }
    if ($Path -like "/api/admin/operations/audit?*") {
        return [pscustomobject]@{ events = @() }
    }
    return [pscustomobject]@{ status = "DRY_RUN" }
}

function Invoke-EverySaleApi {
    param(
        [Parameter(Mandatory = $true)] [ValidateSet("GET", "POST", "PATCH", "DELETE")] [string] $Method,
        [Parameter(Mandatory = $true)] [string] $Path,
        [Parameter(Mandatory = $true)] [hashtable] $Headers,
        [object] $Body
    )

    $uri = "$($BaseUrl.TrimEnd('/'))$Path"
    if ($DryRun) {
        Write-Host "DRY  $Method $Path" -ForegroundColor DarkCyan
        if ($null -ne $Body) {
            Write-Host ($Body | ConvertTo-Json -Depth 10 -Compress) -ForegroundColor DarkGray
        }
        return New-DryRunResponse $Path
    }

    Write-Host "CALL $Method $Path" -ForegroundColor Cyan
    $parameters = @{
        Method = $Method
        Uri = $uri
        Headers = $Headers
        UseBasicParsing = $true
    }
    if ($null -ne $Body) {
        $parameters.ContentType = "application/json; charset=utf-8"
        $parameters.Body = $Body | ConvertTo-Json -Depth 10
    }

    try {
        return Invoke-RestMethod @parameters
    } catch {
        $details = $_.Exception.Message
        if ($_.ErrorDetails -and $_.ErrorDetails.Message) {
            $details = $_.ErrorDetails.Message
        }
        throw "EverySale API failed: $Method $Path`n$details"
    }
}

function Invoke-Step {
    param(
        [Parameter(Mandatory = $true)] [string] $Name,
        [Parameter(Mandatory = $true)] [scriptblock] $Action
    )

    Write-Host ""
    Write-Host "==> $Name" -ForegroundColor Yellow
    $result = & $Action
    Write-Host "PASS $Name" -ForegroundColor Green
    return $result
}

$sellerHeaders = New-Headers $sellerUserId $sellerCustomerId "CUSTOMER,SELLER"
$adminHeaders = New-Headers $adminUserId "CUST-ADMIN-SMOKE" "ADMIN"

Invoke-Step "Service health" {
    Invoke-EverySaleApi "GET" "/api/system/health" $sellerHeaders
} | Out-Null

$readiness = Invoke-Step "Distribution readiness report" {
    Invoke-EverySaleApi "GET" "/api/system/readiness" $sellerHeaders
}

$seller = Invoke-Step "Create seller profile" {
    Invoke-EverySaleApi "POST" "/api/me/seller-profile" $sellerHeaders @{
        displayName = "EverySale Smoke Seller $runId"
    }
}

Invoke-Step "Submit seller verification" {
    Invoke-EverySaleApi "POST" "/api/c2c/seller/verification" $sellerHeaders @{
        evidenceRef = "smoke://seller-verification/$runId"
        note = "Automated commercial smoke verification"
    }
} | Out-Null

Invoke-Step "Approve seller verification" {
    Invoke-EverySaleApi "POST" "/api/admin/operations/queues/sellerVerifications/items/$($seller.sellerId)/actions/approve" $adminHeaders @{
        note = "Smoke test seller approval"
    }
} | Out-Null

$payoutAccount = Invoke-Step "Submit payout account token" {
    Invoke-EverySaleApi "POST" "/api/c2c/seller/payout-account" $sellerHeaders @{
        accountRef = "vault://smoke/$runId"
        bankCode = "088"
        bankName = "Every Bank"
        accountHolderName = "EverySale Seller"
        accountLast4 = "1049"
        note = "Tokenized smoke payout account"
    }
}

Invoke-Step "Approve payout account" {
    Invoke-EverySaleApi "POST" "/api/admin/operations/queues/payoutAccounts/items/$($seller.sellerId)/actions/approve" $adminHeaders @{
        note = "Smoke test payout account approval"
    }
} | Out-Null

$listing = Invoke-Step "Create C2C draft listing" {
    Invoke-EverySaleApi "POST" "/api/c2c/listings" $sellerHeaders @{
        name = "Commercial $SaleType Item $runId"
        description = "EverySale end-to-end commercial $SaleType smoke listing"
        price = 25000
        category = "C2C"
        quantity = 1
        itemCondition = "GOOD"
        brand = "EverySale Test"
        authenticityNote = "Smoke fixture; not a real item"
        defectDescription = "None"
    }
}

Invoke-Step "Submit listing for review" {
    Invoke-EverySaleApi "POST" "/api/c2c/listings/$($listing.listingId)/submit" $sellerHeaders
} | Out-Null

Invoke-Step "Approve listing" {
    Invoke-EverySaleApi "POST" "/api/admin/operations/queues/listingReviews/items/$($listing.listingId)/actions/approve" $adminHeaders @{
        note = "Smoke test listing approval"
    }
} | Out-Null

$saleEventBody = @{
    saleType = $SaleType
    price = 25000
    stockQuantity = 1
    publishImmediately = $false
    startsAt = (Get-Date).AddMinutes(-1).ToString("yyyy-MM-ddTHH:mm:ss")
    endsAt = (Get-Date).AddHours(2).ToString("yyyy-MM-ddTHH:mm:ss")
}
if ($SaleType -eq "AUCTION") {
    $saleEventBody.minBidIncrement = 1000
    $saleEventBody.reservePrice = 25000
}

$saleEvent = Invoke-Step "Create $SaleType sale event" {
    Invoke-EverySaleApi "POST" "/api/c2c/listings/$($listing.listingId)/sale-events" $sellerHeaders $saleEventBody
}

Invoke-Step "Publish sale event" {
    Invoke-EverySaleApi "POST" "/api/c2c/sale-events/$($saleEvent.saleEventId)/publish" $sellerHeaders
} | Out-Null

Invoke-Step "Verify public marketplace event" {
    Invoke-EverySaleApi "GET" "/api/marketplace/events/$($saleEvent.saleEventId)" $sellerHeaders
} | Out-Null

$intent = $null
if (-not $SkipPaymentIntent -and $SaleType -eq "FIXED_PRICE") {
    $intent = Invoke-Step "Create Toss checkout intent" {
        Invoke-EverySaleApi "POST" "/api/marketplace/events/$($saleEvent.saleEventId)/checkout/toss/intents" $sellerHeaders @{
            customerId = $sellerCustomerId
            quantity = 1
            idempotencyKey = "SMOKE-INTENT-$runId"
            correlationId = "SMOKE-COR-$runId"
            clientId = "commercial-smoke"
            shippingInfo = @{
                recipientName = "EverySale Smoke Buyer"
                postalCode = "04524"
                address = "Smoke test address"
                method = "PARCEL"
                contactPhone = "010-0000-0000"
            }
            paymentInfo = @{
                amount = 25000
                currency = "KRW"
                paymentMethod = "CREDIT_CARD"
                merchantId = $seller.sellerId
                orderName = "Commercial $SaleType Item $runId"
            }
        }
    }
}

$queues = Invoke-Step "Inspect operations queues" {
    Invoke-EverySaleApi "GET" "/api/admin/operations/queues?limit=5" $adminHeaders
}

$audit = Invoke-Step "Inspect operation audit trail" {
    Invoke-EverySaleApi "GET" "/api/admin/operations/audit?limit=20" $adminHeaders
}

Write-Host ""
Write-Host "EverySale C2C commercial smoke completed." -ForegroundColor Green
Write-Host "runId=$runId"
Write-Host "saleType=$SaleType"
Write-Host "sellerId=$($seller.sellerId)"
Write-Host "payoutAccountId=$($payoutAccount.payoutAccountId)"
Write-Host "listingId=$($listing.listingId)"
Write-Host "saleEventId=$($saleEvent.saleEventId)"
if ($null -ne $intent) {
    Write-Host "tossIntentId=$($intent.intentId)"
    Write-Host "tossOrderId=$($intent.orderId)"
}
if ($null -ne $readiness.status) {
    Write-Host "readiness=$($readiness.status)"
}
if ($null -ne $queues.totalOpen) {
    Write-Host "operationsOpen=$($queues.totalOpen)"
}
if ($audit.PSObject.Properties.Name -contains "events") {
    Write-Host "auditEvents=$(@($audit.events).Count)"
}
