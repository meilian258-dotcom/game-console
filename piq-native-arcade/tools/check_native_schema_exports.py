"""Read-only PE export inventory: never loads the DLL or invokes its native code."""
import argparse,json,mmap,struct
from pathlib import Path
import check_native_determinism as old

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--report',type=Path,required=True);a=p.parse_args()
    if a.report.exists():raise FileExistsError(a.report)
    identity=old.identity(old.DLL)
    if identity['sha256']!=old.LOCKS[old.DLL]:raise ValueError('Pinned DLL changed')
    with old.DLL.open('rb')as f,mmap.mmap(f.fileno(),0,access=mmap.ACCESS_READ)as data:
        def u16(at):return struct.unpack_from('<H',data,at)[0]
        def u32(at):return struct.unpack_from('<I',data,at)[0]
        pe=u32(0x3c)
        if data[:2]!=b'MZ'or data[pe:pe+4]!=b'PE\0\0':raise ValueError('PE header')
        n=u16(pe+6);opt=pe+24;optional_bytes=u16(pe+20)
        if u16(opt)!=0x20b or not 1<=n<=96:raise ValueError('PE64 section bound')
        sections=[]
        for i in range(n):
            at=opt+optional_bytes+40*i;sections.append((u32(at+12),max(u32(at+8),u32(at+16)),u32(at+20)))
        def offset(rva):
            for start,size,raw in sections:
                if start<=rva<start+size:return raw+rva-start
            raise ValueError('RVA outside sections')
        export=offset(u32(opt+112));count=u32(export+24);names=offset(u32(export+32))
        if count>100000:raise ValueError('Export bound')
        exports=[]
        for i in range(count):
            at=offset(u32(names+4*i));end=data.find(b'\0',at,at+4096)
            if end<at:raise ValueError('Unterminated export')
            exports.append(data[at:end].decode('ascii'))
        result={'schema':'piq-native-schema-exports-1','ok':True,'read_only_no_native_execution':True,'dll':identity,'exports':exports,'export_count':count,'coff_symbol_count':u32(pe+16),'state_schema_accessor_exported':any('indexed_item'in s or 'dump_registry'in s or 'save_manager'in s for s in exports),'conclusion':'Serialized bytes contain a 32-byte header and values only. Current stable libretro API does not expose registered state names/types/offsets. Do not identify or omit unknown state bytes by guessed offsets.'}
    a.report.parent.mkdir(parents=True,exist_ok=True)
    with a.report.open('x',encoding='utf-8')as out:json.dump(result,out,ensure_ascii=False,indent=2)
    print(json.dumps(result,ensure_ascii=True))
if __name__=='__main__':main()
