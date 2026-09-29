"""Pin the official core to its embedded source commit, retaining full source."""
from pathlib import Path
import hashlib, io, json, re, struct, tarfile
from acquire_mgba import ROOT, fetch

COMMIT='e31759b24e7a4e3899285ff720d7b573ac328ae7'
CORE_SHA='D1BA96BC1AF23997D5C8003A6F6F8BE7ACBA9D770D4D42D14557AAEB469FA16B'
URL=f'https://codeload.github.com/libretro/mgba/tar.gz/{COMMIT}'

def pe_imports(data):
    u16=lambda p:struct.unpack_from('<H',data,p)[0]
    u32=lambda p:struct.unpack_from('<I',data,p)[0]
    pe=u32(0x3c); opt=pe+24
    if data[pe:pe+4]!=b'PE\0\0' or u16(pe+4)!=0x8664 or u16(opt)!=0x20b:
        raise ValueError('Not AMD64 PE32+')
    sections=pe+24+u16(pe+20)
    def offset(rva):
        for index in range(u16(pe+6)):
            p=sections+index*40; size=max(u32(p+8),u32(p+16)); address=u32(p+12)
            if address<=rva<address+size:
                result=u32(p+20)+rva-address
                if result>=len(data):raise ValueError('Bad RVA')
                return result
        raise ValueError('Unknown RVA')
    p=offset(u32(opt+120)); result=[]
    for _ in range(32):
        name=u32(p+12)
        if not name:return result
        n=offset(name); end=data.index(b'\0',n,n+256)
        result.append(data[n:end].decode('ascii').lower()); p+=20
    raise ValueError('Unbounded imports')

def main():
    runtime=ROOT/'runtime/incoming-mgba-20260911-v1'
    dll=(runtime/'mgba_libretro.dll').read_bytes()
    if hashlib.sha256(dll).hexdigest().upper()!=CORE_SHA or COMMIT.encode() not in dll:
        raise ValueError('Core changed or missing source identity')
    imports=pe_imports(dll)
    if set(imports)-{'kernel32.dll','msvcrt.dll','ole32.dll','shell32.dll','shlwapi.dll'}:
        raise ValueError(f'Unreviewed dependency {imports}')
    target=ROOT/'vendor'/f'mgba-{COMMIT}'
    if target.exists():raise FileExistsError(target)
    data,url,headers=fetch(URL,32*1024*1024)
    if len(data)>16*1024*1024:raise ValueError('Source archive budget needs review')
    total=0; selected={}; prefix=f'mgba-{COMMIT}/'
    with tarfile.open(fileobj=io.BytesIO(data),mode='r:gz') as archive:
        for member in archive:
            if member.isdir() and member.name==prefix.rstrip('/'):
                continue
            if not member.name.startswith(prefix) or '..' in member.name.split('/'):
                raise ValueError('Unsafe source archive')
            total+=member.size
            if total>100*1024*1024:raise ValueError('Inflated source budget')
            relative=member.name[len(prefix):]
            if relative in {'LICENSE','README.md','version.cmake','src/platform/libretro/libretro.c','src/platform/libretro/libretro-audio.h','src/platform/libretro/Makefile','src/platform/libretro/Makefile.common'} and member.isfile():
                selected[relative]=archive.extractfile(member).read()
    if b'Mozilla Public License' not in selected.get('LICENSE',b''):
        raise ValueError('Expected MPL license absent')
    target.mkdir(parents=True)
    (target/'source.tar.gz').write_bytes(data)
    for path,content in selected.items():
        p=target/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(content)
    report={'ok':True,'executed':False,'core_sha256':CORE_SHA,'embedded_commit':COMMIT,
            'embedded_version':'0.11-219-e31759b','source_url':url,
            'source_sha256':hashlib.sha256(data).hexdigest().upper(),'source_archive_bytes':len(data),
            'source_extracted_total_bytes':total,'pe_imports':imports,
            'source_correspondence':'Official binary embeds full commit; official source archived at that commit. Not independently reproducible-build verified.',
            'license':'MPL-2.0; full source archive retains third-party notices','headers':headers}
    (target/'source-verification.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(report))

if __name__=='__main__':main()
