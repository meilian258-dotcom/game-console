package cn.piq.fcarcade.client.cabinet;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Captures actual edges without rebinding Minecraft controls or polling at 20 Hz. */
final class CabinetPlayScreen extends Screen {
    private final CabinetKeys keys=new CabinetKeys();
    private boolean focused=true;
    CabinetPlayScreen(){super(Component.literal("方块电玩 · 街机"));}
    private void send(){CabinetClientBackends.input(keys.player1(),keys.player2());}
    @Override public boolean keyPressed(int key,int scan,int modifiers){
        checkFocus();
        if(key==GLFW.GLFW_KEY_ESCAPE){onClose();return true;}
        if(minecraft.isWindowActive()&&CabinetClientBackends.running()&&keys.press(key))send();return true;
    }
    @Override public boolean keyReleased(int key,int scan,int modifiers){checkFocus();if(minecraft.isWindowActive()&&keys.release(key))send();return true;}
    private void checkFocus(){boolean active=minecraft.isWindowActive();if(!active&&focused){keys.clear();CabinetClientBackends.clearInput();}focused=active;}
    @Override public void tick(){checkFocus();}
    @Override public void render(GuiGraphics g,int mx,int my,float partial){
        checkFocus();
        String[] text={CabinetClientBackends.running()?"P1 方向键 · J/K/U/I/O/P · Backspace 选择/投币 · Enter 开始":"正在加载模拟器和游戏…",
                "P2 WASD · F/G/R/T/Y/H · 5 选择/投币 · 2 开始（同机键盘）","Esc 停止并离开 · 失焦松开全部按键"};
        int[] colors={0xffffff,0xc5dedc,0x9fced0};int boxWidth=Math.max(1,width-16),total=0;
        for(String line:text)total+=Math.min(3,font.split(Component.literal(line),boxWidth).size())*(font.lineHeight+1)+3;
        int y=Math.max(6,height-total-8);g.fill(4,y-4,width-4,height-4,0x9b000000);
        for(int i=0;i<text.length;i++){CabinetUi.paragraph(g,font,text[i],8,y,boxWidth,3,colors[i]);y+=Math.min(3,font.split(Component.literal(text[i]),boxWidth).size())*(font.lineHeight+1)+3;}
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){CabinetClientBackends.stop(null,true);}
    @Override public void removed(){keys.clear();CabinetClientBackends.clearInput();}
}
