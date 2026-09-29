// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.nativearcade.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import java.util.*;

/** Transparent non-pausing screen captures keys without altering any user's key bindings. */
final class NativeArcadePlayScreen extends Screen {
    private final Set<Integer> down=new HashSet<>();
    private boolean focused=true;
    // libretro IDs: B Y SELECT START UP DOWN LEFT RIGHT A X L R ...
    private static final int[] P1={GLFW.GLFW_KEY_J,GLFW.GLFW_KEY_U,GLFW.GLFW_KEY_BACKSPACE,GLFW.GLFW_KEY_ENTER,
        GLFW.GLFW_KEY_UP,GLFW.GLFW_KEY_DOWN,GLFW.GLFW_KEY_LEFT,GLFW.GLFW_KEY_RIGHT,GLFW.GLFW_KEY_K,GLFW.GLFW_KEY_I,GLFW.GLFW_KEY_O,GLFW.GLFW_KEY_P};
    private static final int[] P2={GLFW.GLFW_KEY_F,GLFW.GLFW_KEY_R,GLFW.GLFW_KEY_5,GLFW.GLFW_KEY_2,
        GLFW.GLFW_KEY_W,GLFW.GLFW_KEY_S,GLFW.GLFW_KEY_A,GLFW.GLFW_KEY_D,GLFW.GLFW_KEY_G,GLFW.GLFW_KEY_T,GLFW.GLFW_KEY_Y,GLFW.GLFW_KEY_H};
    NativeArcadePlayScreen(){super(Component.literal("原生街机"));}
    private int mask(int[] keys){int m=0;for(int i=0;i<keys.length;i++)if(down.contains(keys[i]))m|=1<<i;return m;}
    private void send(){NativeArcadeClient.input(mask(P1),mask(P2));}
    @Override public boolean keyPressed(int key,int scan,int modifiers){
        if(key==GLFW.GLFW_KEY_ESCAPE){onClose();return true;}
        if(minecraft.isWindowActive()&&NativeArcadeClient.running()&&down.add(key))send();return true;
    }
    @Override public boolean keyReleased(int key,int scan,int modifiers){if(down.remove(key))send();return true;}
    @Override public void tick(){
        boolean active=minecraft.isWindowActive();if(!active&&focused){down.clear();NativeArcadeClient.clearInput();}focused=active;
    }
    @Override public void render(GuiGraphics g,int mx,int my,float partial){
        // Deliberately no background/blur: the physical world cabinet remains visible.
        int y=height-51;g.fill(4,y-3,width-4,height-4,0x9b000000);
        g.drawCenteredString(font,NativeArcadeClient.running()?"P1 方向键 · J/K/U/I/O/P · Backspace 投币 · Enter 开始":"正在校验并启动独立 MAME 核心…",width/2,y,0xffffff);
        g.drawCenteredString(font,"P2 WASD · F/G/R/T/Y/H · 5 投币 · 2 开始（同机键盘）",width/2,y+13,0xc5dedc);
        g.drawCenteredString(font,"Esc 停止并离开 · 失焦自动松开全部按键 · 不改变 Minecraft 键位",width/2,y+26,0x9fced0);
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){NativeArcadeClient.stop(null);}
    @Override public void removed(){down.clear();NativeArcadeClient.clearInput();}
}
