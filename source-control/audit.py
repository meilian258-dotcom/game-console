"""Read-only source/index guard. Prints paths/rule names, never matched secrets.

Python 3.11+, standard library only. This is a guardrail, not a security certification.
"""
from __future__ import annotations
import argparse, fnmatch, hashlib, json, re, subprocess, sys
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[1]
MODULES = (
    'piq-fc-arcade', 'piq-retro-platform', 'piq-sfc-home', 'piq-sfc-arcade',
    'piq-native-arcade', 'piq-gba', 'piq-j2me-arcade', 'piq-computer',
    'piq-flash-box', 'piq-pvz-addon', 'piq-md-home',
)
ROOT_FILES = {'.gitignore', '.gitattributes', '.gitmodules', 'GIT_WORKFLOW.md', 'README.md'}
APPROVED_WORKFLOWS = {'.github/workflows/snapshot.yml',
                      '.github/workflows/mame-lifecycle-candidate.yml'}
APPROVED_SUBMODULES = {
    'piq-pvz-addon/vendor/PvZ-Portable': {
        'url': 'https://github.com/KLuoNuoYa/PvZ-Portable.git', 'branch': 'libretro',
    },
}
BLOCKED_DIRS = {
    '.git', '.gradle', '.toolchains', '.idea', '.vscode', '.vs', '__pycache__',
    'node_modules', 'target', 'build', 'bin', 'obj', 'out', 'candidates',
    'run', 'logs', 'game-console', 'private-qa', 'private', 'backups', 'saves',
    'roms', 'bios',
}
BLOCKED_SUFFIXES = set('log pyc pyo class o obj pdb ilk lib a rlib rmeta dmp tmp bak swp args jar dll so dylib exe wasm zip 7z rar tar gz xz nupkg nes sfc smc gba gb gbc gen rom iso chd cue swf pak sav srm state mca dat bin pem key pfx p12 jks keystore'.split())
SECRET_RULES = {
    'private-key': re.compile(rb'-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----'),
    'github-token': re.compile(rb'\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,})\b'),
    'google-api-key': re.compile(rb'\bAIza[A-Za-z0-9_-]{30,}\b'),
    'aws-access-key': re.compile(rb'\b(?:AKIA|ASIA)[A-Z0-9]{16}\b'),
    'credential-url': re.compile(rb'https?://[^\s/:@<>"\x27]{1,64}:[^\s/@<>"\x27]{8,128}@'),
}
ASSIGNMENT = re.compile(rb'''(?i)\b(?:password|passwd|api_key|apiKey|access_token|secret_key)\s*["']?\s*[:=]\s*["']([^"'\r\n]{12,256})["']''')
PLACEHOLDER = re.compile(rb'(?i)(example|placeholder|test|dummy|changeme|your[_ -]|<|\$|\{|\\|\.encode\(|not-a-real|redacted)')

def git(*args: str) -> bytes:
    return subprocess.check_output(['git', '-C', str(ROOT), *args], stderr=subprocess.PIPE)

def path_issues(name: str) -> list[str]:
    p = PurePosixPath(name)
    parts = p.parts
    issues = []
    if not parts or p.is_absolute() or '..' in parts or '\\' in name or ':' in name:
        return ['unsafe-path']
    if name not in ROOT_FILES | APPROVED_WORKFLOWS and parts[0] not in (*MODULES, 'source-control'):
        issues.append('outside-source-scope')
    if any(part.lower() in BLOCKED_DIRS or part.lower().startswith('publish') for part in parts[:-1]):
        issues.append('generated-or-private-directory')
    if len(parts) > 2 and parts[0] in MODULES and parts[1].lower() == 'world':
        issues.append('runtime-world-directory')
    low = p.name.lower()
    if (low.startswith('.env') or low in {'credentials.json', 'secrets.json', 'local.properties'}
            or '登录信息' in low or '服务器信息' in low):
        issues.append('credential-file')
    wrapper = len(parts) >= 3 and parts[-3:] == ('gradle', 'wrapper', 'gradle-wrapper.jar')
    if p.suffix.lower().lstrip('.') in BLOCKED_SUFFIXES and not wrapper:
        issues.append('binary-content-or-generated-file')
    if name.startswith('piq-fc-arcade/design/zapper-private/') or fnmatch.fnmatch(low, 'frame-*.png'):
        issues.append('private-game-capture')
    if name.startswith('source-control/reports/'):
        issues.append('local-audit-report')
    return issues

