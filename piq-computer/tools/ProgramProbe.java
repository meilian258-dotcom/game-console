package cn.piq.computer.client;
import java.nio.file.*;
import java.util.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
/** Actual adapter/native process probe. Isolated game directory; not a Minecraft GUI test. */
public final class ProgramProbe {
    static void check(boolean ok,String what){if(!ok)throw new AssertionError(what);}
    static void picture(ProgramBackend p,byte[] b,Path file)throws Exception{var image=new BufferedImage(p.width(),p.height(),BufferedImage.TYPE_INT_ARGB);int[] pixels=new int[p.width()*p.height()];for(int i=0;i<pixels.length;i++){int a=i*4;pixels[i]=((b[a+3]&255)<<24)|((b[a]&255)<<16)|((b[a+1]&255)<<8)|(b[a+2]&255);}image.setRGB(0,0,p.width(),p.height(),pixels,0,p.width());ImageIO.write(image,"png",file.toFile());}
    public static void main(String[] args)throws Exception{
        if(args[0].equals("optional-load")){check(!ComputerPrograms.busy(),"unexpected program");check(ComputerPrograms.backend()==null,"unexpected backend");System.out.println("ComputerPrograms loads without the optional PvZ JAR");return;}
        boolean flash=args[0].equals("flash");Path root=Path.of(args[1]),content=Path.of(args[2]),out=Path.of(args[3]);Files.createDirectories(out);
        ProgramBackend backend=flash?new FlashComputerBackend(root,content):new PvzComputerBackend(root,content,UUID.fromString("03e2e551-18ca-4567-b6ca-d8c46a9fbab5"));
        int frames=0;Set<Integer> hashes=new HashSet<>();boolean started=false,pauseStable=false;Integer pauseHash=null;int step=0;byte[] latest=null;long before=System.nanoTime(),start=0;
        try{
            backend.volume(0);
            while(System.nanoTime()-before<65_000_000_000L){
                check(!backend.finished(),backend.error());
                if(!started&&backend.ready()){started=true;start=System.nanoTime();}
                double seconds=started?(System.nanoTime()-start)/1e9:0;
                byte[] bytes=backend.frame();
                if(bytes!=null){check(bytes.length==backend.width()*backend.height()*4,"frame size");frames++;int hash=Arrays.hashCode(bytes);hashes.add(hash);latest=bytes.clone();backend.releaseFrame(bytes);
                    if(frames==1)picture(backend,latest,out.resolve("first.png"));
                    if(seconds>20&&seconds<22){if(pauseHash==null)pauseHash=hash;else check(pauseHash==hash,"paused image changed");pauseStable=true;}
                }
                if(started&&seconds>=3&&step==0){backend.pointer(320,430,1,true);step++;}
                if(seconds>=3.3&&step==1){backend.pointer(320,430,0,true);step++;}
                if(seconds>=6&&step==2){backend.pointer(320,248,1,true);step++;}
                if(seconds>=6.3&&step==3){backend.pointer(320,248,0,true);step++;}
                if(seconds>=9&&step==4){backend.pointer(310,451,1,true);step++;}
                if(seconds>=9.3&&step==5){backend.pointer(310,451,0,true);step++;}
                if(seconds>=12&&step==6){if(latest!=null)picture(backend,latest,out.resolve("before-input.png"));backend.key(262,true);step++;}
                if(seconds>=14&&step==7){backend.key(262,false);if(latest!=null)picture(backend,latest,out.resolve("p1-input.png"));backend.key(68,true);step++;}
                if(seconds>=16&&step==8){backend.key(68,false);if(latest!=null)picture(backend,latest,out.resolve("p2-input.png"));step++;}
                if(seconds>=18&&step==9){backend.releaseInput();backend.pause(true);step++;}
                if(seconds>=22&&step==10){backend.pause(false);step++;}
                if(seconds>=25)break;Thread.sleep(8);
            }
            check(started,"no ready");check(frames>25,"too few frames");check(hashes.size()>3,"blank/stale video");
            // PvZ intentionally stops producing frames while paused; Flash sends static heartbeat frames.
            if(flash)check(pauseStable,"no paused heartbeat");if(latest!=null)picture(backend,latest,out.resolve("last.png"));
        }finally{backend.releaseInput();backend.close();}
        check(backend.error().isEmpty(),backend.error());
        String report="{\"kind\":\""+args[0]+"\",\"ready\":"+started+",\"frames\":"+frames+",\"uniqueFrames\":"+hashes.size()+",\"pauseStable\":"+pauseStable+",\"inputSequenceSent\":"+step+",\"exitConfirmed\":true,\"minecraftTest\":false}";
        Files.writeString(out.resolve("result.json"),report);System.out.println(report);
    }
}
