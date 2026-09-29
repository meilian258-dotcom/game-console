package cn.piq.fcarcade.core.wasm;

import java.nio.file.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.security.MessageDigest;
import java.util.HexFormat;

/** User-authorized local-only game smoke. Does not save/copy the ROM or a memory state. */
public final class ZapperPrivateRomProbe {
    public static void main(String[] args)throws Exception {
        Path romPath=Path.of(args[0]).toAbsolutePath().normalize(),out=Path.of(args[1]).toAbsolutePath().normalize();
        for(Path p=romPath;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IllegalArgumentException("No linked ROM paths");
        if(!Files.isRegularFile(romPath,LinkOption.NOFOLLOW_LINKS)||Files.size(romPath)>8*1024*1024)throw new IllegalArgumentException("ROM is not a bounded regular file");
        byte[] rom;try(var in=Files.newInputStream(romPath,LinkOption.NOFOLLOW_LINKS)){rom=in.readNBytes(8*1024*1024+1);}if(rom.length>8*1024*1024)throw new IllegalArgumentException("ROM grew beyond limit");
        int shoot=args.length>2?Integer.parseInt(args[2]):-1,x=args.length>3?Integer.parseInt(args[3]):128,y=args.length>4?Integer.parseInt(args[4]):100;
        boolean off=args.length>5&&Boolean.parseBoolean(args[5]);
        Files.createDirectories(out);byte[] rgba=new byte[256*240*4];float[] audio=new float[4096];
        try(var core=new ZapperWasmNesCore()) {
            core.loadRom(rom);core.setZapperState(128,100,false,false);
            for(int f=0;f<720;f++) {
                if(f==120){core.setControllerState(0,8);core.setZapperState(128,100,false,true);}if(f==125){core.setControllerState(0,0);core.setZapperState(x,y,false,false);}
                if(f==shoot)core.setZapperState(x,y,off,true);if(f==shoot+6)core.setZapperState(x,y,off,false);
                core.runFrame();core.copyAudioSamples(audio);core.copyFrameRgba(rgba);
                if(f==119||f>=125&&f%30==0||shoot>=0&&f>=shoot-1&&f<=shoot+12)
                    image(out.resolve(String.format("frame-%04d.png",f)),rgba);
            }
        }
        System.out.println("{\"ok\":true,\"actual_wasm_core\":true,\"frames\":720,\"rom_sha256\":\""+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rom))+"\",\"shoot_frame\":"+shoot+",\"aim_x\":"+x+",\"aim_y\":"+y+",\"offscreen\":"+off+",\"minecraft_started\":false,\"rom_copied\":false}");
    }
    private static void image(Path path,byte[] rgba)throws Exception {
        if(Files.exists(path))throw new IllegalArgumentException("Refusing to overwrite private image");
        var image=new BufferedImage(256,240,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<240;y++)for(int x=0;x<256;x++){int i=(y*256+x)*4;image.setRGB(x,y,0xff000000|((rgba[i]&255)<<16)|((rgba[i+1]&255)<<8)|(rgba[i+2]&255));}
        ImageIO.write(image,"PNG",path.toFile());
    }
}
