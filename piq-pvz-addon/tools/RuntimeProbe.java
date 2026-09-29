// SPDX-License-Identifier: GPL-3.0-or-later
// Independent Java smoke test against the final addon JAR, not a Minecraft integration test.
import cn.piq.pvz.runtime.PvzRuntime;
import java.nio.file.*;
import java.util.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

public final class RuntimeProbe {
    private static void require(boolean v,String message){if(!v)throw new IllegalStateException(message);}
    private static byte[] waitFrame(PvzRuntime runtime,long milliseconds) throws Exception {
        long end=System.nanoTime()+milliseconds*1_000_000;byte[] data;
        while(System.nanoTime()<end){if((data=runtime.poll())!=null)return data;require(!runtime.finished(),runtime.error());Thread.sleep(50);}
        throw new IllegalStateException("No frame: "+runtime.status());
    }
    private static void image(byte[] bytes,Path path) throws Exception {
        BufferedImage image=new BufferedImage(800,600,BufferedImage.TYPE_INT_ARGB);
        for(int y=0,i=0;y<600;y++)for(int x=0;x<800;x++,i+=4)image.setRGB(x,y,0xff000000|(bytes[i]&255)<<16|(bytes[i+1]&255)<<8|(bytes[i+2]&255));
        ImageIO.write(image,"PNG",path.toFile());
    }
    private static void capture(PvzRuntime r,Path file)throws Exception {byte[] data=waitFrame(r,45000);try{image(data,file);}finally{r.releaseFrame(data);}}
    public static void main(String[] args) throws Exception {
        Path game=Path.of(args[0]).toAbsolutePath(),data=Path.of(args[1]).toAbsolutePath();Files.createDirectories(game);
        UUID player=UUID.fromString("83b6c928-2aa2-445c-a491-557c78006edb");
        Path saved;
        try(PvzRuntime runtime=new PvzRuntime(game,data,player)){
            runtime.volume(0);capture(runtime,game.resolve("java-first-frame.png"));saved=runtime.saveDirectory();
            boolean rejected=false;try(PvzRuntime duplicate=new PvzRuntime(game,data,player)){}catch(Exception e){rejected=true;System.out.println("LOCK_REJECTED "+e.getClass().getSimpleName());}
            require(rejected,"Duplicate save writer was allowed");
            runtime.pause(true);Thread.sleep(500);runtime.releaseFrame(runtime.poll());Thread.sleep(350);require(runtime.poll()==null,"Frames still arrive while paused");
            runtime.pause(false);runtime.releaseFrame(waitFrame(runtime,5000));
            runtime.input(0,0,0,0,false);runtime.key(true,13,0);runtime.key(false,13,0);
            System.out.println("READY "+runtime.status());runtime.close();require(runtime.finished(),"Close unfinished");require(runtime.error().isEmpty(),runtime.error());
        }
        try(PvzRuntime restarted=new PvzRuntime(game,data,player)){
            restarted.volume(0);capture(restarted,game.resolve("java-restart-frame.png"));require(saved.equals(restarted.saveDirectory()),"Save namespace changed");
            restarted.close();require(restarted.error().isEmpty(),restarted.error());
        }
        System.out.println("PASS jar-resource-pins frame pause resume duplicate-lock graceful-close restart");
    }
}
