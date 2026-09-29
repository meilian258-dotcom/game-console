"""Bounded helper regression: format-aware protocol, backpressure, sustained pause."""
import argparse, hashlib, json, pathlib, queue, subprocess, threading, time
ROOT=pathlib.Path(__file__).resolve().parent
parser=argparse.ArgumentParser()
parser.add_argument("--helper",type=pathlib.Path,default=ROOT/"publish-0.1.2/FlashBox.Helper.exe")
parser.add_argument("--format",choices=("png","jpeg"),default="jpeg")
parser.add_argument("--version",default="0.1.2")
parser.add_argument("--report",default="protocol-prototype3-report.json")
args=parser.parse_args()
EXE=args.helper.resolve()
SWF=ROOT/"private-test/the-forest-temple.swf"
cases=[]
def launch():
    p=subprocess.Popen([str(EXE),"--swf",str(SWF),"--format",args.format],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,
        text=True,encoding="utf-8",creationflags=subprocess.CREATE_NO_WINDOW)
    events=queue.Queue(); errors=[]; gate=threading.Event(); gate.set()
    def read():
        while True:
            gate.wait()
            line=p.stdout.readline()
            if not line: return
            # Deliberately blocked stdout may end with a partial in-flight frame
            # after close. The real Java caller already ignores input when closed.
            if not line.endswith("\n"):
                errors.append("in-flight frame truncated during intentional closed-reader test")
                return
            events.put((time.monotonic(),json.loads(line)))
    threading.Thread(target=read,daemon=True).start()
    threading.Thread(target=lambda:errors.extend(p.stderr.read().splitlines()),daemon=True).start()
    until=time.monotonic()+35
    while time.monotonic()<until:
        received,item=events.get(timeout=5)
        if item.get("kind")=="ready":
            assert item["version"]==args.version and item["format"]==args.format
            return p,events,errors,gate
        if item.get("kind")=="error": raise AssertionError(item)
    raise AssertionError("ready timeout")
def send(p,obj):
    p.stdin.write(json.dumps(obj)+"\n");p.stdin.flush()
def collect(events,seconds):
    result=[]; until=time.monotonic()+seconds
    while time.monotonic()<until:
        try: result.append(events.get(timeout=min(.1,max(.001,until-time.monotonic()))))
        except queue.Empty: pass
    return result
def frames(items): return [i for _,i in items if i.get("kind")=="frame"]
def close(p):
    if p.poll() is None:
        try: send(p,{"op":"close"})
        except OSError: pass
    try:return p.wait(timeout=8)
    except subprocess.TimeoutExpired:p.kill();p.wait();raise
for name,value in [("invalid-input-mask",{"op":"keys","p1":32,"p2":0}),
                   ("out-of-range-mouse",{"op":"mouse","x":640,"y":0,"down":True}),
                   ("unknown-operation",{"op":"bad"})]:
    proc,events,errors,gate=launch()
    try:
        send(proc,value); code=proc.wait(timeout=8); got=collect(events,.3)
        cases.append({"case":name,"ok":code==1 and any(i.get("kind")=="error" for _,i in got),"exitCode":code})
    finally:close(proc)
proc,events,errors,gate=launch()
try:
    proc.stdin.write("x"*8193+"\n");proc.stdin.flush();code=proc.wait(timeout=8)
    got=collect(events,.3)
    cases.append({"case":"oversized-command","ok":code==1 and any(i.get("kind")=="error" for _,i in got),"exitCode":code})
finally:close(proc)
proc,events,errors,gate=launch()
try:
    send(proc,{"op":"keys","p1":2,"p2":4});proc.stdin.close();code=proc.wait(timeout=8)
    cases.append({"case":"held-keys-eof-clean-close","ok":code==0,"exitCode":code})
finally:
    if proc.poll() is None:proc.kill();proc.wait()
proc,events,errors,gate=launch()
try:
    collect(events,3)
    send(proc,{"op":"pause"});collect(events,1)
    got=collect(events,16); fs=frames(got)
    stamps=[at for at,i in got if i.get("kind")=="frame"]
    stable=len({hashlib.sha256(i[args.format].encode()).hexdigest() for i in fs})==1
    maxgap=max(b-a for a,b in zip(stamps,stamps[1:])) if len(stamps)>1 else 99
    send(proc,{"op":"resume"});resumed=frames(collect(events,2));code=close(proc)
    cases.append({"case":"sixteen-second-pause-heartbeat","ok":len(fs)>30 and stable and maxgap<2 and len(resumed)>5 and code==0,
        "pausedFrames":len(fs),"identicalImage":stable,"maximumFrameGapSeconds":round(maxgap,3),"resumedFrames":len(resumed),"exitCode":code})
finally:close(proc)
proc,events,errors,gate=launch()
try:
    before=frames(collect(events,3));gate.clear();time.sleep(2)
    send(proc,{"op":"pause"});time.sleep(.3);gate.set()
    after=frames(collect(events,2))
    seqs=[before[-1]["seq"]]+[i["seq"] for i in after]
    maxgap=max(b-a-1 for a,b in zip(seqs,seqs[1:]))
    code=close(proc)
    cases.append({"case":"slow-reader-replaces-pending-frame","ok":maxgap>5 and code==0,
        "largestDroppedSequenceGap":maxgap,"exitCode":code})
finally:gate.set();close(proc)
proc,events,errors,gate=launch()
try:
    collect(events,2);gate.clear();time.sleep(1)
    started=time.monotonic();send(proc,{"op":"close"});code=proc.wait(timeout=8)
    elapsed=time.monotonic()-started
    cases.append({"case":"blocked-output-does-not-block-close","ok":code==0 and elapsed<8,
                  "exitSeconds":round(elapsed,3),"exitCode":code,
                  "note":"Caller intentionally stops reading; an in-flight frame may be truncated at process exit. No further frames are consumed after close."})
finally:gate.set();close(proc)
bad=subprocess.run([str(EXE),"--swf",str(SWF),"--format","gif"],capture_output=True,text=True,encoding="utf-8",creationflags=subprocess.CREATE_NO_WINDOW,timeout=10)
cases.append({"case":"invalid-format-rejected-before-start","ok":bad.returncode==1 and json.loads(bad.stdout)["kind"]=="error"})
result={"ok":all(c["ok"] for c in cases),"helperVersion":args.version,"format":args.format,"cases":cases}
(ROOT/"private-test"/args.report).write_text(json.dumps(result,indent=2)+"\n",encoding="utf-8")
print(json.dumps(result,indent=2))
raise SystemExit(0 if result["ok"] else 1)
