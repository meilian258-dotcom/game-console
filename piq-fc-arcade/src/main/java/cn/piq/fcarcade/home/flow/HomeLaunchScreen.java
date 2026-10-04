// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.home.flow;

import cn.piq.fcarcade.client.ui.*;
import cn.piq.retro.flow.DeviceSessionFlow.Stage;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import static cn.piq.fcarcade.home.flow.HomeLaunchNetwork.*;

/** One shared save page followed by an independent, explicit second-player confirmation. */
public final class HomeLaunchScreen extends DeviceScreen {
    private static Connection seenConnection;
    private static final LinkedHashMap<UUID,Long> REVISIONS=new LinkedHashMap<>();
    private View data;private final Connection connection;
    private DeviceFormLayout layout;private int selected,savePlayers=1;private String draft="Save 1";
    private boolean sent,replacing,confirmFresh;
    static void receive(Connection connection,View view){var mc=Minecraft.getInstance();if(mc.getConnection()==null||mc.getConnection().getConnection()!=connection)return;
        if(seenConnection!=connection){seenConnection=connection;REVISIONS.clear();}
        long last=REVISIONS.getOrDefault(view.token(),-1L);if(view.revision()<last)return;REVISIONS.put(view.token(),view.revision());while(REVISIONS.size()>128)REVISIONS.remove(REVISIONS.keySet().iterator().next());
        boolean terminal=switch(view.stage()){case READY,CLOSED,CANCELLED,FAILED->true;default->false;};
        if(terminal){if(mc.screen instanceof HomeLaunchScreen s&&s.data.token().equals(view.token())){s.replacing=true;mc.setScreen(null);}if(!view.message().isBlank()&&mc.player!=null)mc.player.displayClientMessage(Component.literal(view.message()),false);return;}
        if(mc.screen instanceof HomeLaunchScreen s&&s.data.token().equals(view.token())){boolean changedStage=s.data.stage()!=view.stage();s.data=view;s.sent=false;s.confirmFresh=false;if(!view.rows().isEmpty()&&s.selected>=view.rows().size())s.select(0);if(changedStage&&view.stage()==Stage.SAVE_SELECTION)s.select(Math.min(s.selected,view.rows().size()-1));s.rebuildWidgets();}
        else if(view.revision()>last)mc.setScreen(new HomeLaunchScreen(connection,view));
    }
    private HomeLaunchScreen(Connection connection,View data){super(Component.literal(data.label()+" / 开局"));this.connection=connection;this.data=data;if(!data.rows().isEmpty())select(0);}
    private void select(int index){selected=index;var row=data.rows().get(index);draft=row.name();savePlayers=row.players();}
    private boolean connected(){var c=Minecraft.getInstance().getConnection();return c!=null&&c.getConnection()==connection&&connection.isConnected();}
    private void send(int operation,Choice choice){if(sent||!connected())return;sent=true;HomeLaunchNetwork.send(new Action(data.token(),data.revision(),operation,choice));rebuildWidgets();}
    private void start(boolean resume){var row=data.rows().get(selected);send(SELECT,new Choice(row.slot(),row.version(),draft,savePlayers,resume));}
    private void button(String text,int x,int y,int w,Runnable run,boolean enabled){addRenderableWidget(DeviceUi.button(font,text,x,y,w,20,run,enabled&&!sent,DeviceUi.Tone.NORMAL));}
    @Override protected void init(){DeviceUi.prepare();layout=DeviceFormLayout.of(width,height,5);int x=layout.left(),w=layout.bodyWidth(),half=(w-6)/2;
        if(width<320||height<240){button("取消开局",x,layout.footerY(),w,this::onClose,true);return;}
        if(confirmFresh){button("确认从头开始",x,layout.rowY(3),half,()->start(false),true);button("返回选择",x+half+6,layout.rowY(3),w-half-6,()->{confirmFresh=false;rebuildWidgets();},true);}
        else if(data.stage()==Stage.SAVE_SELECTION){int count=data.rows().size(),bw=(w-(count-1)*4)/Math.max(1,count);
            for(int i=0;i<count;i++){int index=i;var row=data.rows().get(i);button((i==selected?"> ":"")+(data.mode()==1?"卡带进度":"槽位 "+row.slot())+(row.occupied()?" · 已有":" · 空"),x+i*(bw+4),layout.rowY(0),bw,()->{select(index);rebuildWidgets();},true);}
            var name=new EditBox(font,layout.fieldX(),layout.rowY(2),layout.fieldWidth(),20,Component.literal("存档名称"));name.setMaxLength(32);name.setValue(draft);name.setResponder(v->draft=v);name.setEditable(!sent);addRenderableWidget(name);
            button(savePlayers==2?"存档标签：双人":"存档标签：单人",x,layout.rowY(3),w,()->{savePlayers=savePlayers==1?2:1;rebuildWidgets();},data.maxPlayers()>1);
            var row=data.rows().get(selected);button(row.occupied()&&row.compatible()?"继续游戏":"开始游戏",x,layout.rowY(4),half,()->{if(row.occupied()&&!row.compatible()){confirmFresh=true;rebuildWidgets();}else start(row.occupied());},true);
            button("从头开始…",x+half+6,layout.rowY(4),w-half-6,()->{confirmFresh=true;rebuildWidgets();},row.occupied());
        }else if(data.stage()==Stage.JOIN_CONFIRM){button("允许第二名玩家加入",x,layout.rowY(2),w,()->send(ALLOW,null),true);button("不允许，仅单人开始",x,layout.rowY(3),w,()->send(DENY,null),true);if(data.mode()!=0)button("返回存档选择",x,layout.rowY(4),w,()->send(BACK,null),true);}
        addRenderableWidget(DeviceUi.button(font,"取消开局",x,layout.footerY(),w,20,this::onClose,true,DeviceUi.Tone.NORMAL));
    }
    @Override public void onClose(){if(!connected()){replacing=true;Minecraft.getInstance().setScreen(null);return;}HomeLaunchNetwork.send(new Action(data.token(),data.revision(),CANCEL,null));REVISIONS.put(data.token(),Long.MAX_VALUE);replacing=true;Minecraft.getInstance().setScreen(null);}
    @Override public void removed(){if(!replacing&&connected()){HomeLaunchNetwork.send(new Action(data.token(),data.revision(),CANCEL,null));REVISIONS.put(data.token(),Long.MAX_VALUE);}}
    @Override public void tick(){if(!connected()){replacing=true;Minecraft.getInstance().setScreen(null);}}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void render(GuiGraphics g,int mx,int my,float dt){g.fill(0,0,width,height,DeviceUi.BG);var p=layout.panel();String heading=data.stage()==Stage.JOIN_CONFIRM?"是否允许第二名玩家加入？":data.stage()==Stage.SAVE_SELECTION?"选择存档":"开局准备";
        DeviceUi.panel(g,font,p.x(),p.y(),p.width(),p.height(),data.label()+" / "+heading,data.title());
        if(width>=320&&height>=240){String info=confirmFresh?"旧进度不会立即删除；下一次成功保存才替换。":data.stage()==Stage.JOIN_CONFIRM?"只决定本局加入许可；不会修改存档单双人标签。":data.stage()==Stage.SAVE_SELECTION?(data.maxPlayers()>1?"选择进度后再确认本局是否允许 2P。":"选择进度后开始本局单人游戏。"):"正在准备，尚未开放输入和公开音画。";
            DeviceUi.text(g,font,info,layout.left(),layout.rowY(1)+6,layout.bodyWidth(),DeviceUi.MUTED);if(data.stage()==Stage.SAVE_SELECTION&&!confirmFresh)DeviceUi.text(g,font,"存档名称",layout.left(),layout.rowY(2)+6,layout.labelWidth(),DeviceUi.TEXT);
            DeviceUi.status(g,font,data.message(),layout.left(),layout.statusY(),layout.bodyWidth(),false);
        }else DeviceUi.text(g,font,"请降低 GUI 缩放或放大窗口",layout.left(),layout.rowY(0),layout.bodyWidth(),DeviceUi.MUTED);
        super.render(g,mx,my,dt);
    }
}
