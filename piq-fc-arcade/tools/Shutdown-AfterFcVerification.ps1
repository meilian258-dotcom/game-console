param(
    [Parameter(Mandatory=$true)][string]$CandidatePath,
    [Parameter(Mandatory=$true)][ValidatePattern('^[A-Fa-f0-9]{64}$')][string]$ExpectedSHA256,
    [ValidateRange(15,120)][int]$DelaySeconds = 60
)
$ErrorActionPreference = 'Stop'
$fcCandidate = (Resolve-Path -LiteralPath $CandidatePath).Path
if ((Get-FileHash -LiteralPath $fcCandidate -Algorithm SHA256).Hash -ne $ExpectedSHA256) {
    throw 'Verified FC candidate has changed. Shutdown cancelled.'
}
Start-Sleep -Seconds $DelaySeconds
if ((Get-FileHash -LiteralPath $fcCandidate -Algorithm SHA256).Hash -ne $ExpectedSHA256) {
    throw 'Verified FC candidate changed during the delay. Shutdown cancelled.'
}
# A positive shutdown.exe /t implicitly forces application closure. Delay here
# instead, then request a non-forced shutdown so unsaved applications may block it.
& "$env:SystemRoot\System32\shutdown.exe" /s /t 0 /d p:0:0 /c 'FC candidate verification completed; shutdown requested by user.'
if ($LASTEXITCODE -ne 0) { throw "Windows shutdown returned $LASTEXITCODE" }
