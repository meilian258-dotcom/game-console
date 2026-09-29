"""Final-JAR-only alpha19 optional-add-on/network/runtime compatibility audit.

Does not compile production, execute mod entry points, start Minecraft/native
cores, connect sockets, install files or accept an EULA. Only explicit probes
are compiled against the supplied JARs and the cached real NeoForge libraries.
"""
from __future__ import annotations
import argparse
from collections import Counter
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import tomllib
import zipfile

from verify_device_ui_alpha18 import without_code_debug, methods, disassemble

ROOT=Path(__file__).resolve().parents[2]
TOOLS=Path(__file__).resolve().parent
DELIVERY=ROOT/"制作Mod/03-街机模拟"
META="META-INF/neoforge.mods.toml"
MANIFEST="META-INF/MANIFEST.MF"
JAVA=Path("C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin")
MC=Path("C:/Users/13498/.gradle/caches/neoformruntime/intermediate_results/sourcesAndCompiledWithNeoForge_e75ff7a3db3c8d7760682f321018318019b04f3c_output.jar")
CACHE=Path("C:/Users/13498/.gradle/caches/modules-2/files-2.1")
BASELINES={
    "fc":(DELIVERY/"PIQ-FC街机/alpha18-device-ui-v2/piq_fc_arcade-0.31.0-alpha.18.jar","E4A9FC00A492A1E8C7FC4E89D9534A1FBE6A389B64438C393E52D6163AACEC47"),
    "sfc":(DELIVERY/"PIQ-SFC家用/0.1.0-alpha.5/piq_sfc_home-0.1.0-alpha.5.jar","82578C8DEF9567B1408D8B7F384E8DCC92D388498091ADC0EC308D56AF5D811C"),
    "native":(DELIVERY/"PIQ原生街机/0.1.0-alpha.5/piq_native_arcade-0.1.0-alpha.5.jar","11B8420C09BD44E942F555C7F01BCE14CF8666F8D455046B532875A9240FFA87"),
    "core":(DELIVERY/"PIQ-SFC街机/piq_sfc_arcade-0.2.0-alpha.6.jar","38FA46C5D283EAD9E1F6666D01398F495E2E1A3260A1517BE8EE959710963363"),
}


def require(ok,why):
    if not ok:raise ValueError(why)


def digest(raw):return hashlib.sha256(raw).hexdigest().upper()


def archive(path):
    raw=Path(path).read_bytes();result={}
    with zipfile.ZipFile(io.BytesIO(raw)) as jar:
        require(len(jar.infolist())<20000,"Too many JAR entries")
        names=[entry.orig_filename for entry in jar.infolist()]
        require(all(count==1 for count in Counter(names).values()),"Duplicate ZIP entries")
        for entry in jar.infolist():
            name=entry.orig_filename
            require(name==entry.filename and name and not name.startswith("/") and "\\" not in name and ":" not in name
                    and not any(ord(c)<32 for c in name) and not any(p in ("..",".","") for p in name.rstrip("/").split("/")),"Unsafe ZIP entry")
            require(not entry.flag_bits&1,"Encrypted JAR entry")
            if not entry.is_dir():result[name]=jar.read(entry)
        require(jar.testzip() is None,"JAR CRC failure")
    require(META in result,"Missing NeoForge metadata")
    return digest(raw),result


