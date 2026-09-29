// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.gba.client;

import cn.piq.fcarcade.client.ui.DeviceScreen;
import cn.piq.fcarcade.client.ui.DeviceUi;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** Explanation only: the physical handheld never advertises selectable network modes. */
final class GbaHandheldSyncInfoScreen extends DeviceScreen {
    private final GbaHandheldScreen parent;
    private final GbaHandheldClient.Binding binding;
    private int x,y,w;
    GbaHandheldSyncInfoScreen(GbaHandheldScreen parent,GbaHandheldClient.Binding binding){
        super(Component.literal("GBA 掌机同步方式"));this.parent=parent;this.binding=binding;
    }
    @Override protected void init(){
        DeviceUi.prepare();w=Math.max(160,Math.min(310,width-20));x=(width-w)/2;y=Math.max(1,(height-204)/2);
        addRenderableWidget(DeviceUi.button(font,"返回掌机设置",x+10,y+174,w-20,20,this::onClose,true,DeviceUi.Tone.NORMAL));
    }
    @Override public void tick(){if(!binding.current())minecraft.setScreen(null);}
    @Override public void onClose(){minecraft.setScreen(binding.current()?parent:null);}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void render(GuiGraphics g,int mouseX,int mouseY,float partial){
        g.fill(0,0,width,height,DeviceUi.BG);DeviceUi.panel(g,font,x,y,w,204,"GBA 掌机同步方式","本地单机（固定）；本页不改变运行模式");
        String[] lines={"掌机游戏在本机运行，电池档也保存在本机。","玩家托管串流：不适用，掌机不广播音画。","服务器托管串流：不适用，不远程运行掌机。","这里不是多人本地同步，也不支持 GBA 通讯线。","用机柜玩 GBA 时，请在机柜设置同步模式。","机柜的服务器托管存档与掌机本地档分开。"};
        int hovered=-1;
        for(int i=0;i<lines.length;i++){
            int row=y+49+i*19;DeviceUi.text(g,font,lines[i],x+10,row,w-20,DeviceUi.TEXT);
            if(mouseX>=x+10&&mouseX<x+w-10&&mouseY>=row&&mouseY<row+15)hovered=i;
        }
        super.render(g,mouseX,mouseY,partial);
        if(hovered>=0)g.renderTooltip(font,Component.literal(lines[hovered]),mouseX,mouseY);
    }
}
