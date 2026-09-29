"""Bounded process/IPC failure probes; uses only already-extracted local test SWF."""
import json, pathlib, queue, subprocess, threading, time
ROOT = pathlib.Path(__file__).resolve().parent
EXE = ROOT / "bin/Release/net6.0-windows/win-x64/publish/FlashBox.Helper.exe"
SWF = ROOT / "private-test/the-forest-temple.swf"
cases = []
def run_case(name, command=None, eof=False):
    p = subprocess.Popen([str(EXE), "--swf", str(SWF)], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                         stderr=subprocess.PIPE, text=True, encoding="utf-8", creationflags=subprocess.CREATE_NO_WINDOW)
    received = queue.Queue(); stderr = []
    def read():
        for line in p.stdout:
            received.put(json.loads(line))
    threading.Thread(target=read, daemon=True).start()
    threading.Thread(target=lambda: stderr.extend(p.stderr.read().splitlines()), daemon=True).start()
    deadline = time.monotonic()+20; seen=[]
    try:
        while time.monotonic()<deadline:
            try: event=received.get(timeout=1)
            except queue.Empty:
                if p.poll() is not None: raise AssertionError("Helper exited before ready")
                continue
            if event.get("kind")=="ready": break
            if event.get("kind")=="error": raise AssertionError(event)
        else: raise AssertionError("No ready")
        if eof:
            p.stdin.close()
        else:
            p.stdin.write(command+"\n"); p.stdin.flush()
        code=p.wait(timeout=10)
        while not received.empty():
            item=received.get()
            if item.get("kind")!="frame": seen.append(item)
        ok=code==0 if eof else code==1 and any(x.get("kind")=="error" and x.get("fatal") for x in seen)
        cases.append({"case":name,"ok":ok,"exitCode":code,"events":seen,"stderr":stderr})
    finally:
        if p.poll() is None: p.kill(); p.wait()
bad=subprocess.run([str(EXE),"--swf","relative.swf"], capture_output=True,text=True,encoding="utf-8",creationflags=subprocess.CREATE_NO_WINDOW,timeout=10)
cases.append({"case":"absolute-file-required","ok":bad.returncode==1 and json.loads(bad.stdout)["kind"]=="error"})
run_case("unknown-operation-rejected", '{"op":"unsupported"}')
run_case("oversized-command-rejected", "x"*8193)
run_case("stdin-eof-clean-exit", eof=True)
result={"ok":all(c["ok"] for c in cases),"cases":cases}
(ROOT/"private-test/protocol-report.json").write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding="utf-8")
print(json.dumps(result,ensure_ascii=False,indent=2))
raise SystemExit(0 if result["ok"] else 1)
