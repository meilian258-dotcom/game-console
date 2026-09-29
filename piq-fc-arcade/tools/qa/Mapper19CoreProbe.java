package cn.piq.fcarcade.core.wasm;

import cn.piq.fcarcade.core.NesCore;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import javax.imageio.ImageIO;

/** Original, in-memory Mapper 19 diagnostics. Optional private ROMs are never bundled or copied. */
public final class Mapper19CoreProbe {
    private static int assertions, comparedFrames;
    private static long comparedSamples;
    private static boolean audible;
    private static void require(boolean ok,String message){assertions++;if(!ok)throw new AssertionError(message);}
    private static void rejected(Runnable action,String message){
        boolean rejected=false;try{action.run();}catch(IllegalArgumentException expected){rejected=true;}
        require(rejected,message);
    }
    private static void emit(List<Integer> p,int... values){for(int value:values)p.add(value&255);}
    private static void write(List<Integer> p,int address,int value){emit(p,0xa9,value,0x8d,address,address>>8);}
    private static void jump(List<Integer> p,int address){emit(p,0x4c,address,address>>8);}
    private static void ppuRead(List<Integer> p,int address,int zeroPage){
        emit(p,0x2c,2,0x20);write(p,0x2006,address>>8);write(p,0x2006,address);
        emit(p,0xad,7,0x20,0xad,7,0x20,0x85,zeroPage);
    }
    private static byte[] diagnostic(int variant){
        byte[] rom=new byte[16+32768+8192];
        rom[0]='N';rom[1]='E';rom[2]='S';rom[3]=26;rom[4]=2;rom[5]=1;rom[6]=0x31;rom[7]=0x10;
        for(int bank=0;bank<3;bank++)Arrays.fill(rom,16+bank*8192,16+(bank+1)*8192,(byte)(0xa0+bank));
        var p=new ArrayList<Integer>();
        emit(p,0x78,0xd8,0xa2,0xff,0x9a);
        write(p,0x2000,0);write(p,0x2001,0);write(p,0xe000,0x40);write(p,0xe800,0xc1);write(p,0xf000,2);
        write(p,0xf800,0);write(p,0xc000,0xfe);write(p,0xc800,0xff);write(p,0xd000,0xfe);write(p,0xd800,0xff);
        for(int bank=0;bank<8;bank++)write(p,0x8000+bank*0x800,bank);
        for(int bank=0;bank<3;bank++)emit(p,0xad,0,0x80+bank*0x20,0x85,0x10+bank);
        // Exercise all three independently switchable PRG windows, then restore them.
        write(p,0xe000,0x42);emit(p,0xad,0,0x80,0x85,0x13);
        write(p,0xe800,0xc0);emit(p,0xad,0,0xa0,0x85,0x14);
        write(p,0xf000,1);emit(p,0xad,0,0xc0,0x85,0x15);
        write(p,0xe000,0x40);write(p,0xe800,0xc1);write(p,0xf000,2);
        for(int bank=0;bank<8;bank++)ppuRead(p,bank*1024,0x20+bank);
        for(int bank=0;bank<8;bank++)write(p,0x8000+bank*0x800,7-bank);
        for(int bank=0;bank<8;bank++)ppuRead(p,bank*1024,0x30+bank);
        for(int bank=0;bank<8;bank++)write(p,0x8000+bank*0x800,bank);
        write(p,0x2006,0x20);write(p,0x2006,0);write(p,0x2007,0x51);
        write(p,0x2006,0x24);write(p,0x2006,0);write(p,0x2007,0x72);
        ppuRead(p,0x2800,0x16);ppuRead(p,0x2c00,0x17);
        // Palette and a filled tile map make background rendering observable.
        emit(p,0x2c,2,0x20);write(p,0x2006,0x3f);write(p,0x2006,0);
        for(int color:new int[]{0x0f,0x30,0x16,0x21})write(p,0x2007,color);
        write(p,0x2006,0x20);write(p,0x2006,0);emit(p,0xa2,4,0xa0,0,0xa9,1);
        int fill=0xe000+p.size();emit(p,0x8d,7,0x20,0xc8,0xd0,0xfa,0xca,0xd0,0xf7);
        // Restore attribute table to palette zero.
        write(p,0x2006,0x23);write(p,0x2006,0xc0);emit(p,0xa2,64,0xa9,0);
        emit(p,0x8d,7,0x20,0xca,0xd0,0xfa);
        write(p,0x2005,0);write(p,0x2005,0);write(p,0x2001,0x0a);
        // Native pulse channel, not N163 expansion audio.
        write(p,0x4015,1);write(p,0x4000,0xbf);write(p,0x4001,0);write(p,0x4002,0x80);write(p,0x4003,0x08);
        emit(p,0xa9,0,0x85,0);
        int loop=0xe000+p.size();
        emit(p,0x2c,2,0x20,0x10,0xfb); // Wait for vblank and acknowledge it.
        write(p,0x4016,1);write(p,0x4016,0);emit(p,0xa2,0);
        int input=0xe000+p.size();
        emit(p,0xad,0x16,0x40,0x29,1,0x9d,0,1,0xad,0x17,0x40,0x29,1,0x9d,8,1,0xe8,0xe0,8);
        emit(p,0xd0,(input-(0xe000+p.size()+2))&255,0xe6,0);jump(p,loop);
        int irq=0xe000+p.size();emit(p,0x40);
        for(int n=0;n<p.size();n++)rom[16+24576+n]=(byte)(int)p.get(n);
        for(int offset:new int[]{0x7ffa,0x7ffc,0x7ffe}){
            int vector=offset==0x7ffc?0xe000:irq;rom[16+offset]=(byte)vector;rom[16+offset+1]=(byte)(vector>>8);
        }
        for(int row=0;row<8;row++)rom[16+32768+16+row]=(byte)((row&1)==0?0xaa:0x55);
        for(int bank=0;bank<8;bank++)rom[16+32768+1024*bank]=(byte)(0x10+bank);
        rom[16+32768+31]=(byte)variant;
        require(fill>=0xe000&&p.size()<8192,"generated ROM fits fixed PRG bank");
        return rom;
    }
    private static byte[] ram(NesCore core){byte[] out=new byte[NesCore.CPU_RAM_BYTES];core.copyCpuRam(out);return out;}
    private static byte[] frame(NesCore core){byte[] out=new byte[NesCore.RGBA_BYTES];core.copyFrameRgba(out);return out;}
    private static void frames(NesCore core,int count){float[] audio=new float[4096];for(int i=0;i<count;i++){core.runFrame();core.copyAudioSamples(audio);}}
    private static void compare(NesCore one,NesCore two){
        one.runFrame();two.runFrame();
        require(Arrays.equals(frame(one),frame(two)),"deterministic RGBA at frame "+comparedFrames);
        require(Arrays.equals(ram(one),ram(two)),"deterministic RAM at frame "+comparedFrames);
        float[] a=new float[4096],b=new float[4096];int na=one.copyAudioSamples(a),nb=two.copyAudioSamples(b);
        require(na==nb,"deterministic audio count");
        for(int i=0;i<na;i++){
            require(Float.floatToIntBits(a[i])==Float.floatToIntBits(b[i]),"deterministic audio sample");
            require(Float.isFinite(a[i]),"finite audio sample");if(Math.abs(a[i])>0.00001f)audible=true;
        }
        comparedFrames++;comparedSamples+=na;
    }
    private static void ports(NesCore core,int p1,int p2){
        core.setControllerState(0,p1);core.setControllerState(1,p2);frames(core,3);byte[] ram=ram(core);
        for(int bit=0;bit<8;bit++){
            require(ram[0x100+bit]==((p1>>bit)&1),"P1 bit "+bit);
            int expected=(bit==2||bit==3)?0:(p2>>bit)&1;
            require(ram[0x108+bit]==expected,"P2 existing supported bit "+bit);
        }
    }
    private static void synthetic()throws Exception{
        byte[] rom=diagnostic(0);
        try(var one=new NamcoWasmNesCore();var two=new NamcoWasmNesCore();var other=new NamcoWasmNesCore()){
            byte[] wrongMapper=rom.clone();wrongMapper[6]=1;wrongMapper[7]=0;
            rejected(()->one.loadRom(wrongMapper),"non-19 ROM rejected before load");
            rejected(()->one.loadRom(new byte[8]),"bad ROM rejected before load");
            one.loadRom(rom);two.loadRom(rom);other.loadRom(diagnostic(1));
            require(!one.supportsZapper(),"Mapper19 does not claim light-gun support");
            require(one.stateNamespace().equals("nes-mapper19-v1/"+NamcoWasmNesCore.MODULE_SHA256),"namespace includes actual module identity");
            for(int i=0;i<150;i++){
                int p1=(i/5*37)&255,p2=(i/7*19)&0xf3;
                one.setControllerState(0,p1);two.setControllerState(0,p1);one.setControllerState(1,p2);two.setControllerState(1,p2);compare(one,two);
            }
            byte[] initial=ram(one);for(int i=0;i<6;i++)require(Byte.toUnsignedInt(initial[0x10+i])==new int[]{0xa0,0xa1,0xa2,0xa2,0xa0,0xa1}[i],"PRG bank marker "+i);
            for(int bank=0;bank<8;bank++){
                require(Byte.toUnsignedInt(initial[0x20+bank])==0x10+bank,"CHR initial 1KiB bank "+bank);
                require(Byte.toUnsignedInt(initial[0x30+bank])==0x17-bank,"CHR switched 1KiB bank "+bank);
            }
            require(initial[0x16]==0x51&&initial[0x17]==0x72,"Namco CIRAM vertical nametable mapping");
            require(audible&&comparedSamples>0,"native APU sound is exercised");
            Set<Integer> colors=new HashSet<>();byte[] picture=frame(one);
            for(int i=0;i<picture.length;i+=4)colors.add(ByteBuffer.wrap(picture,i,4).getInt());
            require(colors.size()>1,"checkerboard video contains distinct colors, not a blank opaque frame");
            for(int bit=0;bit<8;bit++)ports(one,1<<bit,(1<<bit)&0xf3);
            ports(one,0,0);ports(one,0xff,0xf3);
            byte[] saved=one.saveTransientState();require(ByteBuffer.wrap(saved).getInt()==0x504e3139,"independent state magic");
            two.loadTransientState(saved);
            for(int i=0;i<80;i++){int p=i*11&255;one.setControllerState(0,p);two.setControllerState(0,p);compare(one,two);}
            byte[] before=one.saveTransientState(),otherBefore=other.saveTransientState();
            rejected(()->other.loadTransientState(saved),"wrong ROM state rejected");
            require(Arrays.equals(otherBefore,other.saveTransientState()),"wrong ROM state did not mutate target");
            for(int offset:new int[]{0,4,8,40,72,104,108,112,116,120}){
                byte[] bad=saved.clone();bad[offset]^=1;
                rejected(()->one.loadTransientState(bad),"state magic/version/module/ROM/baseline/ABI rejected at "+offset);
                require(Arrays.equals(before,one.saveTransientState()),"invalid state is atomic at "+offset);
            }
            rejected(()->one.loadTransientState(Arrays.copyOf(saved,saved.length-1)),"truncated state rejected");
            rejected(()->one.loadTransientState(Arrays.copyOf(saved,saved.length+1)),"trailing compressed data rejected");
            rejected(()->one.loadTransientState(null),"null state rejected");
            require(Arrays.equals(before,one.saveTransientState()),"all malformed states preserved live state");
            rejected(()->one.setControllerState(-1,1),"negative port rejected");rejected(()->one.setControllerState(2,1),"third port rejected");
            one.reset();frames(one,5);byte[] reset=ram(one);
            for(int i=0;i<16;i++)require(reset[0x100+i]==0,"reset releases both ports "+i);
            // Establish both streams from the same post-reset snapshot; compare future video and audio.
            two.loadTransientState(one.saveTransientState());for(int i=0;i<20;i++)compare(one,two);
        }
    }
    private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static void png(byte[] rgba,Path path)throws Exception{
        BufferedImage image=new BufferedImage(NesCore.WIDTH,NesCore.HEIGHT,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<NesCore.HEIGHT;y++)for(int x=0;x<NesCore.WIDTH;x++){
            int at=(y*NesCore.WIDTH+x)*4;image.setRGB(x,y,((rgba[at+3]&255)<<24)|((rgba[at]&255)<<16)|((rgba[at+1]&255)<<8)|(rgba[at+2]&255));
        }
        if(Files.exists(path))throw new IllegalStateException("Refusing to overwrite private screenshot");
        if(!ImageIO.write(image,"PNG",path.toFile()))throw new IllegalStateException("No PNG encoder");
    }
    private static void privateRom(Path romPath,Path output)throws Exception{
        long size=Files.size(romPath);if(size<16||size>16*1024*1024)throw new IllegalArgumentException("Private ROM size");
        byte[] rom=Files.readAllBytes(romPath);String identity=sha(rom);Files.createDirectory(output);
        Set<String> frameHashes=new HashSet<>();long samples=0;double energy=0;
        try(var core=new NamcoWasmNesCore();var peer=new NamcoWasmNesCore()){
            core.loadRom(rom);peer.loadRom(rom);float[] audio=new float[4096];
            for(int n=0;n<1800;n++){
                // Select default 1-player mode. Movement waits until the landing/title transition has finished.
                int mask=n>=300&&n<308?8:n>=1200&&n<1380?0x81:n>=1380&&n<1560?0x22:n>=1560&&n<1740?0x41:0;
                core.setControllerState(0,mask);core.setControllerState(1,0);core.runFrame();
                int count=core.copyAudioSamples(audio);samples+=count;for(int i=0;i<count;i++)energy+=(double)audio[i]*audio[i];
                if(n%30==0)frameHashes.add(sha(frame(core)));
                if(n==299||n==899||n==1199||n==1379||n==1559||n==1799)png(frame(core),output.resolve("frame-"+(n+1)+".png"));
            }
            peer.loadTransientState(core.saveTransientState());for(int n=0;n<120;n++)compare(core,peer);
        }
        require(identity.equals(sha(Files.readAllBytes(romPath))),"private input ROM unchanged");
        String report="{\"private_diagnostic_only\":true,\"rom_sha256\":\""+identity+"\",\"frames\":1920,\"mode\":\"1-player default; P2 neutral\",\"input_sequence\":\"Start at frame301; Right+A at1201; Down+B at1381; Left+A at1561\",\"distinct_sampled_frames\":"+frameHashes.size()+",\"audio_samples\":"+samples+",\"audio_rms\":"+Math.sqrt(energy/Math.max(1,samples))+",\"asserts_gameplay_success\":false,\"screenshots_not_for_distribution\":true}";
        Files.writeString(output.resolve("private-summary.json"),report,StandardOpenOption.CREATE_NEW);
    }
    public static void main(String[] args)throws Exception{
        if(args.length==1&&args[0].equals("--expect-module-reject")){
            boolean identityRejected=false;
            try(var ignored=new NamcoWasmNesCore()){}catch(IllegalStateException expected){
                for(Throwable cause=expected;cause!=null;cause=cause.getCause())
                    if(String.valueOf(cause.getMessage()).contains("module identity mismatch"))identityRejected=true;
            }
            require(identityRejected,"modified WASM resource rejected by pinned identity before execution");
            System.out.println("{\"ok\":true,\"modified_module_rejected\":true}");return;
        }
        synthetic();if(args.length!=0){if(args.length!=2)throw new IllegalArgumentException("Optional args: ROM private-new-output");privateRom(Path.of(args[0]),Path.of(args[1]));}
        System.out.println("{\"ok\":true,\"assertions\":"+assertions+",\"compared_frames\":"+comparedFrames+",\"compared_audio_samples\":"+comparedSamples+",\"synthetic_rom_in_memory\":true,\"private_rom_used\":"+(args.length!=0)+",\"p2_start_select_supported\":false,\"minecraft_started\":false}");
    }
}
