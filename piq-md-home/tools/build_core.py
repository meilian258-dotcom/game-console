"""Build pinned unmodified BlastEm with LLVM-MinGW. No shell/make dependency.
Usage: python build_core.py SOURCE_DIR TOOLCHAIN_BIN OUTPUT_DIR
Corresponds to Makefile Windows/x86_64/LIBRETRO with -O2 (no LTO).
"""
import sys,subprocess,json,hashlib,concurrent.futures
from pathlib import Path
src,tc,out=map(lambda p:Path(p).resolve(),sys.argv[1:4]);out.mkdir(parents=True,exist_ok=True)
for stem in ['upd78k2','sh2']:
    result=subprocess.run([sys.executable,'cpu_dsl.py','-d','call',stem+'.cpu'],cwd=src,check=True,stdout=subprocess.PIPE)
    (src/(stem+'.c')).write_bytes(result.stdout)
for name in ['rom.db','systems.cfg']:
    text=(src/name).read_text()
    (src/(name+'.c')).write_text('const char '+name.replace('.','_')+'_data[] =\n'+''.join(json.dumps(line+'\n')+'\n' for line in text.splitlines())+';\n')
files='''system genesis vdp io romdb hash xband realtec i2c nor 68kinst disasm m68k_core m68k_core_x86 sega_mapper multi_game megawifi net_win serialize terminal_win config tern util paths gst gen backend mem_win arena gen_x86 backend_x86 ym2612 ymf262 ym_common psg wave flac vgm event_log render_audio rf5c164 saves jcart gen_player coleco pico_pcm ymz263b segacd lc8951 cdimage chdimage cdd_mcu cd_graphics cdd_fader sft_mapper mediaplayer laseractive upd78k2_dis upd78k2 osd_font pd0178 radica 32x 32x_video sh2 sh2_decode sh7095 sms i8255 korean_sms_multi z80inst z80_to_x86 libblastem lib_stubs rom.db systems.cfg vfs_file
libchdr/libchdr_bitstream libchdr/libchdr_cdrom libchdr/libchdr_chd libchdr/libchdr_flac libchdr/libchdr_huffman libchdr/zstd_stub lzma/LzmaDec
zlib/adler32 zlib/compress zlib/crc32 zlib/deflate zlib/gzclose zlib/gzlib zlib/gzread zlib/gzwrite zlib/infback zlib/inffast zlib/inflate zlib/inftrees zlib/trees zlib/uncompr zlib/zutil'''.split()
cc=str(tc/'x86_64-w64-mingw32-clang.exe')
flags=['-std=gnu99','-O2','-DX86_64','-DIS_LIB','-DDISABLE_NUKLEAR','-I.','-Ilibchdr/include','-Ilibchdr','-Ilzma','-Izlib']
def compile(stem):
    obj=out/(stem.replace('/','_')+'.o')
    # The emulator's root io.h shadows MinGW's <io.h> for bundled zlib.
    extra=['-include',str(tc.parent/'include/io.h')] if stem.startswith('zlib/') else []
    cmd=[cc,*flags,*extra,'-c',stem+'.c','-o',str(obj)]
    r=subprocess.run(cmd,cwd=src,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
    (out/(stem.replace('/','_')+'.log')).write_bytes(r.stdout)
    if r.returncode:raise RuntimeError(stem+'\n'+r.stdout.decode(errors='replace')[-6000:])
    return str(obj)
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:objects=list(pool.map(compile,files))
dll=out/'blastem_libretro.dll'
cmd=[cc,'-shared','-static','-s','-o',str(dll),*objects,'-lm','-lmingw32','-lws2_32','-lcomdlg32','-lole32']
r=subprocess.run(cmd,cwd=src,stdout=subprocess.PIPE,stderr=subprocess.STDOUT);(out/'link.log').write_bytes(r.stdout)
if r.returncode:raise RuntimeError(r.stdout.decode(errors='replace'))
receipt={'sha256':hashlib.sha256(dll.read_bytes()).hexdigest(),'bytes':dll.stat().st_size,'sources':files,'flags':flags,'zlib_extra_include':str(tc.parent/'include/io.h'),'compiler':subprocess.check_output([cc,'--version'],encoding='utf-8')}
(out/'build.json').write_text(json.dumps(receipt,indent=2));print(json.dumps(receipt,indent=2))
