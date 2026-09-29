"""Export non-private prototype 2 evidence; never embed games or screenshots."""
import datetime, hashlib, json, pathlib
ROOT=pathlib.Path(__file__).resolve().parent
before=json.loads((ROOT/"private-test/performance/prototype1-baseline/report.json").read_text(encoding="utf-8"))
after=json.loads((ROOT/"private-test/performance/prototype2-first/report.json").read_text(encoding="utf-8"))
regression=json.loads((ROOT/"private-test/protocol-performance-report.json").read_text(encoding="utf-8"))
old_path=ROOT/"bin/Release/net6.0-windows/win-x64/publish/runtime-manifest.json"
new_path=ROOT/"publish-0.1.1/runtime-manifest.json"
old=json.loads(old_path.read_text(encoding="utf-8")); new=json.loads(new_path.read_text(encoding="utf-8"))
old_entries={f["path"]:f["sha256"] for f in old["files"]}
vendor_unchanged=all(old_entries[f["path"]]==f["sha256"] for f in new["files"] if f["path"].startswith(("vendor/","web/")))
assert before["swfSha256"]==after["swfSha256"] and vendor_unchanged
assert len(new["files"])==27 and new["version"]=="0.1.1"
for entry in new["files"]:
    data=(new_path.parent/entry["path"]).read_bytes()
    assert len(data)==entry["size"] and hashlib.sha256(data).hexdigest().upper()==entry["sha256"]
report={
    "schemaVersion":1,"ok":before["ok"] and after["ok"] and regression["ok"],
    "createdUtc":datetime.datetime.now(datetime.timezone.utc).isoformat(),
    "scope":"Standalone Windows x64 WebView2 helper; local 640x480 PNG arrival only, not Minecraft end-to-end rendering or remote multiplayer.",
    "method":{"scene":"Private user-provided Forest Temple first level, stationary animated gameplay",
              "sampleSeconds":15,"runsPerVersion":1,"sameSwfSha256":before["swfSha256"],
              "sameRuffleAndWebAssets":vendor_unchanged,"controlledLab":False,
              "note":"Sequential same-host samples; concurrent host load was not controlled. This is a smoke comparison, not a statistical benchmark."},
    "before":{"helperVersion":"0.1.0","readySeconds":before["readySeconds"],**before["steady"]},
    "after":{"helperVersion":"0.1.1","readySeconds":after["readySeconds"],**after["steady"]},
    "observedFpsImprovementPercent":round((after["steady"]["fps"]/before["steady"]["fps"]-1)*100,2),
    "captureTargetFps":30,"achievedTargetFps":False,
    "input":{"p1MovementVisuallyVerified":True,"p2MovementVisuallyVerified":True,"mouseMenusVerified":True,
             "inputLatencyMs":None,"note":"Existing scene animation contaminates changed-region timing. No reliable numeric input-latency improvement is claimed. Ordered input processing is now independent from awaiting capture."},
    "pauseAndResume":{"pauseIdenticalFrameVerified":after["pauseStable"],"resumeChangedFrameVerified":after["resumeChanged"]},
    "regression":regression,
    "runtime":{"version":"0.1.1","relativePath":"piq-flash-box/runtime/publish-0.1.1","files":len(new["files"]),
               "bytes":sum(f["size"] for f in new["files"]),"manifestSha256":hashlib.sha256(new_path.read_bytes()).hexdigest().upper(),
               "previousManifestSha256":hashlib.sha256(old_path.read_bytes()).hexdigest().upper(),
               "fixedRuffleTag":new["ruffle"]["tag"]},
    "notImplemented":["30fps guarantee","native binary frame transport","two-PC input synchronization","Minecraft positional audio","persisted Flash game saves"],
    "privacy":"No SWF content, private absolute file paths or gameplay screenshots are included in this report or runtime package."
}
destination=ROOT.parents[1]/"outputs/flash-box-prototype2/runtime-performance.json"
destination.parent.mkdir(parents=True,exist_ok=True)
destination.write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
print(json.dumps({"ok":report["ok"],"report":str(destination),"sha256":hashlib.sha256(destination.read_bytes()).hexdigest().upper()},ensure_ascii=False))
