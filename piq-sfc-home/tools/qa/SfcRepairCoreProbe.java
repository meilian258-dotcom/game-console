package cn.piq.sfchome.client;

import cn.piq.sfcarcade.core.*;
import cn.piq.sfchome.server.SfcRepairLedger;
import com.google.gson.Gson;
import java.nio.file.Path;
import java.util.*;

/** Two real WASM instances, deliberately diverged state, uninterrupted host and state/history repair. */
public final class SfcRepairCoreProbe {
    private static int checks,compared;private static long snapshotNanos;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static int p1(int frame){return frame%9<3?1<<(frame/9%12):0;}
    private static int p2(int frame){return frame%7<2?1<<(frame/7%12):0;}
    private static String frame(SfcExecutionCore core,int a,int b){var r=core.runFrame(new SfcControllerState(a),new SfcControllerState(b));byte[] video=new byte[r.videoMode().requiredRgbaBytes()];core.copyRgbaFrame(video);short[] audio=new short[r.requiredPcmShorts()];int n=core.copyAudioPcm16(audio);check(n==r.stereoSampleFrames(),"stereo frame count");byte[] combined=Arrays.copyOf(video,video.length+n*4);for(int i=0;i<n*2;i++){combined[video.length+i*2]=(byte)audio[i];combined[video.length+i*2+1]=(byte)(audio[i]>>>8);}return SfcExecutionCore.digest(combined);}
    public static void main(String[]args)throws Exception{
        Path expected=Path.of(args[0]).toRealPath();for(Class<?> c:List.of(SfcExecutionCore.class,SfcRepairLedger.class,SfcCheckpoints.class))check(Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(expected),"production origin "+c.getSimpleName());
        var history=new SfcRepairLedger();var cache=new SfcCheckpoints();UUID hostId=new UUID(1,1),peerId=new UUID(2,2),lease=new UUID(3,3);byte[] rom=SfcTwoPortInputProbe.rom();int stateBytes;double fps;
        try(var host=new SfcExecutionCore();var peer=new SfcExecutionCore()){
            host.loadRom(SfcRomImage.fromBytes(rom));peer.loadRom(SfcRomImage.fromBytes(rom));fps=host.initialize();check(fps==peer.initialize(),"same real core FPS");check(Arrays.equals(host.saveState(),peer.saveState()),"same initialized state");
            for(int i=0;i<600;i++){String a=frame(host,p1(i),p2(i)),b=frame(peer,p1(i),i==599?4095:p2(i));history.append(i,new int[]{p1(i)},new int[]{p2(i)});if(i<599){check(a.equals(b),"identical real RGBA and full stereo PCM "+i);compared++;}}
            long began=System.nanoTime();var checkpoint=cache.put(600,host.saveState());snapshotNanos=System.nanoTime()-began;stateBytes=checkpoint.bytes().length;
            String bad=SfcExecutionCore.digest(peer.saveState());check(!bad.equals(checkpoint.sha()),"injected input produces actual divergent state");
            check(history.report(hostId,true,600,checkpoint.sha()).isEmpty(),"host establishes canonical hash");var mismatch=history.report(peerId,false,600,bad);check(mismatch.size()==1,"actual mismatch detected");var repair=history.start(mismatch.getFirst(),lease,100);check(repair!=null,"one isolated repair starts");
            var pictures=new ArrayList<String>();
            // The canonical host is NOT paused while the peer's snapshot is transferred.
            for(int i=600;i<720;i++){pictures.add(frame(host,p1(i),p2(i)));history.append(i,new int[]{p1(i)},new int[]{p2(i)});}
            check(history.next()==720,"healthy host advanced 120 frames during peer repair");String liveHost=SfcExecutionCore.digest(host.saveState());
            for(int at=0,tick=101;at<checkpoint.bytes().length;at+=SfcRepairLedger.CHUNK,tick++)check(repair.append(repair.token,600,stateBytes,at,checkpoint.sha(),Arrays.copyOfRange(checkpoint.bytes(),at,Math.min(stateBytes,at+SfcRepairLedger.CHUNK)),tick),"bounded incremental state SHA");
            check(repair.phase==SfcRepairLedger.Phase.SEND,"canonical state accepted");var restored=new java.io.ByteArrayOutputStream();while(repair.phase==SfcRepairLedger.Phase.SEND){int old=repair.sent;byte[] part=repair.peekPart();check(repair.sent==old,"backpressure peek does not consume state");restored.writeBytes(part);repair.sentPart(part.length);}
            peer.loadState(restored.toByteArray());check(SfcExecutionCore.digest(peer.saveState()).equals(checkpoint.sha()),"actual WASM imported exact checkpoint");check(repair.restored(repair.token,600,checkpoint.sha(),true),"state acknowledgment transitions to replay");
            var replay=history.replay(600);check(replay.p1().length==120,"exact history range");for(int i=0;i<replay.p1().length;i++){check(frame(peer,replay.p1()[i],replay.p2()[i]).equals(pictures.get(i)),"repaired RGBA/full PCM frame "+i);compared++;}
            check(SfcExecutionCore.digest(peer.saveState()).equals(liveHost),"repaired live core state equals continuously running host");check(SfcExecutionCore.digest(host.saveState()).equals(liveHost),"peer repair never replaced host core state");
            repair.resume=720;repair.phase=SfcRepairLedger.Phase.DONE;check(!repair.done(UUID.randomUUID(),720,true),"old token rejected");check(!repair.done(repair.token,719,true),"wrong frame rejected");check(repair.done(repair.token,720,true),"exact replay completion");history.cancel();
            // Restore -> continue through every physical SFC bit, including both shoulder buttons.
            for(int port=0;port<2;port++)for(int bit=0;bit<12;bit++)for(int edge:new int[]{1<<bit,0}){int a=port==0?edge:0,b=port==1?edge:0;check(frame(host,a,b).equals(frame(peer,a,b)),"post-repair input edge "+port+":"+bit);compared++;}
            check(Arrays.equals(host.saveState(),peer.saveState()),"post-repair state remains identical");
        }
        System.out.println(new Gson().toJson(Map.of("ok",true,"assertions",checks,"real_core_instances",2,"rgba_full_stereo_pcm_frames_compared",compared,"host_frames_advanced_during_repair",120,"state_bytes",stateBytes,"checkpoint_save_and_sha_millis",snapshotNanos/1e6,"fps",fps,"minecraft_or_socket_started",false,"commercial_rom_used",false)));
    }
}