def metadata_graph(jars):
    parsed={key:tomllib.loads(entries[META].decode("utf-8")) for key,entries in jars.items()}
    expected={"fc":{"piq_fc_arcade":"0.31.0-alpha.19"},"sfc":{"piq_sfc_arcade":"0.2.0-alpha.6","piq_sfc_home":"0.1.0-alpha.6"},"native":{"piq_native_arcade":"0.1.0-alpha.6"}}
    owners={};all_mods={};dependencies={}
    for key,data in parsed.items():
        actual={mod["modId"]:mod["version"] for mod in data.get("mods",[])}
        require(actual==expected[key] and len(actual)==len(data["mods"]),"Unexpected mod IDs/versions in "+key)
        require(data.get("modLoader")=="javafml" and data.get("loaderVersion")=="[4,)"
                and data.get("license")==("All Rights Reserved" if key=="fc" else "GPL-3.0-or-later"),"Unexpected loader/license metadata")
        require(set(data.get("dependencies",{}))==set(actual),"Missing/foreign dependency owner")
        for mod,version in actual.items():
            require(mod not in owners,"Duplicate mod ID across jars")
            owners[mod]=key;all_mods[mod]=version
            deps=data["dependencies"][mod]
            require(len({d["modId"] for d in deps})==len(deps),"Duplicate dependency entry")
            require(all(d.get("side")=="BOTH" and (d.get("type")=="required" or mod=="piq_fc_arcade" and d.get("modId")=="waterframes" and d.get("type")=="optional" and d.get("versionRange")=="[2.1.23,3)") for d in deps),"Required BOTH dependency weakened")
            dependencies[mod]=deps
    require(not any("piq_retro_platform"==d["modId"] for deps in dependencies.values() for d in deps),"Independent platform mod dependency forbidden")
    require({d["modId"] for d in dependencies["piq_fc_arcade"]}=={"minecraft","neoforge","waterframes"}
            and {d["modId"] for d in dependencies["piq_fc_arcade"] if d["type"]=="required"}=={"minecraft","neoforge"},"FC main must not depend on add-ons; retain optional WaterFrames integration")
    for mod in ("piq_sfc_home","piq_native_arcade"):
        fc=[d for d in dependencies[mod] if d["modId"]=="piq_fc_arcade"]
        require(len(fc)==1 and fc[0]["versionRange"]=="[0.31.0-alpha.19,0.32.0)" and fc[0]["ordering"]=="AFTER","Addon requires FC19")
    require({d["modId"] for d in dependencies["piq_sfc_home"]}=={"minecraft","neoforge","piq_fc_arcade","piq_sfc_arcade"},"Home dependency set")
    require({d["modId"] for d in dependencies["piq_sfc_arcade"]}=={"minecraft","neoforge","piq_fc_arcade"},"Frozen core dependency set")
    require({d["modId"] for d in dependencies["piq_native_arcade"]}=={"minecraft","neoforge","piq_fc_arcade"},"Native dependency set")
    def present(keys):
        available={"minecraft","neoforge"}|{mod for mod,key in owners.items() if key in keys}
        return all(d["modId"] in available for mod,key in owners.items() if key in keys for d in dependencies[mod] if d["type"]=="required")
    require(present({"fc"}) and present({"fc","sfc"}) and present({"fc","native"}) and present(set(jars)),"Valid install graph rejected")
    require(not present({"sfc"}) and not present({"native"}) and not present({"sfc","native"}),"Missing FC was accepted")
    return {"mods":all_mods,"owners":owners,"dependencies":dependencies,"fc_alone":True,"sfc_without_fc_rejected":True,"native_without_fc_rejected":True,"independent_platform_mod":False}


def unique_ownership(jars):
    classes={};runtime={}
    for key,entries in jars.items():
        for name in entries:
            if name.endswith(".class"):
                require(name not in classes,"Duplicate class ownership: "+name)
                classes[name]=key
                if name.startswith("cn/piq/retro/"):require(key=="fc","Platform API/input must be owned by FC")
                if name.startswith(("io/github/kawamuray/wasmtime/","io/github/wasmtime/")):require(key=="fc","Wasmtime must be owned by FC")
            if name.lower().endswith((".wasm",".dll",".so",".dylib",".exe")):
                require(name not in runtime,"Duplicate runtime ownership")
                runtime[name]=key
                require(key=="fc" or key=="sfc" and name=="assets/piq_sfc_arcade/core/piq_sfc_wasm.wasm","Unapproved runtime owner")
            require(not name.startswith(("META-INF/jarjar/","META-INF/versions/")),"Nested or multi-release runtime needs separate audit")
    require(any(n.startswith("cn/piq/retro/") for n in classes),"Main mod does not contain public platform")
    require(classes.get("cn/piq/retro/client/GamepadInput.class")=="fc","Missing main-owned physical gamepad input")
    require(runtime.get("assets/piq_sfc_arcade/core/piq_sfc_wasm.wasm")=="sfc","Missing bundled SFC runtime")
    return {"classes":len(classes),"duplicate_classes":0,"platform_owner":"fc","runtime_owners":runtime}


def replace_class_utf8(raw,before,after):
    """Replace exactly one permitted constant-pool UTF8 value, no opcodes/fields."""
    require(raw[:4]==b"\xca\xfe\xba\xbe","Invalid class")
    at=10;count=int.from_bytes(raw[8:10],"big");index=1;matches=[]
    while index<count:
        tag=raw[at];at+=1
        if tag==1:
            size=int.from_bytes(raw[at:at+2],"big");start=at;at+=2
            if raw[at:at+size]==before.encode("utf-8"):matches.append((start,at+size))
            at+=size
        elif tag in (3,4):at+=4
        elif tag in (5,6):at+=8;index+=1
        elif tag in (7,8,16,19,20):at+=2
        elif tag in (9,10,11,12,17,18):at+=4
        elif tag==15:at+=3
        else:raise ValueError("Invalid constant pool tag")
        index+=1
    require(len(matches)==1,"Expected exactly one approved message constant")
    start,end=matches[0];replacement=after.encode("utf-8")
    return raw[:start]+len(replacement).to_bytes(2,"big")+replacement+raw[end:]


