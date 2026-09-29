// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.client;

import cn.piq.fcarcade.client.ui.*;
import cn.piq.fcarcade.netplay.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;

/** Shared FC/SFC/FBNeo save action; no per-addon upload or runtime state-rewind implementation. */
public final class NetplaySaveScreen extends DeviceScreen {
    private final Screen parent;private final Connection connection;
    private List<NetplayProcess> runs=List.of();private int page,x,y,w,age;private String message="";
    private NetplaySaveScreen(Screen parent,Connection connection){super(Component.literal("Netplay 存档"));this.parent=parent;this.connection=connection;}
    public static void open(Screen parent){var mc=Minecraft.getInstance();if(mc.getConnection()!=null)mc.setScreen(new NetplaySaveScreen(parent,mc.getConnection().getConnection()));}
    private boolean current(){return minecraft.getConnection()!=null&&minecraft.getConnection().getConnection()==connection&&minecraft.level!=null;}
    @Override protected void init(){
        DeviceUi.prepare();runs=NetplayNetwork.clientRuns(connection);page=Math.min(page,Math.max(0,(runs.size()-1)/3));
        w=Math.min(420,width-24);x=(width-w)/2;y=Math.max(8,(height-224)/2);
        if(width>=320&&height>=240){
            for(int i=page*3;i<Math.min(runs.size(),page*3+3);i++){
                var run=runs.get(i);int row=y+46+(i-page*3)*37;
                addRenderableWidget(DeviceUi.button(font,"立即保存",x+w-92,row,82,20,()->save(run),run.canSave(),DeviceUi.Tone.NORMAL));
            }
            int col=(w-32)/3;
            addRenderableWidget(DeviceUi.button(font,"上一页",x+10,y+196,col,20,()->{page--;rebuildWidgets();},page>0,DeviceUi.Tone.NORMAL));
            addRenderableWidget(DeviceUi.button(font,"下一页",x+16+col,y+196,col,20,()->{page++;rebuildWidgets();},(page+1)*3<runs.size(),DeviceUi.Tone.NORMAL));
            addRenderableWidget(DeviceUi.button(font,"返回",x+22+col*2,y+196,col,20,this::onClose,true,DeviceUi.Tone.NORMAL));
        }else addRenderableWidget(Button.builder(Component.literal("返回"),b->onClose()).bounds(x,Math.max(35,height-32),w,20).build());
    }
    private void save(NetplayProcess run){
        if(!current()||!NetplayNetwork.clientRuns(connection).contains(run)||!run.canSave())return;
        message="正在捕获并上传，等待服务器落盘确认…";
        run.saveNow().whenComplete((v,e)->minecraft.execute(()->{if(current())message=e==null?"服务器已确认保存。":"保存未确认；请检查运行诊断，不要删除原档。";}));
    }
    @Override public void tick(){if(!current()){minecraft.setScreen(null);return;}if(++age%20==0)rebuildWidgets();}
    @Override public void onClose(){minecraft.setScreen(current()?parent:null);}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void render(GuiGraphics g,int mx,int my,float partial){
        g.fill(0,0,width,height,DeviceUi.BG);DeviceUi.panel(g,font,x,y,w,224,"Netplay 存档","当前连接 · 只有主持能保存 · 不暂停游戏");
        if(width>=320&&height>=240){
            if(runs.isEmpty())DeviceUi.text(g,font,"当前没有 Netplay 会话。",x+10,y+52,w-20,DeviceUi.MUTED);
            for(int i=page*3;i<Math.min(runs.size(),page*3+3);i++){
                var run=runs.get(i);int row=y+46+(i-page*3)*37;
                DeviceUi.text(g,font,run.saveLabel(),x+10,row+1,w-110,DeviceUi.TEXT);
                DeviceUi.text(g,font,run.grant().host()?run.saveStatus():"进度由主持保存，本端只接收同步",x+10,row+16,w-110,DeviceUi.MUTED);
            }
            DeviceUi.text(g,font,"启用保存后每30秒及正常关机保存；重开自动恢复。",x+10,y+161,w-20,DeviceUi.MUTED);
            DeviceUi.text(g,font,message.isEmpty()?"不在多人局中倒带读档；异常断线以最后确认版为准。":message,x+10,y+178,w-20,DeviceUi.TEXT);
        }else DeviceUi.text(g,font,"请降低GUI缩放或放大窗口。",x+10,y+46,w-20,DeviceUi.TEXT);
        super.render(g,mx,my,partial);
    }
}
