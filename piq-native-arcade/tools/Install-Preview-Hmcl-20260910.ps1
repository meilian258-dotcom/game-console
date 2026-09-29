$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$releaseBase = 'G:\服务器\服务器Codex\制作Mod\03-街机模拟'
$nativeRelease = Join-Path $releaseBase 'PIQ原生街机\0.1.0-alpha.1'
$instanceRoot = 'F:\Minecraft\HMCL\versions\1.21.1-NeoForge'
$modsRoot = Join-Path $instanceRoot 'mods'
$backupBase = 'F:\Minecraft\HMCL\mod-backups\1.21.1-NeoForge'
$backupRoot = Join-Path $backupBase ('fc-sfc-native-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
$oldName = 'piq_fc_arcade-0.31.0-alpha.11.jar'
$oldPath = Join-Path $modsRoot $oldName
$oldHash = '7B6CD6B4D38E8E02B20F2CC021779BEECD4C3582450B40A588EFB349C650DED8'
function Hash([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash }
function Safe([string]$Path, [string]$Root) {
    $full = [IO.Path]::GetFullPath($Path)
    $base = [IO.Path]::GetFullPath($Root).TrimEnd('\') + '\'
    if (-not $full.StartsWith($base,[StringComparison]::OrdinalIgnoreCase)) { throw "Outside intended directory: $full" }
    for ($cursor=$full; $cursor; $cursor=[IO.Path]::GetDirectoryName($cursor)) {
        if ((Test-Path -LiteralPath $cursor) -and ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw "Linked path refused: $cursor" }
    }
}
function No-Game {
    $running=@(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" | Where-Object { $_.CommandLine -match '1\.21\.1-NeoForge|net\.minecraft|BootstrapLauncher|modlauncher|--gameDir' })
    if ($running.Count) { throw 'Minecraft is running; close it first. No process was stopped.' }
}
function Protected-Snapshot {
    $result=@()
    foreach ($name in @('config','defaultconfigs','saves','piq-fc','options.txt','1.21.1-NeoForge.json')) {
        $path=Join-Path $instanceRoot $name
        if (-not (Test-Path -LiteralPath $path)) { continue }
        foreach ($f in (Get-ChildItem -LiteralPath $path -File -Recurse -Force | Sort-Object FullName)) {
            $result += [pscustomobject]@{Path=$f.FullName;Bytes=$f.Length;ModifiedUtc=$f.LastWriteTimeUtc.Ticks}
        }
    }
    $result | ConvertTo-Json -Depth 4 -Compress
}
Safe $instanceRoot 'F:\Minecraft\HMCL\versions'
Safe $backupRoot $backupBase
No-Game
if ((Test-Path -LiteralPath $backupRoot) -or (Test-Path -LiteralPath (Join-Path $instanceRoot 'piq-native-arcade'))) { throw 'Prior install/backup found; re-audit required.' }
$existing=@(Get-ChildItem -LiteralPath $modsRoot -File -Filter '*.jar')
if ($existing.Count -ne 1 -or $existing[0].Name -ne $oldName -or (Hash $oldPath) -ne $oldHash) { throw 'Existing mods changed since review.' }
$version=Get-Content -LiteralPath (Join-Path $instanceRoot '1.21.1-NeoForge.json') -Raw | ConvertFrom-Json
if (@($version.patches | Where-Object { $_.id -eq 'neoforge' -and $_.version -eq '21.1.250' }).Count -ne 1) { throw 'Unexpected loader version.' }
$manifestPath=Join-Path $nativeRelease 'FINAL_CHECKSUMS.json'
if ((Hash $manifestPath) -ne '8842E62BD2B0D39A6E026ACC4172F123ED1C3B32319CCE666C8265CCF8F6A85A') { throw 'Frozen manifest mismatch.' }
$manifest=Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
$items=@()
foreach ($rel in @('mods/piq_fc_arcade-0.31.0-alpha.14.jar','mods/piq_native_arcade-0.1.0-alpha.1.jar','piq-native-arcade/runtime/mame_libretro.dll','piq-native-arcade/runtime/jna-5.14.0.jar','piq-native-arcade/runtime/piq-native-helper.jar','piq-native-arcade/diagnostic/invaders.zip')) {
    $items += @{Source=(Join-Path $nativeRelease $rel);Relative=$rel;Hash=$manifest.files.PSObject.Properties[$rel].Value.sha256}
}
$items += @{Source=(Join-Path $releaseBase 'PIQ-SFC街机\piq_sfc_arcade-0.2.0-alpha.6.jar');Relative='mods/piq_sfc_arcade-0.2.0-alpha.6.jar';Hash='38FA46C5D283EAD9E1F6666D01398F495E2E1A3260A1517BE8EE959710963363'}
$items += @{Source=(Join-Path $releaseBase 'PIQ-SFC家用\piq_sfc_home-0.1.0-alpha.1.jar');Relative='mods/piq_sfc_home-0.1.0-alpha.1.jar';Hash='E9D78E0B97F065EA3EE50CC5AE5903EC20CA2ABB59ED5A286DFC01853226C396'}
foreach ($item in $items) {
    Safe $item.Source $releaseBase
    $target=Join-Path $instanceRoot $item.Relative
    Safe $target $instanceRoot
    if ((Test-Path -LiteralPath $target) -or (Hash $item.Source) -ne $item.Hash) { throw "Payload mismatch: $($item.Relative)" }
}
$protectedBefore=Protected-Snapshot
$optionHash=Hash (Join-Path $instanceRoot 'options.txt')
$versionHash=Hash (Join-Path $instanceRoot '1.21.1-NeoForge.json')
[IO.Directory]::CreateDirectory($backupRoot) | Out-Null
$staging=Join-Path $backupRoot 'staged'
$oldBackup=Join-Path $backupRoot ('previous\mods\' + $oldName)
foreach ($item in $items) {
    $stage=Join-Path $staging $item.Relative
    Safe $stage $backupRoot
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($stage)) | Out-Null
    Copy-Item -LiteralPath $item.Source -Destination $stage
    if ((Hash $stage) -ne $item.Hash) { throw "Staging failed: $($item.Relative)" }
}
No-Game
if ((Hash $oldPath) -ne $oldHash) { throw 'Old FC changed before commit.' }
Safe $oldPath $modsRoot
Safe $oldBackup $backupRoot
[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($oldBackup)) | Out-Null
$installed=@()
$movedOld=$false
try {
    Move-Item -LiteralPath $oldPath -Destination $oldBackup
    $movedOld=$true
    if ((Hash $oldBackup) -ne $oldHash) { throw 'Backup hash mismatch.' }
    foreach ($item in $items) {
        $target=Join-Path $instanceRoot $item.Relative
        Safe $target $instanceRoot
        [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($target)) | Out-Null
        Move-Item -LiteralPath (Join-Path $staging $item.Relative) -Destination $target
        $installed += $item
        if ((Hash $target) -ne $item.Hash) { throw "Installed hash mismatch: $($item.Relative)" }
    }
    if ((Protected-Snapshot) -cne $protectedBefore) { throw 'Protected file metadata changed during installation.' }
    if ((Hash (Join-Path $instanceRoot 'options.txt')) -ne $optionHash -or (Hash (Join-Path $instanceRoot '1.21.1-NeoForge.json')) -ne $versionHash) { throw 'Options/version changed.' }
    if (@(Get-ChildItem -LiteralPath $modsRoot -File -Filter '*.jar').Count -ne 4) { throw 'Unexpected final MOD count.' }
} catch {
    $installFailure=$_
    foreach ($item in $installed) {
        $target=Join-Path $instanceRoot $item.Relative
        $rollback=Join-Path $backupRoot ('failed-install\' + $item.Relative)
        Safe $target $instanceRoot
        Safe $rollback $backupRoot
        [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($rollback)) | Out-Null
        Move-Item -LiteralPath $target -Destination $rollback
    }
    if ($movedOld -and -not (Test-Path -LiteralPath $oldPath)) { Move-Item -LiteralPath $oldBackup -Destination $oldPath }
    throw $installFailure
}
$report=[ordered]@{
    Status='installed-and-hash-verified-not-launched';Timestamp=(Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz')
    Instance=$instanceRoot;NeoForge='21.1.250';Backup=$backupRoot
    PreviousMod=@{File=$oldBackup;Sha256=$oldHash}
    Installed=@($items | ForEach-Object { @{File=(Join-Path $instanceRoot $_.Relative);Sha256=(Hash (Join-Path $instanceRoot $_.Relative));Bytes=(Get-Item -LiteralPath (Join-Path $instanceRoot $_.Relative)).Length} })
    ProtectedFiles='config/defaultconfigs/saves/piq-fc/options/version metadata unchanged; options and version SHA unchanged'
    Launched=$false;ShutdownRequested=$false
}
$reportPath=Join-Path $backupRoot 'installation-verification.json'
[IO.File]::WriteAllText($reportPath,($report | ConvertTo-Json -Depth 7),[Text.UTF8Encoding]::new($false))
$report | ConvertTo-Json -Depth 7
