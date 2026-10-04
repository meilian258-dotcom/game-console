// SPDX-License-Identifier: GPL-3.0-or-later
import cn.piq.mdhome.client.MdProfile;
import cn.piq.retro.libretro.*;
import cn.piq.retro.netplay.RollbackTimeline;
import cn.piq.retro.storage.RuntimeWorkspace;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Diagnostic only: records strict failures rather than treating video agreement as Netplay acceptance. */
public final class MdRollbackGateProbe {
    private static Path out;
    private static byte[] rom;
    private static final List<String> rows = new ArrayList<>();
    private static int strongChecks;
    private static void exact(String name,byte[] a,byte[] b)throws Exception {
        strongChecks++;
        if(!Arrays.equals(a,b)){compare(name,a,b);throw new AssertionError(name);}
    }
    private static LibretroRuntime open() {
        var profile=MdProfile.profile();
        String sha=System.getProperty("piq.probe.core.sha");
        if(sha!=null) profile=new LibretroProfile("Genesis Plus GX PIQ Netplay",profile.extension(),profile.fullPath(),profile.devices(),
            profile.mesenGun(),profile.options(),Map.of("windows-x64",new LibretroProfile.Artifact("/core/windows-x64/gx_probe.dll",sha)));
        var core = LibretroRuntimes.create(profile, sha==null?MdProfile.class:MdRollbackGateProbe.class, LibretroRuntimes.Backend.JNI_TRIAL);
        core.load(rom);
        return core;
    }
    private static LibretroProcess.Output step(LibretroRuntime core, int p1, int p2, int mask) {
        return core.run(List.of(new LibretroProcess.Controls(new int[]{MdProfile.input(MdProfile.Core.GENESIS_PLUS_GX,p1),
            MdProfile.input(MdProfile.Core.GENESIS_PLUS_GX,p2)},0)),mask);
    }
    private static void warm(LibretroRuntime core) { for(int i=0;i<90;i++)step(core,0,0,3); }
    private static int p1(int i) { return i>=5 && i<16?1:0; }
    private static int p2(int i) { return i>=10?256:0; }
    private static String hash(byte[] bytes)throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static void compare(String name,byte[] a,byte[] b)throws Exception {
        var offsets=new ArrayList<Integer>();int count=0;
        for(int i=0;i<Math.min(a.length,b.length);i++)if(a[i]!=b[i]){count++;if(offsets.size()<32)offsets.add(i);}
        String row="{\"case\":\""+name+"\",\"equal\":"+Arrays.equals(a,b)+",\"firstDifference\":"+Arrays.mismatch(a,b)
            +",\"differentBytes\":"+count+",\"firstOffsets\":"+offsets+",\"aLength\":"+a.length+",\"bLength\":"+b.length
            +",\"aSha256\":\""+hash(a)+"\",\"bSha256\":\""+hash(b)+"\"}";
        rows.add(row);System.out.println(row);
        if(!Arrays.equals(a,b)){Files.write(out.resolve(name+"-a.bin"),a);Files.write(out.resolve(name+"-b.bin"),b);}
    }
    private static byte[] pcm(short[] values) { var b=java.nio.ByteBuffer.allocate(values.length*2).order(java.nio.ByteOrder.LITTLE_ENDIAN);for(short v:values)b.putShort(v);return b.array(); }
    private static void malformedSnapshots() throws Exception {
        byte[] good;
        try(var core=open()){warm(core);good=core.serialize();}
        var malformed=new LinkedHashMap<String,byte[]>();
        malformed.put("truncated",Arrays.copyOf(good,good.length-1));
        malformed.put("oversized",Arrays.copyOf(good,good.length+1));
        byte[] magic=good.clone();magic[0]^=1;malformed.put("magic",magic);
        byte[] checksum=good.clone();checksum[checksum.length-1]^=1;malformed.put("checksum",checksum);
        byte[] used=good.clone();used[16]^=1;malformed.put("used-length",used);
        byte[] reserved=good.clone();reserved[20]=1;malformed.put("reserved",reserved);
        for(var entry:malformed.entrySet()) {
            // A rejected JNI restore closes its owner normally. Each case gets a fresh owner.
            try(var core=open()) {
                boolean rejected=false;
                try{core.restore(entry.getValue());}catch(IllegalStateException expected){
                    String reason=entry.getValue().length==good.length?"Core rejected state":"State/core size mismatch";
                    if(!expected.getMessage().contains(reason))throw expected;
                    rejected=true;
                }
                if(!rejected)throw new AssertionError("Accepted malformed snapshot: "+entry.getKey());
            }
            if(LibretroRuntimes.isJniBusy())throw new AssertionError("Rejected snapshot retained owner slot");
            rows.add("{\"case\":\"reject-"+entry.getKey()+"\",\"equal\":true,\"rejected\":true,\"slotsReleased\":true}");
        }
    }
    private static void strong()throws Exception {
        int[] a=new int[600],b=new int[600];var random=new Random(0x47584e50);
        for(int i=0;i<a.length;i++){a[i]=random.nextInt(4096);b[i]=random.nextInt(4096);}
        var stateHashes=new ArrayList<String>();var ramHashes=new ArrayList<String>();
        var videoHashes=new ArrayList<String>();var audioHashes=new ArrayList<String>();
        var seeds=new TreeMap<Integer,byte[]>();long energy=0;
        try(var host=open();var peer=open()) {
            warm(host);warm(peer);
            for(int i=0;i<600;i++) {
                if(i%120==0)seeds.put(i,host.serialize());
                var x=step(host,a[i],b[i],3);var y=step(peer,a[i],b[i],3);
                byte[] xs=host.serialize();
                exact("strong-independent-state-"+i,xs,peer.serialize());
                exact("strong-independent-video-"+i,x.rgba(),y.rgba());
                exact("strong-independent-pcm-"+i,pcm(x.stereo()),pcm(y.stereo()));
                exact("strong-independent-sram-"+i,host.saveMemory().ram(),peer.saveMemory().ram());
                stateHashes.add(hash(xs));ramHashes.add(hash(host.saveMemory().ram()));
                videoHashes.add(hash(x.rgba()));audioHashes.add(hash(pcm(x.stereo())));
                if(i==480){Files.write(out.resolve("cold-expected-state.bin"),xs);Files.write(out.resolve("cold-expected-video.bin"),x.rgba());
                    Files.write(out.resolve("cold-expected-pcm.bin"),pcm(x.stereo()));Files.write(out.resolve("cold-expected-sram.bin"),host.saveMemory().ram());}
                for(short sample:x.stereo())energy+=Math.abs((int)sample);
            }
            for(var entry:seeds.entrySet()) {
                peer.restore(entry.getValue());exact("strong-seed-"+entry.getKey(),entry.getValue(),peer.serialize());
                for(int i=entry.getKey();i<entry.getKey()+120;i++) {
                    var x=step(peer,a[i],b[i],3);strongChecks+=4;
                    if(!hash(peer.serialize()).equals(stateHashes.get(i))||!hash(peer.saveMemory().ram()).equals(ramHashes.get(i))
                        ||!hash(x.rgba()).equals(videoHashes.get(i))||!hash(pcm(x.stereo())).equals(audioHashes.get(i)))
                        throw new AssertionError("strong replay state/PCM/video/SRAM frame "+i);
                }
            }
        }
        if(energy==0||new HashSet<>(videoHashes).size()<2||new HashSet<>(ramHashes).size()<2||new HashSet<>(audioHashes).size()<2)
            throw new AssertionError("strong ROM did not exercise AV/input/SRAM");
        // A completely fresh late join receives a seed from a far advanced instance.
        try(var peer=open()) {peer.restore(seeds.get(480));
            for(int i=480;i<600;i++){var x=step(peer,a[i],b[i],3);strongChecks+=4;
                if(!hash(peer.serialize()).equals(stateHashes.get(i))||!hash(peer.saveMemory().ram()).equals(ramHashes.get(i))
                    ||!hash(x.rgba()).equals(videoHashes.get(i))||!hash(pcm(x.stereo())).equals(audioHashes.get(i))) {
                    if(i==480){compare("cold-state",Files.readAllBytes(out.resolve("cold-expected-state.bin")),peer.serialize());
                        compare("cold-video",Files.readAllBytes(out.resolve("cold-expected-video.bin")),x.rgba());
                        compare("cold-pcm",Files.readAllBytes(out.resolve("cold-expected-pcm.bin")),pcm(x.stereo()));
                        compare("cold-sram",Files.readAllBytes(out.resolve("cold-expected-sram.bin")),peer.saveMemory().ram());}
                    throw new AssertionError("cold latejoin "+i);
                }}
        }
        rows.add("{\"case\":\"strong-600frame-dual-sixbutton-FM-PSG-SRAM\",\"equal\":true,\"exactChecks\":"+strongChecks+",\"audioEnergy\":"+energy+"}");
    }
    public static void main(String[] args)throws Exception {
        out=Path.of(args[0]);rom=Files.readAllBytes(out.resolve("diagnostic.md"));
        RuntimeWorkspace.configure(Files.createDirectory(out.resolve("workspace")));
        // Exact former v3 sequence, before any adjusted experiment.
        try(var core=open()) {
            System.out.println("CORE "+core.coreVersion()+" "+core.info());warm(core);
            byte[] before=core.serialize(),battery=core.saveMemory().ram();
            for(int i=0;i<20;i++)step(core,1,0,3);
            core.restore(before);compare("old-state-only-sram",battery,core.saveMemory().ram());
            for(int i=0;i<20;i++)step(core,1,0,3);
            byte[] state=core.serialize();step(core,0,0,3);core.restore(state);
            compare("old-immediate-roundtrip",state,core.serialize());
            core.restore(state);for(int i=0;i<24;i++)step(core,p1(i),p2(i),3);
            byte[] expected=core.serialize();var expectedAv=step(core,0,0,3);
            core.restore(state);
            var adapter=new RollbackTimeline.Core<LibretroProcess.Output>() {
                public byte[] save(){return core.serialize();}
                public void restore(byte[] s){core.restore(s);}
                public LibretroProcess.Output step(int a,int b,boolean present){return MdRollbackGateProbe.step(core,a,b,present?3:0);}
            };
            var timeline=new RollbackTimeline<>(adapter,0);for(int i=0;i<24;i++)timeline.advance(0,0,0);
            var canonical=new ArrayList<RollbackTimeline.Input>();for(int i=0;i<24;i++)canonical.add(new RollbackTimeline.Input(i,p1(i),p2(i),3));
            if(!timeline.canonical(canonical)||timeline.replayedFrames()!=19)throw new AssertionError("rollback sequence changed");
            compare("old-rollback-state",expected,core.serialize());var actualAv=step(core,0,0,3);
            compare("old-rollback-video",expectedAv.rgba(),actualAv.rgba());compare("old-rollback-pcm",pcm(expectedAv.stereo()),pcm(actualAv.stereo()));
        }
        // Same-instance restore: baseline starts naturally; replay starts via state_load.
        try(var core=open()) {
            warm(core);byte[] checkpoint=core.serialize();var states=new ArrayList<byte[]>();var outputs=new ArrayList<LibretroProcess.Output>();
            for(int i=0;i<30;i++){outputs.add(step(core,p1(i),p2(i),3));states.add(core.serialize());}
            core.restore(checkpoint);compare("restore-initial-roundtrip",checkpoint,core.serialize());
            for(int i=0;i<30;i++) {
                var frame=step(core,p1(i),p2(i),3);
                if(i==0||i==1||i==5||i==10||i==29){compare("restore-frame"+i+"-state",states.get(i),core.serialize());
                    compare("restore-frame"+i+"-video",outputs.get(i).rgba(),frame.rgba());compare("restore-frame"+i+"-pcm",pcm(outputs.get(i).stereo()),pcm(frame.stereo()));}
            }
        }
        // Separate DLL owners: same initial inputs, then real late-join state restore.
        try(var host=open();var peer=open()) {
            warm(host);warm(peer);compare("independent-cold-state",host.serialize(),peer.serialize());
            for(int i=0;i<24;i++){step(host,p1(i),p2(i),3);step(peer,p1(i),p2(i),3);}
            compare("independent-sequence-state",host.serialize(),peer.serialize());
            byte[] state=host.serialize();peer.restore(state);compare("latejoin-initial-state",state,peer.serialize());
            for(int i=0;i<30;i++) {
                var ha=step(host,p1(i),p2(i),3);var pa=step(peer,p1(i),p2(i),3);
                if(i==0||i==1||i==5||i==10||i==29){compare("latejoin-frame"+i+"-state",host.serialize(),peer.serialize());
                    compare("latejoin-frame"+i+"-video",ha.rgba(),pa.rgba());compare("latejoin-frame"+i+"-pcm",pcm(ha.stereo()),pcm(pa.stereo()));}
            }
        }
        if("1".equals(System.getenv("PIQ_PROBE_STRONG"))){strong();malformedSnapshots();if(rows.stream().anyMatch(s->s.contains("\"equal\":false")))throw new AssertionError("Earlier exact gate failed");}
        if(LibretroRuntimes.isJniBusy())throw new AssertionError("native owner slot leak");
        Files.writeString(out.resolve("observations.json"),"{\"scope\":\"strict diagnostic, not release acceptance\",\"slotsReleased\":true,\"rows\":["+String.join(",",rows)+"]}");
        System.out.println("DIAGNOSTIC_COMPLETE rows="+rows.size()+"; failed equalities remain failed Netplay gates");
    }
}