def content_issues(name: str, data: bytes) -> list[str]:
    issues = []
    if len(data) > 10 * 1024 * 1024:
        issues.append('over-10MiB-review-required')
    for key, pattern in SECRET_RULES.items():
        if pattern.search(data): issues.append(key)
    for match in ASSIGNMENT.finditer(data):
        if not PLACEHOLDER.search(match.group(1)):
            issues.append('possible-secret-literal'); break
    if data.startswith((b'MZ', b'\x7fELF', b'\x00asm', b'\xca\xfe\xba\xbe')):
        issues.append('executable-magic')
    if data.startswith(b'PK\x03\x04') and not name.endswith('/gradle/wrapper/gradle-wrapper.jar'):
        issues.append('archive-magic')
    if data.startswith(b'NES\x1a'):
        issues.append('rom-magic')
    return issues

def index_entries():
    entries = []
    for raw in git('ls-files', '--stage', '-z').split(b'\0'):
        if not raw: continue
        meta, name = raw.split(b'\t', 1)
        mode, oid, stage = meta.split()
        entries.append((name.decode('utf8'), oid, mode, stage))
    return entries


def working_files(entries=None):
    entries = index_entries() if entries is None else entries
    tracked = {name for name, _, _, _ in entries}
    links = {name for name, _, mode, _ in entries if mode == b'160000'}
    unmerged = {name for name, _, _, stage in entries if stage != b'0'}
    new = git('ls-files', '--others', '--exclude-standard', '-z').split(b'\0')
    for name in sorted(tracked | {raw.decode('utf8') for raw in new if raw}):
        if name in links:
            yield name, None, 'non-regular-or-unmerged' if name in unmerged else None
            continue
        p = ROOT / name
        if not p.is_file() or p.is_symlink() or p.resolve() != p.absolute():
            yield name, None, 'missing-or-link'; continue
        yield name, p.read_bytes(), 'non-regular-or-unmerged' if name in unmerged else None


