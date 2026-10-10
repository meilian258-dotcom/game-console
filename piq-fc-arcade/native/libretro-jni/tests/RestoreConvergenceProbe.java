// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.netplay;

import cn.piq.retro.libretro.*;
import cn.piq.retro.libretro.jni.NativeLibretroBridge;
import cn.piq.retro.storage.RuntimeWorkspace;
import java.nio.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Diagnostic only: describes unmodified Mesen-S state blocks; never patches state bytes. */
public final class RestoreConvergenceProbe {
    private record Block(int prefix,int size){int data(){return prefix+4;}}
    private record Layout(int version,int memoryStart,int spcStart,int cycleOffset,long master,long cycle,double ratio){
        long target(){return (long)(master*ratio);}
        String json(){return "{\"version\":"+version+",\"memoryBlock\":"+memoryStart+",\"spcBlock\":"+spcStart
                +",\"cycleOffset\":"+cycleOffset+",\"masterClock\":"+master+",\"spcCycle\":"+cycle
                +",\"ratio\":"+ratio+",\"targetCycle\":"+target()+",\"targetMinusCycle\":"+(target()-cycle)+"}";}
    }
    private static Block block(ByteBuffer b,int offset){
        if(offset<0||offset>b.capacity()-4)throw new IllegalArgumentException("block offset");
        int size=b.getInt(offset);if(size<0||size>b.capacity()-offset-4)throw new IllegalArgumentException("block size");
        return new Block(offset,size);
    }
    private static Layout layout(byte[] bytes){
        ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if(bytes.length<64||bytes[0]!='M'||bytes[1]!='S'||bytes[2]!='S'||b.getInt(7)!=9||bytes[51]!=0)
            throw new IllegalArgumentException("Expected uncompressed SNES MSS version 9");
        int nameLength=b.getInt(52);if(nameLength<0||nameLength>128)throw new IllegalArgumentException("name size");
        int offset=56+nameLength;Block[] top=new Block[8];
        for(int i=0;i<top.length;i++){top[i]=block(b,offset);offset=top[i].data()+top[i].size;}
        Block memory=block(b,top[1].data()),first=block(b,top[7].data());
        if(memory.size<8||first.size!=15)throw new IllegalArgumentException("Reviewed scalar layout differs");
        int next=first.data()+first.size;
        for(int i=1;i<5;i++){var part=block(b,next);next=part.data()+part.size;}
        Block clock=block(b,next);if(clock.size!=10)throw new IllegalArgumentException("Reviewed clock layout differs");
        return new Layout(9,top[1].prefix,top[7].prefix,first.data()+1,b.getLong(memory.data()),
                b.getLong(first.data()+1),b.getDouble(clock.data()+2));
    }
    private static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static String diff(byte[] a,byte[] b){
        int count=Math.abs(a.length-b.length),first=-1;StringBuilder changes=new StringBuilder();
        for(int i=0;i<Math.min(a.length,b.length);i++)if(a[i]!=b[i]){
            count++;if(first<0)first=i;
            if(count<=8){if(changes.length()>0)changes.append(',');changes.append("{\"offset\":").append(i)
                    .append(",\"before\":").append(a[i]&255).append(",\"after\":").append(b[i]&255).append('}');}
        }
        return "{\"equal\":"+(count==0)+",\"changed\":"+count+",\"firstOffset\":"+first+",\"firstChanges\":["+changes+"]}";
    }
    private static void warm(LibretroRuntime core,byte[] content){
        core.load(content);var neutral=List.of(new LibretroProcess.Controls(new int[]{0,0},0));
        core.run(neutral,0);core.reset();core.run(neutral,0);byte[] state=core.serialize();core.restore(state);
        if(!Arrays.equals(state,core.serialize()))throw new AssertionError("startup roundtrip");
    }
    private static List<LibretroProcess.Controls> input(int frame){return List.of(new LibretroProcess.Controls(frame<125?new int[]{256,1}:new int[]{16,128},0));}
    private static void record(StringBuilder report,String label,byte[] before,byte[] after){
        String item="{\"label\":\""+label+"\",\"diff\":"+diff(before,after)+",\"before\":"+layout(before).json()+",\"after\":"+layout(after).json()+"}";
        if(report.length()>0)report.append(",\n");report.append(item);System.out.println(item);
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=5)throw new IllegalArgumentException("newOutput content expectedSHA profileClass checkpointFrame");
        Path root=Files.createDirectory(Path.of(args[0]).toAbsolutePath()),source=Path.of(args[1]);
        byte[] content=Files.readAllBytes(source);long size=Files.size(source);var mtime=Files.getLastModifiedTime(source);
        if(!hash(content).equals(args[2]))throw new IllegalArgumentException("content identity");
        int checkpoint=Integer.parseInt(args[4]);if(checkpoint<1||checkpoint>3600)throw new IllegalArgumentException("frame bound");
        RuntimeWorkspace.configure(Files.createDirectory(root.resolve("instance")));
        NetplayProfile profile=(NetplayProfile)Class.forName(args[3]).getMethod("profile").invoke(null);
        StringBuilder evidence=new StringBuilder();Throwable failure=null;
        try(var control=new LibretroJniRuntime(profile.jni(),profile.owner());
            var changed=new LibretroJniRuntime(profile.jni(),profile.owner());
            var verifier=new LibretroJniRuntime(profile.jni(),profile.owner())){
            warm(control,content);warm(changed,content);warm(verifier,content);
            byte[] initial=control.serialize();var memory=control.saveMemory();
            for(var core:List.of(control,changed,verifier)){core.restoreSaveMemory(memory);core.restore(initial);}
            for(int frame=0;frame<checkpoint;frame++){
                control.run(input(frame),3);changed.run(input(frame),3);
                if(!Arrays.equals(control.serialize(),changed.serialize()))throw new AssertionError("pre-checkpoint difference");
            }
            byte[] original=control.serialize();
            for(int attempt=1;attempt<=3;attempt++){changed.restore(original);record(evidence,"restore-original-"+attempt,original,changed.serialize());}
            byte[] fixed=changed.serialize();
            for(int attempt=1;attempt<=3;attempt++){changed.restore(fixed);record(evidence,"restore-fixed-point-"+attempt,fixed,changed.serialize());}
            for(int frame=checkpoint;frame<checkpoint+120;frame++){
                var a=control.run(input(frame),3);var b=changed.run(input(frame),3);
                int relative=frame+1-checkpoint;
                if(Set.of(1,2,3,10,60,120).contains(relative)){
                    byte[] left=control.serialize(),right=changed.serialize();
                    record(evidence,"continue-"+relative,left,right);
                    System.out.println("VIDEO relative="+relative+" equal="+Arrays.equals(a.rgba(),b.rgba())+" audioEqual="+Arrays.equals(a.stereo(),b.stereo()));
                    // A separate verifier cannot mutate either ongoing comparison timeline.
                    verifier.restoreSaveMemory(control.saveMemory());verifier.restore(left);
                    record(evidence,"control-frame-restore-"+relative,left,verifier.serialize());
                }
            }
        }catch(Throwable ex){failure=ex;ex.printStackTrace();}
        boolean unchanged=Files.size(source)==size&&Files.getLastModifiedTime(source).equals(mtime)&&hash(Files.readAllBytes(source)).equals(args[2]);
        boolean released=NativeLibretroBridge.availableSlots()==4;
        Files.writeString(root.resolve("result.json"),"{\"completed\":"+(failure==null)+",\"inputUnchanged\":"+unchanged
                +",\"slotsReleased\":"+released+",\"records\":[\n"+evidence+"\n]}\n",StandardOpenOption.CREATE_NEW);
        if(failure!=null||!unchanged||!released)throw new AssertionError("Probe failed",failure);
        System.out.println("RESTORE_CONVERGENCE_COMPLETED inputUnchanged="+unchanged+" slotsReleased="+released);
    }
}
