param(
    [Parameter(Mandatory=$true)][string]$CoreClasses,
    [string]$EvidenceName = 'home-hosted39-source-v1'
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$evidence = Join-Path $repo ('piq-sfc-home/build/' + $EvidenceName)
if ($EvidenceName -notmatch '^[a-z0-9_-]+$' -or (Test-Path -LiteralPath $evidence)) { throw 'Choose a new simple evidence directory name; evidence is never overwritten.' }
$core = (Resolve-Path -LiteralPath $CoreClasses).Path
$qa = Join-Path ([System.IO.Path]::GetTempPath()) ('piq-sfchome-hosted39-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $qa | Out-Null
$classes = Join-Path $qa 'classes'; $scratch = Join-Path $qa 'scratch'
New-Item -ItemType Directory -Path $classes,$scratch,$evidence | Out-Null
$javaBin = 'C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin'
$argTemplate = (Get-Content -LiteralPath (Join-Path $repo 'piq-fc-arcade/design/audio-fixes-20260913/source-v3/compile.args'))[1].Trim('"').Split(';')
$deps = @($argTemplate | Where-Object { $_ -like 'C:/*' } | Select-Object -First 77) + @($argTemplate | Where-Object { $_ -match '/net.neoforged/(bus/|mergetool/2.0.3/.+/mergetool-2.0.3-api)|/cpw.mods/(modlauncher/|securejarhandler/)' })
$fc = Join-Path $repo 'piq-fc-arcade/build/review-release38-v1/piq_fc_arcade-0.31.0-alpha.38.jar'
$sfc = Join-Path $repo 'piq-fc-arcade/build/review-release38-v1/piq_sfc-0.1.0-alpha.22.jar'
$prior = Join-Path $repo 'piq-fc-arcade/build/home-modes39-qa-v1'
$cp = (@($classes,$prior,$core,$fc,$sfc) + $deps) -join ';'
$sources = @(
 'piq-fc-arcade/src/main/java/cn/piq/fcarcade/cabinet/CabinetHostingConfig.java',
 'piq-fc-arcade/src/main/java/cn/piq/fcarcade/server/hosted/HostedServerLimits.java',
 'piq-fc-arcade/src/main/java/cn/piq/fcarcade/server/hosted/ServerCoreHandle.java',
 'piq-fc-arcade/src/main/java/cn/piq/fcarcade/server/hosted/ServerCoreWorker.java',
 'piq-fc-arcade/src/main/java/cn/piq/fcarcade/home/HomeSystems.java',
 'piq-fc-arcade/src/main/java/cn/piq/fcarcade/home/HomeSyncPolicy.java',
 'piq-fc-arcade/src/main/java/cn/piq/fcarcade/home/HomeSyncSettings.java',
 'piq-fc-arcade/src/main/java/cn/piq/fcarcade/client/HomeSyncSettingsScreen.java',
 'piq-sfc-home/src/main/java/cn/piq/sfchome/net/SfcHomeNetwork.java',
 'piq-sfc-home/src/main/java/cn/piq/sfchome/net/SfcHostedNetwork.java',
 'piq-sfc-home/src/main/java/cn/piq/sfchome/server/SfcHomeServer.java',
 'piq-sfc-home/src/main/java/cn/piq/sfchome/server/SfcHostedWorker.java',
 'piq-sfc-home/src/main/java/cn/piq/sfchome/server/SfcWatchProvider.java',
 'piq-sfc-home/src/main/java/cn/piq/sfchome/client/SfcHomeClient.java',
 'piq-sfc-home/src/main/java/cn/piq/sfchome/client/SfcPlayback.java',
 'piq-sfc-home/tools/qa/SfcHomeHosted39Probe.java',
 'piq-fc-arcade/tools/qa/SfcTwoPortInputProbe.java',
 'piq-sfc-arcade/src/test/java/cn/piq/sfcarcade/core/SfcLegalTestRom.java'
) | ForEach-Object { Join-Path $repo $_ }
$hashes = [ordered]@{}; foreach ($source in $sources) {$hashes[$source] = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash}
$oldNativeErrorPreference = $PSNativeCommandUseErrorActionPreference; $PSNativeCommandUseErrorActionPreference = $false
try {
    $compile = & (Join-Path $javaBin 'javac.exe') -encoding UTF-8 -proc:none -implicit:none -cp $cp -d $classes $sources 2>&1
    $compile | Set-Content -LiteralPath (Join-Path $evidence 'compile.log') -Encoding utf8
    if ($LASTEXITCODE -ne 0) {throw 'Targeted javac failed; see compile.log.'}
    $run = & (Join-Path $javaBin 'java.exe') -Xmx1G '-Djava.awt.headless=true' '-Dfile.encoding=UTF-8' -cp $cp cn.piq.sfchome.client.SfcHomeHosted39Probe $scratch 2>&1
    $run | Set-Content -LiteralPath (Join-Path $evidence 'run.log') -Encoding utf8
    if ($LASTEXITCODE -ne 0) {throw 'Actual home worker probe failed; see run.log.'}
    foreach ($source in $sources) {if ($hashes[$source] -ne (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash) {throw 'Source changed during probe; do not accept evidence.'}}
    $result = ($run | ForEach-Object {"$_"} | Where-Object {$_ -like '{"ok":*'} | Select-Object -Last 1) | ConvertFrom-Json
    if (!$result.ok) {throw 'Missing successful probe result.'}
    $report = [ordered]@{ok=$true;mode='production-source';qa_directory=$qa;core_classes=$core;prerequisite_classes=$prior;sources=$hashes;result=$result;scope='Actual home worker/core/codec/receiver; no Minecraft, protection-mod, real multiplayer network or final-JAR integration test.'}
    $report | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $evidence 'report.json') -Encoding utf8
    $result | ConvertTo-Json -Compress
} finally {$PSNativeCommandUseErrorActionPreference=$oldNativeErrorPreference}
