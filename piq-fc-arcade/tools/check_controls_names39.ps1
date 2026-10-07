param(
    [string]$FcJar = '',
    [string]$ReportDirectory = ''
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$workspaceRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '..'))
if (-not $ReportDirectory) { $ReportDirectory = Join-Path $projectRoot ('build/controls-names39-' + [Guid]::NewGuid().ToString('N')) }
if (Test-Path -LiteralPath $ReportDirectory) { throw 'Refuse to overwrite existing QA evidence' }
$reportRoot = New-Item -ItemType Directory -Path $ReportDirectory
$classes = New-Item -ItemType Directory -Path (Join-Path $reportRoot 'classes')
$empty = New-Item -ItemType Directory -Path (Join-Path $reportRoot 'empty')
$toolGradleHome = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path ([Environment]::GetFolderPath('UserProfile')) '.gradle' }
$junitCache = Join-Path $toolGradleHome 'caches/modules-2/files-2.1'
$jars = @()
foreach ($dependency in @(@('org.junit.platform','1.13.4'),@('org.junit.jupiter','5.13.4'),@('org.opentest4j','1.3.0'),@('org.apiguardian','1.1.2'))) {
    $jars += Get-ChildItem -LiteralPath (Join-Path $junitCache $dependency[0]) -Filter '*.jar' -Recurse |
        Where-Object { $_.FullName.Replace('\','/').Contains('/' + $dependency[1] + '/') -and $_.Name -notmatch '-(sources|javadoc)\.jar$' } |
        ForEach-Object FullName
}
if ($jars.Count -lt 7) { throw 'Required cached JUnit dependencies missing' }
$tests = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'src/test/java/cn/piq/retro/client') -Filter 'Keyboard*Test.java' | Sort-Object Name)
if ($tests.Count -ne 6) { throw 'Review added/removed keyboard suites before running' }
$expected = 0
foreach ($test in $tests) { $expected += [regex]::Matches([IO.File]::ReadAllText($test.FullName),'@Test\b').Count }
$runner = Join-Path $PSScriptRoot 'qa/CabinetGameDedup35Runner.java'
$production = @('KeyboardConfig','KeyboardConfigStore','KeyboardControlState','KeyboardRouting','KeyboardPresentation') |
    ForEach-Object { Join-Path $projectRoot ('src/main/java/cn/piq/retro/client/' + $_ + '.java') }
