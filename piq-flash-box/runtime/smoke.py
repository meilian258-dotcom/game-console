"""Local-only runtime smoke. Private SWF and screenshots never enter publish/."""
import base64, hashlib, json, pathlib, queue, struct, subprocess, sys, threading, time, zipfile

ROOT = pathlib.Path(__file__).resolve().parent
PRIVATE = ROOT / "private-test"
PRIVATE.mkdir(exist_ok=True)
ARCHIVE = pathlib.Path(r"E:\Dow\Autosplit-FaW-master.zip")
EXPECTED_ARCHIVE = "21e1a7afd529fb22d746b9b29520515483e40d1eb0ba7804b8e1f7e204e0017e"
EXPECTED_SWF = "83c59cd2e3ba63d86f1ddf6dd22bdeefa8d5964985fdd0e92d4c5161d99236e9"
assert hashlib.sha256(ARCHIVE.read_bytes()).hexdigest() == EXPECTED_ARCHIVE
with zipfile.ZipFile(ARCHIVE) as archive:
    swf = archive.read("Autosplit-FaW-master/the-forest-temple/the-forest-temple.swf")
assert hashlib.sha256(swf).hexdigest() == EXPECTED_SWF
swf_path = PRIVATE / "the-forest-temple.swf"
if swf_path.exists():
    assert swf_path.read_bytes() == swf
else:
    swf_path.write_bytes(swf)
exe = ROOT / "bin/Release/net6.0-windows/win-x64/publish/FlashBox.Helper.exe"
p = subprocess.Popen([str(exe), "--swf", str(swf_path), "--width", "640", "--height", "480"],
                     stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                     text=True, encoding="utf-8", creationflags=subprocess.CREATE_NO_WINDOW)
events = queue.Queue()
errors = []
def read_out():
    for line in p.stdout:
        try: events.put(json.loads(line))
        except Exception as exc: errors.append("invalid stdout: " + str(exc))
def read_err():
    for line in p.stderr:
        errors.append(line.strip())
threading.Thread(target=read_out, daemon=True).start()
threading.Thread(target=read_err, daemon=True).start()
def send(value):
    p.stdin.write(json.dumps(value) + "\n"); p.stdin.flush()
report = {"swfSha256": EXPECTED_SWF, "frames": 0, "ready": None, "events": [], "errors": errors}
gameplay = "--gameplay" in sys.argv
last_frame = None
start = time.monotonic()
try:
    while time.monotonic() - start < 70:
        try: event = events.get(timeout=0.5)
        except queue.Empty:
            if p.poll() is not None: break
            continue
        if event.get("kind") == "frame":
            data = base64.b64decode(event["png"], validate=True)
            assert data[:8] == b"\x89PNG\r\n\x1a\n"
            assert struct.unpack(">II", data[16:24]) == (640, 480)
            report["frames"] += 1
            last_frame = data
            if report["frames"] == 1: (PRIVATE / "frame-first.png").write_bytes(data)
            if gameplay and report["frames"] == 75:
                send({"op":"mouse", "x":320, "y":430, "down":True})
                send({"op":"mouse", "x":320, "y":430, "down":False})
            if gameplay and report["frames"] == 130:
                (PRIVATE / "frame-after-play.png").write_bytes(data)
                send({"op":"mouse", "x":320, "y":248, "down":True})
                send({"op":"mouse", "x":320, "y":248, "down":False})
            if gameplay and report["frames"] == 200:
                (PRIVATE / "frame-level-menu.png").write_bytes(data)
                send({"op":"mouse", "x":310, "y":451, "down":True})
                send({"op":"mouse", "x":310, "y":451, "down":False})
            if gameplay and report["frames"] == 260:
                (PRIVATE / "frame-level-before-input.png").write_bytes(data)
                send({"op":"keys", "p1":2, "p2":0})
            if gameplay and report["frames"] == 270:
                send({"op":"keys", "p1":0, "p2":0})
                (PRIVATE / "frame-level-p1-input.png").write_bytes(data)
                send({"op":"keys", "p1":0, "p2":2})
            if gameplay and report["frames"] == 280:
                send({"op":"keys", "p1":0, "p2":0})
                (PRIVATE / "frame-level-p2-input.png").write_bytes(data)
            if gameplay and report["frames"] == 290:
                send({"op":"pause"})
            if gameplay and report["frames"] == 300:
                (PRIVATE / "frame-paused-1.png").write_bytes(data)
                report["pausedHash1"] = hashlib.sha256(data).hexdigest()
            if gameplay and report["frames"] == 320:
                (PRIVATE / "frame-paused-2.png").write_bytes(data)
                report["pausedHash2"] = hashlib.sha256(data).hexdigest()
                report["pauseFrameStable"] = report["pausedHash1"] == report["pausedHash2"]
                send({"op":"resume"})
            if report["frames"] >= (340 if gameplay else 75):
                send({"op":"keys", "p1":2, "p2":1})
                send({"op":"keys", "p1":0, "p2":0})
                send({"op":"pause"})
                send({"op":"resume"})
                send({"op":"close"})
                report["events"].append("keys-release-pause-resume-close-sent")
                break
        else:
            report["events"].append(event)
            if event.get("kind") == "ready": report["ready"] = event
            if event.get("kind") == "error": break
finally:
    if last_frame: (PRIVATE / "frame-last.png").write_bytes(last_frame)
    if p.poll() is None:
        try: send({"op":"close"})
        except (BrokenPipeError, OSError): pass
    try: report["exitCode"] = p.wait(timeout=8)
    except subprocess.TimeoutExpired:
        p.kill(); report["exitCode"] = p.wait(); report["forcedKill"] = True
    report["elapsedSeconds"] = round(time.monotonic()-start, 3)
    report["ok"] = report["ready"] is not None and report["frames"] >= 75 and report["exitCode"] == 0 and not errors and (not gameplay or report.get("pauseFrameStable", False))
    (PRIVATE / "smoke-report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
raise SystemExit(0 if report["ok"] else 1)
