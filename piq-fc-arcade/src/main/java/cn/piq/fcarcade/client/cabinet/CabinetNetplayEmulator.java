package cn.piq.fcarcade.client.cabinet;

import cn.piq.fcarcade.cabinet.CabinetRoomNetwork;
import cn.piq.fcarcade.netplay.*;
import cn.piq.retro.api.*;
import net.minecraft.network.Connection;
import java.util.Arrays;

/** The optional addon owns content/profile; this adapter owns the one authorized native lane. */
final class CabinetNetplayEmulator implements RetroEmulator {
    private final NetplayProcess process;private final Connection connection;private final long wire;private final int port;
    private boolean closed;
    private NetplayProcess.Frame video;private int[] videoPixels;
    CabinetNetplayEmulator(CabinetRoomNetwork.NetplayStart grant,Connection connection,CabinetBackend.NetplayContent content){
        this.connection=connection;wire=grant.wire();port=grant.assignment().port();
        if(content.profile().sampleRate()!=48000)throw new IllegalArgumentException("Cabinet audio requires 48 kHz");
        if(grant.assignment().capacity()>content.profile().ports())throw new IllegalArgumentException("附属核心未支持当前席位数，请更新配套街机附属");
        process=new NetplayProcess(new NetplayProcess.Grant(wire,grant.ticket(),port==0,true,port),content::rom,
                chunk->NetplayNetwork.upstream(connection,chunk),content.profile(),content::auxiliary,
                grant.assignment().coinRequired()||cn.piq.fcarcade.cabinet.PgmServicePolicy.supportsBackend(grant.assignment().backend().toString()),grant.assignment().coinRequired());
        if(content.files()!=null)process.fileContent(content.files());
        NetplayNetwork.bind(connection,process);process.start();
    }
    @Override public boolean isReady(){return !closed&&process.ready();}
    @Override public String error(){String error=process.error();if(error!=null)cn.piq.fcarcade.client.ui.DeviceNotices.record("街机 Netplay",process.diagnostic(),new IllegalStateException(error));return error;}
    @Override public void offerInput(int p1,int p2){process.inputRetroPad(port==0?p1:p2);}
    void localInput(int mask){process.inputRetroPad(mask);}
    void serverInput(int port,int mask){process.cabinetInput(port,mask);}
    void coin(int port,long sequence){process.cabinetCoin(port,sequence);}
    @Override public boolean supportsCoinPreservingRelease(){return true;}
    @Override public void releaseGameplayPortKeepingCoin(int port){process.cabinetRelease(port);}
    @Override public void offerInputs(int p1,int p2,int p3,int p4){int[] masks={p1,p2,p3,p4};for(int p=0;p<4;p++)serverInput(p,masks[p]);}
    @Override public void clearInput(){process.inputRetroPad(0);}
    @Override public void releasePort(int value){process.cabinetRelease(value);if(value==port)clearInput();}
    @Override public RetroFrame pollFrame(){
        NetplayProcess.Frame latest=null;short[] pcm=new short[32768];int count=0;boolean received=false;
        for(int i=0;i<6;i++){var frame=process.poll();if(frame==null)break;received=true;if(frame.rgba().length>0)latest=frame;int n=Math.min(frame.stereo().length,pcm.length-count);System.arraycopy(frame.stereo(),0,pcm,count,n);count+=n;}
        if(latest!=null){video=latest;byte[] rgba=latest.rgba();videoPixels=new int[latest.width()*latest.height()];
            for(int i=0;i<videoPixels.length;i++)videoPixels[i]=0xff000000|(rgba[i*4]&255)|((rgba[i*4+1]&255)<<8)|((rgba[i*4+2]&255)<<16);}
        if(!received||video==null)return null;
        return new RetroFrame(video.width(),video.height(),videoPixels,video.aspect(),video.rotation(),Arrays.copyOf(pcm,count));
    }
    @Override public void close(){if(closed)return;closed=true;NetplayNetwork.unbind(connection,process);process.close();}
}