$sources = @($tests.FullName) + @($runner)
$mode = 'source-pure-tests'
$fcHash = $null
if ($FcJar) {
    $FcJar = (Resolve-Path -LiteralPath $FcJar).Path
    $fcHash = (Get-FileHash -LiteralPath $FcJar -Algorithm SHA256).Hash
    $jars += $FcJar
    $mode = 'final-jar-only-pure-tests'
} else { $sources += $production }
$before = @{}
foreach ($source in $sources) { $before[$source] = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash }
$classpath = (@($classes.FullName) + $jars) -join [IO.Path]::PathSeparator
& javac.exe --release 21 -encoding UTF-8 -proc:none -sourcepath $empty.FullName -cp $classpath -d $classes.FullName @sources
if ($LASTEXITCODE -ne 0) { throw 'Pure keyboard test compilation failed' }
$names = @($tests | ForEach-Object { 'cn.piq.retro.client.' + $_.BaseName })
$result = & java.exe '-Dfile.encoding=UTF-8' -cp $classpath CabinetGameDedup35Runner $expected @names
if ($LASTEXITCODE -ne 0) { throw 'Pure keyboard tests failed' }
$unit = $result | Select-Object -Last 1 | ConvertFrom-Json
if (-not $unit.ok -or $unit.passed -ne $expected) { throw 'Invalid JUnit result' }
foreach ($source in $sources) { if ((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -ne $before[$source]) { throw 'Source changed during QA' } }
if ($FcJar -and (Get-FileHash -LiteralPath $FcJar -Algorithm SHA256).Hash -ne $fcHash) { throw 'Final JAR changed during QA' }

# Language checks compare against the immutable FC38 companion baseline. Only
# translation values may change; placeholder tokens and every key are retained.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$baseline = Join-Path $projectRoot 'build/review-release38-v1'
$projects = @(
    @('piq-fc-arcade','piq_fc_arcade','piq_fc_arcade-0.31.0-alpha.38.jar'),
    @('piq-sfc-home','piq_sfc_home','piq_sfc-0.1.0-alpha.22.jar'),
    @('piq-sfc-arcade','piq_sfc_arcade','piq_sfc-0.1.0-alpha.22.jar'),
    @('piq-gba','piq_gba','piq_gba-0.1.0-alpha.6.jar'),
    @('piq-native-arcade','piq_native_arcade','piq_native_arcade-0.1.0-alpha.13.jar')
)
$exact = @{
    'block.piq_fc_arcade.famicom_console' = @('FC家用游戏机（FC）','Home Console (FC)')
    'item.piq_fc_arcade.famicom_console' = @('FC家用游戏机（FC）','Home Console (FC)')
    'block.piq_fc_arcade.subor_console' = @('大霸王学习机','DaBawang Learning Computer')
    'block.piq_fc_arcade.legacy_fc_arcade' = @('单人街机','Single-Player Arcade Cabinet')
    'item.piq_fc_arcade.legacy_fc_arcade' = @('单人街机','Single-Player Arcade Cabinet')
    'block.piq_fc_arcade.dual_cabinet' = @('双人街机','Two-Player Arcade Cabinet')
    'block.piq_native_arcade.cabinet' = @('双人街机','Two-Player Arcade Cabinet')
    'block.piq_sfc_home.console' = @('SFC家用游戏机（SFC）','Home Console (SFC)')
    'item.piq_gba.handheld' = @('GBA掌上游戏机（GBA）','Handheld Console (GBA)')
}
$languageReports = @()
foreach ($project in $projects) {
    $archive = [IO.Compression.ZipFile]::OpenRead((Join-Path $baseline $project[2]))
    try {
        $localeIndex = 0
        foreach ($locale in @('zh_cn','en_us')) {
            $entryName = 'assets/' + $project[1] + '/lang/' + $locale + '.json'
            $path = Join-Path $workspaceRoot ($project[0] + '/src/main/resources/' + $entryName)
            $text = [IO.File]::ReadAllText($path)
            $current = $text | ConvertFrom-Json -AsHashtable
            $keyMatches = [regex]::Matches($text,'"([^"\\]+)"\s*:')
            if ($keyMatches.Count -ne $current.Count) { throw "Duplicate translation key: $path" }
            $entry = $archive.GetEntry($entryName)
            if ($null -eq $entry) { throw "Missing baseline language: $entryName" }
            $reader = [IO.StreamReader]::new($entry.Open())
            try { $original = $reader.ReadToEnd() | ConvertFrom-Json -AsHashtable } finally { $reader.Dispose() }
            if (@(Compare-Object @($original.Keys | Sort-Object) @($current.Keys | Sort-Object)).Count -ne 0) { throw "Translation IDs changed: $path" }
            $changed = @()
            foreach ($key in $current.Keys) {
                if ($current[$key] -cmatch '\bPIQ\b') { throw "Unexpected display brand: $key" }
                if ($exact.ContainsKey($key) -and $current[$key] -cne $exact[$key][$localeIndex]) { throw "Approved name mismatch: $key" }
                $oldPlaceholders = [regex]::Matches($original[$key],'%(?:[0-9]+\$)?[sd%]') | ForEach-Object Value
                $newPlaceholders = [regex]::Matches($current[$key],'%(?:[0-9]+\$)?[sd%]') | ForEach-Object Value
                if (($oldPlaceholders -join '|') -cne ($newPlaceholders -join '|')) { throw "Format placeholders changed: $key" }
                if ($current[$key] -cne $original[$key]) { $changed += $key }
            }
            $languageReports += @{ path=$path; entries=$current.Count; changed_keys=@($changed | Sort-Object); sha256=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash }
            $localeIndex++
        }
    } finally { $archive.Dispose() }
}
$report = @{
    ok=$true; mode=$mode; unit=$unit; fc_jar=$FcJar; fc_sha256=$fcHash;
    source_sha256=$before; languages=$languageReports;
    minecraft_started=$false; native_core_started=$false; installed=$false;
    limits=@('Pure keyboard/config/routing tests only; no actual Minecraft UI, OS key delivery or multiplayer test.','Language source keys/placeholders checked against frozen baseline; this is not a final companion-JAR packaging check.')
}
$report | ConvertTo-Json -Depth 8 | Out-File -LiteralPath (Join-Path $reportRoot 'report.json') -Encoding utf8
$report | Select-Object ok,mode,unit,@{Name='language_files';Expression={$_.languages.Count}},@{Name='report';Expression={Join-Path $reportRoot 'report.json'}} | ConvertTo-Json -Depth 4
