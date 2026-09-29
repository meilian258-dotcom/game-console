"""Run only the pinned local prototype host; evidence and saves stay in the audit folder."""
import argparse, hashlib, json, pathlib, queue, struct, subprocess, threading, time
from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--seconds', type=int, default=12)
parser.add_argument('--label', default='boot')
parser.add_argument('--click', default='')
parser.add_argument('--initial-delay', type=float, default=5)
parser.add_argument('--step-delay', type=float, default=2)
parser.add_argument('--press-time', type=float, default=.12)
parser.add_argument('--output', default='outputs/pvz-prototype1')
args = parser.parse_args()
out = ROOT.parent / args.output
out.mkdir(parents=True,exist_ok=True)
data = ROOT.parent / 'outputs/pvz-prototype1/data/dist/system/pvz'
save = out / 'private-save'
save.mkdir(parents=True, exist_ok=True)
log = open(out / (args.label + '.log'), 'wb')
p = subprocess.Popen([str(ROOT/'src/main/resources/core/pvz/piq-pvz-host.exe'),str(ROOT/'src/main/resources/core/pvz/pvz_libretro.dll'),str(data),str(save)],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=log,creationflags=subprocess.CREATE_NO_WINDOW)
latest = [None]; stats = {'packets':0,'frames':0,'nonzero_pcm':0,'crc':set()}; errors=[]
def exact(n):
    parts=[]
    while n:
        v=p.stdout.read(n)
        if not v: raise EOFError('worker ended')
        parts.append(v);n-=len(v)
    return b''.join(parts)
def reader():
    try:
        while True:
            magic,seq,w,h,n,a=struct.unpack('<6I',exact(24))
            assert magic==0x315A5650 and n in (0,800*600*4) and a<=16384
            pic=exact(n);pcm=exact(a*2)
            stats['packets']+=1
            if any(pcm):stats['nonzero_pcm']+=1
            if n:
                latest[0]=pic;stats['frames']+=1;stats['crc'].add(hashlib.sha256(pic).hexdigest())
    except Exception as e: errors.append(str(e))
t=threading.Thread(target=reader,daemon=True);t.start()
def send(cmd,a=0,b=0,c=0,d=0):
    p.stdin.write(struct.pack('<6I',0x315A5650,cmd,a,b&0xffffffff,c&0xffffffff,d));p.stdin.flush()
try:
    deadline=time.monotonic()+args.seconds
    if args.click:
        time.sleep(args.initial_delay)
        for step,pair in enumerate(args.click.split(';')):
            if latest[0]:Image.frombytes('RGBA',(800,600),latest[0]).save(out/f'{args.label}-before-{step}.png')
            x,y=map(int,pair.split(','));x=x*65535//800-32768;y=y*65535//600-32768
            send(1,0,x,y,4);time.sleep(.15);send(1,0,x,y,5);time.sleep(args.press_time);send(1,0,x,y,4);time.sleep(args.step_delay)
    while time.monotonic()<deadline and p.poll() is None:time.sleep(.1)
    if latest[0]:Image.frombytes('RGBA',(800,600),latest[0]).save(out/(args.label+'.png'))
    send(0)
    p.wait(15)
finally:
    if p.poll() is None:p.kill();p.wait()
    t.join(3);log.close()
    stats['distinct_frames']=len(stats.pop('crc'));stats['exit']=p.returncode;stats['reader_end']=errors
    stats['save_files']=[str(f.relative_to(save)) for f in save.rglob('*') if f.is_file()]
    (out/(args.label+'.json')).write_text(json.dumps(stats,indent=2),encoding='utf-8')
    print(json.dumps({k:v for k,v in stats.items() if k!='save_files'}))
