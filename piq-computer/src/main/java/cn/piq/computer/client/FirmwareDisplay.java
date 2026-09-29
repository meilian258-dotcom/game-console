package cn.piq.computer.client;

import cn.piq.computer.Assembly;
import cn.piq.computer.world.ComputerEntity;
import cn.piq.fcarcade.client.HomeVideoDisplay;
import cn.piq.fcarcade.home.*;
import com.mojang.blaze3d.platform.NativeImage;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Firmware only, not an emulator. Upload on actual state changes, with a bounded texture cache. */
public final class FirmwareDisplay {
    private record Frame(String key,DynamicTexture texture,ResourceLocation id){}
    private static final Map<ComputerEntity,Frame> FRAMES=new LinkedHashMap<>();
    public static boolean owns(HomeTvBlockEntity tv){
        var l=tv.getLevel();var p=tv.consolePos();return tv.powered()&&l!=null&&p!=null&&l.hasChunkAt(p)&&l.getBlockEntity(p) instanceof ComputerEntity pc&&pc.powered&&HomeHardware.connected(l,pc,tv);
    }
    public static ResourceLocation texture(ComputerEntity pc){
        String key=pc.installed+":"+pc.powered+":"+(pc.peripheral(true)!=null)+":"+(pc.peripheral(false)!=null)+":"+pc.pointerX+":"+pc.pointerY+":"+pc.buttons+":"+pc.lastKey+":"+pc.typed;
        Frame f=FRAMES.get(pc);if(f!=null&&f.key.equals(key))return f.id;
        var mc=Minecraft.getInstance();
        if(f==null){if(FRAMES.size()>=16)remove(FRAMES.keySet().iterator().next());var t=new DynamicTexture(640,480,false);t.setFilter(false,false);f=new Frame("",t,mc.getTextureManager().register("piq_computer/firmware",t));}
        BufferedImage b=draw(pc);NativeImage n=f.texture.getPixels();int[] pixels=((java.awt.image.DataBufferInt)b.getRaster().getDataBuffer()).getData();
        for(int y=0;y<480;y++)for(int x=0;x<640;x++){int c=pixels[y*640+x];n.setPixelRGBA(x,y,(c&0xff00ff00)|((c>>>16)&255)|((c&255)<<16));}
        f.texture.upload();FRAMES.put(pc,new Frame(key,f.texture,f.id));return f.id;
    }
    private static BufferedImage draw(ComputerEntity pc){
        var b=new BufferedImage(640,480,BufferedImage.TYPE_INT_ARGB);var g=b.createGraphics();
        try{
            g.setColor(new Color(10,19,29));g.fillRect(0,0,640,480);g.setFont(new Font(Font.MONOSPACED,Font.BOLD,25));g.setColor(new Color(117,231,204));g.drawString("PIQ COMPUTER",28,43);
            g.setFont(new Font(Font.MONOSPACED,Font.PLAIN,15));g.setColor(Color.LIGHT_GRAY);g.drawString("HARDWARE & INPUT TEST",28,70);g.drawLine(28,85,610,85);
            int y=115;for(var p:Assembly.Part.values()){g.setColor(Assembly.has(pc.installed,p)?new Color(153,219,168):Color.GRAY);g.drawString((Assembly.has(pc.installed,p)?"[OK] ":"[--] ")+p.name()+(p==Assembly.Part.RAM_2?" (optional)":""),28,y);y+=23;}
            g.setColor(Color.LIGHT_GRAY);g.drawString("KEYBOARD: "+(pc.peripheral(true)!=null?"CONNECTED":"NOT CONNECTED"),310,115);g.drawString("MOUSE: "+(pc.peripheral(false)!=null?"CONNECTED":"NOT CONNECTED"),310,138);
            g.drawString("KEY: "+pc.lastKey,310,180);g.drawString("BUTTONS: "+pc.buttons,310,203);g.drawString("POINTER: "+pc.pointerX+", "+pc.pointerY,310,226);
            g.setColor(new Color(28,43,56));g.fillRoundRect(24,314,592,62,8,8);g.setColor(Color.WHITE);g.setFont(new Font(Font.DIALOG,Font.PLAIN,18));g.drawString(pc.typed.isEmpty()?"Type here to test input...":pc.typed,35,350);
            g.setFont(new Font(Font.MONOSPACED,Font.PLAIN,14));g.setColor(new Color(230,194,116));g.drawString("Right-click keyboard / mouse to begin.",28,410);g.setColor(Color.LIGHT_GRAY);g.drawString("ENTER: clear text     ESC: release controls",28,443);
            g.setColor(pc.buttons==0?Color.WHITE:new Color(255,160,73));g.drawLine(pc.pointerX-7,pc.pointerY,pc.pointerX+7,pc.pointerY);g.drawLine(pc.pointerX,pc.pointerY-7,pc.pointerX,pc.pointerY+7);
        }finally{g.dispose();}return b;
    }
    public static void seen(ComputerEntity pc){var p=Minecraft.getInstance().player;if(pc.powered&&p!=null&&p.distanceToSqr(pc.getBlockPos().getCenter())<=1024)texture(pc);}
    public static void render(RenderLevelStageEvent e){
        if(e.getStage()!=RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES)return;var mc=Minecraft.getInstance();
        for(var pc:new ArrayList<>(FRAMES.keySet())){
            if(mc.level==null||mc.player==null||pc.getLevel()!=mc.level||pc.isRemoved()||!pc.powered||!mc.level.hasChunkAt(pc.getBlockPos())||mc.level.getBlockEntity(pc.getBlockPos())!=pc||mc.player.distanceToSqr(pc.getBlockPos().getCenter())>1024){remove(pc);continue;}
            var game=ComputerPrograms.texture(pc);if(game==null)game=ComputerStreams.texture(pc);var p=pc.televisionPos();if(p!=null&&mc.level.hasChunkAt(p)&&mc.level.getBlockEntity(p) instanceof HomeTvBlockEntity tv&&HomeHardware.connected(mc.level,pc,tv))HomeVideoDisplay.render(e,ComputerEntity.SYSTEM,pc.getBlockPos(),pc.hardwareId(),p,tv.hardwareId(),pc.linkId(),game==null?texture(pc):game,4.0/3);
        }
    }
    private static void remove(ComputerEntity pc){var f=FRAMES.remove(pc);if(f!=null)Minecraft.getInstance().getTextureManager().release(f.id);}
    public static void clear(){for(var pc:new ArrayList<>(FRAMES.keySet()))remove(pc);}
}