def protected_class(key,name):
    if key=="sfc":
        return name.startswith(("cn/piq/sfchome/net/","cn/piq/sfchome/server/","cn/piq/sfchome/data/")) or name.startswith("cn/piq/sfchome/client/SfcPlayback") or name.startswith("cn/piq/sfchome/client/SfcJoinClient")
    if key=="native":
        return name.startswith("cn/piq/nativearcade/bridge/") or name=="cn/piq/nativearcade/NativeArcadeMod.class"
    prefix="cn/piq/fcarcade/"
    return (name.startswith(prefix) and ("Payload" in name or name.startswith(tuple(prefix+p for p in ("server/","session/","save/","storage/","core/","home/")))
            or name.startswith(tuple(prefix+p for p in ("FcNetwork","cabinet/CabinetNetwork","cabinet/CabinetLeaseLedger","cabinet/CabinetTarget","cabinet/ServerCabinets"))))) or not name.startswith("cn/piq/")


def frozen_protection(jars,baselines):
    result={}
    for key in ("fc","sfc","native"):
        before=baselines[key];after=jars[key];same=[];debug=[];text_only=[]
        for name,raw in before.items():
            protect=name.endswith(".class") and protected_class(key,name)
            protect|=name.lower().endswith((".wasm",".dll",".so",".dylib",".exe"))
            if not protect:continue
            require(name in after,"Protected entry deleted: "+name)
            if raw==after[name]:same.append(name)
            elif name.endswith(".class") and without_code_debug(raw)==without_code_debug(after[name]):debug.append(name)
            elif key=="fc" and name=="cn/piq/fcarcade/cabinet/ServerCabinets.class":
                replaced=replace_class_utf8(raw,"已结束街机；再次右键可选择游戏","已结束街机；再次右键启动，Shift 空手右键配置游戏")
                require(without_code_debug(replaced)==without_code_debug(after[name]),"ServerCabinets changed beyond exact permitted notice text")
                text_only.append(name)
            else:raise ValueError("Protected protocol/server/lease/core bytes changed: "+name)
        result[key]={"byte_identical_protected_entries":same,"debug_tables_only_identical_execution":debug,"exact_notice_constant_only":text_only}
    protected_core={n:v for n,v in baselines["core"].items() if n not in (META,MANIFEST)}
    for name,raw in protected_core.items():require(jars["sfc"].get(name)==raw,"Frozen bundled SFC6 entry changed/missing: "+name)
    core_meta=tomllib.loads(baselines["core"][META].decode("utf-8"));combined=tomllib.loads(jars["sfc"][META].decode("utf-8"))
    require([m for m in combined["mods"] if m["modId"]=="piq_sfc_arcade"]==core_meta["mods"],"Frozen core mod metadata changed")
    require(combined["dependencies"]["piq_sfc_arcade"]==core_meta["dependencies"]["piq_sfc_arcade"],"Frozen core dependency metadata changed")
    result["frozen_sfc6"]={"byte_identical_entries":len(protected_core),"sha256_by_entry":{n:digest(v) for n,v in sorted(protected_core.items())}}
    return result


def run(command,cwd,timeout=90):
    done=subprocess.run(list(map(str,command)),cwd=cwd,capture_output=True,text=True,encoding="utf-8",errors="replace",timeout=timeout)
    require(done.returncode==0,"Probe failed: "+done.stdout[-3000:]+"\n"+done.stderr[-4000:])
    return done.stdout


def dependencies():
    preferred=[]
    for group,artifact,version in (("net.neoforged.fancymodloader","loader","4.0.43"),("cpw.mods","securejarhandler","3.0.8"),("com.electronwill.night-config","core","3.8.3"),("com.electronwill.night-config","toml","3.8.3")):
        candidates=list((CACHE/group/artifact/version).rglob(artifact+"-"+version+".jar"));require(len(candidates)==1,"Required real FML dependency missing");preferred.extend(candidates)
    manifest=json.loads((MC.parent.parent/"artifacts/minecraft_1.21.1_version_manifest.json").read_text(encoding="utf-8"))
    for library in manifest["libraries"]:
        parts=library["name"].split(":")
        if len(parts)==3:
            preferred.extend((CACHE/parts[0]/parts[1]/parts[2]).rglob(parts[1]+"-"+parts[2]+".jar"))
    rest=[p for p in CACHE.rglob("*.jar") if not any(t in p.name for t in ("-sources","-javadoc","-userdev"))]
    return list(dict.fromkeys(preferred+rest))


def parse_last_json(output):
    lines=[line for line in output.splitlines() if line.startswith("{")]
    require(lines,"Probe did not emit JSON")
    return json.loads(lines[-1])


