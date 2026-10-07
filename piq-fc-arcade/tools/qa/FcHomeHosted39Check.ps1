param([string]$Jdk='', [string]$GradleCache='')
$ErrorActionPreference='Stop'
if (-not $Jdk) {
    if ($env:JAVA_HOME) { $Jdk = $env:JAVA_HOME }
    else { $Jdk = Split-Path -Parent (Split-Path -Parent (Get-Command javac -ErrorAction Stop).Source) }
}
if (-not (Test-Path -LiteralPath (Join-Path $Jdk 'bin/javac.exe'))) { throw 'JDK 21 not found; set -Jdk or JAVA_HOME.' }
if (-not $GradleCache) {
    $toolGradleHome = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path ([Environment]::GetFolderPath('UserProfile')) '.gradle' }
    $GradleCache = Join-Path $toolGradleHome 'caches'
}
$fcProject=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$fcOutput=Join-Path ([IO.Path]::GetTempPath()) ('piq-fc-home39-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fcOutput | Out-Null
$fcModules=Join-Path $GradleCache 'modules-2/files-2.1'
$fcManifest=Get-Content (Join-Path $GradleCache 'neoformruntime/artifacts/minecraft_1.21.1_version_manifest.json') -Raw | ConvertFrom-Json
$fcLibraries=@(foreach($entry in $fcManifest.libraries){$v=$entry.name.Split(':');if($v.Length -eq 3){Get-ChildItem "$fcModules/$($v[0])/$($v[1])/$($v[2])" -Filter "$($v[1])-$($v[2]).jar" -Recurse -ErrorAction SilentlyContinue | ForEach-Object FullName}})
$fcLibraries+=@(foreach($group in @('org.slf4j','net.neoforged','net.neoforged.fancymodloader','cpw.mods','org.apache.logging.log4j','org.jetbrains','org.ow2.asm','org.junit.jupiter','org.junit.platform','org.opentest4j','org.apiguardian')){Get-ChildItem "$fcModules/$group" -Filter '*.jar' -Recurse -ErrorAction SilentlyContinue | Where-Object {$_.Name -notmatch '(sources|javadoc|userdev|1.11.4|5.11.4|1.12.2|5.12.2)'} | ForEach-Object FullName})
$fcClasspath=(@($fcOutput,"$fcProject/build/classes/java/main","$fcProject/build/moddev/artifacts/neoforge-21.1.236-merged.jar")+$fcLibraries)-join ';'
$fcSources=@('server/hosted/NesManagedState.java','server/hosted/ServerCoreContext.java','server/hosted/ServerCoreHandle.java','server/hosted/NesServerCoreFactory.java','server/hosted/HostedServerLimits.java','cabinet/CabinetHostingConfig.java','server/FcHomeHostedNetwork.java','server/FcHomeHostedRun.java','server/ServerArcadeSessions.java','client/ClientArcadeSession.java','client/ClientArcadeEvents.java') | ForEach-Object {"$fcProject/src/main/java/cn/piq/fcarcade/$_"}
$fcSources+=@("$fcProject/src/test/java/cn/piq/fcarcade/server/FcHomeHostedNetwork39Test.java","$fcProject/src/test/java/cn/piq/fcarcade/server/hosted/NesManagedState39Test.java","$PSScriptRoot/FcHomeHosted39Tests.java")
& "$Jdk/bin/javac.exe" --release 21 -proc:none -encoding UTF-8 -cp $fcClasspath -d $fcOutput $fcSources
if($LASTEXITCODE -ne 0){throw 'FC home hosted targeted compilation failed'}
& "$Jdk/bin/java.exe" -cp $fcClasspath FcHomeHosted39Tests
if($LASTEXITCODE -ne 0){throw 'FC home hosted boundary tests failed'}
Write-Output "FC_HOME_HOSTED_QA_OUTPUT=$fcOutput"
