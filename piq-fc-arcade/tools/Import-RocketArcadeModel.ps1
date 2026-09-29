param([Parameter(Mandatory=$true)][string]$ModelArchive)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$fcSource = (Resolve-Path -LiteralPath $ModelArchive).Path
$fcExpected = '1F1A5491A70B2C53F6134E2F08D1130574CE85552F9F340C296B866920009402'
if ((Get-FileHash -LiteralPath $fcSource).Hash -ne $fcExpected) {
    throw 'The reviewed model archive has changed; inspect it before importing.'
}
$fcProject = Split-Path -Parent $PSScriptRoot
$fcAssetRoot = Join-Path $fcProject 'src/main/resources/assets/piq_fc_arcade'
$fcZip = [IO.Compression.ZipFile]::OpenRead($fcSource)
try {
    $fcPrefix = ([char]0x6700).ToString() + ([char]0x7ec8) + ([char]0x6a21) + ([char]0x578b) + ([char]0x5408) + ([char]0x96c6) + '_20260907/'
    # Select the inspected single-player folder only, never extract arbitrary paths.
    $fcJsonEntry = @($fcZip.Entries | Where-Object { $_.FullName -like ($fcPrefix + '02_*/*') -and $_.FullName.EndsWith('/arcade_side_fit.json') -and $_.FullName -notlike '*minecraft_assets*' })
    $fcPngEntry = @($fcZip.Entries | Where-Object { $_.FullName -like ($fcPrefix + '02_*/*') -and $_.FullName.EndsWith('/minecraft_assets/assets/arcade_side_fit/textures/block/skin.png') })
    if ($fcJsonEntry.Count -ne 1 -or $fcPngEntry.Count -ne 1) { throw 'Reviewed single-player assets were not unique.' }
    $fcReader = [IO.StreamReader]::new($fcJsonEntry[0].Open(), [Text.Encoding]::UTF8)
    try { $fcText = $fcReader.ReadToEnd() } finally { $fcReader.Dispose() }
    $fcJson = $fcText | ConvertFrom-Json
    if ($fcJson.elements.Count -ne 155 -or $fcJson.textures.'0' -ne 'arcade_side_fit:block/skin') {
        throw 'Unexpected source model structure.'
    }
    $fcImportedText = $fcText.Replace('arcade_side_fit:block/skin', 'piq_fc_arcade:block/rocket_arcade_skin')
    $fcModelBytes = [Text.UTF8Encoding]::new($false).GetBytes($fcImportedText)
    $fcPngStream = $fcPngEntry[0].Open()
    $fcPngBuffer = [IO.MemoryStream]::new()
    try { $fcPngStream.CopyTo($fcPngBuffer); $fcPngBytes = $fcPngBuffer.ToArray() }
    finally { $fcPngStream.Dispose(); $fcPngBuffer.Dispose() }
    foreach ($fcAsset in @(
        @{Path=(Join-Path $fcAssetRoot 'models/block/rocket_arcade_body.json'); Bytes=$fcModelBytes},
        @{Path=(Join-Path $fcAssetRoot 'textures/block/rocket_arcade_skin.png'); Bytes=$fcPngBytes}
    )) {
        if (Test-Path -LiteralPath $fcAsset.Path) {
            $fcExisting = [Convert]::ToBase64String([IO.File]::ReadAllBytes($fcAsset.Path))
            if ($fcExisting -ne [Convert]::ToBase64String($fcAsset.Bytes)) {
                throw ('Refusing to overwrite a changed imported asset: ' + $fcAsset.Path)
            }
        } else {
            [IO.File]::WriteAllBytes($fcAsset.Path, $fcAsset.Bytes)
        }
        Get-FileHash -LiteralPath $fcAsset.Path
    }
} finally { $fcZip.Dispose() }
