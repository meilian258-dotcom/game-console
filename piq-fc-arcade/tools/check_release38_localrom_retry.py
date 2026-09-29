"""One explicitly requested, unchanged LocalRomLibraryTest retry with new evidence files."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET

import build_release38 as b


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--log', type=Path, required=True)
    a = p.parse_args()
    log = b.safe_path(a.log)
    report, saved_xml = log.with_suffix('.json'), log.with_suffix('.xml')
    b.require(log.suffix == '.log' and log.is_relative_to(b.ROOT / 'outputs/release38'), 'New release38 log required')
    b.require(all(not x.exists() for x in [log, report, saved_xml]), 'Never overwrite retry evidence')
    production = b.ROOT / 'piq-fc-arcade/src/main/java/cn/piq/fcarcade/client/rom/LocalRomLibrary.java'
    test = b.ROOT / 'piq-fc-arcade/src/test/java/cn/piq/fcarcade/client/rom/LocalRomLibraryTest.java'
    before = {str(x): b.file_sha(x) for x in [production, test]}
    result = subprocess.run(['cmd.exe', '/d', '/c', 'gradlew.bat', 'test', '--offline', '--console=plain',
                             '--tests', 'cn.piq.fcarcade.client.rom.LocalRomLibraryTest'],
                            cwd=b.ROOT / 'piq-fc-arcade', env=dict(os.environ, JAVA_HOME=str(b.JAVA)),
                            capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=600)
    log.parent.mkdir(parents=True, exist_ok=True)
    with log.open('x', encoding='utf-8') as f:
        f.write(result.stdout + '\n' + result.stderr)
    source = b.ROOT / 'piq-fc-arcade/build/test-results/test/TEST-cn.piq.fcarcade.client.rom.LocalRomLibraryTest.xml'
    raw = b.safe_path(source, True).read_bytes()
    with saved_xml.open('xb') as f:
        f.write(raw)
    document = ET.fromstring(raw)
    counts = {k: int(document.attrib[k]) for k in ['tests', 'failures', 'errors', 'skipped']}
    unchanged = all(b.file_sha(Path(x)) == sha for x, sha in before.items())
    ok = result.returncode == 0 and unchanged and counts['tests'] == 13 and counts['errors'] == counts['failures'] == 0
    b.exclusive_json(report, dict(schema='piq-release38-focused-retry-1', ok=ok, attempt=1,
        source_unchanged=unchanged, source_sha256=before, counts=counts, log_sha256=b.file_sha(log),
        xml_sha256=b.digest(raw), assertions_modified=False, installed=False, minecraft_started=False))
    print(json.dumps(dict(ok=ok, log=str(log), report=str(report), counts=counts), ensure_ascii=False))
    b.require(ok, 'Unchanged isolated test retry failed; stop and report without more retries')


if __name__ == '__main__':
    main()
