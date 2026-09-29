param(
    [Parameter(Mandatory=$true)][string]$BuiltJar,
    [Parameter(Mandatory=$true)][string]$AppearanceBaselineJar,
    [Parameter(Mandatory=$true)][string]$DestinationDirectory,
    [ValidateSet('alpha1', 'alpha2', 'alpha3', 'alpha4', 'alpha5', 'alpha6', 'alpha7', 'alpha8', 'alpha9', 'alpha10', 'alpha11', 'alpha12', 'alpha13', 'alpha14', 'alpha15')][string]$Review = 'alpha1',
    [string]$ReviewedManifestSHA256
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$baselineHash = '253D0F6433F2BA901447E05C9A0FB5183ACAB911BD560FCD8BBBFF2998619503'
$manifestHash = '161C254C631148DBF6567E630E5D3E47ABDBF8BC2419B66655DA075300DFAC00'
$manifestPath = Join-Path $PSScriptRoot 'home-fc-reviewed-assets.json'
$expectedAssetCount = 22
if ($Review -eq 'alpha2') {
    $manifestHash = '1D928AB5673508728FDBBCB15BE145A35796982994ED0CDF9ECCFA029A6EF33A'
    $manifestPath = Join-Path $PSScriptRoot 'home-fc-alpha2-final-reviewed-assets.json'
    $expectedAssetCount = 25
}
if ($Review -eq 'alpha3') {
    $manifestHash = '79546C62BB8D3A0282DF9AF16D870AE190CDA4DFD3D420916B8909EE6011C5F5'
    $manifestPath = Join-Path $PSScriptRoot 'home-fc-alpha3-final-reviewed-assets.json'
    $expectedAssetCount = 32
}
if ($Review -eq 'alpha4') {
    $manifestHash = '0CC1BF7B6D1EA7CF74E9F265487F79978868534848A99C3CE4E291F974A9B8C5'
    if ($ReviewedManifestSHA256 -and $ReviewedManifestSHA256 -ne $manifestHash) {
        throw 'The optional alpha4 manifest SHA must match the immutable built-in frozen review.'
    }
    $manifestPath = Join-Path $PSScriptRoot 'home-fc-alpha4-final-reviewed-assets.json'
    $expectedAssetCount = 32
} elseif ($Review -notin @('alpha5','alpha6','alpha7','alpha8','alpha9','alpha10','alpha11','alpha12','alpha13','alpha14','alpha15') -and $ReviewedManifestSHA256) {
    throw 'Prior reviews use immutable built-in manifest hashes; do not override them.'
}
if ($Review -eq 'alpha5') {
    $manifestHash = 'A7B169ED12C753154BF9C4B607EDD5E9D14B6447506B11F41948D41C8A69BD93'
    if ($ReviewedManifestSHA256 -and $ReviewedManifestSHA256 -ne $manifestHash) {
        throw 'The optional alpha5 hash must match the immutable frozen review.'
    }
    $manifestPath = Join-Path $PSScriptRoot 'home-fc-alpha5-final-reviewed-assets.json'
    $expectedAssetCount = 37
}
if ($Review -eq 'alpha6') {
    $manifestHash = '39783A052DA1AAC658A3C2597CB1A2BCD91390C4E6FA31740BEDC044DC8ED297'
    if ($ReviewedManifestSHA256 -and $ReviewedManifestSHA256 -ne $manifestHash) {
        throw 'The optional alpha6 hash must match the immutable frozen review.'
    }
    $manifestPath = Join-Path $PSScriptRoot 'home-fc-alpha6-final-reviewed-assets.json'
    $expectedAssetCount = 47
}
if ($Review -eq 'alpha7') {
    $manifestHash = '0984715042265467B50251EC043A0E77832C8DA8563A53D79894B5EDCD27AF32'
    if ($ReviewedManifestSHA256 -and $ReviewedManifestSHA256 -ne $manifestHash) {
        throw 'The optional alpha7 hash must match the immutable frozen review.'
    }
    $manifestPath = Join-Path $PSScriptRoot 'home-fc-alpha7-final-reviewed-assets.json'
    $expectedAssetCount = 47
}
if ($Review -eq 'alpha8') {
    $manifestHash = '4CE2ABC1732C3909D75341F852D20332DDE29A27EB0E0961F50AB8553EC7BFE7'
    if ($ReviewedManifestSHA256 -and $ReviewedManifestSHA256 -ne $manifestHash) {
        throw 'The optional alpha8 hash must match the immutable frozen review.'
    }
    $manifestPath = Join-Path $PSScriptRoot 'home-fc-alpha8-final-reviewed-assets.json'
    $expectedAssetCount = 47
}
if ($Review -eq 'alpha9') {
    $manifestHash = '08E9FFFD36EB13B2A5428305DF543C928FB417A1FF320CCE77663BF0CB2B9B73'
    if ($ReviewedManifestSHA256 -and $ReviewedManifestSHA256 -ne $manifestHash) {
        throw 'The optional alpha9 hash must match the immutable frozen review.'
    }
    $manifestPath = Join-Path $PSScriptRoot 'home-fc-alpha9-final-reviewed-assets-v2.json'
    $expectedAssetCount = 57
}
if ($Review -eq 'alpha10') {
    $manifestHash = '16E55B59247FC4C46A8F82E717EAF9945724165D3BBB74FF96FDCEBB4B599D0E'
    if ($ReviewedManifestSHA256 -and $ReviewedManifestSHA256 -ne $manifestHash) {
        throw 'The optional alpha10 hash must match the immutable frozen review.'
    }
    $manifestPath = Join-Path $PSScriptRoot 'home-fc-alpha10-final-reviewed-assets.json'
    $expectedAssetCount = 64
}
if ($Review -eq 'alpha11') {
    $manifestHash = '45808431141DC018CEA0846CEB073AB1280058CC9783DE29B023CADF1BAE29C4'
    if ($ReviewedManifestSHA256 -and $ReviewedManifestSHA256 -ne $manifestHash) {
        throw 'The optional alpha11 hash must match the immutable frozen review.'
    }
    $manifestPath = Join-Path $PSScriptRoot 'home-fc-alpha11-final-reviewed-assets.json'
    $expectedAssetCount = 64
}
if ($Review -eq 'alpha12') {
    $manifestHash = '1C11E36CA3BE0A5E883A5F3A6C6F5870EABFBFE58D1C4249996725A996A6BB7A'
    if ($ReviewedManifestSHA256 -and $ReviewedManifestSHA256 -ne $manifestHash) {
        throw 'The optional alpha12 hash must match the immutable frozen review.'
    }
    $manifestPath = Join-Path $PSScriptRoot 'home-fc-alpha12-final-reviewed-assets.json'
    $expectedAssetCount = 67
}
if ($Review -in @('alpha13','alpha14','alpha15')) {
    $manifestHash = 'F226112BC14BE3589EBE103BBB986B7238AF44B739A093144A5F7299390A2122'
    if ($ReviewedManifestSHA256 -and $ReviewedManifestSHA256 -ne $manifestHash) {
        throw 'The optional alpha13 hash must match the immutable frozen review.'
    }
    $manifestPath = Join-Path $PSScriptRoot 'home-fc-alpha13-final-reviewed-assets.json'
    $expectedAssetCount = 68
}
if ((Get-FileHash -LiteralPath $manifestPath).Hash -ne $manifestHash) {
    throw 'Home resource review manifest changed; review again before packaging.'
}
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
$approved = @{}
foreach ($property in $manifest.assets.PSObject.Properties) { $approved[$property.Name] = $property.Value }
if ($approved.Count -ne $expectedAssetCount) { throw "Expected exactly $expectedAssetCount reviewed home assets." }
if ($Review -eq 'alpha4') {
    if ($manifest.version -ne '0.31.0-alpha.4' -or $manifest.protocol -ne 24) {
        throw 'The alpha4 manifest must declare version 0.31.0-alpha.4 and protocol 24.'
    }
    $previousPath = Join-Path $PSScriptRoot 'home-fc-alpha3-final-reviewed-assets.json'
    if ((Get-FileHash -LiteralPath $previousPath).Hash -ne '79546C62BB8D3A0282DF9AF16D870AE190CDA4DFD3D420916B8909EE6011C5F5') {
        throw 'Frozen alpha3 review changed.'
    }
    $previous = (Get-Content -LiteralPath $previousPath -Raw | ConvertFrom-Json).assets
    foreach ($property in $previous.PSObject.Properties) {
        if (-not $approved.ContainsKey($property.Name) -or $approved[$property.Name] -notmatch '^[0-9A-Fa-f]{64}$') {
            throw "Missing/invalid inherited reviewed asset: $($property.Name)"
        }
        if ($property.Name -ne 'assets/piq_fc_arcade/models/item/fc_controller.json' -and $approved[$property.Name] -ne $property.Value) {
            throw "Only the alpha4 controller item pose resource may change: $($property.Name)"
        }
    }
}
$built = (Resolve-Path -LiteralPath $BuiltJar).Path
if ($Review -eq 'alpha15') {
    # Core hosting changes no existing geometry, model, texture, or blockstate.
    if ($manifest.version -ne '0.31.0-alpha.13' -or $manifest.protocol -ne 30) { throw 'Invalid inherited appearance review.' }
    if ([IO.Path]::GetFileName($built) -ne 'piq_fc_arcade-0.31.0-alpha.15.jar') { throw 'alpha15 filename mismatch.' }
}
if ($Review -eq 'alpha14') {
    # alpha14 is API-only. Reuse the exact frozen alpha13 appearance manifest, not a new permissive review.
    if ($manifest.version -ne '0.31.0-alpha.13' -or $manifest.protocol -ne 30) { throw 'Invalid inherited alpha13 review.' }
    if ([IO.Path]::GetFileName($built) -ne 'piq_fc_arcade-0.31.0-alpha.14.jar') { throw 'alpha14 filename mismatch.' }
}
if ($Review -eq 'alpha13') {
    if ($manifest.version -ne '0.31.0-alpha.13' -or $manifest.protocol -ne 30) { throw 'Invalid alpha13 version/protocol.' }
    $previousPath = Join-Path $PSScriptRoot 'home-fc-alpha12-final-reviewed-assets.json'
    if ((Get-FileHash -LiteralPath $previousPath).Hash -ne '1C11E36CA3BE0A5E883A5F3A6C6F5870EABFBFE58D1C4249996725A996A6BB7A') {
        throw 'Frozen alpha12 review changed.'
    }
    $previous = (Get-Content -LiteralPath $previousPath -Raw | ConvertFrom-Json).assets
    $preservedCount = 0
    foreach ($property in $previous.PSObject.Properties) {
        if (-not $approved.ContainsKey($property.Name) -or $approved[$property.Name] -ne $property.Value) {
            throw "Unapproved alpha12 appearance change: $($property.Name)"
        }
        $preservedCount++
    }
    if ($preservedCount -ne 67) { throw 'Exactly 67 inherited alpha12 assets must remain unchanged.' }
    $name = 'assets/piq_fc_arcade/models/item/tv_remote.json'
    if (-not $approved.ContainsKey($name) -or $approved[$name] -notmatch '^[0-9A-Fa-f]{64}$') {
        throw 'Missing or invalid new alpha13 remote appearance.'
    }
    if ([IO.Path]::GetFileName($built) -ne 'piq_fc_arcade-0.31.0-alpha.13.jar') { throw 'alpha13 filename mismatch.' }
}
if ($Review -eq 'alpha12') {
    if ($manifest.version -ne '0.31.0-alpha.12' -or $manifest.protocol -ne 29) { throw 'Invalid alpha12 version/protocol.' }
    $previousPath = Join-Path $PSScriptRoot 'home-fc-alpha11-final-reviewed-assets.json'
    if ((Get-FileHash -LiteralPath $previousPath).Hash -ne '45808431141DC018CEA0846CEB073AB1280058CC9783DE29B023CADF1BAE29C4') {
        throw 'Frozen alpha11 review changed.'
    }
    $previous = (Get-Content -LiteralPath $previousPath -Raw | ConvertFrom-Json).assets
    $preservedCount = 0
    foreach ($property in $previous.PSObject.Properties) {
        if (-not $approved.ContainsKey($property.Name) -or $approved[$property.Name] -ne $property.Value) {
            throw "Unapproved alpha11 appearance change: $($property.Name)"
        }
        $preservedCount++
    }
    if ($preservedCount -ne 64) { throw 'Exactly 64 inherited alpha11 assets must remain unchanged.' }
    $newAppearance = @(
        'assets/piq_fc_arcade/models/block/cartridge_computer.json',
        'assets/piq_fc_arcade/models/item/cartridge_computer.json',
        'assets/piq_fc_arcade/blockstates/cartridge_computer.json'
    )
    foreach ($name in $newAppearance) {
        if (-not $approved.ContainsKey($name) -or $approved[$name] -notmatch '^[0-9A-Fa-f]{64}$') {
            throw "Missing or invalid new alpha12 appearance: $name"
        }
    }
    if ([IO.Path]::GetFileName($built) -ne 'piq_fc_arcade-0.31.0-alpha.12.jar') { throw 'alpha12 filename mismatch.' }
}
if ($Review -eq 'alpha11') {
    if ($manifest.version -ne '0.31.0-alpha.11' -or $manifest.protocol -ne 28) { throw 'Invalid alpha11 version/protocol.' }
    $previousPath = Join-Path $PSScriptRoot 'home-fc-alpha10-final-reviewed-assets.json'
    if ((Get-FileHash -LiteralPath $previousPath).Hash -ne '16E55B59247FC4C46A8F82E717EAF9945724165D3BBB74FF96FDCEBB4B599D0E') {
        throw 'Frozen alpha10 review changed.'
    }
    $previous = (Get-Content -LiteralPath $previousPath -Raw | ConvertFrom-Json).assets
    $preservedCount = 0
    $changedCount = 0
    foreach ($property in $previous.PSObject.Properties) {
        if (-not $approved.ContainsKey($property.Name) -or $approved[$property.Name] -notmatch '^[0-9A-Fa-f]{64}$') {
            throw "Missing or invalid inherited alpha10 asset: $($property.Name)"
        }
        if ($property.Name -eq 'assets/piq_fc_arcade/models/block/home_vintage_tv.json') {
            if ($approved[$property.Name] -eq $property.Value) { throw 'The alpha11 Vintage TV body was not updated.' }
            $changedCount++
        } else {
            if ($approved[$property.Name] -ne $property.Value) { throw "Unapproved alpha10 appearance change: $($property.Name)" }
            $preservedCount++
        }
    }
    if ($preservedCount -ne 63 -or $changedCount -ne 1) { throw 'Exactly one Vintage body change and 63 unchanged alpha10 assets are required.' }
    if ([IO.Path]::GetFileName($built) -ne 'piq_fc_arcade-0.31.0-alpha.11.jar') { throw 'alpha11 filename mismatch.' }
}
if ($Review -eq 'alpha10') {
    if ($manifest.version -ne '0.31.0-alpha.10' -or $manifest.protocol -ne 28) { throw 'Invalid alpha10 version/protocol.' }
    $previousPath = Join-Path $PSScriptRoot 'home-fc-alpha9-final-reviewed-assets-v2.json'
    if ((Get-FileHash -LiteralPath $previousPath).Hash -ne '08E9FFFD36EB13B2A5428305DF543C928FB417A1FF320CCE77663BF0CB2B9B73') {
        throw 'Frozen alpha9 v2 review changed.'
    }
    $previous = (Get-Content -LiteralPath $previousPath -Raw | ConvertFrom-Json).assets
    $preservedCount = 0
    foreach ($property in $previous.PSObject.Properties) {
        if (-not $approved.ContainsKey($property.Name) -or $approved[$property.Name] -ne $property.Value) {
            throw "Unapproved alpha9 appearance change: $($property.Name)"
        }
        $preservedCount++
    }
    if ($preservedCount -ne 57) { throw 'Exactly 57 inherited alpha9 assets must remain unchanged.' }
    $newAppearance = @(
        'assets/piq_fc_arcade/models/block/home_large_lcd_tv.json',
        'assets/piq_fc_arcade/models/item/large_lcd_tv.json',
        'assets/piq_fc_arcade/blockstates/large_lcd_tv.json',
        'assets/piq_fc_arcade/blockstates/large_lcd_tv_part.json',
        'assets/piq_fc_arcade/models/block/home_vintage_tv.json',
        'assets/piq_fc_arcade/models/item/vintage_tv.json',
        'assets/piq_fc_arcade/blockstates/vintage_tv.json'
    )
    foreach ($name in $newAppearance) {
        if (-not $approved.ContainsKey($name) -or $approved[$name] -notmatch '^[0-9A-Fa-f]{64}$') {
            throw "Missing or invalid new alpha10 appearance: $name"
        }
    }
    if ([IO.Path]::GetFileName($built) -ne 'piq_fc_arcade-0.31.0-alpha.10.jar') { throw 'alpha10 filename mismatch.' }
}
if ($Review -eq 'alpha9') {
    if ($manifest.version -ne '0.31.0-alpha.9' -or $manifest.protocol -ne 27) { throw 'Invalid alpha9 version/protocol.' }
    $previousPath = Join-Path $PSScriptRoot 'home-fc-alpha8-final-reviewed-assets.json'
    if ((Get-FileHash -LiteralPath $previousPath).Hash -ne '4CE2ABC1732C3909D75341F852D20332DDE29A27EB0E0961F50AB8553EC7BFE7') {
        throw 'Frozen alpha8 review changed.'
    }
    $previous = (Get-Content -LiteralPath $previousPath -Raw | ConvertFrom-Json).assets
    $preservedCount = 0
    foreach ($property in $previous.PSObject.Properties) {
        if (-not $approved.ContainsKey($property.Name) -or $approved[$property.Name] -notmatch '^[0-9A-Fa-f]{64}$') {
            throw "Missing or invalid inherited alpha8 asset: $($property.Name)"
        }
        if ($property.Name -ne 'assets/piq_fc_arcade/models/block/dual_arcade_body.json') {
            if ($approved[$property.Name] -ne $property.Value) { throw "Unapproved alpha8 appearance change: $($property.Name)" }
            $preservedCount++
        }
    }
    if ($preservedCount -ne 46) { throw 'Exactly 46 inherited alpha8 assets must remain unchanged.' }
    $newAppearance = @(
        'assets/piq_fc_arcade/models/block/home_fc_board_0.json',
        'assets/piq_fc_arcade/models/block/home_fc_board_1.json',
        'assets/piq_fc_arcade/models/block/home_fc_board_2.json',
        'assets/piq_fc_arcade/models/block/home_fc_cartridge_shell.json',
        'assets/piq_fc_arcade/models/item/fc_cartridge_board.json',
        'assets/piq_fc_arcade/models/item/fc_cartridge_shell.json',
        'assets/piq_fc_arcade/models/block/home_wide_lcd_tv.json',
        'assets/piq_fc_arcade/models/item/wide_lcd_tv.json',
        'assets/piq_fc_arcade/blockstates/wide_lcd_tv.json',
        'assets/piq_fc_arcade/blockstates/wide_lcd_tv_part.json'
    )
    foreach ($name in $newAppearance) {
        if (-not $approved.ContainsKey($name) -or $approved[$name] -notmatch '^[0-9A-Fa-f]{64}$') {
            throw "Missing or invalid new alpha9 appearance: $name"
        }
    }
    if ([IO.Path]::GetFileName($built) -ne 'piq_fc_arcade-0.31.0-alpha.9.jar') { throw 'alpha9 filename mismatch.' }
}
if ($Review -eq 'alpha8') {
    if ($manifest.version -ne '0.31.0-alpha.8' -or $manifest.protocol -ne 26) { throw 'Invalid alpha8 version/protocol.' }
    $previousPath = Join-Path $PSScriptRoot 'home-fc-alpha7-final-reviewed-assets.json'
    if ((Get-FileHash -LiteralPath $previousPath).Hash -ne '0984715042265467B50251EC043A0E77832C8DA8563A53D79894B5EDCD27AF32') {
        throw 'Frozen alpha7 review changed.'
    }
    $allowedChanges = @('assets/piq_fc_arcade/meshes/home_subor_sb926_wide.json','assets/piq_fc_arcade/models/block/dual_arcade_body.json')
    $previous = (Get-Content -LiteralPath $previousPath -Raw | ConvertFrom-Json).assets
    $preservedCount = 0
    foreach ($property in $previous.PSObject.Properties) {
        if (-not $approved.ContainsKey($property.Name) -or $approved[$property.Name] -notmatch '^[0-9A-Fa-f]{64}$') {
            throw "Missing or invalid inherited alpha7 asset: $($property.Name)"
        }
        if ($property.Name -notin $allowedChanges) {
            if ($approved[$property.Name] -ne $property.Value) { throw "Unapproved alpha7 appearance change: $($property.Name)" }
            $preservedCount++
        }
    }
    if ($preservedCount -ne 45) { throw 'Exactly 45 inherited alpha7 assets must remain unchanged.' }
    if ([IO.Path]::GetFileName($built) -ne 'piq_fc_arcade-0.31.0-alpha.8.jar') { throw 'alpha8 filename mismatch.' }
}
if ($Review -eq 'alpha7') {
    if ($manifest.version -ne '0.31.0-alpha.7' -or $manifest.protocol -ne 26) { throw 'Invalid alpha7 version/protocol.' }
    $previousPath = Join-Path $PSScriptRoot 'home-fc-alpha6-final-reviewed-assets.json'
    if ((Get-FileHash -LiteralPath $previousPath).Hash -ne '39783A052DA1AAC658A3C2597CB1A2BCD91390C4E6FA31740BEDC044DC8ED297') {
        throw 'Frozen alpha6 review changed.'
    }
    $allowedChanges = @('assets/piq_fc_arcade/meshes/home_subor_sb926_wide.json','assets/piq_fc_arcade/models/block/dual_arcade_body.json')
    $previous = (Get-Content -LiteralPath $previousPath -Raw | ConvertFrom-Json).assets
    $preservedCount = 0
    foreach ($property in $previous.PSObject.Properties) {
        if (-not $approved.ContainsKey($property.Name) -or $approved[$property.Name] -notmatch '^[0-9A-Fa-f]{64}$') {
            throw "Missing or invalid inherited alpha6 asset: $($property.Name)"
        }
        if ($property.Name -notin $allowedChanges) {
            if ($approved[$property.Name] -ne $property.Value) { throw "Unapproved alpha6 appearance change: $($property.Name)" }
            $preservedCount++
        }
    }
    if ($preservedCount -ne 45) { throw 'Exactly 45 inherited alpha6 assets must remain unchanged.' }
    if ([IO.Path]::GetFileName($built) -ne 'piq_fc_arcade-0.31.0-alpha.7.jar') { throw 'alpha7 filename mismatch.' }
}
if ($Review -eq 'alpha6') {
    if ($manifest.version -ne '0.31.0-alpha.6' -or $manifest.protocol -ne 26) { throw 'Invalid alpha6 version/protocol.' }
    $previousPath = Join-Path $PSScriptRoot 'home-fc-alpha5-final-reviewed-assets.json'
    if ((Get-FileHash -LiteralPath $previousPath).Hash -ne 'A7B169ED12C753154BF9C4B607EDD5E9D14B6447506B11F41948D41C8A69BD93') {
        throw 'Frozen alpha5 review changed.'
    }
    $previous = (Get-Content -LiteralPath $previousPath -Raw | ConvertFrom-Json).assets
    foreach ($property in $previous.PSObject.Properties) {
        if (-not $approved.ContainsKey($property.Name) -or $approved[$property.Name] -ne $property.Value) {
            throw "Existing alpha5 appearance must not change: $($property.Name)"
        }
    }
    if ([IO.Path]::GetFileName($built) -ne 'piq_fc_arcade-0.31.0-alpha.6.jar') { throw 'alpha6 filename mismatch.' }
}
if ($Review -eq 'alpha5') {
    if ($manifest.version -ne '0.31.0-alpha.5' -or $manifest.protocol -ne 25) { throw 'Invalid alpha5 version/protocol.' }
    $previousPath = Join-Path $PSScriptRoot 'home-fc-alpha4-final-reviewed-assets.json'
    if ((Get-FileHash -LiteralPath $previousPath).Hash -ne '0CC1BF7B6D1EA7CF74E9F265487F79978868534848A99C3CE4E291F974A9B8C5') {
        throw 'Frozen alpha4 review changed.'
    }
    $previous = (Get-Content -LiteralPath $previousPath -Raw | ConvertFrom-Json).assets
    foreach ($property in $previous.PSObject.Properties) {
        if (-not $approved.ContainsKey($property.Name) -or $approved[$property.Name] -ne $property.Value) {
            throw "Existing alpha4 appearance must not change: $($property.Name)"
        }
    }
    if ([IO.Path]::GetFileName($built) -ne 'piq_fc_arcade-0.31.0-alpha.5.jar') { throw 'alpha5 filename mismatch.' }
}
if ($Review -eq 'alpha2' -and [IO.Path]::GetFileName($built) -ne 'piq_fc_arcade-0.31.0-alpha.2.jar') {
    throw 'The alpha2 review is pinned to piq_fc_arcade-0.31.0-alpha.2.jar.'
}
if ($Review -eq 'alpha3' -and [IO.Path]::GetFileName($built) -ne 'piq_fc_arcade-0.31.0-alpha.3.jar') {
    throw 'The alpha3 review is pinned to piq_fc_arcade-0.31.0-alpha.3.jar.'
}
if ($Review -eq 'alpha4' -and [IO.Path]::GetFileName($built) -ne 'piq_fc_arcade-0.31.0-alpha.4.jar') {
    throw 'The alpha4 review is pinned to piq_fc_arcade-0.31.0-alpha.4.jar.'
}
$baseline = (Resolve-Path -LiteralPath $AppearanceBaselineJar).Path
if ((Get-FileHash -LiteralPath $baseline).Hash -ne $baselineHash) {
    throw 'Appearance baseline must be the verified 0.30.0-beta.3 candidate.'
}
if (-not (Test-Path -LiteralPath $DestinationDirectory)) {
    New-Item -ItemType Directory -Path $DestinationDirectory | Out-Null
}
$destinationRoot = (Resolve-Path -LiteralPath $DestinationDirectory).Path
$destination = Join-Path $destinationRoot ([IO.Path]::GetFileName($built))
if (Test-Path -LiteralPath $destination) { throw 'Candidate already exists; never overwrite a delivered JAR.' }
$temporary = Join-Path $destinationRoot ('.home-fc-candidate-' + [Guid]::NewGuid().ToString('N') + '.jar')

function Get-HomeEntryHash($entry) {
    $stream = $entry.Open()
    $sha = [Security.Cryptography.SHA256]::Create()
    try { [BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-', '') }
    finally { $stream.Dispose(); $sha.Dispose() }
}

# This turn explicitly upgrades the console wrapper, but does not release the
# user's previous controller-texture drafts. Restore only those two textures.
$restore = @(
    'assets/piq_fc_arcade/textures/block/famicom_controller_1.png',
    'assets/piq_fc_arcade/textures/block/famicom_controller_2.png'
)
$old = [IO.Compression.ZipFile]::OpenRead($baseline)
try {
    Copy-Item -LiteralPath $built -Destination $temporary
    $candidate = [IO.Compression.ZipFile]::Open($temporary, [IO.Compression.ZipArchiveMode]::Update)
    try {
        foreach ($name in $restore) {
            $original = $old.GetEntry($name)
            $draft = $candidate.GetEntry($name)
            if ($null -eq $original -or $null -eq $draft) { throw "Missing protected texture: $name" }
            $draft.Delete()
            $restored = $candidate.CreateEntry($name, [IO.Compression.CompressionLevel]::Optimal)
            $inputStream = $original.Open(); $outputStream = $restored.Open()
            try { $inputStream.CopyTo($outputStream) }
            finally { $inputStream.Dispose(); $outputStream.Dispose() }
        }
    } finally { $candidate.Dispose() }
    $candidate = [IO.Compression.ZipFile]::OpenRead($temporary)
    try {
        if (@($candidate.Entries | Group-Object FullName | Where-Object Count -gt 1).Count -ne 0) {
            throw 'Duplicate archive entries are forbidden.'
        }
        foreach ($name in $approved.Keys) {
            $entry = $candidate.GetEntry($name)
            if ($null -eq $entry -or (Get-HomeEntryHash $entry) -ne $approved[$name]) {
                throw "Missing or changed reviewed resource: $name"
            }
        }
        $unchanged = 0
        foreach ($entry in $old.Entries) {
            if ($entry.FullName.EndsWith('/') -or $entry.FullName -notmatch '^assets/piq_fc_arcade/(models|textures|blockstates|meshes)/') { continue }
            if ($approved.ContainsKey($entry.FullName)) { continue }
            $actual = $candidate.GetEntry($entry.FullName)
            if ($null -eq $actual -or (Get-HomeEntryHash $actual) -ne (Get-HomeEntryHash $entry)) {
                throw "Unexpected non-home appearance change: $($entry.FullName)"
            }
            $unchanged++
        }
        foreach ($entry in $candidate.Entries) {
            if ($entry.FullName.EndsWith('/') -or $entry.FullName -notmatch '^assets/piq_fc_arcade/(models|textures|blockstates|meshes)/') { continue }
            if ($null -eq $old.GetEntry($entry.FullName) -and -not $approved.ContainsKey($entry.FullName)) {
                throw "Unreviewed new appearance: $($entry.FullName)"
            }
        }
        if ($Review -in @('alpha3', 'alpha4', 'alpha5', 'alpha6', 'alpha7', 'alpha8', 'alpha9', 'alpha10', 'alpha11', 'alpha12', 'alpha13', 'alpha14', 'alpha15') -and $unchanged -ne 43) {
            throw "Expected exactly 43 unchanged non-home appearance assets, found $unchanged."
        }
        $entryCount = $candidate.Entries.Count
    } finally { $candidate.Dispose() }
    Move-Item -LiteralPath $temporary -Destination $destination
    [pscustomobject]@{
        Candidate=$destination; Bytes=(Get-Item -LiteralPath $destination).Length;
        SHA256=(Get-FileHash -LiteralPath $destination).Hash; Entries=$entryCount;
        AppearanceBaselineSHA256=$baselineHash; HomeManifestSHA256=$manifestHash;
        Review=$Review; ReviewedHomeAssets=$approved.Count; UnchangedAppearanceAssets=$unchanged;
        RestoredDraftTextures=$restore; Status='packaged-awaiting-final-runtime-and-resource-checks'
    } | ConvertTo-Json -Depth 3
} finally {
    $old.Dispose()
    # Preserve any incomplete temporary candidate for diagnosis; do not delete
    # or overwrite old JARs, source drafts, ROMs, saves or user models.
}
