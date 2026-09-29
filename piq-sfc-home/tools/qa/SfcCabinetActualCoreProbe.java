package cn.piq.sfchome.client.cabinet;
import cn.piq.sfcarcade.core.*;
import cn.piq.sfcarcade.core.wasm.WasmSfcCore;
import cn.piq.sfchome.client.SfcCoreLease;
import cn.piq.fcarcade.cabinet.CabinetFrame;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Original 65816 two-port fixture through the actual new async adapter and frozen old WASM core. */
public final class SfcCabinetActualCoreProbe {
    static int checks;static double ntsc,pal;static int audioShorts;
    static void require(boolean ok,String text){checks++;if(!ok)throw new AssertionError(text);}
    static void until(BooleanSupplier test)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(!test.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(5);require(test.getAsBoolean(),"Timed out waiting for adapter/core");}
    static final class Observed implements SfcCore {
        final WasmSfcCore actual=new WasmSfcCore();final List<Integer> masks=new CopyOnWriteArrayList<>();volatile double fps;
        public String backendName(){return actual.backendName();}public void loadRom(SfcRomImage r){actual.loadRom(r);}
        public SfcFrameResult runFrame(SfcControllerState a,SfcControllerState b){masks.add(a.mask()|(b.mask()<<12));var f=actual.runFrame(a,b);fps=f.videoMode().targetFramesPerSecond();return f;}
        public void copyRgbaFrame(byte[] b){actual.copyRgbaFrame(b);}public int copyAudioPcm16(short[] b){return actual.copyAudioPcm16(b);}
        public byte[] saveState(){return actual.saveState();}public void loadState(byte[] s){actual.loadState(s);}public byte[] saveSram(){return actual.saveSram();}public void loadSram(byte[] s){actual.loadSram(s);}public void reset(boolean h){actual.reset(h);}public void close(){actual.close();}
    }
    static boolean color(CabinetFrame frame,int port){
        if(frame==null)return false;int matches=0;
        for(int pixel:frame.abgr()){
            int r=pixel&255,g=(pixel>>>8)&255,b=(pixel>>>16)&255;
            require((pixel>>>24)==255,"Opaque ABGR");
            if(port<0?r<10&&g<10&&b<10:port==0?r>200&&g<20&&b<20:g>200&&r<20&&b<20)matches++;
        }
        require(frame.rotation()==0,"Unrotated SFC frame");require(frame.displayAspect()>1.1f&&frame.displayAspect()<1.5f,"Core pixel aspect");
        return matches>frame.abgr().length/2;
    }
    static void waitColor(SfcCabinetSession s,int port)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<end){if(s.error()!=null)throw new AssertionError(s.error());if(color(s.pollFrame(),port))return;Thread.sleep(5);}throw new AssertionError("Port color missing "+port);}
    static boolean sequence(List<Integer> values,int... target){outer:for(int i=0;i<=values.size()-target.length;i++){for(int j=0;j<target.length;j++)if(values.get(i+j)!=target[j])continue outer;return true;}return false;}
    static byte[] fixture(boolean pal){byte[] rom=SfcTwoPortInputProbe.rom();if(pal){rom[0x7fd9]=2;int sum=0;for(int i=0;i<rom.length;i++)if(i<0x7fdc||i>0x7fdf)sum=(sum+(rom[i]&255))&65535;int crc=(sum+0x1fe)&65535,inv=crc^65535;rom[0x7fdc]=(byte)inv;rom[0x7fdd]=(byte)(inv>>>8);rom[0x7fde]=(byte)crc;rom[0x7fdf]=(byte)(crc>>>8);}return rom;}
    public static void main(String[] args)throws Exception{
        var observed=new java.util.concurrent.atomic.AtomicReference<Observed>();
        var s=new SfcCabinetSession(SfcRomImage.fromBytes(fixture(false)),()->{var c=new Observed();observed.set(c);return c;});
        try{
            until(s::isReady);var core=observed.get();ntsc=core.fps;require(ntsc>59&&ntsc<61,"NTSC core metadata");
            for(int port=0;port<2;port++)for(int bit=0;bit<12;bit++){
                s.clearInput();waitColor(s,-1);int mask=1<<bit;s.offerInput(port==0?mask:0,port==1?mask:0);waitColor(s,port);
                require(core.masks.contains(port==0?mask:mask<<12),"All twelve actual port inputs");
            }
            s.clearInput();waitColor(s,-1);int before=core.masks.size();
            s.offerInput(1,0);s.offerInput(0,0);s.offerInput(2,0);s.offerInput(0,0);
            until(()->sequence(core.masks.subList(before,core.masks.size()),1,0,2,0));
            s.pollFrame();Thread.sleep(130);var merged=s.pollFrame();require(merged!=null,"Aggregated output");
            audioShorts=merged.pcm48k().length;require(audioShorts>6000&&audioShorts<=32768&&(audioShorts&1)==0,"Accumulated actual 48k stereo PCM");
            s.offerInput(1,1);s.offerInput(4,8);s.clearInput();waitColor(s,-1);
            require(SfcCoreLease.occupied(),"Owner remains held during real run");
        }finally{s.close();until(()->!SfcCoreLease.occupied());}
        var p=new SfcCabinetSession(SfcRomImage.fromBytes(fixture(true)),()->{var c=new Observed();observed.set(c);return c;});
        try{until(p::isReady);var core=observed.get();pal=core.fps;require(pal>49&&pal<51,"PAL core metadata");int begin=core.masks.size();Thread.sleep(500);int count=core.masks.size()-begin;require(count>=19&&count<=32,"PAL worker pacing "+count);p.offerInput(0,2048);waitColor(p,1);}finally{p.close();until(()->!SfcCoreLease.occupied());}
        System.out.println("{\"passed\":true,\"assertions\":"+checks+",\"two_port_key_cases\":24,\"ntsc_fps\":"+ntsc+",\"pal_fps\":"+pal+",\"merged_pcm_shorts\":"+audioShorts+",\"fast_edge_sequence\":true,\"commercial_roms\":false,\"minecraft_gameplay\":false}");
    }
}