def java_probes(paths):
    deps=dependencies();discovery=None;packets=None;staging={}
    with tempfile.TemporaryDirectory(prefix="piq-alpha19-compat-") as folder:
        tmp=Path(folder);classes=tmp/"classes";classes.mkdir();empty=tmp/"empty";empty.mkdir()
        # The Windows launcher reads @argfiles using its native encoding. Use
        # byte-identical ASCII temp names rather than losing Chinese path bytes.
        staged={}
        for key,path in paths.items():
            raw=path.read_bytes();target=tmp/(key+".jar")
            with target.open("xb") as stream:stream.write(raw)
            expected=digest(raw)
            require(digest(target.read_bytes())==expected and digest(path.read_bytes())==expected,"Candidate copy changed: "+key)
            staged[key]=target
            staging[key]={"original":str(path),"temporary_probe_copy":str(target),"sha256":expected,"byte_identical_before":True}
        resources=MC.parent/"stripClient_1c8e7d85886c0a45d98a9d38d082e6a9f34fe0f3_resourcesOutput.jar"
        # No old SFC core on the classpath: the supplied combined JAR must own it.
        cp=os.pathsep.join(map(str,[classes,MC,resources,*staged.values(),*deps]))
        args=tmp/"classpath.args";args.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding="utf-8")
        probe=TOOLS/"probes/Alpha19ModDiscoveryProbe.java"
        packet=ROOT/"piq-sfc-home/tools/qa/SfcJoinPacketProbe.java"
        run([JAVA/"javac.exe","@"+str(args),"-encoding","UTF-8","-proc:none","-sourcepath",empty,"-d",classes,probe,packet],tmp)
        discovery=parse_last_json(run([JAVA/"java.exe","-Djava.awt.headless=true","--add-opens=java.base/java.lang.invoke=ALL-UNNAMED","@"+str(args),"Alpha19ModDiscoveryProbe",staged["fc"],staged["sfc"],staged["native"]],tmp))
        require(discovery.get("ok") and discovery.get("production_origin")=="final-jar-only","FML probe contract")
        packets=parse_last_json(run([JAVA/"java.exe","-Djava.awt.headless=true","@"+str(args),"cn.piq.sfchome.net.SfcJoinPacketProbe"],tmp))
        require(packets.get("passed") and packets.get("assertions")==37 and packets.get("max_upload_outer_packet_bytes",999999)<32767,"Outer network codec probe contract")
        for key,path in paths.items():
            require(digest(path.read_bytes())==staging[key]["sha256"] and digest(staged[key].read_bytes())==staging[key]["sha256"],"Candidate copy changed after probes: "+key)
            staging[key]["byte_identical_after"]=True
    return {"fml_discovery":discovery,"sfc_real_neoforge_outer_packet_codec":packets,"hash_checked_temporary_copies":staging,"production_compiled":False,"old_separate_core_on_classpath":False}


def main():
    sys.stdout.reconfigure(encoding="utf-8");parser=argparse.ArgumentParser(description=__doc__)
    for key in ("fc","sfc","native"):parser.add_argument("--"+key,type=Path,required=True)
    parser.add_argument("--report",type=Path,required=True);args=parser.parse_args()
    require(not args.report.exists(),"Refusing to overwrite existing report")
    paths={key:getattr(args,key).resolve(strict=True) for key in ("fc","sfc","native")}
    require(len(set(paths.values()))==3,"Expected three distinct delivered JARs")
    jars={};hashes={};baselines={}
    for key,path in paths.items():hashes[key],jars[key]=archive(path)
    for key,(path,expected) in BASELINES.items():
        found,baselines[key]=archive(path);require(found==expected,"Frozen baseline SHA mismatch: "+key)
    graph=metadata_graph(jars);owners=unique_ownership(jars);protection=frozen_protection(jars,baselines)
    probes=java_probes(paths)
    require(all(archive(path)[0]==hashes[key] for key,path in paths.items()),"Candidate changed during audit")
    result={"ok":True,"schema":"piq-retro-alpha19-compat-1","jars":{key:{"path":str(path),"sha256":hashes[key]} for key,path in paths.items()},
            "dependency_graph":graph,"ownership":owners,"protection":protection,"probes":probes,
            "network_scope":{"fc":"existing FC multiplayer retained","sfc":"existing SFC permission/lease/invitation/state-transfer retained","native":"localOnly; no Minecraft multiplayer emulation claim"},
            "installed":False,"minecraft_or_native_core_started":False,"limits":["No live client/server, physical controller or protection-plugin test.","Metadata discovery and ASM scanning do not equal full FML bootstrap or gameplay."]}
    args.report.parent.mkdir(parents=True,exist_ok=True)
    with args.report.open("x",encoding="utf-8") as stream:json.dump(result,stream,ensure_ascii=False,indent=2)
    print(json.dumps({"ok":True,"report":str(args.report),"jars":hashes,"fml":probes["fml_discovery"]["level"],"packet_assertions":37},ensure_ascii=False))


if __name__=="__main__":main()
