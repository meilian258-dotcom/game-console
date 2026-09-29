# User-authorized local copy of exactly two pinned files. No downloads, extraction or execution.
# Default performs read-only preflight. Root must review and explicitly invoke -Apply to copy.
[CmdletBinding()]
param([switch]$Apply)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$instanceRoot = 'F:\Minecraft\HMCL\versions\1.21.1-NeoForge'
$libraryRoot = 'F:\Minecraft\HMCL\versions\1.21.1-NeoForge\piq-native-arcade\roms'
$copyItems = @(
    [pscustomobject]@{
        Name='kof97.zip'
        Source='E:\Dow\rom\neogeo\kof97.zip'
        SourceRoot='E:\Dow\rom\neogeo'
        Sha256='804F892924D4650545D3EA2D19FB85670094DC46DB882FECAF3E03009E2C4B9F'
    },
    [pscustomobject]@{
        Name='neogeo.zip'
        Source='E:\Dow\rom\WinKawaks1.65模拟器\WinKawaks1.65\roms\neogeo\neogeo.zip'
        SourceRoot='E:\Dow\rom\WinKawaks1.65模拟器\WinKawaks1.65\roms\neogeo'
        Sha256='E1FFD4AB180E2F6AA4A3AA4D2C6F991E19D8EF762BEC8B5A6283704A2EDC3BBC'
    }
)

