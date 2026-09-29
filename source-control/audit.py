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
ROOT_FILES = {'.gitignore', '.gitattributes', 'GIT_WORKFLOW.md'}
BLOCKED_DIRS = {
    '.git', '.gradle', '.toolchains', '.idea', '.vscode', '.vs', '__pycache__',
    'node_modules', 'target', 'build', 'bin', 'obj', 'out', 'candidates',
    'run', 'logs', 'game-console', 'private-qa', 'private', 'backups', 'saves',
    'world', 'roms', 'bios',
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
    if name not in ROOT_FILES and parts[0] not in (*MODULES, 'source-control'):
        issues.append('outside-source-scope')
    if any(part.lower() in BLOCKED_DIRS or part.lower().startswith('publish') for part in parts[:-1]):
        issues.append('generated-or-private-directory')
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

def working_files():
    tracked = git('ls-files', '-z').split(b'\0')
    new = git('ls-files', '--others', '--exclude-standard', '-z').split(b'\0')
    for raw in sorted(set(tracked + new)):
        if not raw: continue
        name = raw.decode('utf8')
        p = ROOT / name
        if not p.is_file() or p.is_symlink() or p.resolve() != p.absolute():
            yield name, None, 'missing-or-link'; continue
        yield name, p.read_bytes(), None

def indexed_files():
    entries = []
    for raw in git('ls-files', '--stage', '-z').split(b'\0'):
        if not raw: continue
        meta, name = raw.split(b'\t', 1)
        mode, oid, stage = meta.split()
        entries.append((name.decode('utf8'), oid, mode, stage))
    proc = subprocess.run(['git', '-C', str(ROOT), 'cat-file', '--batch'],
                          input=b''.join(oid + b'\n' for _, oid, _, _ in entries),
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
    data = proc.stdout; offset = 0
    for name, oid, mode, stage in entries:
        end = data.index(b'\n', offset)
        header = data[offset:end].split()
        if len(header) != 3 or header[1] != b'blob':
            raise ValueError('Non-blob index entry: ' + name)
        size = int(header[2]); offset = end + 1
        blob = data[offset:offset + size]; offset += size + 1
        yield name, blob, None if mode in (b'100644', b'100755') and stage == b'0' else 'non-regular-or-unmerged'

def audit(staged=False):
    findings=[]; files=[]; counts={}
    for name, data, problem in (indexed_files() if staged else working_files()):
        issues = path_issues(name) + ([problem] if problem else [])
        if data is not None:
            issues += content_issues(name, data)
            files.append(dict(path=name, bytes=len(data), sha256=hashlib.sha256(data).hexdigest()))
            component=name.split('/')[0];counts[component]=counts.get(component,0)+1
        if issues: findings.append(dict(path=name, rules=issues))
    return dict(ok=not findings, mode='index' if staged else 'working-tree',
                count=len(files), bytes=sum(f['bytes'] for f in files), components=counts,
                findings=findings, files=files)

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--staged',action='store_true');parser.add_argument('--report',type=Path)
    args=parser.parse_args();result=audit(args.staged)
    if args.report:
        args.report.parent.mkdir(parents=True,exist_ok=True)
        with args.report.open('x',encoding='utf8') as f:json.dump(result,f,ensure_ascii=False,indent=2)
    summary={k:v for k,v in result.items() if k!='files'}
    print(json.dumps(summary,ensure_ascii=True,indent=2));return 0 if result['ok'] else 1

if __name__=='__main__':sys.exit(main())
