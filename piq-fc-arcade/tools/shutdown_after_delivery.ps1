param(
    [Parameter(Mandatory = $true)][string]$Archive,
    [Parameter(Mandatory = $true)][string]$ExpectedSha256,
    [ValidateRange(10, 30)][int]$DelaySeconds = 15
)
$ErrorActionPreference = 'Stop'
# User explicitly requested normal shutdown after delivery. No force-close flag.
$deliveryFile = Get-Item -LiteralPath $Archive
if ($deliveryFile.PSIsContainer -or $deliveryFile.Extension -ne '.zip') { throw 'Not a delivery ZIP' }
$verificationFile = [IO.Path]::ChangeExtension($deliveryFile.FullName, '.verification.json')
$deliveryVerification = Get-Content -LiteralPath $verificationFile -Raw | ConvertFrom-Json
if (-not $deliveryVerification.ok -or $deliveryVerification.check_only -or $deliveryVerification.sha256 -ne $ExpectedSha256) {
    throw 'Delivery verification failed'
}
if ((Get-FileHash -LiteralPath $deliveryFile.FullName -Algorithm SHA256).Hash -ne $ExpectedSha256) { throw 'ZIP hash mismatch' }
$shutdownRecord = [IO.Path]::ChangeExtension($deliveryFile.FullName, '.shutdown.json')
if (Test-Path -LiteralPath $shutdownRecord) { throw 'Shutdown already attempted; refusing replay' }
$shutdownState = [ordered]@{
    authorization = 'User requested shutdown after all work is completed'
    package = $deliveryFile.FullName
    package_sha256 = $ExpectedSha256
    scheduled_at = (Get-Date).ToString('o')
    delay_seconds = $DelaySeconds
    force_close = $false
    status = 'scheduled'
}
[IO.File]::WriteAllText($shutdownRecord, ($shutdownState | ConvertTo-Json))
Start-Sleep -Seconds $DelaySeconds
# Do not let a new game started during the short handoff window lose its world.
$minecraftProcesses = @(Get-CimInstance Win32_Process | Where-Object {
    $_.Name -match '^javaw?\.exe$' -and $_.CommandLine -match 'net\.minecraft|cpw\.mods\.bootstraplauncher|neoforgeclient|minecraftclient'
})
if ($minecraftProcesses.Count -gt 0) {
    $shutdownState.status = 'cancelled_minecraft_running'
    [IO.File]::WriteAllText($shutdownRecord, ($shutdownState | ConvertTo-Json))
    exit 2
}
if ((Get-FileHash -LiteralPath $deliveryFile.FullName -Algorithm SHA256).Hash -ne $ExpectedSha256) { throw 'Delivery changed before shutdown' }
$shutdownState.status = 'requesting_normal_shutdown'
$shutdownState.requested_at = (Get-Date).ToString('o')
[IO.File]::WriteAllText($shutdownRecord, ($shutdownState | ConvertTo-Json))
# /t > 0 implies /f on Windows. Delay above, then /t 0, to preserve blockers.
& "$env:SystemRoot\System32\shutdown.exe" /s /t 0
$shutdownState.command_exit_code = $LASTEXITCODE
$shutdownState.status = if ($LASTEXITCODE -eq 0) { 'normal_shutdown_requested' } else { 'shutdown_request_failed' }
[IO.File]::WriteAllText($shutdownRecord, ($shutdownState | ConvertTo-Json))
exit $LASTEXITCODE