function Safe-Path([string]$Path,[string]$Root,[switch]$AllowRoot) {
    $full=[IO.Path]::GetFullPath($Path)
    $base=[IO.Path]::GetFullPath($Root).TrimEnd('\')
    if(-not (($AllowRoot -and $full.Equals($base,[StringComparison]::OrdinalIgnoreCase)) -or
        $full.StartsWith($base+'\',[StringComparison]::OrdinalIgnoreCase))) { throw "Outside fixed copy scope: $full" }
    for($cursor=$full;$cursor;$cursor=[IO.Path]::GetDirectoryName($cursor)) {
        $node=$null
        try { $node=Get-Item -LiteralPath $cursor -Force -ErrorAction Stop }
        catch [System.Management.Automation.ItemNotFoundException] { }
        if($null -eq $node) { continue }
        if($node.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Reparse/link refused: $cursor" }
        if($cursor -ine $full -and -not $node.PSIsContainer) { throw "Parent is not a directory: $cursor" }
    }
    return $full
}

function Assert-GameExited {
    $java=@(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'")
    if(@($java | Where-Object { [string]::IsNullOrWhiteSpace($_.CommandLine) }).Count) {
        throw 'Cannot inspect a Java process. Close the game and retry; no process was stopped.'
    }
    $active=@($java | Where-Object {
        $_.CommandLine -match '1\.21\.1-NeoForge|net\.minecraft|BootstrapLauncher|modlauncher|--gameDir|cn\.piq\.nativearcade\.bridge\.NativeCoreWorker'
    })
    if($active.Count) { throw 'Minecraft or its native emulator is running. Close it first; no process was stopped.' }
}

function Stream-Sha256([IO.Stream]$Stream) {
    $algorithm=[Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($algorithm.ComputeHash($Stream)).Replace('-','') }
    finally { $algorithm.Dispose() }
}

function File-State([string]$Path,[string]$Root) {
    $full=Safe-Path $Path $Root
    $before=Get-Item -LiteralPath $full -Force
    if($before.PSIsContainer) { throw "Expected a regular file: $full" }
    # Windows sharing excludes writers and deletion during the actual read/hash.
    $stream=[IO.FileStream]::new($full,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read)
    try {
        $digest=Stream-Sha256 $stream
        $null=Safe-Path $full $Root
        $after=Get-Item -LiteralPath $full -Force
        if($before.Length -ne $after.Length -or $stream.Length -ne $after.Length -or
           $before.CreationTimeUtc.Ticks -ne $after.CreationTimeUtc.Ticks -or
           $before.LastWriteTimeUtc.Ticks -ne $after.LastWriteTimeUtc.Ticks) { throw "File changed while hashing: $full" }
        return [pscustomobject]@{Path=$full;Bytes=$after.Length;CreatedUtc=$after.CreationTimeUtc.Ticks;ModifiedUtc=$after.LastWriteTimeUtc.Ticks;Sha256=$digest}
    } finally { $stream.Dispose() }
}

function Same-State($First,$Second) {
    return $First.Bytes -eq $Second.Bytes -and $First.CreatedUtc -eq $Second.CreatedUtc -and
        $First.ModifiedUtc -eq $Second.ModifiedUtc -and $First.Sha256 -eq $Second.Sha256
}

function Prepare-Library {
    $null=Safe-Path $libraryRoot $instanceRoot
    $current=$instanceRoot
    foreach($segment in @('piq-native-arcade','roms')) {
        Assert-GameExited
        $current=Join-Path $current $segment
        $null=Safe-Path $current $instanceRoot
        if(-not (Test-Path -LiteralPath $current)) {
            # Only this validated component is missing; its parent was already validated.
            [IO.Directory]::CreateDirectory($current) | Out-Null
        }
        $null=Safe-Path $current $instanceRoot
        if(-not (Get-Item -LiteralPath $current -Force).PSIsContainer) { throw "Library component is not a directory: $current" }
    }
}

function Stage-PinnedFile($Plan,[string]$Stage) {
    Assert-GameExited
    $from=Safe-Path $Plan.Item.Source $Plan.Item.SourceRoot
    $to=Safe-Path $Stage $libraryRoot
    if(Test-Path -LiteralPath $to) { throw "Refusing to overwrite staging path: $to" }
    if(-not (Same-State $Plan.SourceBefore (File-State $from $Plan.Item.SourceRoot))) { throw "Source changed after preflight: $from" }
    $sourceStream=[IO.FileStream]::new($from,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read)
    try {
        if((Stream-Sha256 $sourceStream) -cne $Plan.Item.Sha256) { throw "Source hash changed before copy: $from" }
        $sourceStream.Position=0
        $stageStream=[IO.FileStream]::new($to,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
        try { $sourceStream.CopyTo($stageStream,65536); $stageStream.Flush($true) }
        finally { $stageStream.Dispose() }
        $sourceStream.Position=0
        if((Stream-Sha256 $sourceStream) -cne $Plan.Item.Sha256) { throw "Source hash changed during copy: $from" }
    } finally { $sourceStream.Dispose() }
    $staged=File-State $to $libraryRoot
    if($staged.Sha256 -cne $Plan.Item.Sha256 -or $staged.Bytes -ne $Plan.SourceBefore.Bytes) { throw "Staged checksum/size mismatch: $to" }
    if(-not (Same-State $Plan.SourceBefore (File-State $from $Plan.Item.SourceRoot))) { throw "Original source changed after copy: $from" }
    return $staged
}

$plans=[Collections.Generic.List[object]]::new()
$ownedStages=[Collections.Generic.List[string]]::new()
$copied=[Collections.Generic.List[object]]::new()
$skipped=[Collections.Generic.List[object]]::new()
try {
    Assert-GameExited
    $null=Safe-Path $instanceRoot 'F:\Minecraft\HMCL\versions'
    if(-not (Get-Item -LiteralPath $instanceRoot -Force).PSIsContainer) { throw 'The fixed Minecraft instance does not exist.' }
    $null=Safe-Path $libraryRoot $instanceRoot
    if((Test-Path -LiteralPath $libraryRoot) -and -not (Get-Item -LiteralPath $libraryRoot -Force).PSIsContainer) { throw 'ROM library path is not a directory.' }
    foreach($item in $copyItems) {
        $source=File-State $item.Source $item.SourceRoot
        if($source.Sha256 -cne $item.Sha256) { throw "User source differs from reviewed hash: $($item.Name)" }
        $target=Join-Path $libraryRoot $item.Name
        $null=Safe-Path $target $libraryRoot
        $existing=$null
        if(Test-Path -LiteralPath $target) {
            $existing=File-State $target $libraryRoot
            if($existing.Sha256 -cne $item.Sha256) { throw "A different target already exists; it will NOT be overwritten: $target" }
        }
        $plans.Add([pscustomobject]@{Item=$item;SourceBefore=$source;Target=$target;Existing=$existing;Stage=$null;StageState=$null})
    }
    Assert-GameExited
    if(-not $Apply) {
        [ordered]@{Status='read-only-preflight-passed';Timestamp=(Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz');Directory=$libraryRoot;
            WouldCopy=@($plans | Where-Object {$null -eq $_.Existing} | ForEach-Object {$_.Item.Name});
            WouldSkipSameHash=@($plans | Where-Object {$null -ne $_.Existing} | ForEach-Object {$_.Item.Name});
            Sources=@($plans | ForEach-Object {$_.SourceBefore});NoWrites=$true;ApplyRequired=$true;
            NoExtraction=$true;NoExecution=$true;PermanentDeletions=0;ReleaseFilesTouched=0} | ConvertTo-Json -Depth 6
        return
    }

    Prepare-Library
    $copyRun=[guid]::NewGuid().ToString('N')
    # Stage all missing items and validate both original sources before publishing either file.
    foreach($plan in $plans) {
        if($null -ne $plan.Existing) { continue }
        $plan.Stage=Join-Path $libraryRoot ('.piq-user-kof97-'+$copyRun+'-'+$plan.Item.Name+'.tmp')
        $null=Safe-Path $plan.Stage $libraryRoot
        $ownedStages.Add($plan.Stage)
        $plan.StageState=Stage-PinnedFile $plan $plan.Stage
    }
    foreach($plan in $plans) {
        if(-not (Same-State $plan.SourceBefore (File-State $plan.Item.Source $plan.Item.SourceRoot))) { throw 'Source changed before commit; staged files are retained for review.' }
    }
    foreach($plan in $plans) {
        Assert-GameExited
        $null=Safe-Path $plan.Target $libraryRoot
        if($null -ne $plan.Existing) {
            if(-not (Same-State $plan.Existing (File-State $plan.Target $libraryRoot))) { throw 'Existing same-hash target changed; preserve it and stop.' }
            $skipped.Add([pscustomobject]@{Name=$plan.Item.Name;Path=$plan.Target;Sha256=$plan.Item.Sha256;Reason='already-present-same-hash'})
            continue
        }
        if(Test-Path -LiteralPath $plan.Target) { throw "Target appeared during copy; no overwrite: $($plan.Target)" }
        $null=Safe-Path $plan.Stage $libraryRoot
        if(-not (Same-State $plan.StageState (File-State $plan.Stage $libraryRoot))) { throw 'Owned staging file changed; refuse to publish.' }
        # File.Move's two-argument overload is non-overwriting, unlike Move-Item-to-directory fallback.
        # Only our exact same-directory stage is moved; no original user file is moved or removed.
        [IO.File]::Move($plan.Stage,$plan.Target)
        $state=File-State $plan.Target $libraryRoot
        if($state.Sha256 -cne $plan.Item.Sha256 -or $state.Bytes -ne $plan.SourceBefore.Bytes) { throw 'Published copy verification failed; preserve files for review.' }
        $copied.Add([pscustomobject]@{Name=$plan.Item.Name;Path=$plan.Target;Bytes=$state.Bytes;Sha256=$state.Sha256})
    }
    foreach($plan in $plans) {
        if(-not (Same-State $plan.SourceBefore (File-State $plan.Item.Source $plan.Item.SourceRoot))) { throw 'Original source changed during transaction.' }
        if((File-State $plan.Target $libraryRoot).Sha256 -cne $plan.Item.Sha256) { throw 'Target changed during final verification.' }
    }
    Assert-GameExited
    [ordered]@{Status='copied-and-hash-verified-not-launched';Timestamp=(Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz');Directory=$libraryRoot;
        Copied=@($copied.ToArray());Skipped=@($skipped.ToArray());SourceBytesAndMetadataUnchanged=$true;
        NoExtraction=$true;NoExecution=$true;PermanentDeletions=0;ReleaseFilesTouched=0;
        Scope='Only the two user-authorized pinned ZIP copies. No ROM enters a release/source archive.'} | ConvertTo-Json -Depth 6
} catch {
    $failure=$_
    # Never delete/overwrite on failure. Successful copies and uniquely named .tmp stages remain recoverable.
    [ordered]@{Status='copy-refused-or-incomplete-preserved-for-review';Timestamp=(Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz');Failure=$failure.Exception.Message;
        Directory=$libraryRoot;CopiedBeforeFailure=@($copied.ToArray());Skipped=@($skipped.ToArray());
        OwnedStagingPaths=@($ownedStages.ToArray());ApplyRequested=[bool]$Apply;PermanentDeletions=0;ReleaseFilesTouched=0;
        Recovery='Do not overwrite or delete any source/target. Any newly owned .tmp files remain in the fixed ROM folder for manual review.'} | ConvertTo-Json -Depth 6
    throw $failure
}
