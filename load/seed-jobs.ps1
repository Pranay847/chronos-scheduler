<#
.SYNOPSIS
    Seeds N one-time jobs due over a window, then reports the drift the scheduler measured.

.DESCRIPTION
    The minimal load harness, deliberately built in Phase 2 rather than Phase 8.

    The build plan's own advice is to build the benchmark harness early and not at the end, and
    then schedules load testing as phase 8 of 10 — by which point every number the project claims
    depends on a tool that does not exist yet. This script is the smallest thing that closes that
    gap: it produces real drift data on day one, so every later phase has a before/after signal
    instead of a promise.

    It is not a substitute for the k6 suite in Phase 8. It has no ramp, no VU model, and no
    thresholds. What it does have is the property that matters right now: the numbers come out of
    the service's own histogram, not out of a stopwatch in the harness.

.PARAMETER Count
    How many jobs to create.

.PARAMETER WithinSeconds
    Spread the jobs' scheduled times evenly across this many seconds from now. Use a small value
    to create a thundering herd, a large one to model steady arrival.

.PARAMETER BaseUrl
    Chronos base URL.

.PARAMETER SinkUrl
    Where the webhooks should be delivered. Anything that returns 2xx quickly works.

.EXAMPLE
    .\seed-jobs.ps1 -Count 500 -WithinSeconds 30
#>
[CmdletBinding()]
param(
    [int]$Count = 100,
    [int]$WithinSeconds = 10,
    [string]$BaseUrl = "http://localhost:8080",
    [string]$SinkUrl = "http://localhost:8080/actuator/health"
)

$ErrorActionPreference = "Stop"

Write-Host "Seeding $Count jobs across ${WithinSeconds}s -> $SinkUrl" -ForegroundColor Cyan

$start = Get-Date
$created = 0
$failed = 0

for ($i = 0; $i -lt $Count; $i++) {
    # Spread scheduled times evenly. All jobs due at the same instant is a valid test too
    # (-WithinSeconds 0) but it measures claim contention rather than steady-state drift.
    $offsetMs = if ($Count -le 1) { 0 } else { [int](($WithinSeconds * 1000.0) * $i / $Count) }
    $runAt = (Get-Date).ToUniversalTime().AddMilliseconds($offsetMs).ToString("yyyy-MM-ddTHH:mm:ss.fffZ")

    $body = @{
        name     = "seed-$i"
        schedule = @{ type = "ONE_TIME"; runAt = $runAt }
        target   = @{ url = $SinkUrl; method = "POST"; payload = @{ n = $i } }
    } | ConvertTo-Json -Depth 5 -Compress

    try {
        Invoke-RestMethod -Uri "$BaseUrl/v1/jobs" -Method Post -Body $body -ContentType "application/json" | Out-Null
        $created++
    } catch {
        $failed++
        if ($failed -le 3) { Write-Warning "create failed: $($_.Exception.Message)" }
    }
}

$elapsed = ((Get-Date) - $start).TotalSeconds
Write-Host ("Created {0} jobs ({1} failed) in {2:N1}s" -f $created, $failed, $elapsed) -ForegroundColor Green

$settle = $WithinSeconds + 10
Write-Host "Waiting ${settle}s for the backlog to drain..." -ForegroundColor Cyan
Start-Sleep -Seconds $settle

# Read the drift straight out of the service's own histogram. Buckets, not percentiles - the
# scrape has to stay aggregatable once there is more than one worker.
Write-Host "`n--- scheduler drift (from /actuator/prometheus) ---" -ForegroundColor Cyan
try {
    $scrape = Invoke-RestMethod -Uri "$BaseUrl/actuator/prometheus" -Method Get

    $buckets = $scrape -split "`n" | Where-Object { $_ -like "scheduler_drift_seconds_bucket*" }
    if (-not $buckets) {
        Write-Warning "No scheduler_drift_seconds_bucket found. Is management.prometheus.metrics.export.enabled=true?"
    } else {
        $buckets | ForEach-Object {
            if ($_ -match 'le="([^"]+)"\}\s+([0-9.]+)') {
                $le = $matches[1]; $n = [double]$matches[2]
                if ($le -ne "+Inf") { "{0,10}s  {1,8:N0}" -f $le, $n } else { "{0,10}   {1,8:N0}" -f "total", $n }
            }
        }
    }

    $scrape -split "`n" |
        Where-Object { $_ -like "scheduler_delivery_total*" -or $_ -like "scheduler_jobs_due_depth*" -or $_ -like "scheduler_lease_lost_total*" } |
        ForEach-Object { Write-Host $_ }
} catch {
    Write-Warning "Could not scrape metrics: $($_.Exception.Message)"
}

Write-Host "`nFor a true p99 across workers, query Prometheus:" -ForegroundColor DarkGray
Write-Host '  histogram_quantile(0.99, sum(rate(scheduler_drift_seconds_bucket[5m])) by (le))' -ForegroundColor DarkGray
