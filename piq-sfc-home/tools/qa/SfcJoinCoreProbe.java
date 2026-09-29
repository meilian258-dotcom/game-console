package cn.piq.sfcarcade.core;

import cn.piq.sfcarcade.core.wasm.WasmSfcCore;
import cn.piq.sfchome.server.SfcJoinGate;
import java.util.*;

/** Two live frozen core instances; original fixture only, no Minecraft/network emulation claim. */
public final class SfcJoinCoreProbe {
    static int assertions;
    static void check(boolean ok,String why){assertions++;if(!ok)throw new AssertionError(why);}
    public static void main(String[]args)throws Exception{
        UUID token=UUID.randomUUID(),host=UUID.randomUUID(),guest=UUID.randomUUID(),lease=UUID.randomUUID(),console=UUID.randomUUID();
        try(WasmSfcCore p1=new WasmSfcCore();WasmSfcCore p2=new WasmSfcCore()){
            var image=SfcRomImage.fromBytes(SfcLegalTestRom.create());p1.loadRom(image);p2.loadRom(image);
            var initial=SfcJoinGate.sha(p1.saveState());check(initial.equals(SfcJoinGate.sha(p2.saveState())),"Both initial states agree");
            for(int i=0;i<127;i++)p1.runFrame(new SfcControllerState(i%2==0?128:256),SfcControllerState.NONE);
            byte[] captured=p1.saveState();String digest=SfcJoinGate.sha(captured);
            check(!initial.equals(digest),"P1 advanced before join, not reset");check(captured.length<SfcJoinGate.MAX_STATE,"Actual state fits budget");
            SfcJoinGate gate=new SfcJoinGate(token,host,guest,lease,console,0);
            check(!gate.approve(guest,token,true,1),"Applicant cannot approve self");check(gate.approve(host,token,true,1),"P1 consent");
            check(gate.capture(guest,127,2),"P2 loaded before pause boundary");
            check(!gate.append(guest,token,127,captured.length,0,digest,new byte[]{0},3),"P2 cannot supply source state");
            check(!gate.append(host,UUID.randomUUID(),127,captured.length,0,digest,new byte[]{0},3),"Unbound state rejected");
            for(int at=0;at<captured.length;at+=SfcJoinGate.CHUNK){int end=Math.min(captured.length,at+SfcJoinGate.CHUNK);check(gate.append(host,token,127,captured.length,at,digest,Arrays.copyOfRange(captured,at,end),3),"Ordered chunk accepted");}
            check(gate.phase()==SfcJoinGate.Phase.APPLYING,"Complete digest-checked state awaits import");
            check(digest.equals(SfcJoinGate.sha(p1.saveState())),"P1 core unchanged while transferring");
            p2.loadState(captured);check(digest.equals(SfcJoinGate.sha(p2.saveState())),"Real P2 imported exact current P1 state");
            check(!gate.commit(guest,token,128,digest,4),"Wrong frame commit rejected");
            check(!gate.commit(host,token,127,digest,4),"Only P2 acknowledges import");
            check(gate.commit(guest,token,127,SfcJoinGate.sha(p2.saveState()),4),"Atomic exact-frame/hash commit");
            check(!gate.commit(guest,token,127,digest,4),"Commit replay rejected");
            int frames=240;long videoBytes=0,pcmShorts=0;
            for(int i=0;i<frames;i++){
                var a=new SfcControllerState((i%3==0?128:0)|(i%7==0?256:0));var b=new SfcControllerState((i%5==0?64:0)|(i%11==0?1024:0));
                var left=p1.runFrame(a,b);var right=p2.runFrame(a,b);
                check(left.videoMode().equals(right.videoMode()),"Subsequent video timing matches at "+i);
                byte[] x=new byte[left.videoMode().requiredRgbaBytes()],y=new byte[right.videoMode().requiredRgbaBytes()];
                p1.copyRgbaFrame(x);p2.copyRgbaFrame(y);check(Arrays.equals(x,y),"Subsequent pixels match at "+i);videoBytes+=x.length;
                short[] pa=new short[left.requiredPcmShorts()],pb=new short[right.requiredPcmShorts()];
                check(p1.copyAudioPcm16(pa)==p2.copyAudioPcm16(pb)&&Arrays.equals(pa,pb),"Subsequent audio matches at "+i);pcmShorts+=pa.length;
                if(i%30==0)check(Arrays.equals(p1.saveState(),p2.saveState()),"Subsequent full core state matches at "+i);
            }
            String continued=SfcJoinGate.sha(p1.saveState());check(!continued.equals(digest),"Original P1 continued instead of staying paused");
            check(continued.equals(SfcJoinGate.sha(p2.saveState())),"Both cores finish at original boundary+240");
            var failed=new SfcJoinGate(UUID.randomUUID(),host,guest,lease,console,10);check(failed.approve(host,failed.token,true,11),"Second request consent");check(failed.capture(guest,367,12),"Second transfer boundary");failed.close();
            check(continued.equals(SfcJoinGate.sha(p1.saveState())),"Canceled transfer did not reset/load/mutate P1");
            p1.runFrame(SfcControllerState.NONE,SfcControllerState.NONE);check(!continued.equals(SfcJoinGate.sha(p1.saveState())),"P1 can advance alone after canceled transfer");
            System.out.println("{\"passed\":true,\"assertions\":"+assertions+",\"live_core_instances\":2,\"before_join_frames\":127,\"synchronized_following_frames\":240,\"state_bytes\":"+captured.length+",\"state_sha256\":\""+digest+"\",\"compared_video_bytes\":"+videoBytes+",\"compared_pcm_shorts\":"+pcmShorts+",\"minecraft_started\":false,\"commercial_roms\":false}");
        }
    }
}
