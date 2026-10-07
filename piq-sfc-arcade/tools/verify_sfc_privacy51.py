"""Verify the full SFC candidate and original in isolated JVMs; no Minecraft launch.

The output is PRIVATE test scratch: it includes the original unredacted WASM.
Only its verification.json is suitable as a public validation receipt.
"""
# SPDX-License-Identifier: GPL-3.0-or-later
import argparse, hashlib, json, os, subprocess, zipfile
from pathlib import Path
from package_sfc_privacy51 import INPUT_JAR_SHA, MODULE

def sha(data): return hashlib.sha256(data).hexdigest()

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--original-jar',required=True,type=Path)
    parser.add_argument('--candidate-jar',required=True,type=Path)
    parser.add_argument('--fc-jar',required=True,type=Path)
    parser.add_argument('--java-home',required=True,type=Path)
    parser.add_argument('--output',required=True,type=Path)
    args=parser.parse_args()
    assert not args.output.exists(), 'Use a new scratch directory'
    before={path:sha(path.read_bytes()) for path in (args.original_jar,args.candidate_jar,args.fc_jar)}
    assert before[args.original_jar]==INPUT_JAR_SHA
    args.output.mkdir(parents=True)
    classes=args.output/'classes';classes.mkdir()
    dependencies=[]
    with zipfile.ZipFile(args.fc_jar) as jar:
        if 'ai/tegmentum/wasmtime4j/jni/JniWasmRuntime.class' in jar.namelist():
            # Current full FC artifacts include the runtime classes directly.
            dependencies.append(args.fc_jar)
        else:
            for name in jar.namelist():
                if name.startswith('META-INF/jarjar/') and name.endswith('.jar') and 'wasmtime4j' in name:
                    target=args.output/Path(name).name
                    target.write_bytes(jar.read(name));dependencies.append(target)
            assert len(dependencies)==3, 'Expected the three existing Wasmtime runtime modules'
    for source,name in [(args.original_jar,'original.wasm'),(args.candidate_jar,'candidate.wasm')]:
        with zipfile.ZipFile(source) as jar:(args.output/name).write_bytes(jar.read(MODULE))
    project=Path(__file__).resolve().parents[1]
    tests=project/'src/test/java/cn/piq/sfcarcade/core'
    sources=[tests/(name+'.java') for name in ('SfcLegalTestRom','SfcWasmRuntimeSmoke','SfcCoreSelfTest','SfcWasmPrivacyCompatibility')]
    javac=args.java_home/'bin/javac.exe';java=args.java_home/'bin/java.exe'
    cp=lambda jar:os.pathsep.join(map(str,[classes,jar,*dependencies]))
    result=subprocess.run([str(javac),'--release','21','-encoding','UTF-8','-cp',cp(args.candidate_jar),'-d',str(classes),*map(str,sources)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=60)
    if result.returncode:raise RuntimeError(result.stderr)
    runs=[]
    jobs=[('original-smoke',args.original_jar,'SfcWasmRuntimeSmoke',[]),
          ('candidate-smoke',args.candidate_jar,'SfcWasmRuntimeSmoke',[]),
          ('candidate-contract',args.candidate_jar,'SfcCoreSelfTest',[]),
          ('cross-version',args.candidate_jar,'SfcWasmPrivacyCompatibility',[str(args.output/'original.wasm'),str(args.output/'candidate.wasm')])]
    for label,jar,mainclass,extra in jobs:
        result=subprocess.run([str(java),'-Dfile.encoding=UTF-8','-cp',cp(jar),'cn.piq.sfcarcade.core.'+mainclass,*extra],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=180)
        # Logs remain private scratch and are not part of the public source bundle.
        (args.output/(label+'.log')).write_text(result.stdout+result.stderr,encoding='utf-8')
        if result.returncode:raise RuntimeError(label+' failed: '+result.stderr)
        item={'test':label,'exit_code':result.returncode}
        if label=='cross-version':item['result']=json.loads(result.stdout.strip().splitlines()[-1])
        runs.append(item)
        print(label+': passed',flush=True)
    assert all(sha(path.read_bytes())==value for path,value in before.items())
    report={'ok':True,'original_jar_sha256':before[args.original_jar],'candidate_jar_sha256':before[args.candidate_jar],
            'shared_runtime_jar_sha256':before[args.fc_jar],'inputs_unchanged':True,'runs':runs,
            'test_source_sha256':{path.name:sha(path.read_bytes()) for path in sources},
            'minecraft_client_tested':False,'commercial_rom_or_bios_used':False}
    (args.output/'verification.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(report,indent=2))

if __name__=='__main__':main()
