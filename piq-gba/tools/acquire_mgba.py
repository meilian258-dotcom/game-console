"""Bounded official dependency retrieval. Never executes downloaded content."""
from pathlib import Path
import argparse, hashlib, io, json, struct, urllib.request, zipfile

ROOT = Path(__file__).resolve().parents[1]
URL = 'https://buildbot.libretro.com/nightly/windows/x86_64/latest/mgba_libretro.dll.zip'

def fetch(url, limit):
    req = urllib.request.Request(url, headers={'User-Agent': 'PIQ-GBA-feasibility/1'})
    with urllib.request.urlopen(req, timeout=40) as response:
        if not response.url.startswith('https://'):
            raise ValueError('HTTPS required')
        data = response.read(limit + 1)
        if len(data) > limit:
            raise ValueError('Dependency size exceeded budget')
        return data, response.url, dict(response.headers)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    output = Path(args.output).resolve()
    if not output.is_relative_to(ROOT) or output.exists():
        raise ValueError('New output inside piq-gba required')
    data, final_url, headers = fetch(URL, 2 * 1024 * 1024)
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        names = archive.namelist()
        if names != ['mgba_libretro.dll'] or archive.testzip():
            raise ValueError('Unexpected core ZIP inventory/CRC')
        entry = archive.getinfo(names[0])
        if entry.file_size > 8 * 1024 * 1024:
            raise ValueError('Unpacked DLL exceeds budget')
        dll = archive.read(entry)
    if dll[:2] != b'MZ':
        raise ValueError('Not PE')
    pe = struct.unpack_from('<I', dll, 0x3c)[0]
    if dll[pe:pe+4] != b'PE\0\0' or struct.unpack_from('<H', dll, pe+4)[0] != 0x8664:
        raise ValueError('Windows AMD64 required')
    output.mkdir(parents=True)
    (output/'mgba_libretro.dll.zip').write_bytes(data)
    (output/'mgba_libretro.dll').write_bytes(dll)
    sha = lambda b: hashlib.sha256(b).hexdigest().upper()
    report = {'schema':'piq-gba-dependency-acquisition-1','executed':False,
              'url':URL,'resolved_url':final_url,'headers':headers,
              'zip':{'bytes':len(data),'sha256':sha(data)},
              'dll':{'bytes':len(dll),'sha256':sha(dll),'machine':'AMD64'},
              'scope':'Downloaded from official libretro HTTPS; identity alone is not a security audit or source correspondence proof.'}
    (output/'acquisition.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(report,ensure_ascii=False))

if __name__ == '__main__':
    main()
