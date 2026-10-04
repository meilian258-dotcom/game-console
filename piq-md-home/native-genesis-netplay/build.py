"""Build an isolated, fixed-source GX netplay core; never overwrite normal GX.

Usage: python build.py <new-output-directory> [llvm-mingw-bin]
The resulting source-complete ZIP is mandatory alongside any distributed DLL.
"""
from pathlib import Path
import argparse, hashlib, json, os, shutil, subprocess, sys, zipfile

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[1]
COMMIT="c2838c7dc4236fc2fe94e5dbd08b41486067918e"
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument("output",type=Path)
parser.add_argument("toolchain",type=Path,nargs="?",default=ROOT/"outputs/libretro64/retroarch-poc/toolchain/llvm-mingw-20250910-ucrt-x86_64/bin")
parser.add_argument("--source",type=Path,default=ROOT/"outputs/md-gx-20260930/genesis-source.zip",help="independently supplied upstream ZIP; pinned SHA is mandatory")
args=parser.parse_args()
ARCHIVE=args.source.resolve()
EXPECTED="dd4f5ef7ad3bae410854da8d0d7b99c99f13caa1df77f4ef1f2b8f59be3b6977"
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
if sha(ARCHIVE)!=EXPECTED: raise RuntimeError("Locked GX source archive mismatch")
out=args.output.resolve()
out.mkdir(parents=True,exist_ok=False)
with zipfile.ZipFile(ARCHIVE) as archive: archive.extractall(out/"source")
src=out/"source"/("Genesis-Plus-GX-"+COMMIT)
def patch(path,old,new):
    p=src/path; text=p.read_text(encoding="utf-8")
    if text.count(old)!=1: raise RuntimeError(f"Patch anchor {path}: {text.count(old)}")
    p.write_text(text.replace(old,new),encoding="utf-8",newline="\n")
for name,dest in [("system","core/system.c"),("sound","core/sound/sound.c"),("gamepad","core/input_hw/gamepad.c"),("blip","core/sound/blip_buf.c"),("i2c","core/cart_hw/eeprom_i2c.c"),("spi","core/cart_hw/eeprom_spi.c"),("93c","core/cart_hw/eeprom_93c.c")]:
    p=src/dest
    p.write_text(p.read_text(encoding="utf-8")+(HERE/(name+".inc")).read_text(encoding="utf-8"),encoding="utf-8",newline="\n")
shutil.copy2(HERE/"np_state.h",src/"core/np_state.h")
patch("core/state.c","  save_param(&Z80, sizeof(Z80_Regs));", "  { Z80_Regs canonical=Z80; canonical.irq_callback=NULL; save_param(&canonical, sizeof(canonical)); }")
patch("core/sound/ym2612.c","  save_param(&ym2612, sizeof(ym2612));", """  { /* Derived pointers are rebuilt by the original loader from DT indices / ALGO. */
    YM2612 canonical=ym2612;
    for(c=0;c<6;c++) {
      for(s=0;s<4;s++) canonical.CH[c].SLOT[s].DT=NULL;
      canonical.CH[c].connect1=NULL; canonical.CH[c].connect2=NULL;
      canonical.CH[c].connect3=NULL; canonical.CH[c].connect4=NULL;
      canonical.CH[c].mem_connect=NULL;
    }
    save_param(&canonical, sizeof(canonical));
  }""")
p=src/"libretro/libretro.c"; text=p.read_text(encoding="utf-8")
text=text.replace('info->library_name = "Genesis Plus GX";', 'info->library_name = "Genesis Plus GX PIQ Netplay";')
a=text.index("size_t retro_serialize_size(void)"); b=text.index("void retro_cheat_reset(void)",a)
text=text[:a]+(HERE/"libretro.inc").read_text(encoding="utf-8")+"\n"+text[b:]
a=text.index("         /* if emulation is not running,"); b=text.index("      case RETRO_MEMORY_SYSTEM_RAM:",a)
text=text[:a]+"         /* Fixed memory domain for exact rollback/import in this core only. */\n         return 0x10000;\n      }\n\n"+text[b:]
p.write_text(text,encoding="utf-8",newline="\n")
tool=args.toolchain.resolve()
env=dict(os.environ); env["PATH"]=str(tool)+os.pathsep+env["PATH"]
cmd=[str(tool/"mingw32-make.exe"),"-f","Makefile.libretro","-j4","platform=win","CC=clang","CXX=clang++","GIT_VERSION=c2838c7-piqnp1","HAVE_CHD=0","HAVE_CDROM=0","SHARED=-shared -static-libgcc -Wl,--no-undefined,--no-insert-timestamp"]
with (out/"build.log").open("wb") as log: result=subprocess.run(cmd,cwd=src,env=env,stdout=log,stderr=subprocess.STDOUT)
if result.returncode: raise RuntimeError(f"Build failed: {out/'build.log'}")
dll=out/"genesis_plus_gx_piq_netplay_libretro.dll"
shutil.copy2(src/"genesis_plus_gx_libretro.dll",dll)
# Include every upstream source/component and our exact generator/patch inputs.
with zipfile.ZipFile(out/"genesis-plus-gx-piqnp1-source.zip","w",zipfile.ZIP_DEFLATED) as archive:
    def add_source(p,name):
        info=zipfile.ZipInfo(name,(1980,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED
        archive.writestr(info,p.read_bytes())
    for p in sorted(src.rglob("*")):
        if p.is_file() and p.suffix not in (".o",".dll"): add_source(p,"source/"+p.relative_to(src).as_posix())
    for p in sorted(HERE.glob("*")):
        if p.is_file(): add_source(p,"piq-patch/"+p.name)
compiler_version=subprocess.run([str(tool/"clang.exe"),"--version"],capture_output=True,text=True,encoding="utf-8",check=True).stdout
receipt={"commit":COMMIT,"sourceArchiveSha256":EXPECTED,"dll":str(dll),"sha256":sha(dll),"sourceCompleteSha256":sha(out/"genesis-plus-gx-piqnp1-source.zip"),"command":cmd,"compiler":str(tool),"compilerVersion":compiler_version,"compilerSha256":sha(tool/"clang.exe"),"gate":"not yet verified"}
(out/"build-receipt.json").write_text(json.dumps(receipt,indent=2),encoding="utf-8")
print(json.dumps(receipt))
