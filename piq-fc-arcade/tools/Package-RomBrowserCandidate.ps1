[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$project=Split-Path -Parent $PSScriptRoot
$release=Join-Path (Split-Path -Parent $project) '制作Mod\03-街机模拟\PIQ-FC街机'
$baseline=Join-Path $release 'alpha15-reviewed-v2\piq_fc_arcade-0.31.0-alpha.15.jar'
$built=Join-Path $project 'build\libs\piq_fc_arcade-0.31.0-alpha.16.jar'
$output=Join-Path $release 'alpha16-rom-browser'
$destination=Join-Path $output 'piq_fc_arcade-0.31.0-alpha.16.jar'
function Safe-Path([string]$Path,[string]$Root){
    $absolute=[IO.Path]::GetFullPath($Path);$base=[IO.Path]::GetFullPath($Root).TrimEnd('\')
    if(-not $absolute.StartsWith($base+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Unexpected artifact path'}
    for($cursor=$absolute;$cursor;$cursor=[IO.Path]::GetDirectoryName($cursor)){
        if((Test-Path -LiteralPath $cursor) -and ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)){throw "Linked artifact path: $cursor"}
    }
    return $absolute
}
function Entry-Hash($entry){$stream=$entry.Open();$digest=[Security.Cryptography.SHA256]::Create();try{return [Convert]::ToHexString($digest.ComputeHash($stream))}finally{$stream.Dispose();$digest.Dispose()}}
$null=Safe-Path $baseline $release;$null=Safe-Path $built $project;$null=Safe-Path $destination $release
if((Get-FileHash -LiteralPath $baseline).Hash -ne '7FF788233234A25AA67AAEFFB171E4275C3597452B6C62781508E2EF4A5B6499'){throw 'Frozen alpha15 baseline changed'}
if(Test-Path -LiteralPath $destination){throw 'Refusing to overwrite existing alpha16 candidate'}
[IO.Directory]::CreateDirectory($output)|Out-Null
$temporary=Join-Path $output ('.candidate-'+[guid]::NewGuid().ToString('N')+'.jar')
$null=Safe-Path $temporary $output
Copy-Item -LiteralPath $built -Destination $temporary
$previous=[IO.Compression.ZipFile]::OpenRead($baseline)
try{
    $candidate=[IO.Compression.ZipFile]::Open($temporary,[IO.Compression.ZipArchiveMode]::Update)
    try{
        foreach($name in @('assets/piq_fc_arcade/textures/block/famicom_controller_1.png','assets/piq_fc_arcade/textures/block/famicom_controller_2.png')){
            $original=$previous.GetEntry($name);$draft=$candidate.GetEntry($name)
            if($null -eq $original -or $null -eq $draft){throw "Missing protected texture: $name"}
            $draft.Delete();$entry=$candidate.CreateEntry($name,[IO.Compression.CompressionLevel]::Optimal)
            $input=$original.Open();$target=$entry.Open();try{$input.CopyTo($target)}finally{$input.Dispose();$target.Dispose()}
        }
    }finally{$candidate.Dispose()}
    $candidate=[IO.Compression.ZipFile]::OpenRead($temporary)
    try{
        $seen=[Collections.Generic.HashSet[string]]::new();foreach($entry in $candidate.Entries){if(-not $seen.Add($entry.FullName)){throw "Duplicate JAR entry: $($entry.FullName)"}}
        $count=0
        foreach($entry in $previous.Entries){
            if($entry.FullName -notmatch '^assets/.+/(models|textures|blockstates)/' -or $entry.FullName.EndsWith('/')){continue}
            $actual=$candidate.GetEntry($entry.FullName)
            if($null -eq $actual -or (Entry-Hash $actual) -ne (Entry-Hash $entry)){throw "Protected appearance changed: $($entry.FullName)"}
            $count++
        }
        foreach($entry in $candidate.Entries){if($entry.FullName -match '^assets/.+/(models|textures|blockstates)/' -and -not $entry.FullName.EndsWith('/') -and $null -eq $previous.GetEntry($entry.FullName)){throw 'Unexpected new appearance resource'}}
        $reader=[IO.StreamReader]::new($candidate.GetEntry('META-INF/neoforge.mods.toml').Open())
        try{if($reader.ReadToEnd() -notmatch '0\.31\.0-alpha\.16'){throw 'Candidate metadata version mismatch'}}finally{$reader.Dispose()}
    }finally{$candidate.Dispose()}
    $null=Safe-Path $temporary $output;$null=Safe-Path $destination $output
    if(Test-Path -LiteralPath $destination){throw 'Candidate destination appeared during packaging'}
    Move-Item -LiteralPath $temporary -Destination $destination
    [pscustomobject]@{Path=$destination;SHA256=(Get-FileHash -LiteralPath $destination).Hash;Bytes=(Get-Item -LiteralPath $destination).Length;ProtectedAppearanceEntries=$count;Status='awaiting-independent-final-audit'}|ConvertTo-Json
}finally{$previous.Dispose()}
