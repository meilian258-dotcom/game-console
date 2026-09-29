param(
    [Parameter(Mandatory=$true)][string]$BuiltJar,
    [Parameter(Mandatory=$true)][string]$AppearanceBaselineJar,
    [Parameter(Mandatory=$true)][string]$DestinationDirectory
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Get-EntryHash($Entry) {
    $stream = $Entry.Open()
    $hash = [Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($hash.ComputeHash($stream)).Replace('-', '') }
    finally { $stream.Dispose(); $hash.Dispose() }
}

$built = (Resolve-Path -LiteralPath $BuiltJar).Path
$baseline = (Resolve-Path -LiteralPath $AppearanceBaselineJar).Path
$expectedBaseline = '05D7F698DEF8A1CD468410BEC3C7F59F4733DAC3F0E3E0D5F5B703B5ABA0D053'
if ((Get-FileHash -LiteralPath $baseline).Hash -ne $expectedBaseline) {
    throw 'Appearance baseline must be the verified, published 0.29.3 JAR.'
}
if (-not (Test-Path -LiteralPath $DestinationDirectory)) {
    New-Item -ItemType Directory -Path $DestinationDirectory | Out-Null
}
$destinationRoot = (Resolve-Path -LiteralPath $DestinationDirectory).Path
$destination = Join-Path $destinationRoot ([IO.Path]::GetFileName($built))
if (Test-Path -LiteralPath $destination) { throw "Candidate already exists: $destination" }
$temporary = Join-Path $destinationRoot ('.fc-candidate-' + [Guid]::NewGuid().ToString('N') + '.jar')

# Keep the user's unpublished controller/model drafts in src/main/resources.
# Only these three known draft entries are overlaid in the candidate archive.
$appearanceEntries = @(
    'assets/piq_fc_arcade/models/block/famicom_console.json',
    'assets/piq_fc_arcade/textures/block/famicom_controller_1.png',
    'assets/piq_fc_arcade/textures/block/famicom_controller_2.png'
)
# The user approved the classic upright model, then its generic skin and seam
# fixes on 2026-09-08. Only the four reviewed cabinet resources may differ.
$approvedRocketAssets = @{
    'assets/piq_fc_arcade/models/block/legacy_fc_arcade.json' = 'EDC3A21C1EC13F5F646FAB221DC0ADF931BD72B27625C91934AC097860605A37'
    'assets/piq_fc_arcade/models/item/legacy_fc_arcade.json' = '69E82C4311CB8020E8D521201787DB00FC6AC7F7EBD4E0198123BB823FF425CE'
    'assets/piq_fc_arcade/models/block/rocket_arcade_body.json' = 'E4BB95E7EBBB6B989070B952E7BA5D90CF1B16D8A00078BEE513A39344ED98F7'
    'assets/piq_fc_arcade/textures/block/rocket_arcade_skin.png' = '789512ED7F867C015C6666D40809845DE430E85834CCF7BA4E48BCA57DE815E8'
}
$old = [IO.Compression.ZipFile]::OpenRead($baseline)
try {
    Copy-Item -LiteralPath $built -Destination $temporary
    $candidate = [IO.Compression.ZipFile]::Open($temporary, [IO.Compression.ZipArchiveMode]::Update)
    try {
        foreach ($name in $appearanceEntries) {
            $sourceEntry = $old.GetEntry($name)
            $existingEntry = $candidate.GetEntry($name)
            if ($null -eq $sourceEntry -or $null -eq $existingEntry) { throw "Missing expected asset: $name" }
            $existingEntry.Delete()
            $outputEntry = $candidate.CreateEntry($name, [IO.Compression.CompressionLevel]::Optimal)
            $inputStream = $sourceEntry.Open()
            $outputStream = $outputEntry.Open()
            try { $inputStream.CopyTo($outputStream) }
            finally { $inputStream.Dispose(); $outputStream.Dispose() }
        }
    } finally { $candidate.Dispose() }

    $candidate = [IO.Compression.ZipFile]::OpenRead($temporary)
    try {
        $verifiedAssets = 0
        foreach ($name in $approvedRocketAssets.Keys) {
            $approved = $candidate.GetEntry($name)
            if ($null -eq $approved -or (Get-EntryHash $approved) -ne $approvedRocketAssets[$name]) {
                throw "Reviewed rocket asset missing or changed: $name"
            }
        }
        foreach ($entry in $old.Entries) {
            if ($entry.FullName.EndsWith('/')) { continue }
            if ($entry.FullName -notmatch '^assets/piq_fc_arcade/(models|textures|blockstates)/') { continue }
            $actual = $candidate.GetEntry($entry.FullName)
            if ($approvedRocketAssets.ContainsKey($entry.FullName)) { continue }
            if ($null -eq $actual -or (Get-EntryHash $actual) -ne (Get-EntryHash $entry)) {
                throw "Unexpected appearance change: $($entry.FullName)"
            }
            $verifiedAssets++
        }
        foreach ($entry in $candidate.Entries) {
            if ($entry.FullName.EndsWith('/')) { continue }
            if ($entry.FullName -notmatch '^assets/piq_fc_arcade/(models|textures|blockstates)/') { continue }
            if ($null -eq $old.GetEntry($entry.FullName) -and -not $approvedRocketAssets.ContainsKey($entry.FullName)) {
                throw "Unreviewed new appearance asset: $($entry.FullName)"
            }
        }
    } finally { $candidate.Dispose() }
    Move-Item -LiteralPath $temporary -Destination $destination
    [pscustomobject]@{
        Candidate = $destination
        Bytes = (Get-Item -LiteralPath $destination).Length
        SHA256 = (Get-FileHash -LiteralPath $destination).Hash
        AppearanceBaselineSHA256 = $expectedBaseline
        VerifiedAppearanceAssets = $verifiedAssets
        ApprovedRocketAssets = $approvedRocketAssets
        RestoredPublishedAssets = $appearanceEntries
        Status = 'packaged-awaiting-final-runtime-smoke'
    } | ConvertTo-Json -Depth 3
} finally {
    $old.Dispose()
    # On failure preserve the temporary archive for inspection; never replace
    # the previous published JAR or delete any source/model draft.
}
