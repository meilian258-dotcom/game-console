"""Final-JAR-only GBA13 probes. Uses original diagnostic ROM, never user saves/ROMs.
Requires explicit local Java21/NeoForge dependency directory; does not download/deploy.
"""
import argparse,hashlib,json,os,subprocess,sys,tempfile,shutil
from pathlib import Path
R=Path(__file__).resolve().parents[1]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
    p=argparse.ArgumentParser()
    for k in ('fc','gba','dependencies','minecraft-client','jdk','output'):p.add_argument('--'+k,type=Path,required=True)
    a=p.parse_args();a.output.mkdir(parents=True,exist_ok=False)
    inputs={str(f):sha(f) for f in (a.fc,a.gba,a.minecraft_client)}
    # JVM Windows @argfiles may decode with the system code page before -Dfile.encoding.
    # Use owned ASCII copies; retain this directory and its identity for inspection.
    qa=Path(tempfile.mkdtemp(prefix='gc-gba13-qa-'));classes=qa/'probe-classes';classes.mkdir()
    fc=qa/'fc.jar';gba=qa/'gba.jar';shutil.copy2(a.fc,fc);shutil.copy2(a.gba,gba)
    assert sha(fc)==sha(a.fc) and sha(gba)==sha(a.gba)
    (a.output/'qa-path.json').write_text(json.dumps(dict(ownedQaDirectory=str(qa),inputs=inputs),ensure_ascii=False,indent=2),encoding='utf8')
    deps=[f for f in a.dependencies.glob('*.jar') if not f.name.startswith(('piq_','game-console'))]
    cp=os.pathsep.join(str(f.resolve()) for f in [classes,gba,fc,*deps,a.minecraft_client])
    args=a.output/'classpath.args';args.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf8')
    probe=[R/'tools/qa'/name for name in ('GbaCartridge13Probe.java','GbaHandheldClientProbe.java','GbaHandheldLayoutProbe.java','GbaJniProbe.java')]
    def run(name,command,timeout=90):
        with (a.output/(name+'.log')).open('xb') as log:code=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,timeout=timeout).returncode
        if code:raise RuntimeError(name+' failed; see '+str(a.output/(name+'.log')))
    run('compile',[str(a.jdk/'javac.exe'),'@'+str(args),'-encoding','UTF-8','--release','21','-proc:none','-d',str(classes),*map(str,probe)])
    java=[str(a.jdk/'java.exe'),'-Xcheck:jni','-Xmx768m','-Dfile.encoding=UTF-8','@'+str(args)]
    run('card',java+['GbaCartridge13Probe',str(gba)])
    run('gate',java+['cn.piq.gba.client.GbaHandheldClientProbe',str(qa/'gate'),str(gba)])
    run('layout',java+['cn.piq.gba.client.GbaHandheldLayoutProbe'])
    sys.path.insert(0,str(R/'tools'));from diagnostic_rom import create
    native=qa/'native';native.mkdir();(native/'diagnostic.gba').write_bytes(create())
    run('native',java+['GbaJniProbe',str(native.resolve())])
    assert all(sha(Path(f))==h for f,h in inputs.items())
    result=dict(ok=True,inputs=inputs,probes=[f.stem for f in probe],finalJarOnly=True,realMgbaJni=True,minecraftPlayerTested=False)
    (a.output/'receipt.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf8');print(json.dumps(result,ensure_ascii=False))
if __name__=='__main__':main()
