"""Build optional screen replay compat; requires explicit read-only, Mojmap client inputs.
Python 3.11+, JDK 21. Never launches or modifies a game instance.
"""
from pathlib import Path
import argparse, hashlib, json, os, subprocess, zipfile

VERSION = '0.1.0-test.6'
ROOT = Path(__file__).resolve().parent
REPLAY_API_VERSION = 1
PACKAGED_TEST = 'cn.piq.replaycompat.PackagedMetadataTest'

def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

def validate_build_directory(path):
    """Fresh scoped output only; preserve all previous classes, logs and artifacts."""
    if not path.is_absolute() or '..' in path.parts or not str(path).isascii():
        raise ValueError('--build-dir must be an absolute ASCII directory without parent traversal')
    if path == Path(path.anchor) or path == Path.home() or path == ROOT:
        raise ValueError('--build-dir must be a dedicated output directory')
    if os.name == 'nt' and (path.drive.startswith('\\\\') or path.drive.upper() == os.environ.get('SystemDrive', 'C:').upper()):
        raise ValueError('--build-dir must use a local non-system work drive on Windows')
    for parent in (path, *path.parents):
        if parent.is_symlink() or (hasattr(parent, 'is_junction') and parent.is_junction()):
            raise ValueError('--build-dir must not traverse a symlink or junction')
    if path.exists():
        raise FileExistsError('--build-dir already exists; use a fresh directory to preserve previous evidence')
    return path

def build_environment(temporary):
    env = dict(os.environ, TEMP=str(temporary), TMP=str(temporary))
    # Do not let unrelated global JVM flags redirect this task's temporary files.
    for key in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'CLASSPATH'):
        env.pop(key, None)
    return env

