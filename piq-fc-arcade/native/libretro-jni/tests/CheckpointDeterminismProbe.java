// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.netplay;

import cn.piq.retro.libretro.*;
import cn.piq.retro.libretro.jni.NativeLibretroBridge;
import cn.piq.retro.storage.RuntimeWorkspace;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Isolated, packaged-core checkpoint transparency test. Never writes the supplied content. */
public final class CheckpointDeterminismProbe {
    private record Diff(boolean equal, int leftBytes, int rightBytes, long changed, int first,
                        String leftHash, String rightHash) {
        String json() { return "{\"equal\":"+equal+",\"leftBytes\":"+leftBytes+",\"rightBytes\":"+rightBytes
                +",\"changed\":"+changed+",\"firstOffset\":"+first+",\"leftHash\":\""+leftHash
                +"\",\"rightHash\":\""+rightHash+"\"}"; }
    }
    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static Diff diff(byte[] a, byte[] b) throws Exception {
        long count=Math.abs(a.length-b.length); int first=-1;
        for(int i=0;i<Math.min(a.length,b.length);i++)if(a[i]!=b[i]){count++;if(first<0)first=i;}
        if(first<0&&a.length!=b.length)first=Math.min(a.length,b.length);
        return new Diff(count==0,a.length,b.length,count,first,hash(a),hash(b));
    }
    private static LibretroProcess.Controls input(int frame) {
        // Same two-port transitions as the generic Netplay diagnostic, independent of wall time.
        return new LibretroProcess.Controls(frame<125?new int[]{256,1}:new int[]{16,128},0);
    }
    private static void warm(LibretroRuntime core,byte[] content)throws Exception {
        core.load(content);
        var neutral=List.of(new LibretroProcess.Controls(new int[]{0,0},0));
        core.run(neutral,0);core.reset();core.run(neutral,0);
        byte[] probe=core.serialize();core.restore(probe);
        if(!Arrays.equals(probe,core.serialize()))throw new AssertionError("Initial roundtrip is inexact");
    }
    private static byte[] checkpoint(LibretroRuntime core,String operation) {
        byte[] state=core.serialize(),current=core.serialize();
        if(operation.equals("checkpoint")) {
            try {core.restore(state);core.saveMemory();return core.serialize();}
            finally {core.restore(current);}
        }
        if(operation.equals("read-only")){core.saveMemory();return core.serialize();}
        if(operation.equals("restore-once")){core.restore(state);return core.serialize();}
        if(operation.equals("none"))return state;
        throw new IllegalArgumentException("operation");
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=7)throw new IllegalArgumentException("output content sha256 profileClass checkpointFrame followFrames operation");
        Path root=Path.of(args[0]).toAbsolutePath(),contentFile=Path.of(args[1]).toAbsolutePath();
        int checkpoint=Integer.parseInt(args[4]),follow=Integer.parseInt(args[5]);String operation=args[6];
        if(checkpoint<1||checkpoint>3600||follow<60||follow>1200
                ||!Set.of("checkpoint","read-only","restore-once","none").contains(operation))throw new IllegalArgumentException("bounded scenario");
        Files.createDirectory(root);
        long contentSize=Files.size(contentFile);var contentTime=Files.getLastModifiedTime(contentFile);
        if(contentSize<16||contentSize>32L*1024*1024+512)throw new IllegalArgumentException("content size");
        byte[] content=Files.readAllBytes(contentFile);
        if(!hash(content).equals(args[2]))throw new IllegalArgumentException("content identity");
        RuntimeWorkspace.configure(Files.createDirectory(root.resolve("instance")));
        NetplayProfile profile=(NetplayProfile)Class.forName(args[3]).getMethod("profile").invoke(null);
        if(profile.jni()==null||profile.ports()!=2)throw new IllegalArgumentException("two-port JNI profile required");
        StringBuilder observations=new StringBuilder();
        int compared=0,preState=-1,postState=-1,preVideo=-1,postVideo=-1;
        boolean immediate=true,seedExact=true,closed=false,unchanged=false;
        Throwable failure=null;
        try(var left=new LibretroJniRuntime(profile.jni(),profile.owner());
            var right=new LibretroJniRuntime(profile.jni(),profile.owner())) {
            warm(left,content);warm(right,content);
            // Equalize the startup seed and the number of restores before the A/B branch.
            byte[] initial=left.serialize();var memory=left.saveMemory();
            left.restoreSaveMemory(memory);left.restore(initial);
            right.restoreSaveMemory(memory);right.restore(initial);
            if(!Arrays.equals(initial,left.serialize())||!Arrays.equals(initial,right.serialize()))
                throw new AssertionError("Shared starting seed is inexact");
            for(int frame=0;frame<checkpoint+follow;frame++) {
                if(frame==checkpoint) {
                    byte[] before=left.serialize(),seed=checkpoint(right,operation);
                    var seedDiff=diff(before,seed);var now=diff(left.serialize(),right.serialize());
                    seedExact=seedDiff.equal;immediate=now.equal;
                    observations.append("{\"kind\":\"checkpoint\",\"frame\":").append(frame)
                            .append(",\"seed\":").append(seedDiff.json()).append(",\"state\":").append(now.json()).append("},\n");
                    System.out.println("CHECKPOINT frame="+frame+" seedExact="+seedExact+" immediateEqual="+immediate);
                }
                var controls=List.of(input(frame));
                var a=left.run(controls,3);var b=right.run(controls,3);
                var state=diff(left.serialize(),right.serialize());var video=diff(a.rgba(),b.rgba());compared++;
                boolean pre=frame<checkpoint;
                if(!state.equal){if(pre&&preState<0)preState=frame+1;else if(!pre&&postState<0)postState=frame+1;}
                if(!video.equal){if(pre&&preVideo<0)preVideo=frame+1;else if(!pre&&postVideo<0)postVideo=frame+1;}
                boolean first=frame+1==preState||frame+1==postState||frame+1==preVideo||frame+1==postVideo;
                int relative=frame+1-checkpoint;
                if(first||frame+1==checkpoint||relative==1||relative==2||relative==60||relative==follow) {
                    observations.append("{\"kind\":\"frame\",\"frame\":").append(frame+1)
                            .append(",\"relative\":").append(relative).append(",\"state\":").append(state.json())
                            .append(",\"video\":").append(video.json()).append("},\n");
                    System.out.println("FRAME "+(frame+1)+" relative="+relative+" stateEqual="+state.equal+" stateChanged="+state.changed
                            +" firstOffset="+state.first+" videoEqual="+video.equal);
                }
            }
        }catch(Throwable ex){failure=ex;ex.printStackTrace();}
        finally {
            closed=NativeLibretroBridge.availableSlots()==4;
            unchanged=Files.size(contentFile)==contentSize&&Files.getLastModifiedTime(contentFile).equals(contentTime)
                    &&hash(Files.readAllBytes(contentFile)).equals(args[2]);
            if(observations.length()>0)observations.setLength(observations.length()-2);
            boolean equal=failure==null&&preState<0&&postState<0&&preVideo<0&&postVideo<0&&immediate&&seedExact&&closed&&unchanged;
            String report="{\n\"scope\":\"Packaged JNI two-core deterministic input; no Minecraft, network or display GPU\",\n"
                    +"\"operation\":\""+operation+"\",\"checkpointFrame\":"+checkpoint+",\"followFrames\":"+follow+",\"comparedFrames\":"+compared
                    +",\n\"firstPreStateDifference\":"+preState+",\"firstPostStateDifference\":"+postState
                    +",\"firstPreVideoDifference\":"+preVideo+",\"firstPostVideoDifference\":"+postVideo
                    +",\n\"seedExact\":"+seedExact+",\"immediateStateEqual\":"+immediate+",\"slotsReleased\":"+closed+",\"inputUnchanged\":"+unchanged
                    +",\"completed\":"+(failure==null)+",\"equal\":"+equal+",\n\"observations\":[\n"+observations+"\n]}\n";
            Files.writeString(root.resolve("result.json"),report,StandardOpenOption.CREATE_NEW);
            System.out.println("CHECKPOINT_DETERMINISM equal="+equal+" preState="+preState+" postState="+postState+" slotsReleased="+closed+" inputUnchanged="+unchanged);
            if(!equal&&failure==null)failure=new AssertionError("A/B mismatch; inspect result.json");
        }
        if(failure!=null)throw new AssertionError("Probe did not pass",failure);
    }
}
