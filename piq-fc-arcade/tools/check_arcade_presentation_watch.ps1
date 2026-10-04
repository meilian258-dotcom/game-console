param(
    [Parameter(Mandatory=$true)][string]$FcJar,
    [Parameter(Mandatory=$true)][string]$NativeJar,
    [Parameter(Mandatory=$true)][string]$ReportDirectory,
    [switch]$SourceOverlay,
    [string]$DependencyCache='C:/Users/13498/.gradle/caches/modules-2/files-2.1'
)
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$workspaceRoot=[IO.Path]::GetFullPath((Join-Path $projectRoot '..'))
$FcJar=(Resolve-Path -LiteralPath $FcJar).Path
$NativeJar=(Resolve-Path -LiteralPath $NativeJar).Path
if(Test-Path -LiteralPath $ReportDirectory){throw 'Refuse to overwrite earlier QA evidence'}
$report=New-Item -ItemType Directory -Path $ReportDirectory
$classes=New-Item -ItemType Directory -Path (Join-Path $report.FullName 'classes')
$empty=New-Item -ItemType Directory -Path (Join-Path $report.FullName 'empty')
$libraries=@()
foreach($dependency in @(@('org.junit.platform','1.13.4'),@('org.junit.jupiter','5.13.4'),@('org.opentest4j','1.3.0'),@('org.apiguardian','1.1.2'))){
    $libraries+=Get-ChildItem -LiteralPath (Join-Path $DependencyCache $dependency[0]) -Filter '*.jar' -Recurse |
        Where-Object {$_.FullName.Replace('\','/').Contains('/'+$dependency[1]+'/') -and $_.Name -notmatch '-(sources|javadoc)\.jar$'} |
        ForEach-Object FullName
}
if($libraries.Count -lt 7){throw 'Required cached JUnit dependencies missing'}
$names=@('layout.CabinetVideoGeometryTest','netplay.NetplayProfileTest','netplay.JniNetplaySessionTest','netplay.JniCabinetObserverTest')
$tests=@($names | ForEach-Object {Join-Path $projectRoot ('src/test/java/cn/piq/fcarcade/'+$_.Replace('.','/')+'.java')})
$expected=0
foreach($test in $tests){$expected+=[regex]::Matches([IO.File]::ReadAllText($test),'@Test\b').Count}
$sources=$tests+@((Join-Path $PSScriptRoot 'qa/CabinetGameDedup35Runner.java'),(Join-Path $PSScriptRoot 'qa/ArcadePresentationWatchProbe.java'))
if($SourceOverlay){
    $sources+=@('layout/CabinetVideoGeometry','netplay/NetplayProfile','netplay/NetplayProcess','netplay/JniNetplaySession') |
        ForEach-Object {Join-Path $projectRoot ('src/main/java/cn/piq/fcarcade/'+$_+'.java')}
    $sources+=@('NativeNetplayProfile','client/NativeSnapshotCore') |
        ForEach-Object {Join-Path $workspaceRoot ('piq-native-arcade/src/main/java/cn/piq/nativearcade/'+$_+'.java')}
}
$before=@{}
foreach($file in ($sources+@($FcJar,$NativeJar))){$before[$file]=(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash}
$classpath=(@($classes.FullName,$FcJar,$NativeJar)+$libraries)-join [IO.Path]::PathSeparator
& javac.exe --release 21 -encoding UTF-8 -proc:none -sourcepath $empty.FullName -cp $classpath -d $classes.FullName @sources
if($LASTEXITCODE -ne 0){throw 'Arcade presentation/watch test compilation failed'}
Push-Location $projectRoot
try{
    $result=& java.exe '-Dfile.encoding=UTF-8' -cp $classpath CabinetGameDedup35Runner $expected @($names | ForEach-Object {'cn.piq.fcarcade.'+$_})
    if($LASTEXITCODE -ne 0){throw 'Arcade presentation/watch JUnit failure'}
    $unit=$result | Select-Object -Last 1 | ConvertFrom-Json
    $probeResult=& java.exe '-Dfile.encoding=UTF-8' -cp $classpath ArcadePresentationWatchProbe
    if($LASTEXITCODE -ne 0){throw 'Packaged arcade profile/presentation probe failed'}
    $probe=$probeResult | Select-Object -Last 1 | ConvertFrom-Json
}finally{Pop-Location}
foreach($file in $before.Keys){if((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $before[$file]){throw "Input changed during QA: $file"}}
if(-not $unit.ok -or $unit.passed -ne $expected -or -not $probe.ok){throw 'Incomplete test result'}
$evidence=[ordered]@{ok=$true;mode=$(if($SourceOverlay){'source-overlay'}else{'final-jars'});junit=$unit;profileProbe=$probe;
    fcJar=$FcJar;fcSha256=$before[$FcJar];nativeJar=$NativeJar;nativeSha256=$before[$NativeJar];
    fakeCoreOnly=$true;nativeStarted=$false;minecraftStarted=$false;liveMultiplayerVerified=$false}
$json=$evidence | ConvertTo-Json -Depth 6
[IO.File]::WriteAllText((Join-Path $report.FullName 'result.json'),$json+"`n",[Text.UTF8Encoding]::new($false))
Write-Output $json
