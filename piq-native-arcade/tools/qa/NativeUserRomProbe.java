package cn.piq.nativearcade.bridge;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/** User-authorized local ROM smoke, no downloaded executables and no ROM contents in output. */
public final class NativeUserRomProbe {
    static void screenshot(NativeProcessSession.Frame f,Path path)throws Exception{
        BufferedImage image=new BufferedImage(f.width(),f.height(),BufferedImage.TYPE_INT_ARGB);
        int[] out=new int[f.abgr().length];for(int i=0;i<out.length;i++){int p=f.abgr()[i];out[i]=0xff000000|((p&0xff)<<16)|(p&0xff00)|((p>>>16)&0xff);}
        image.setRGB(0,0,f.width(),f.height(),out,0,f.width());ImageIO.write(image,"png",path.toFile());
    }
    public static void main(String[] args)throws Exception{
        Path runtime=Path.of(args[0]),rom=Path.of(args[1]),screens=Path.of(args[2]);Files.createDirectories(screens);
        NativeProcessSession session=new NativeProcessSession(runtime,rom);
        long start=System.nanoTime();int frames=0,distinct=0,previous=0;long audio=0;int width=0,height=0;boolean coin=false,released=false,begin=false,endStart=false;
        Set<String> snaps=new HashSet<>();
        try{
            while(Duration.ofNanos(System.nanoTime()-start).toMillis()<42000){
                if(session.error()!=null)throw new IllegalStateException(session.error()+"\n"+session.diagnostics());
                long elapsed=Duration.ofNanos(System.nanoTime()-start).toMillis();
                if(elapsed>26000&&!coin){session.offerInput(1<<2,0);coin=true;}
                if(elapsed>26300&&!released){session.offerInput(0,0);released=true;}
                if(elapsed>30000&&!begin){session.offerInput(1<<3,0);begin=true;}
                if(elapsed>30300&&!endStart){session.offerInput(0,0);endStart=true;}
                var frame=session.pollFrame();if(frame!=null){
                    width=frame.width();height=frame.height();frames++;int hash=Arrays.hashCode(frame.abgr());if(hash!=previous)distinct++;previous=hash;
                    for(short s:frame.pcm48k())if(s!=0)audio++;
                    for(int at:new int[]{20000,24000,28000,34000,39000})if(elapsed>=at&&snaps.add("frame-"+at))screenshot(frame,screens.resolve("frame-"+at+".png"));
                }
                Thread.sleep(10);
            }
            if(frames<100||distinct<5||audio==0)throw new AssertionError("No sustained game video/audio frames="+frames+" distinct="+distinct+" audio="+audio);
            System.out.println("WIDTH="+width+" HEIGHT="+height+" POLLED_FRAMES="+frames+" CHANGED_POLLS="+distinct+" NONZERO_PCM="+audio);
            System.out.println("COIN_START_INPUT_EDGES_SENT="+(coin&&released&&begin&&endStart));
            System.out.println("REQUIRES_SCREENSHOT_REVIEW_FOR_RECOGNIZED_COIN_AND_START=true");
            System.out.println("DIAGNOSTICS="+session.diagnostics().replace('\n',' ').replace('\r',' '));
        }finally{session.clearInput();session.close();long deadline=System.nanoTime()+10_000_000_000L;while(NativeProcessSession.hasLiveSession()&&System.nanoTime()<deadline)Thread.sleep(20);if(NativeProcessSession.hasLiveSession())throw new AssertionError("Native process did not exit");}
        System.out.println("EXACT_NATIVE_CHILD_REAPED=true");
    }
}
