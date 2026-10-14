"""Validate the final compiled JAR and retained handshake target. Not a graphical test."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import subprocess
import tomllib
import zipfile

root = Path(__file__).resolve().parent
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--java-home', required=True, type=Path)
args = p.parse_args()
build = json.loads((root / 'build/build-result.json').read_text(encoding='utf-8'))
jar = Path(build['artifact'])
assert hashlib.sha256(jar.read_bytes()).hexdigest() == build['sha256']
values = [v.strip('"') for v in (root / 'build/javac.args').read_text(encoding='utf-8').splitlines()]
cp = values[values.index('-cp') + 1]
client = next(v for v in cp.split(os.pathsep) if Path(v).name == 'neoforge-21.1.250-client.jar')
target = subprocess.check_output([str(args.java_home / 'bin' / ('javap.exe' if os.name == 'nt' else 'javap')),
    '-p', '-s', '-cp', client, 'net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl'], encoding='utf-8')
assert '(Lnet/minecraft/client/Minecraft;Lnet/minecraft/network/Connection;Lnet/minecraft/client/multiplayer/CommonListenerCookie;)V' in target
assert 'private net.neoforged.neoforge.network.connection.ConnectionType connectionType;' in target
assert 'private boolean initializedConnection;' in target
with zipfile.ZipFile(jar) as z:
    assert z.testzip() is None
    config = json.loads(z.read('piq_replay_quickmode_compat.mixins.json'))
    meta = tomllib.loads(z.read('META-INF/neoforge.mods.toml').decode('utf-8'))
    assert meta['mods'][0]['version'] == '0.1.0-test.3'
    assert meta['mixins'][0]['config'] == 'piq_replay_quickmode_compat.mixins.json'
    assert len(config['client']) == 5
    for mixin in config['client']:
        assert (config['package'] + '.' + mixin).replace('.', '/') + '.class' in z.namelist()
    assert not any(n.startswith(('net/minecraft/', 'com/replaymod/', 'dev/ryanhcode/')) for n in z.namelist())
    assert not any(n.endswith(('.dll', '.so', '.rom', '.mcpr')) or 'Test.class' in n for n in z.namelist())
    assert b'MIT License' in z.read('META-INF/LICENSE')
result = {'ok': True, 'artifact_sha256': build['sha256'], 'jar_crc': 'pass',
          'mixin_resource_wiring': 'pass', 'actual_handshake_constructor_and_shadows': 'pass',
          'bundles_upstream_classes_or_core_or_recording': False, 'graphical_client_test': 'pending'}
(root / 'build/static-verification.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
print(json.dumps(result, indent=2))
