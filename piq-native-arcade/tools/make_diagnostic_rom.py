"""Generate original 8080 diagnostics for the fixed invaders hardware driver, not a commercial game."""
from pathlib import Path
import argparse,hashlib,json,zipfile
def make(path):
    path=Path(path)
    if path.exists():raise FileExistsError(path)
    # DI; LXI SP,2400; enable original UFO oscillator (no sound samples).
    # Fill mapped VRAM by repeatedly reading IN1; OUT6 resets hardware watchdog.
    code=bytes([0xf3,0x31,0x00,0x24,0x3e,0x21,0xd3,0x03,
        0x21,0x00,0x24,0xdb,0x01,0x77,0xd3,0x06,0x23,
        0x7c,0xfe,0x40,0xc2,0x0b,0x00,0xc3,0x08,0x00])
    data=code+bytes(8192-len(code))
    names=['9316b-0869_m739h.h1','9316b-0856_m739g.g1','9316b-0855_m739f.f1','9316b-0854_m739e.e1']
    path.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(path,'w',compression=zipfile.ZIP_STORED) as z:
        for i,name in enumerate(names):
            entry=zipfile.ZipInfo(name,date_time=(2026,9,10,0,0,0))
            z.writestr(entry,data[i*2048:(i+1)*2048])
    return {'path':str(path.resolve()),'zip_sha256':hashlib.sha256(path.read_bytes()).hexdigest().upper(),
        'original_firmware_sha256':hashlib.sha256(data).hexdigest().upper(),
        'notice':'Original diagnostics, not Space Invaders game code. Expected checksum warnings are intentional.'}
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--output',required=True);a=p.parse_args();print(json.dumps(make(a.output),indent=2))