def indexed_files(entries=None):
    entries = index_entries() if entries is None else entries
    # Gitlink commits live in the other repository, not this object database.
    blobs = [entry for entry in entries if entry[2] != b'160000']
    for name, _, mode, stage in entries:
        if mode == b'160000':
            yield name, None, None if stage == b'0' else 'non-regular-or-unmerged'
    proc = subprocess.run(['git', '-C', str(ROOT), 'cat-file', '--batch'],
                          input=b''.join(oid + b'\n' for _, oid, _, _ in blobs),
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
    data = proc.stdout; offset = 0
    for name, oid, mode, stage in blobs:
        end = data.index(b'\n', offset)
        header = data[offset:end].split()
        offset = end + 1
        if len(header) == 2 and header[1] == b'missing':
            yield name, None, 'missing-index-object'; continue
        if len(header) != 3:
            raise ValueError('Invalid object header: ' + name)
        size = int(header[2])
        blob = data[offset:offset + size]; offset += size + 1
        if len(header) != 3 or header[1] != b'blob':
            yield name, None, 'non-blob-index-object'; continue
        yield name, blob, None if mode in (b'100644', b'100755') and stage == b'0' else 'non-regular-or-unmerged'


def submodule_config(data: bytes | None):
    """Accept only our reviewed, ordinary Git config subset; never execute it."""
    if data is None:
        return {}, ['missing-gitmodules']
    try:
        data.decode('utf8')
        # Let Git parse its own syntax; includes are data, never followed.
        parsed = subprocess.run(['git', 'config', '--no-includes', '--null', '--file', '-', '--list'],
                                input=data, capture_output=True, check=False)
        if parsed.returncode:
            return {}, ['invalid-gitmodules']
        allowed = {
            'submodule.' + name + '.' + key: (name, key, value)
            for name, config in APPROVED_SUBMODULES.items()
            for key, value in dict(path=name, **config).items()
        }
        declared = {}; seen = set()
        for raw in parsed.stdout.split(b'\0'):
            if not raw: continue
            key, sep, value = raw.decode('utf8').partition('\n')
            if not sep or key not in allowed or key in seen:
                return {}, ['unapproved-submodule-config']
            seen.add(key)
            name, field, expected = allowed[key]
            if value != expected:
                return {}, ['unapproved-submodule-config']
            declared.setdefault(name, {})[field] = value
        if any(set(values) != {'path', 'url', 'branch'} for values in declared.values()):
            return {}, ['unapproved-submodule-config']
        return declared, []
    except (UnicodeError, OSError):
        return {}, ['invalid-gitmodules']


def working_submodule(name: str, oid: bytes):
    """Read checkout metadata only; no fetch, hooks, build or recursive source audit."""
    path = ROOT / name
    if path.is_symlink() or path.resolve() != path.absolute():
        return 'invalid', ['submodule-path-is-link']
    if not path.exists():
        return 'uninitialized', []
    if not path.is_dir():
        return 'invalid', ['submodule-path-not-directory']
    dotgit = path / '.git'
    if not dotgit.exists():
        return ('invalid', ['submodule-directory-not-checkout']) if any(path.iterdir()) else ('uninitialized', [])
    if dotgit.is_symlink() or dotgit.resolve() != dotgit.absolute():
        return 'invalid', ['submodule-git-metadata-is-link']
    def read(*args):
        return subprocess.run(['git', '-c', 'core.fsmonitor=false', '-C', str(path), *args],
                              capture_output=True, check=True).stdout
    try:
        top = Path(read('rev-parse', '--show-toplevel').decode('utf8').strip()).resolve()
        if top != path.resolve():
            return 'invalid', ['submodule-checkout-path-mismatch']
        head = read('rev-parse', '--verify', 'HEAD').strip()
        issues = [] if head == oid else ['submodule-checkout-pin-mismatch']
        if read('status', '--porcelain=v1', '-z', '--untracked-files=all', '--ignore-submodules=none'):
            issues.append('submodule-dirty-checkout')
        return 'initialized', issues
    except (OSError, UnicodeError, subprocess.CalledProcessError):
        return 'invalid', ['submodule-checkout-unreadable']


def inspect_submodules(entries, candidates, staged):
    links = [(name, oid, stage) for name, oid, mode, stage in entries if mode == b'160000']
    config_files = [(data, problem) for name, data, problem in candidates if name == '.gitmodules']
    findings = [dict(path=name, rules=['approved-submodule-path-not-gitlink'])
                for name, _, mode, _ in entries if name in APPROVED_SUBMODULES and mode != b'160000']
    records = []
    if not links and not config_files:
        return findings, records
    data = config_files[0][0] if len(config_files) == 1 and config_files[0][1] is None else None
    declared, issues = submodule_config(data)
    if issues:
        findings.append(dict(path='.gitmodules', rules=issues))
    names = {name for name, _, _ in links}
    for name in sorted(set(declared) - names):
        findings.append(dict(path=name, rules=['declared-submodule-not-gitlink']))
    for name, oid, stage in links:
        rules = []
        if name not in APPROVED_SUBMODULES: rules.append('unapproved-gitlink')
        if name not in declared: rules.append('gitlink-without-approved-config')
        if stage != b'0': rules.append('non-regular-or-unmerged')
        if not re.fullmatch(rb'(?:[0-9a-f]{40}|[0-9a-f]{64})', oid) or not oid.strip(b'0'):
            rules.append('invalid-gitlink-pin')
        if any(other.startswith(name + '/') for other, _, _, _ in entries):
            rules.append('gitlink-overlapping-index-path')
        record = dict(path=name, commit=oid.decode('ascii', errors='replace'),
                      auditScope='gitlink-metadata-only; submodule source not recursively audited',
                      checkout='not-inspected')
        if not rules:
            record.update(APPROVED_SUBMODULES[name])
            if not staged:
                record['checkout'], checkout_issues = working_submodule(name, oid)
                rules += checkout_issues
        records.append(record)
        if rules: findings.append(dict(path=name, rules=rules))
    return findings, records


def audit(staged=False):
    findings=[]; files=[]; counts={}
    entries = index_entries()
    candidates = list(indexed_files(entries) if staged else working_files(entries))
    for name, data, problem in candidates:
        issues = path_issues(name) + ([problem] if problem else [])
        if data is not None:
            issues += content_issues(name, data)
            files.append(dict(path=name, bytes=len(data), sha256=hashlib.sha256(data).hexdigest()))
            component=name.split('/')[0];counts[component]=counts.get(component,0)+1
        if issues: findings.append(dict(path=name, rules=issues))
    submodule_findings, submodules = inspect_submodules(entries, candidates, staged)
    findings += submodule_findings
    return dict(ok=not findings, mode='index' if staged else 'working-tree',
                count=len(files), bytes=sum(f['bytes'] for f in files), components=counts,
                findings=findings, files=files, submodules=submodules)

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--staged',action='store_true');parser.add_argument('--report',type=Path)
    args=parser.parse_args();result=audit(args.staged)
    if args.report:
        args.report.parent.mkdir(parents=True,exist_ok=True)
        with args.report.open('x',encoding='utf8') as f:json.dump(result,f,ensure_ascii=False,indent=2)
    summary={k:v for k,v in result.items() if k!='files'}
    print(json.dumps(summary,ensure_ascii=True,indent=2));return 0 if result['ok'] else 1

if __name__=='__main__':sys.exit(main())
