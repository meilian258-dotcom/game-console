# Local-only three-JAR update. Default is read-only; root must explicitly invoke -Apply after review.
[CmdletBinding()]
param([switch]$Apply)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Add-Type -AssemblyName System.IO.Compression.FileSystem

$releaseBase = 'G:\服务器\服务器Codex\制作Mod\03-街机模拟'
$instanceRoot = 'F:\Minecraft\HMCL\versions\1.21.1-NeoForge'
$modsRoot = Join-Path $instanceRoot 'mods'
$backupBase = 'F:\Minecraft\HMCL\mod-backups\1.21.1-NeoForge'
$backupRoot = Join-Path $backupBase ('unified-cabinets-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8))
$stagingRoot = Join-Path $backupRoot 'staged\mods'
$previousRoot = Join-Path $backupRoot 'previous\mods'
$rollbackRoot = Join-Path $backupRoot 'failed-new\mods'
$items = @(
    [pscustomobject]@{
        ModId='piq_fc_arcade'; OldName='piq_fc_arcade-0.31.0-alpha.14.jar'; NewName='piq_fc_arcade-0.31.0-alpha.15.jar'
        OldHash='BC17E1B483FAD115DAE156C6ECB56D3911915BC31B3C44058D56F3D61002E68A'
        NewHash='7FF788233234A25AA67AAEFFB171E4275C3597452B6C62781508E2EF4A5B6499'
        Source=(Join-Path $releaseBase 'PIQ-FC街机\alpha15-reviewed-v2\piq_fc_arcade-0.31.0-alpha.15.jar')
    },
    [pscustomobject]@{
        ModId='piq_sfc_home'; OldName='piq_sfc_home-0.1.0-alpha.1.jar'; NewName='piq_sfc_home-0.1.0-alpha.2.jar'
        OldHash='E9D78E0B97F065EA3EE50CC5AE5903EC20CA2ABB59ED5A286DFC01853226C396'
        NewHash='CD6A117779470DFEBBF1E9A594C9A687A6BDEA36AE46A62EE54E51B01D9FBA78'
        Source=(Join-Path $releaseBase 'PIQ-SFC家用\piq_sfc_home-0.1.0-alpha.2.jar')
    },
    [pscustomobject]@{
        ModId='piq_native_arcade'; OldName='piq_native_arcade-0.1.0-alpha.1.jar'; NewName='piq_native_arcade-0.1.0-alpha.2.jar'
        OldHash='CEE8B775B57C34781EA5695D4C4991004AA4D4431CA583FB8166C310C188F4BC'
        NewHash='B3DC23F4DFAE53CD87730FEBB7668D470D111ADDCEE577803B09351CE34EFA56'
        Source=(Join-Path $releaseBase 'PIQ原生街机\0.1.0-alpha.2\piq_native_arcade-0.1.0-alpha.2.jar')
    }
)
$fixedUnchanged = @(
    @{Relative='mods\piq_sfc_arcade-0.2.0-alpha.6.jar';Hash='38FA46C5D283EAD9E1F6666D01398F495E2E1A3260A1517BE8EE959710963363'},
    @{Relative='piq-native-arcade\runtime\mame_libretro.dll';Hash='6172A988AB67FE68F4177A6FC8FBB82619EB2044C330930F0F572F7B1EDC2301'},
    @{Relative='piq-native-arcade\runtime\jna-5.14.0.jar';Hash='34ED1E1F27FA896BCA50DBC4E99CF3732967CEC387A7A0D5E3486C09673FE8C6'},
    @{Relative='piq-native-arcade\runtime\piq-native-helper.jar';Hash='D1A360A8C300FDBC402A1CEFCF63314A045E0C1D9183E7BABA6E40EF9B33F75D'}
)

function Hash([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash }
function Safe([string]$Path,[string]$Root,[switch]$AllowRoot) {
    $full=[IO.Path]::GetFullPath($Path)
    $base=[IO.Path]::GetFullPath($Root).TrimEnd('\')
    if (-not (($AllowRoot -and $full.Equals($base,[StringComparison]::OrdinalIgnoreCase)) -or
        $full.StartsWith($base+'\',[StringComparison]::OrdinalIgnoreCase))) { throw "Outside intended directory: $full" }
    for($cursor=$full;$cursor;$cursor=[IO.Path]::GetDirectoryName($cursor)) {
        $node=$null
        try { $node=Get-Item -LiteralPath $cursor -Force -ErrorAction Stop }
        catch [System.Management.Automation.ItemNotFoundException] { }
        if($null -ne $node -and ($node.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw "Reparse/link refused: $cursor" }
    }
    return $full
}
function No-Game {
    $java=@(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'")
    if(@($java | Where-Object { [string]::IsNullOrWhiteSpace($_.CommandLine) }).Count) { throw 'Cannot inspect a Java process; refuse installation rather than guess whether Minecraft is running.' }
    $running=@($java | Where-Object { $_.CommandLine -match '1\.21\.1-NeoForge|net\.minecraft|BootstrapLauncher|modlauncher|--gameDir|cn\.piq\.nativearcade\.bridge\.NativeCoreWorker' })
    if($running.Count) { throw 'Minecraft or its emulator is running. Close the game first; no process was stopped.' }
}
function File-State([string]$Path,[string]$Root) {
    $full=Safe $Path $Root
    $before=Get-Item -LiteralPath $full -Force
    if($before.PSIsContainer) { throw "Expected a regular file: $full" }
    $digest=Hash $full
    $after=Get-Item -LiteralPath $full -Force
    if($before.Length -ne $after.Length -or $before.LastWriteTimeUtc.Ticks -ne $after.LastWriteTimeUtc.Ticks -or $before.CreationTimeUtc.Ticks -ne $after.CreationTimeUtc.Ticks) { throw "File changed while hashing: $full" }
    [pscustomobject]@{Path=$full;Bytes=$after.Length;ModifiedUtc=$after.LastWriteTimeUtc.Ticks;CreatedUtc=$after.CreationTimeUtc.Ticks;Sha256=$digest}
}
function Same-State($Left,$Right) {
    return $Left.Bytes -eq $Right.Bytes -and $Left.ModifiedUtc -eq $Right.ModifiedUtc -and $Left.CreatedUtc -eq $Right.CreatedUtc -and $Left.Sha256 -eq $Right.Sha256
}
function Tree-Entries([string]$Path,[string]$Root) {
    $full=Safe $Path $Root
    if(-not (Test-Path -LiteralPath $full)) { [pscustomobject]@{Path=$full;Kind='missing'};return }
    $stack=[Collections.Generic.Stack[string]]::new();$stack.Push($full)
    while($stack.Count -gt 0) {
        $current=$stack.Pop();$null=Safe $current $Root
        $node=Get-Item -LiteralPath $current -Force
        if($node.PSIsContainer) {
            [pscustomobject]@{Path=$current;Kind='directory';ModifiedUtc=$node.LastWriteTimeUtc.Ticks;CreatedUtc=$node.CreationTimeUtc.Ticks}
            # Deliberately do not use -Recurse: inspect every child before traversing it.
            foreach($child in @(Get-ChildItem -LiteralPath $current -Force)) {
                $null=Safe $child.FullName $Root
                $stack.Push($child.FullName)
            }
        } else {
            $state=File-State $current $Root
            [pscustomobject]@{Path=$state.Path;Kind='file';Bytes=$state.Bytes;ModifiedUtc=$state.ModifiedUtc;CreatedUtc=$state.CreatedUtc;Sha256=$state.Sha256}
        }
    }
}
function Protected-Snapshot {
    $all=@(foreach($name in @('saves','config','defaultconfig','defaultconfigs','piq-fc','piq-sfc-home','piq-native-arcade','options.txt','1.21.1-NeoForge.json','1.21.1-NeoForge.jar')) {
        Tree-Entries (Join-Path $instanceRoot $name) $instanceRoot
    })
    return @($all | Sort-Object Path)
}
function Snapshot-Json($Snapshot) { ConvertTo-Json -InputObject @($Snapshot) -Depth 6 -Compress }
function Mod-Files {
    return @(Tree-Entries $modsRoot $instanceRoot | Where-Object { $_.Kind -eq 'file' } | Sort-Object Path)
}
function Read-ModIds([string]$Jar,[string]$Root) {
    $null=Safe $Jar $Root
    $ids=[Collections.Generic.List[string]]::new()
    $archive=[IO.Compression.ZipFile]::OpenRead($Jar)
    try {
        foreach($name in @('META-INF/neoforge.mods.toml','META-INF/mods.toml')) {
            $entry=$archive.GetEntry($name);if($null -eq $entry){continue}
            if($entry.Length -gt 2097152) { throw "Unexpectedly large MOD metadata: $Jar" }
            $reader=[IO.StreamReader]::new($entry.Open(),[Text.Encoding]::UTF8,$true)
            try { $text=$reader.ReadToEnd() } finally { $reader.Dispose() }
            $inMod=$false
            foreach($line in ($text -split "`r?`n")) {
                if($line -match '^\s*\[') { $inMod=$line -match '^\s*\[\[mods\]\]\s*(?:#.*)?$';continue }
                if($inMod -and $line -match '^\s*modId\s*=\s*["'']([a-z][a-z0-9_]{1,63})["'']\s*(?:#.*)?$') { $ids.Add($Matches[1]) }
            }
            # NeoForge's preferred descriptor is authoritative if both are present.
            break
        }
    } finally { $archive.Dispose() }
    return $ids.ToArray()
}
function Assert-ModSet($Files,[bool]$Updated) {
    $expected=@{}
    foreach($item in $items) { $expected[$item.ModId]=if($Updated){$item.NewName}else{$item.OldName} }
    $expected['piq_sfc_arcade']='piq_sfc_arcade-0.2.0-alpha.6.jar'
    $locations=@{};foreach($id in $expected.Keys){$locations[$id]=[Collections.Generic.List[string]]::new()}
    foreach($file in $Files) {
        if([IO.Path]::GetExtension($file.Path) -ine '.jar'){continue}
        foreach($id in @(Read-ModIds $file.Path $modsRoot)) {
            if($locations.ContainsKey($id)){$locations[$id].Add($file.Path)}
        }
    }
    foreach($id in $expected.Keys) {
        $want=Join-Path $modsRoot $expected[$id]
        if($locations[$id].Count -ne 1 -or $locations[$id][0] -ine $want) { throw "Missing, renamed or duplicate $id MOD; re-audit required." }
    }
}
function Fixed-Files {
    foreach($entry in $fixedUnchanged) {
        $state=File-State (Join-Path $instanceRoot $entry.Relative) $instanceRoot
        if($state.Sha256 -ne $entry.Hash) { throw "Unexpected existing core/runtime: $($entry.Relative)" }
    }
}
function New-SafeDirectory([string]$Path) {
    $full=Safe $Path $backupRoot -AllowRoot
    [IO.Directory]::CreateDirectory($full) | Out-Null
    $null=Safe $full $backupRoot -AllowRoot
}
function Write-JsonNew([string]$Path,$Data) {
    $full=Safe $Path $backupRoot
    $bytes=[Text.UTF8Encoding]::new($false).GetBytes(($Data | ConvertTo-Json -Depth 10))
    $file=[IO.FileStream]::new($full,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try { $file.Write($bytes,0,$bytes.Length);$file.Flush($true) } finally { $file.Dispose() }
}
function Move-Exact([string]$Source,[string]$SourceRoot,[string]$Target,[string]$TargetRoot,[string]$ExpectedHash) {
    No-Game
    $from=Safe $Source $SourceRoot;$to=Safe $Target $TargetRoot
    if(Test-Path -LiteralPath $to) { throw "Refusing to overwrite existing target: $to" }
    if((File-State $from $SourceRoot).Sha256 -ne $ExpectedHash) { throw "Move source changed: $from" }
    # One shell, fixed literal source/destination; never invoke another shell or use wildcards.
    Move-Item -LiteralPath $from -Destination $to -ErrorAction Stop
}

$null=Safe $instanceRoot 'F:\Minecraft\HMCL\versions'
$null=Safe $modsRoot $instanceRoot
$null=Safe $backupRoot $backupBase
No-Game
if(Test-Path -LiteralPath $backupRoot) { throw 'Backup directory already exists; no overwrite allowed.' }
if([IO.Path]::GetPathRoot($backupRoot) -ine [IO.Path]::GetPathRoot($modsRoot)) { throw 'Backup must stay on the instance volume.' }
$versionPath=Join-Path $instanceRoot '1.21.1-NeoForge.json';$null=Safe $versionPath $instanceRoot
$version=Get-Content -LiteralPath $versionPath -Raw | ConvertFrom-Json
if(@($version.patches | Where-Object { $_.id -eq 'neoforge' -and $_.version -eq '21.1.250' }).Count -ne 1) { throw 'Unexpected loader version; re-audit required.' }
$modsBefore=Mod-Files
Assert-ModSet $modsBefore $false
Fixed-Files
foreach($item in $items) {
    $source=File-State $item.Source $releaseBase
    if($source.Sha256 -ne $item.NewHash) { throw "New payload hash mismatch: $($item.NewName)" }
    $ids=@(Read-ModIds $item.Source $releaseBase)
    if(@($ids | Where-Object { $_ -eq $item.ModId }).Count -ne 1) { throw "New payload has wrong mod ID: $($item.NewName)" }
    $old=File-State (Join-Path $modsRoot $item.OldName) $modsRoot
    if($old.Sha256 -ne $item.OldHash) { throw "Old payload differs from approved install: $($item.OldName)" }
    $newTarget=Join-Path $modsRoot $item.NewName;$null=Safe $newTarget $modsRoot
    if(Test-Path -LiteralPath $newTarget) { throw "New target already exists: $newTarget" }
}
$oldPaths=@($items | ForEach-Object { Join-Path $modsRoot $_.OldName })
$newPaths=@($items | ForEach-Object { Join-Path $modsRoot $_.NewName })
$otherModsBefore=@($modsBefore | Where-Object { $oldPaths -inotcontains $_.Path })
$protectedBefore=Protected-Snapshot
$protectedJson=Snapshot-Json $protectedBefore
$modsJson=Snapshot-Json $modsBefore
No-Game
if(-not $Apply) {
    [ordered]@{Status='read-only-preflight-passed';Instance=$instanceRoot;UpdatedMods=@($items.ModId);UnchangedOtherModFiles=$otherModsBefore.Count;ProtectedEntries=$protectedBefore.Count;BackupWouldBe=$backupRoot;NoWrites=$true;ApplyRequired=$true} | ConvertTo-Json -Depth 5
    return
}

New-SafeDirectory $backupRoot
New-SafeDirectory $stagingRoot
New-SafeDirectory $previousRoot
New-SafeDirectory $rollbackRoot
Write-JsonNew (Join-Path $backupRoot 'installation-plan.json') ([ordered]@{
    Timestamp=(Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz');Instance=$instanceRoot;Items=$items
    BeforeMods=$modsBefore;BeforeProtected=$protectedBefore;RuntimeUnchanged=$fixedUnchanged
    Scope='Only three named JARs. No ROM/config/world changes, no launch, no shutdown.'
})
$installed=[Collections.Generic.List[object]]::new()
$movedOld=[Collections.Generic.List[object]]::new()
try {
    foreach($item in $items) {
        $stage=Join-Path $stagingRoot $item.NewName
        $null=Safe $item.Source $releaseBase;$null=Safe $stage $backupRoot
        if(Test-Path -LiteralPath $stage) { throw "Stage target already exists: $stage" }
        # Copy never changes the release. Its independent staged SHA must match before any old MOD moves.
        Copy-Item -LiteralPath $item.Source -Destination $stage -ErrorAction Stop
        if((File-State $stage $backupRoot).Sha256 -ne $item.NewHash) { throw "Staging checksum failed: $($item.NewName)" }
    }
    No-Game
    if((Snapshot-Json (Mod-Files)) -cne $modsJson) { throw 'MOD files changed while staging; no commit allowed.' }
    if((Snapshot-Json (Protected-Snapshot)) -cne $protectedJson) { throw 'Protected files changed while staging; no commit allowed.' }
    foreach($item in $items) {
        $old=Join-Path $modsRoot $item.OldName;$saved=Join-Path $previousRoot $item.OldName
        Move-Exact $old $modsRoot $saved $backupRoot $item.OldHash
        $record=[pscustomobject]@{Item=$item;Backup=$saved;State=$null};$movedOld.Add($record)
        $record.State=File-State $saved $backupRoot
        if($record.State.Sha256 -ne $item.OldHash) { throw "Old backup hash mismatch: $($item.OldName)" }
    }
    foreach($item in $items) {
        $stage=Join-Path $stagingRoot $item.NewName;$target=Join-Path $modsRoot $item.NewName
        Move-Exact $stage $backupRoot $target $modsRoot $item.NewHash
        $record=[pscustomobject]@{Item=$item;Target=$target;State=$null};$installed.Add($record)
        $record.State=File-State $target $modsRoot
        if($record.State.Sha256 -ne $item.NewHash) { throw "Installed hash mismatch: $($item.NewName)" }
    }
    No-Game
    $modsAfter=Mod-Files
    Assert-ModSet $modsAfter $true
    $otherModsAfter=@($modsAfter | Where-Object { $newPaths -inotcontains $_.Path })
    if((Snapshot-Json $otherModsAfter) -cne (Snapshot-Json $otherModsBefore)) { throw 'Other MOD files changed; transaction validation failed.' }
    $protectedAfter=Protected-Snapshot
    if((Snapshot-Json $protectedAfter) -cne $protectedJson) { throw 'Protected paths/content/metadata changed; transaction validation failed.' }
    Fixed-Files
    foreach($record in $movedOld) { if(-not (Same-State $record.State (File-State $record.Backup $backupRoot))) { throw 'A saved previous MOD changed during commit.' } }
    No-Game
    $report=[ordered]@{
        Status='installed-and-hash-verified-not-launched';Timestamp=(Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz')
        Instance=$instanceRoot;NeoForge='21.1.250';Backup=$backupRoot;Updated=@($installed.ToArray());Previous=@($movedOld.ToArray())
        OtherModFilesUnchanged=$otherModsAfter;ProtectedEntries=$protectedAfter.Count
        ProtectedVerified='Every protected file SHA256, size, creation/modified time, directory metadata, and missing paths unchanged.'
        FixedUnchanged=$fixedUnchanged;Launched=$false;ShutdownRequested=$false;PermanentDeletions=0
    }
    Write-JsonNew (Join-Path $backupRoot 'installation-verification.json') $report
    $report | ConvertTo-Json -Depth 10
} catch {
    $failure=$_
    $rollback=[Collections.Generic.List[object]]::new()
    $canRollback=$true
    try { No-Game } catch { $canRollback=$false;$rollback.Add(@{Action='all';Status='skipped';Reason=$_.Exception.Message}) }
    if($canRollback) {
        foreach($record in $installed) {
            try {
                $null=Safe $record.Target $modsRoot
                if(-not (Test-Path -LiteralPath $record.Target)) { $rollback.Add(@{File=$record.Target;Status='already-absent'});continue }
                $now=File-State $record.Target $modsRoot
                if($null -eq $record.State -or -not (Same-State $now $record.State)) { throw 'Installed target was changed or not fully fingerprinted; preserve it for manual review.' }
                $failed=Join-Path $rollbackRoot $record.Item.NewName
                Move-Exact $record.Target $modsRoot $failed $backupRoot $record.Item.NewHash
                $rollback.Add(@{File=$record.Target;Status='new-payload-moved-to-backup';Backup=$failed})
            } catch { $rollback.Add(@{File=$record.Target;Status='preserved-needs-manual-review';Reason=$_.Exception.Message}) }
        }
        foreach($record in $movedOld) {
            try {
                $old=Join-Path $modsRoot $record.Item.OldName;$new=Join-Path $modsRoot $record.Item.NewName
                $null=Safe $old $modsRoot;$null=Safe $new $modsRoot;$null=Safe $record.Backup $backupRoot
                if((Test-Path -LiteralPath $old) -or (Test-Path -LiteralPath $new)) { throw 'An old/new target now exists; do not overwrite it or restore a duplicate MOD beside it.' }
                $now=File-State $record.Backup $backupRoot
                if($null -eq $record.State -or -not (Same-State $now $record.State)) { throw 'Saved old MOD changed or lacks full fingerprint; preserve backup for manual review.' }
                Move-Exact $record.Backup $backupRoot $old $modsRoot $record.Item.OldHash
                if((File-State $old $modsRoot).Sha256 -ne $record.Item.OldHash) { throw 'Restored old MOD checksum failed.' }
                $rollback.Add(@{File=$old;Status='previous-mod-restored'})
            } catch { $rollback.Add(@{File=$record.Backup;Status='backup-retained-needs-manual-review';Reason=$_.Exception.Message}) }
        }
    }
    $failureReport=[ordered]@{
        Status='installation-failed-review-rollback';Timestamp=(Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz');Instance=$instanceRoot
        Backup=$backupRoot;Failure=$failure.Exception.Message;Rollback=@($rollback.ToArray());UpdatedBeforeFailure=@($installed.ToArray());OldMovedBeforeFailure=@($movedOld.ToArray())
        PermanentDeletions=0;Launched=$false;ShutdownRequested=$false
    }
    try { Write-JsonNew (Join-Path $backupRoot 'installation-failure.json') $failureReport }
    catch { Write-Warning ('Could not create failure audit; preserve backup directory: '+$backupRoot+'; '+$_.Exception.Message) }
    $failureReport | ConvertTo-Json -Depth 10
    throw $failure
}
