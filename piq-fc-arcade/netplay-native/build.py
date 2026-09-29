"""Reproducible local bridge build from the retained, pinned RetroArch POC tree.
Does not modify the old experiment. Build transformations are exact and fail closed.
"""
from pathlib import Path
import hashlib, json, shutil, subprocess, difflib

HERE=Path(__file__).resolve().parent
WS=HERE.parents[1]
POC=WS/'outputs/libretro64/retroarch-poc'
OUT=WS/'outputs/netplay70/native-fixed'
SRC=OUT/'RetroArch-1.22.2'

def replace(text, old, new):
    if text.count(old)!=1: raise RuntimeError('Ambiguous upstream patch: '+old[:80])
    return text.replace(old,new,1)

def main():
    OUT.mkdir(parents=True,exist_ok=True)
    if not SRC.exists(): shutil.copytree(POC/'upstream/RetroArch-1.22.2',SRC)
    changes={}
    for name in ['audio/audio_driver.c','input/input_driver.c','gfx/video_driver.c','runloop.c','network/netplay/netplay_frontend.c']:
        original=(POC/'upstream/RetroArch-1.22.2'/name).read_text(encoding='utf8')
        text=original
        if name.startswith('audio'):
            text=replace(text,'audio_driver_t audio_null = {','''#include "../piq_bridge.h"
audio_driver_t audio_null = {''')
            for old,new in [('NULL, /* init */','piq_audio_init,'),('NULL, /* write */','piq_audio_write,'),('NULL, /* stop */','piq_audio_stop,'),('NULL, /* start */','piq_audio_start,'),('NULL, /* alive */','piq_audio_alive,'),('NULL, /* set_nonblock_state */','piq_audio_nonblock,'),('NULL, /* free */','piq_audio_free,'),('NULL, /* use_float */','piq_audio_float,')]:
                text=replace(text,old,new)
        elif name.startswith('input'):
            text=replace(text,'   const char *scripted = getenv("PIQ_POC_INPUT");\n   unsigned mask = scripted ? (unsigned)strtoul(scripted, NULL, 0) : 0;', '   unsigned mask = piq_input;')
        elif name.startswith('gfx'):
            text=replace(text,'static void *video_null_init(', '#include "../piq_bridge.h"\n\nstatic void *video_null_init(')
            a=text.index('   /* Stand in for display pacing')
            b=text.index('\nstatic void video_null_free',a)
            text=text[:a]+'   video_driver_state_t *piq_video=video_state_get_ptr();\n   return piq_frame(b,c,d,f,e,piq_video->av_info.timing.fps,piq_video->av_info.geometry.aspect_ratio);\n}\n'+text[b:]
            text=replace(text,'   *input      = NULL;', '   piq_rgb32=video->rgb32;\n   *input      = NULL;')
            text=replace(text,'return frontend_driver_get_signal_handler_state() != 1;', 'return !piq_dead && frontend_driver_get_signal_handler_state() != 1;')
            text=replace(text,'static void video_null_set_nonblock_state(void *a, bool b, bool c, unsigned d) { }','static void video_null_set_nonblock_state(void *a, bool b, bool c, unsigned d) { piq_fast=b; }')
        elif name=='runloop.c':
            # PIQ's null driver is an active IPC display, not discarded video.
            # Preserve RetroArch's later replay/runahead suppression unchanged.
            text=replace(text,'if (      (video_st->flags & VIDEO_FLAG_ACTIVE)\n               && !(video_st->current_video->frame == video_null.frame))','if (video_st->flags & VIDEO_FLAG_ACTIVE)')
        else:
            text=replace(text,'#define INET_TO_NETPLAY(in_addr, out_addr)', '''   if (!piq_accept_peer(new_fd)) { socket_close(new_fd); return -1; }

#define INET_TO_NETPLAY(in_addr, out_addr)''')
            text=replace(text,'if (!(connection->flags & NETPLAY_CONN_FLAG_CAN_PLAY))', 'if (!(connection->flags & NETPLAY_CONN_FLAG_CAN_PLAY) || !piq_can_play(connection->fd) || (ntohl(payload) & ~2u))')
        (SRC/name).write_text(text,encoding='utf8',newline='\n')
        changes[name]={'inputSha256':hashlib.sha256(original.encode()).hexdigest(),'outputSha256':hashlib.sha256(text.encode()).hexdigest()}
        (OUT/(Path(name).stem+'.patch')).write_text(''.join(difflib.unified_diff(original.splitlines(True),text.splitlines(True),fromfile=name,tofile=name)),encoding='utf8')
    shutil.copyfile(HERE/'piq_bridge.h',SRC/'piq_bridge.h')
    compiler=POC/'toolchain/llvm-mingw-20250910-ucrt-x86_64/bin/x86_64-w64-mingw32-clang.exe'
    args=[str(compiler),'-std=gnu99','-O2','-DHAVE_GRIFFIN=1','-DRARCH_INTERNAL','-DHAVE_DYNAMIC','-DHAVE_DYLIB','-DHAVE_CONFIGFILE','-DHAVE_NETWORKING','-DHAVE_THREADS','-DHAVE_CC_RESAMPLER','-DHAVE_MENU','-DHAVE_RGUI','-DHAVE_COMPRESSION','-DHAVE_ZLIB','-DHAVE_BUILTINZLIB','-Ilibretro-common/include/compat/zlib','-DWINVER=0x0601','-D_WIN32_WINNT=0x0601','-I.','-Ilibretro-common/include','-Ideps','-Ideps/stb','-Ideps/libz','-include','runloop.h','-include','gfx/gfx_display.h','griffin/griffin.c','-o',str(OUT/'piq-retroarch.exe')]
    args+=['-l'+s for s in ['ws2_32','ole32','comdlg32','user32','gdi32','winmm','shell32','uuid','shlwapi','advapi32']]
    args+=['-Wno-incompatible-pointer-types','-Wno-int-conversion']
    with (OUT/'build.log').open('wb') as log:
        result=subprocess.run(args,cwd=SRC,stdout=log,stderr=subprocess.STDOUT,creationflags=0x08000000)
    if result.returncode: raise RuntimeError('Native build failed; see '+str(OUT/'build.log'))
    exe=OUT/'piq-retroarch.exe'
    receipt={'changes':changes,'executableSha256':hashlib.sha256(exe.read_bytes()).hexdigest(),'bytes':exe.stat().st_size,'compiler':str(compiler),'compilerSha256':hashlib.sha256(compiler.read_bytes()).hexdigest(),'bridgeSha256':hashlib.sha256((HERE/'piq_bridge.h').read_bytes()).hexdigest(),'command':args}
    (OUT/'build.json').write_text(json.dumps(receipt,indent=2),encoding='utf8')
    print(json.dumps(receipt))
if __name__=='__main__': main()