def verify_main_provider(fc):
    """Validate the actual public contract; no main-mod micro-version allowlist."""
    import sys
    sys.path.insert(0, str(ROOT.parent/'source-control'))
    import replay_api
    return replay_api.inspect_archive(fc, require=True)

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--java-home', required=True, type=Path)
    p.add_argument('--libraries', required=True, type=Path)
    p.add_argument('--mods', required=True, type=Path)
    p.add_argument('--build-dir', required=True, type=Path, help='Fresh absolute ASCII work directory; never overwrites a prior build')
    p.add_argument('--neoforge', default='21.1.250')
    p.add_argument('--minecraft-artifact', default='1.21.1-20240808.144430')
    p.add_argument('--version-json', type=Path, help='Resolved launcher version manifest, for exact dependency versions')
    p.add_argument('--fc', type=Path, help='Read-only alternate FC JAR for binary contract regression')
    p.add_argument('--without-iris', action='store_true', help='Exclude optional Iris from the test classpath; never changes installed mods')
    args = p.parse_args()
    build = validate_build_directory(args.build_dir)
    client = args.libraries / f'net/neoforged/neoforge/{args.neoforge}/neoforge-{args.neoforge}-client.jar'
    universal = client.with_name(f'neoforge-{args.neoforge}-universal.jar')
    mods = sorted(args.mods.glob('*.jar'))
    def mod(identifier, files=None):
        found=[]
        for file in mods if files is None else files:
            with zipfile.ZipFile(file) as z:
                for name in ('META-INF/neoforge.mods.toml','META-INF/mods.toml'):
                    if name not in z.namelist():continue
                    import tomllib
                    for m in tomllib.loads(z.read(name).decode('utf-8')).get('mods',[]):
                        if m.get('modId')==identifier:found.append((file,m.get('version')))
        if len(found)!=1:raise SystemExit(f'Need exactly one {identifier}, found {len(found)}')
        return found[0]
    replay, rv = mod('reforgedplaymod'); fc, fv = mod('piq_fc_arcade')
    # The optional capture adapter must also compile and pass with Iris absent.
    # Filter by actual mod ID, not an installer's filename, and retain the installed files.
    optional_inputs=[]
    for file in list(mods):
        with zipfile.ZipFile(file) as z:
            if 'META-INF/neoforge.mods.toml' not in z.namelist(): continue
            import tomllib
            entries=tomllib.loads(z.read('META-INF/neoforge.mods.toml').decode('utf-8')).get('mods',[])
        for entry in entries:
            if entry.get('modId')=='piq_replay_compat':
                mods.remove(file); break  # Never test against the already installed old patch.
            if entry.get('modId')=='iris':
                if args.without_iris: mods.remove(file)
                else: optional_inputs.append({'id':'iris','version':entry.get('version'),'sha256':sha(file)})
                break
    if args.fc:
        mods.remove(fc)
        fc,fv=mod('piq_fc_arcade',[args.fc]);mods.append(fc)
    if rv!='0.3':raise SystemExit('This build targets ReForgedPlay 0.3')
    # Main-mod micro versions are not the compatibility key. Validate the actual
    # provider descriptor, bytecode, ownership and explicit production call sites.
    verify_main_provider(fc)
    if not client.is_file() or not universal.is_file():raise SystemExit('Need named NeoForge client and universal JARs')
    vanilla = args.libraries / f'net/minecraft/client/{args.minecraft_artifact}/client-{args.minecraft_artifact}-srg.jar'
    if not vanilla.is_file():raise SystemExit('Need exact Minecraft 1.21.1 named SRG client JAR')
    if not args.minecraft_artifact.startswith('1.21.1-'):raise SystemExit('Only Minecraft 1.21.1 is supported')
    # Patched NeoForge client is a partial JAR. Its missing classes must come from the
    # same MC version, never whichever old installation is found first on disk.
    manifest = args.version_json or args.mods.parent/(args.mods.parent.name+'.json')
    if not manifest.is_file():raise SystemExit('Pass --version-json for the resolved 1.21.1 launcher manifest')
    libraries=[]
    for item in json.loads(manifest.read_text(encoding='utf-8')).get('libraries',[]):
        rules=item.get('rules',[]); allowed=not rules
        platform='windows' if os.name=='nt' else ('osx' if __import__('sys').platform=='darwin' else 'linux')
        for rule in rules:
            if rule.get('os',{}).get('name',platform)==platform and not rule.get('features'):
                allowed=rule.get('action')=='allow'
        if not allowed:continue
        relative=item.get('downloads',{}).get('artifact',{}).get('path')
        if not relative or 'natives-' in relative:continue
        file=args.libraries/relative
        if not file.is_file():raise SystemExit(f'Missing required library: {relative}')
        libraries.append(file)
    if not libraries:raise SystemExit('Launcher manifest must contain resolved libraries')
    build.mkdir(parents=True,exist_ok=False)
    temporary=build/'tmp';temporary.mkdir()
    for directory in ('jna','wasmtime'):(temporary/directory).mkdir()
    env=build_environment(temporary)
    # Use the loader's existing MixinExtras, never bundle a second copy in the addon.
    mixinextras=build/'mixinextras-neoforge-0.5.3.jar'
    with zipfile.ZipFile(universal) as z:
        nested='META-INF/jarjar/mixinextras-neoforge-0.5.3.jar'
        if nested not in z.namelist():raise SystemExit('Need the reviewed NeoForge MixinExtras 0.5.3 input')
        nested_bytes=z.read(nested)
    if mixinextras.exists() and mixinextras.read_bytes()!=nested_bytes:
        raise SystemExit('Cached MixinExtras differs from the specified NeoForge; preserve it and inspect inputs')
    if not mixinextras.exists():mixinextras.write_bytes(nested_bytes)
    cp = list(dict.fromkeys([client,vanilla,universal,mixinextras,replay,fc,*mods,*libraries]))
    # Each invocation has a fresh directory so removed source classes cannot leak into a new JAR.
    import tempfile
    out=Path(tempfile.mkdtemp(prefix='classes-',dir=build))
    main_sources=sorted((ROOT/'src/main/java').rglob('*.java'))
    test_sources=sorted((ROOT/'src/test/java').rglob('*.java'))
    javac=args.java_home/'bin/javac.exe'; java=args.java_home/'bin/java.exe'
    if os.name!='nt':javac=javac.with_suffix('');java=java.with_suffix('')
    params=['-proc:none','--release','21','-encoding','UTF-8','-cp',os.pathsep.join(map(str,cp)),'-d',str(out),*map(str,main_sources+test_sources)]
    argfile=build/'javac.args'
    argfile.write_text('\n'.join('"'+v.replace('\\','/').replace('"','\\"')+'"' for v in params),encoding='utf-8')
    subprocess.run([str(javac),'-J-Dfile.encoding=UTF-8','-J-Duser.language=en',
                    '-J-Djava.io.tmpdir='+str(temporary),'@'+str(argfile)],check=True,env=env)
    tests=[]
    def run_test(class_name,properties=()):
        run_args=['-ea','-Dfile.encoding=UTF-8','-Djava.io.tmpdir='+str(temporary),
                  '-Djna.tmpdir='+str(temporary/'jna'),'-Dwasmtime4j.native.tmpdir='+str(temporary/'wasmtime'),
                  '-XX:ErrorFile='+str(temporary/'hs_err_pid%p.log'),
                  '-Dpiq.replay.test.temp='+str(temporary),*properties,
                  '-cp',os.pathsep.join([str(out),*map(str,cp)]),class_name]
        # Direct arguments preserve Unicode dependency paths on Windows.
        subprocess.run([str(java),*run_args],check=True,cwd=ROOT.parent,env=env)
        tests.append(class_name)
    for source in test_sources:
        if 'public static void main(' not in source.read_text(encoding='utf-8'):continue
        class_name='.'.join(source.relative_to(ROOT/'src/test/java').with_suffix('').parts)
        if class_name!=PACKAGED_TEST:run_test(class_name)
    test_names={str(s.relative_to(ROOT/'src/test/java').with_suffix('')).replace('\\','/') for s in test_sources}
    libs=build/'libs';libs.mkdir(exist_ok=True)
    jar=libs/f'game-console-replay-compat-1.21.1-{VERSION}.jar'
    def pack(target,api_only=False):
        with zipfile.ZipFile(target,'w',zipfile.ZIP_DEFLATED) as z:
            def add(name,data):
                info=zipfile.ZipInfo(name,(2026,10,10,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED
                info.external_attr=0o100644<<16;z.writestr(info,data)
            for file in sorted(out.rglob('*.class')):
                name=file.relative_to(out).as_posix()
                if name.split('$')[0].removesuffix('.class') in test_names:continue
                if api_only and not name.startswith('cn/piq/replaycompat/api/'):continue
                add(name,file.read_bytes())
            if not api_only:
                for file in sorted((ROOT/'src/main/resources').rglob('*')):
                    if file.is_file():add(file.relative_to(ROOT/'src/main/resources').as_posix(),file.read_bytes())
            add('META-INF/LICENSE-piq-replay-compat',(ROOT/'LICENSE').read_bytes())
            add('META-INF/MANIFEST.MF',f'Manifest-Version: 1.0\nImplementation-Version: {VERSION}\n\n'.encode())
    pack(jar);api=libs/f'game-console-screen-api-{VERSION}.jar';pack(api,True)
    # Verify the artifact actually being delivered, not merely the source TOML.
    run_test(PACKAGED_TEST,('-Dpiq.replay.test.jar='+str(jar),'-Dpiq.replay.test.version='+VERSION))
    result={'version':VERSION,'artifact':jar.name,'sha256':sha(jar),'bytes':jar.stat().st_size,
            'api_artifact':api.name,'api_sha256':sha(api),'fc_version':fv,'fc_sha256':sha(fc),
            'replay_version':rv,'replay_sha256':sha(replay),'neoforge':args.neoforge,'tests':tests,
            'optional_inputs':optional_inputs,'iris_excluded':args.without_iris,
            'replay_api_version':REPLAY_API_VERSION,'packaged_metadata_verified':True,
            'temporary_workspace':'isolated build/tmp; no global environment change',
            'status':'compiled; automated tests passed; Minecraft graphical validation pending'}
    (build/'verification.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(result,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
