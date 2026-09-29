// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.pvz.client;

import cn.piq.fcarcade.client.ui.DeviceScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.*;
import org.lwjgl.system.MemoryStack;
import java.util.*;

/** Local preview captures its input only; no world-keyboard injection or remote seats. */
final class PvzScreen extends DeviceScreen {
    private EditBox path;private Button start,stop;
    private int vx,vy,vw,vh,pad,x,y,buttons;private boolean pointer,focused=true,armed;
    private final Set<Integer> keys=new HashSet<>();
    private static final Map<Integer,Integer> BIND=Map.ofEntries(Map.entry(263,6),Map.entry(262,7),Map.entry(265,4),Map.entry(264,5),Map.entry(74,0),Map.entry(75,8),Map.entry(257,2),Map.entry(258,3),Map.entry(81,10),Map.entry(69,11));
    PvzScreen(){super(Component.literal("PvZ · 本机测试"));}
    @Override protected void init(){
        String draft=path==null?PvzClient.lastPath():path.getValue();release();
        int left=10,area=width-20;
        path=new EditBox(font,left,27,Math.max(70,area-146),20,Component.literal("main.pak 完整路径"));path.setMaxLength(2048);path.setValue(draft);addRenderableWidget(path);
        start=addRenderableWidget(Button.builder(Component.literal("开始"),b->{path.setFocused(false);setFocused(null);release();PvzClient.start(path.getValue());}).bounds(width-148,27,65,20).build());
        stop=addRenderableWidget(Button.builder(Component.literal("结束并保存"),b->{release();PvzClient.stop();}).bounds(width-79,27,69,20).build());
        vh=Math.max(48,Math.min(height-116,(width-20)*3/4));vw=vh*4/3;vx=(width-vw)/2;vy=53;
        addRenderableWidget(Button.builder(Component.literal("返回看电视 / Esc"),b->onClose()).bounds(width-150,height-25,140,20).build());
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void removed(){release();PvzClient.menuRemoved();}
    @Override public void onClose(){minecraft.setScreen(null);}
    private void release(){keys.clear();pad=buttons=0;pointer=false;armed=false;PvzClient.releaseInput();}
    private boolean accepts(){return armed&&PvzClient.ready()&&!path.isFocused()&&minecraft.isWindowActive();}
    private void send(){PvzClient.input(pad,x,y,buttons,pointer);}
    private void updatePad(){pad=0;for(int k:keys)pad|=1<<BIND.get(k);pointer=false;send();}
    @Override public void tick(){
        if(!PvzClient.valid()){onClose();return;}start.active=!PvzClient.running();stop.active=PvzClient.running();
        if(!minecraft.isWindowActive()){if(focused)release();focused=false;return;}focused=true;
        if(!PvzClient.ready()||path.isFocused())return;
        // Standard GLFW mappings only. A physical controller is optional; first active one wins.
        try(MemoryStack stack=MemoryStack.stackPush()){
            GLFWGamepadState state=GLFWGamepadState.malloc(stack);int physical=0;
            for(int id=0;id<=GLFW.GLFW_JOYSTICK_LAST;id++)if(GLFW.glfwGetGamepadState(id,state)){
                int[] mapping={8,0,9,1,10,11,2,3,-1,14,15,4,7,5,6};
                for(int i=0;i<mapping.length;i++)if(mapping[i]>=0&&state.buttons(i)==GLFW.GLFW_PRESS)physical|=1<<mapping[i];
                if(state.axes(0)<-.35)physical|=1<<6;if(state.axes(0)>.35)physical|=1<<7;if(state.axes(1)<-.35)physical|=1<<4;if(state.axes(1)>.35)physical|=1<<5;
                break;
            }
            if(!armed){boolean released=physical==0;for(int key:BIND.keySet())if(GLFW.glfwGetKey(minecraft.getWindow().getWindow(),key)!=GLFW.GLFW_RELEASE)released=false;armed=released;return;}
            int keyboard=0;for(int k:keys)keyboard|=1<<BIND.get(k);
            int next=physical|keyboard;if(next!=pad){pad=next;if(next!=0)pointer=false;send();}
        }
    }
    @Override public boolean keyPressed(int key,int scan,int mods){
        if(accepts()&&BIND.containsKey(key)){keys.add(key);updatePad();return true;}
        if(accepts()&&key==259){PvzClient.key(true,8,0);return true;}
        return super.keyPressed(key,scan,mods);
    }
    @Override public boolean keyReleased(int key,int scan,int mods){if(keys.remove(key)){updatePad();return true;}if(key==259)PvzClient.key(false,8,0);return super.keyReleased(key,scan,mods);}
    @Override public boolean charTyped(char ch,int mods){if(accepts()&&ch>=32&&ch<127){PvzClient.key(true,0,ch);return true;}return super.charTyped(ch,mods);}
    private boolean inside(double mx,double my){return mx>=vx&&my>=vy&&mx<vx+vw&&my<vy+vh;}
    private void move(double mx,double my){x=(int)(Math.max(0,Math.min(.99999,(mx-vx)/vw))*65535)-32768;y=(int)(Math.max(0,Math.min(.99999,(my-vy)/vh))*65535)-32768;pointer=true;send();}
    @Override public void mouseMoved(double mx,double my){if(accepts()&&inside(mx,my))move(mx,my);super.mouseMoved(mx,my);}
    @Override public boolean mouseClicked(double mx,double my,int button){if(PvzClient.ready()&&inside(mx,my)&&(button==0||button==1)){path.setFocused(false);setFocused(null);buttons|=1<<button;move(mx,my);return true;}release();return super.mouseClicked(mx,my,button);}
    @Override public boolean mouseReleased(double mx,double my,int button){if(button>=0&&button<2&&(buttons&(1<<button))!=0){buttons&=~(1<<button);move(mx,my);return true;}return super.mouseReleased(mx,my,button);}
    @Override public void render(GuiGraphics g,int mx,int my,float partial){
        g.fill(0,0,width,height,0xdc101820);g.drawString(font,title,10,9,0xffe4dfb0,false);
        g.fill(vx-1,vy-1,vx+vw+1,vy+vh+1,0xff000000);
        var tex=PvzClient.texture();if(tex!=null)g.blit(tex,vx,vy,vw,vh,0,0,800,600,800,600);
        else g.drawCenteredString(font,"连接并打开电视后，选择 main.pak",width/2,vy+vh/2,0xffdddddd);
        var help=font.split(Component.literal("鼠标点画面 / 方向键移动 · K左键 · J右键 · Tab菜单"),width-20);
        for(int i=0;i<Math.min(2,help.size());i++)g.drawString(font,help.get(i),10,vy+vh+5+10*i,0xffdddddd,false);
        var lines=font.split(Component.literal(PvzClient.status()),width-20);for(int i=0;i<Math.min(2,lines.size());i++)g.drawString(font,lines.get(i),10,vy+vh+7+10*Math.min(2,help.size())+10*i,0xffb9d8b0,false);
        super.render(g,mx,my,partial);
    }
}
