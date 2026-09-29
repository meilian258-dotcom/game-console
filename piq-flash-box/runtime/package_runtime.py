"""Generate a closed runtime inventory; no private test assets are copied."""
import argparse, hashlib, json, pathlib, shutil

ROOT = pathlib.Path(__file__).resolve().parent
parser = argparse.ArgumentParser()
parser.add_argument("--publish", type=pathlib.Path, default=ROOT / "publish-0.1.2")
parser.add_argument("--version", default="0.1.2")
args = parser.parse_args()
PUBLISH = args.publish.resolve()
assert (PUBLISH / "FlashBox.Helper.exe").is_file(), "Run dotnet publish first"
assert not any(PUBLISH.rglob("*.swf")), "A game file must never enter the runtime package"
source_notice = pathlib.Path.home() / ".nuget/packages/microsoft.web.webview2/1.0.4078.44/LICENSE.txt"
shutil.copyfile(source_notice, PUBLISH / "LICENSE-WebView2.txt")
shutil.copyfile(ROOT / "README.md", PUBLISH / "README-runtime.md")
files = []
for path in sorted(PUBLISH.rglob("*")):
    if not path.is_file() or path.name == "runtime-manifest.json":
        continue
    relative = path.relative_to(PUBLISH).as_posix()
    assert not relative.startswith(("private-test/", "downloads/"))
    data = path.read_bytes()
    files.append({"path": relative, "size": len(data), "sha256": hashlib.sha256(data).hexdigest().upper()})
manifest = {"schemaVersion": 1, "version": args.version, "platform": "windows-x64",
            "requirements": ["Microsoft.NETCore.App 6.0", "Microsoft.WindowsDesktop.App 6.0", "Microsoft Edge WebView2 Runtime"],
            "ruffle": {"tag": "nightly-2026-09-16", "archiveSize": 10493235,
                       "archiveSha256": "DD7CCB396C32929ABED6AB4767366ABC56010299CB9A8A43F33FCA5394DC775E",
                       "source": "https://github.com/ruffle-rs/ruffle/releases/tag/nightly-2026-09-16"},
            "files": files}
target = PUBLISH / "runtime-manifest.json"
target.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({"path": str(PUBLISH), "files": len(files), "bytes": sum(f["size"] for f in files),
                  "manifestSha256": hashlib.sha256(target.read_bytes()).hexdigest().upper()}, indent=2))
