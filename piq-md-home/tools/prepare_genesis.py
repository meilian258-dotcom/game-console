"""Stage only the reviewed official GX artifact/license. Never executes or builds native code."""
from pathlib import Path
import argparse, hashlib, zipfile

ZIP_SHA = '36b60039012d38d99e651cfdf06da4ffa3e75f1aa7e50907389771d00564dbcb'
DLL_SHA = '9ffa10a115b20e1b49e9caf0b53f287c640ed4e5bb93f7ed9a23b416a4ccfdf7'
SOURCE_SHA = 'dd4f5ef7ad3bae410854da8d0d7b99c99f13caa1df77f4ef1f2b8f59be3b6977'
COMMIT = 'c2838c7dc4236fc2fe94e5dbd08b41486067918e'

def verified(path, sha):
    path = path.resolve(strict=True)
    if path.stat().st_size > 64*1024*1024:
        raise ValueError('Archive budget exceeded')
    if hashlib.sha256(path.read_bytes()).hexdigest() != sha:
        raise ValueError('Wrong archive SHA256: '+path.name)
    return zipfile.ZipFile(path)

def put(path, data):
    if path.exists() and path.read_bytes() != data:
        raise ValueError('Refusing to overwrite different input: '+str(path))
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        with path.open('xb') as f: f.write(data)

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--archive',type=Path,required=True)
    p.add_argument('--source',type=Path,required=True)
    a=p.parse_args()
    root=Path(__file__).resolve().parents[1]/'src/main/resources'
    with verified(a.archive, ZIP_SHA) as z:
        if z.namelist()!=['genesis_plus_gx_libretro.dll']:
            raise ValueError('Unexpected official archive contents')
        dll=z.read('genesis_plus_gx_libretro.dll')
        if hashlib.sha256(dll).hexdigest()!=DLL_SHA: raise ValueError('Wrong DLL')
    with verified(a.source,SOURCE_SHA) as z:
        license_bytes=z.read(f'Genesis-Plus-GX-{COMMIT}/LICENSE.txt')
    put(root/'core/windows-x64/genesis_plus_gx_libretro.dll',dll)
    put(root/'licenses/genesis-plus-gx/LICENSE.txt',license_bytes)
    print('Verified/staged official Genesis Plus GX '+COMMIT+'; native code not executed')

if __name__=='__main__': main()
