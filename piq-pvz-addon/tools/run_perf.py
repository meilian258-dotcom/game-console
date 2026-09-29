"""Paired standalone JAR test; records actual numbers, not predicted Minecraft FPS."""
import pathlib,tempfile,subprocess,json,hashlib
ROOT=pathlib.Path(__file__).resolve().parents[1]
OUT=ROOT.parent/'outputs/pvz-prototype2';OUT.mkdir(exist_ok=True)
CLASSES=pathlib.Path(tempfile.gettempdir())/'piq-pvz-frame-bench'
JAVA=pathlib.Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin/java.exe')
DATA=ROOT.parent/'outputs/pvz-prototype1/data/dist/system/pvz/main.pak'
entries={
    'v1':ROOT.parent/'制作Mod/03-街机模拟/方块电玩-PvZ测试附属-prototype1-20260924/game_console_pvz-0.1.0-prototype.1.jar',
    'v2':ROOT/'build/libs/game_console_pvz-0.1.0-prototype.2.jar'
}
results={}
for label,jar in entries.items():
    result=subprocess.run([str(JAVA),'-cp',str(CLASSES)+';'+str(jar),'FrameBenchmark',str(OUT/('paired-'+label)),str(DATA)],capture_output=True,timeout=55)
    (OUT/(label+'-bench.stdout')).write_bytes(result.stdout);(OUT/(label+'-bench.stderr')).write_bytes(result.stderr)
    assert result.returncode==0,(label,result.stderr)
    metrics=json.loads(result.stdout.decode('ascii').strip())
    results[label]={'jarSha':hashlib.sha256(jar.read_bytes()).hexdigest().upper(),'metrics':metrics}
    print(label,json.dumps(metrics),flush=True)
assert results['v2']['metrics']['fps']>50,'Do not ship without stable standalone frame pacing'
assert results['v2']['metrics']['fps']>results['v1']['metrics']['fps']*1.5
(OUT/'paired-performance.json').write_text(json.dumps(results,indent=2),encoding='utf-8')
