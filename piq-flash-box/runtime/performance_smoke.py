"""Bounded, local-only helper comparison; game screenshots remain private-test/."""
import argparse, base64, hashlib, io, json, pathlib, queue, statistics, struct, subprocess, threading, time
from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parent
p = argparse.ArgumentParser()
p.add_argument("--helper", type=pathlib.Path, required=True)
p.add_argument("--label", required=True)
p.add_argument("--swf", type=pathlib.Path, default=ROOT / "private-test/the-forest-temple.swf")
p.add_argument("--format", choices=("png","jpeg"))
p.add_argument("--metrics", action="store_true")
p.add_argument("--level-click", default="310,451", help="Local smoke fixture menu coordinate, never a runtime game binding")
args = p.parse_args()
target = ROOT / "private-test/performance" / args.label
target.mkdir(parents=True, exist_ok=True)
events, errors, measurements = queue.Queue(), [], {"capture":[],"write":[]}
command=[str(args.helper.resolve()), "--swf", str(args.swf.resolve()), "--width", "640", "--height", "480"]
if args.format: command += ["--format",args.format]
if args.metrics: command += ["--metrics"]
process = subprocess.Popen(command,
    stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding="utf-8", creationflags=subprocess.CREATE_NO_WINDOW)
def stdout():
    for line in process.stdout:
        events.put((time.perf_counter(), json.loads(line)))
def stderr():
    for line in process.stderr:
        if line.startswith("PERF_CAPTURE "): measurements["capture"].append(json.loads(line[13:]))
        elif line.startswith("PERF_WRITE "): measurements["write"].append(json.loads(line[11:]))
        else: errors.append(line.strip())
threading.Thread(target=stdout, daemon=True).start()
threading.Thread(target=stderr, daemon=True).start()
def send(value):
    process.stdin.write(json.dumps(value) + "\n")
    process.stdin.flush()
def click(x,y):
    send({"op":"mouse","x":x,"y":y,"down":True})
    send({"op":"mouse","x":x,"y":y,"down":False})
frames, latest, ready = [], None, None
def pump(seconds):
    global latest, ready
    until = time.perf_counter()+seconds
    while time.perf_counter()<until:
        try: received, item = events.get(timeout=min(.1,max(.001,until-time.perf_counter())))
        except queue.Empty: continue
        kind = item.get("kind")
        if kind == "frame":
            field = "jpeg" if "jpeg" in item else "png"
            raw = base64.b64decode(item[field],validate=True)
            if field=="png": assert raw[:8] == b"\x89PNG\r\n\x1a\n" and struct.unpack(">II",raw[16:24]) == (640,480)
            else: assert raw[:2]==b"\xff\xd8" and Image.open(io.BytesIO(raw)).size==(640,480)
            latest = raw
            frames.append((received,item["seq"],len(raw)))
        elif kind == "ready": ready = item
        elif kind == "error": raise RuntimeError(item["message"])
        if process.poll() is not None: raise RuntimeError("Helper exited early")
def region_hash(raw, box):
    return hashlib.sha256(Image.open(io.BytesIO(raw)).convert("RGB").crop(box).tobytes()).hexdigest()
report = {"label":args.label,"helperSha256":hashlib.sha256(args.helper.read_bytes()).hexdigest(),
          "helperDllSha256":hashlib.sha256(args.helper.with_suffix(".dll").read_bytes()).hexdigest(),
          "swfSha256":hashlib.sha256(args.swf.read_bytes()).hexdigest(),"errors":errors,
          "scope":"Local 640x480 encoded frame arrival; not native game FPS or network/input-hardware latency","format":args.format or "png"}
start = time.perf_counter()
try:
    while ready is None and time.perf_counter()-start<40: pump(.2)
    assert ready is not None
    report["readySeconds"]=round(time.perf_counter()-start,3)
    pump(5); click(320,430)
    pump(5); click(320,248)
    pump(5); click(*map(int,args.level_click.split(",")))
    pump(5)
    Image.open(io.BytesIO(latest)).save(target/"level-before.png")
    start_index=len(frames)
    pump(15)
    observed=frames[start_index:]
    intervals=[(b[0]-a[0])*1000 for a,b in zip(observed,observed[1:])]
    report["steady"]={"frames":len(observed),"seconds":round(observed[-1][0]-observed[0][0],3),
        "fps":round((len(observed)-1)/(observed[-1][0]-observed[0][0]),3),
        "intervalMedianMs":round(statistics.median(intervals),3),
        "intervalP95Ms":round(sorted(intervals)[int(.95*(len(intervals)-1))],3),
        "sequenceGaps":sum(b[1]-a[1]-1 for a,b in zip(observed,observed[1:])),
        "meanImageBytes":round(statistics.mean(f[2] for f in observed))}
    report["steadySequenceRange"]=[observed[0][1],observed[-1][1]]
    # Whole left playing-field regions include game animation; report only an upper bound
    # to a changed captured region, never call this authoritative keyboard latency.
    report["inputProbes"]=[]
    for player in (1,2):
        box=(0,240,250,480)
        previous=region_hash(latest,box)
        key_at=time.perf_counter()
        send({"op":"keys","p1":2 if player==1 else 0,"p2":2 if player==2 else 0})
        changed=None
        while time.perf_counter()-key_at<.20:
            pump(.01)
            if changed is None and region_hash(latest,box)!=previous: changed=time.perf_counter()-key_at
        send({"op":"keys","p1":0,"p2":0})
        pump(.6)
        Image.open(io.BytesIO(latest)).save(target/f"p{player}-after.png")
        report["inputProbes"].append({"player":player,"firstChangedRegionMs":None if changed is None else round(changed*1000,3),
                                      "caveat":"May include existing game animation; visual evidence verifies movement separately"})
    send({"op":"pause"}); pump(1)
    hash1=hashlib.sha256(latest).hexdigest(); pump(1)
    report["pauseStable"]=hash1==hashlib.sha256(latest).hexdigest()
    send({"op":"resume"}); pump(1)
    report["resumeChanged"]=hash1!=hashlib.sha256(latest).hexdigest()
    report["ready"]=ready
finally:
    if process.poll() is None:
        try: send({"op":"close"})
        except OSError: pass
    try: report["exitCode"]=process.wait(timeout=8)
    except subprocess.TimeoutExpired:
        process.kill(); report["exitCode"]=process.wait(); report["forcedKill"]=True
    report["elapsedSeconds"]=round(time.perf_counter()-start,3)
    if args.metrics and "steadySequenceRange" in report:
        first,last=report["steadySequenceRange"]
        stages={}
        for group,fields in [("capture",("captureMs","normalizeMs","encodeAndQueueMs")),("write",("writeMs",))]:
            sample=[m for m in measurements[group] if first<=m["seq"]<=last]
            for field in fields:
                values=sorted(m[field] for m in sample)
                stages[field]={"samples":len(values),"mean":round(statistics.mean(values),3),"median":round(statistics.median(values),3),"p95":round(values[int(.95*(len(values)-1))],3)}
        stages["actualCaptureDimensions"]=sorted({(m["captureWidth"],m["captureHeight"]) for m in measurements["capture"]})
        report["stagesMs"]=stages
    report["ok"]=report.get("exitCode")==0 and report.get("pauseStable",False) and report.get("resumeChanged",False) and not any("Temporary browser profile" not in e for e in errors)
    (target/"report.json").write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(json.dumps(report,ensure_ascii=False,indent=2))
raise SystemExit(0 if report["ok"] else 1)
