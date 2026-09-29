"""Prototype 3 evidence: no private file paths, games or screenshots exported."""
import datetime, hashlib, json, pathlib
ROOT=pathlib.Path(__file__).resolve().parent
def read(label):return json.loads((ROOT/"private-test/performance"/label/"report.json").read_text(encoding="utf-8"))
forest_png=read("prototype3-png");forest_jpeg=read("prototype3-jpeg")
light_png=read("prototype3-light-level-png");light_jpeg=read("prototype3-light-level-jpeg")
regression=json.loads((ROOT/"private-test/protocol-prototype3-report.json").read_text(encoding="utf-8"))
manifest_path=ROOT/"publish-0.1.2/runtime-manifest.json"
manifest=json.loads(manifest_path.read_text(encoding="utf-8"))
old_path=ROOT/"publish-0.1.1/runtime-manifest.json"
old=json.loads(old_path.read_text(encoding="utf-8"))
old_entries={f["path"]:f["sha256"] for f in old["files"]}
for f in manifest["files"]:
    blob=(manifest_path.parent/f["path"]).read_bytes()
    assert len(blob)==f["size"] and hashlib.sha256(blob).hexdigest().upper()==f["sha256"]
    if f["path"].startswith(("vendor/","web/")):assert f["sha256"]==old_entries[f["path"]]
assert len(manifest["files"])==27 and manifest["version"]=="0.1.2"
final_dll=hashlib.sha256((manifest_path.parent/"FlashBox.Helper.dll").read_bytes()).hexdigest()
assert light_png["helperDllSha256"]==light_jpeg["helperDllSha256"]==final_dll
def sample(record):
    return {"format":record["format"],"readySeconds":record["readySeconds"],"steady":record["steady"],
            "stagesMs":record["stagesMs"],"exitCode":record["exitCode"],"pauseStable":record["pauseStable"],
            "resumeChanged":record["resumeChanged"],"ok":record["ok"]}
def comparison(name,png,jpeg):
    assert png["swfSha256"]==jpeg["swfSha256"]
    return {"gameSample":name,"scene":"First playable level; visually inspected private captures", "swfSha256":png["swfSha256"],
            "png":sample(png),"jpeg":sample(jpeg),
            "fpsRatio":round(jpeg["steady"]["fps"]/png["steady"]["fps"],3),
            "meanImageBytesReductionPercent":round((1-jpeg["steady"]["meanImageBytes"]/png["steady"]["meanImageBytes"])*100,2)}
result={"schemaVersion":1,"ok":all(r["ok"] for r in (forest_png,forest_jpeg,light_png,light_jpeg)) and regression["ok"],
    "createdUtc":datetime.datetime.now(datetime.timezone.utc).isoformat(),"helperVersion":"0.1.2","defaultFormat":"jpeg",
    "scope":"Standalone local Windows x64 WebView2 helper frame arrival; not Minecraft end-to-end FPS, shader-on client FPS, WAN bandwidth or remote multiplayer.",
    "method":{"secondsPerSteadySample":15,"runsPerFormatPerGame":1,"sameHostSequential":True,"controlledLab":False,
              "notes":["Host concurrent workload is not controlled; observed values are smoke measurements, not universal guarantees.",
                       "Forest comparison used the same 0.1.2 candidate with explicit --format; final code change thereafter only selected JPEG as default.",
                       "Light Temple comparison uses final frozen DLL byte-for-byte.",
                       "CapturePreview duration includes browser capture and its internal codec; this API cannot split those two sub-stages.",
                       "An initial Light Temple run stopped on level-selection menu and is excluded from the gameplay comparison."]},
    "comparisons":[comparison("Forest Temple",forest_png,forest_jpeg),comparison("Light Temple",light_png,light_jpeg)],
    "imageReview":{"performed":True,"dimensions":[640,480],"result":"Playable scene, characters, text and terrain remain visible; JPEG is lossy and may soften edges, not pixel-identical to PNG.",
                   "screenshotsRedistributed":False},
    "input":{"independentOrderedInputPump":True,"p1AndP2MovementVerified":True,"mouseMenusVerified":True,"measuredHardwareInputLatencyMs":None,
             "note":"Changed-region timing is contaminated by pre-existing animation, so no numeric input-latency improvement is claimed."},
    "regression":regression,"captureTargetFps":30,"guaranteed30Fps":False,
    "runtime":{"relativePath":"piq-flash-box/runtime/publish-0.1.2","fileCount":len(manifest["files"]),"bytes":sum(f["size"] for f in manifest["files"]),
               "manifestSha256":hashlib.sha256(manifest_path.read_bytes()).hexdigest().upper(),"helperDllSha256":final_dll.upper(),
               "previousManifestSha256":hashlib.sha256(old_path.read_bytes()).hexdigest().upper(),"fixedRuffleTag":manifest["ruffle"]["tag"],"ruffleAndWebAssetsUnchanged":True},
    "protocol":{"readyFormat":"jpeg","frameImageField":"jpeg","optionalDiagnosticFallback":"--format png","sameDimensionsSequenceAndControlCommands":True},
    "privacy":"No private SWF content, absolute user SWF paths, save content or gameplay screenshots are included. No SWF is uploaded.",
    "notImplemented":["Two-PC synchronization","Shared-memory/binary frame transport","Minecraft positional audio","Persistent Flash game saves"]}
destination=ROOT.parents[1]/"outputs/flash-box-prototype3/runtime-performance.json"
destination.parent.mkdir(parents=True,exist_ok=True)
destination.write_text(json.dumps(result,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
print(json.dumps({"ok":result["ok"],"report":str(destination),"sha256":hashlib.sha256(destination.read_bytes()).hexdigest().upper()},ensure_ascii=False))
