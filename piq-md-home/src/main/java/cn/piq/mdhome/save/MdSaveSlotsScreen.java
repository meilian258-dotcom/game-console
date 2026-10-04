// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome.save;

import cn.piq.fcarcade.client.ui.*;
import cn.piq.mdhome.MdMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/** Same compact device form as FC; NONE asks only whether to allow P2, never shows save slots. */
@EventBusSubscriber(modid=MdMod.ID,value=Dist.CLIENT,bus=EventBusSubscriber.Bus.MOD)
public final class MdSaveSlotsScreen extends DeviceScreen {
    private MdSaveNetwork.Selection data;private final Connection connection;
    private DeviceFormLayout layout;private EditBox name;private int selected,players=1;private String draft="Save 1";private boolean sent;
    @SubscribeEvent public static void setup(FMLClientSetupEvent event){event.enqueueWork(()->MdSaveNetwork.receiver(MdSaveSlotsScreen::receive));}
    private static void receive(MdSaveNetwork.Selection data){var mc=Minecraft.getInstance();if(mc.getConnection()==null)return;
        if(data.closed()){if(mc.screen instanceof MdSaveSlotsScreen s&&s.data.token().equals(data.token())){s.sent=true;mc.setScreen(null);}if(mc.player!=null)mc.player.displayClientMessage(Component.literal(data.message()),false);return;}
        if(mc.screen instanceof MdSaveSlotsScreen s&&s.data.token().equals(data.token())){s.data=data;s.sent=false;s.rebuildWidgets();}
        else mc.setScreen(new MdSaveSlotsScreen(data,mc.getConnection().getConnection()));
    }
    private MdSaveSlotsScreen(MdSaveNetwork.Selection data,Connection connection){super(Component.literal("MD / 开局设置"));this.data=data;this.connection=connection;select(0);}
    private void select(int index){selected=index;if(!data.slots().isEmpty()){var s=data.slots().get(index);draft=s.name();players=Math.min(data.maxPlayers(),s.players());}else players=1;}
    private boolean connected(){var c=Minecraft.getInstance().getConnection();return c!=null&&c.getConnection()==connection&&connection.isConnected();}
    @Override protected void init(){
        DeviceUi.prepare();layout=DeviceFormLayout.of(width,height,5);int x=layout.left(),w=layout.bodyWidth(),half=(w-6)/2;
        if(width<320||height<240){addRenderableWidget(DeviceUi.button(font,"取消",x,layout.footerY(),w,20,this::onClose,true,DeviceUi.Tone.NORMAL));return;}
        if(data.mode()!=0){int third=(w-8)/3;for(int i=0;i<data.slots().size();i++){int index=i;var s=data.slots().get(i);addRenderableWidget(DeviceUi.button(font,data.mode()==1?"这张卡带的进度":"槽位 "+s.slot()+(s.occupied()?" · 已有":" · 空"),x+i*(third+4),layout.rowY(0),data.mode()==1?w:third,20,()->{select(index);rebuildWidgets();},!sent,index==selected?DeviceUi.Tone.PRIMARY:DeviceUi.Tone.NORMAL));}
            name=new EditBox(font,layout.fieldX(),layout.rowY(2),layout.fieldWidth(),20,Component.literal("存档名称"));name.setMaxLength(32);name.setValue(draft);name.setResponder(v->draft=v);addRenderableWidget(name);
        }
        addRenderableWidget(DeviceUi.button(font,players==2?"允许 2P 加入":"仅 1P 操作",x,layout.rowY(3),w,20,()->{players=players==1?2:1;rebuildWidgets();},!sent&&data.maxPlayers()>1,DeviceUi.Tone.NORMAL));
        var row=data.slots().isEmpty()?null:data.slots().get(selected);
        String start=row==null||!row.occupied()?"开始游戏":row.compatible()?"继续游戏":"替换为当前游戏…";
        addRenderableWidget(DeviceUi.button(font,start,x,layout.rowY(4),half,20,()->{if(row!=null&&row.occupied()&&!row.compatible())confirm();else send(row!=null&&row.occupied(),false);},!sent,DeviceUi.Tone.PRIMARY));
        addRenderableWidget(DeviceUi.button(font,"从头开始…",x+half+6,layout.rowY(4),w-half-6,20,this::confirm,!sent&&row!=null&&row.occupied(),DeviceUi.Tone.DANGER));
        addRenderableWidget(DeviceUi.button(font,"取消",x,layout.footerY(),w,20,this::onClose,true,DeviceUi.Tone.NORMAL));
    }
    private void confirm(){if(!connected())return;Minecraft.getInstance().setScreen(new DeviceConfirmScreen(ok->{if(ok)send(false,false);else Minecraft.getInstance().setScreen(this);},Component.literal("从头开始？"),Component.literal("旧进度不会立即删除；下一次成功保存才替换当前槽。")));}
    private void send(boolean resume,boolean cancel){if(sent||!connected())return;var s=data.slots().isEmpty()?null:data.slots().get(selected);sent=true;
        MdSaveNetwork.send(new MdSaveNetwork.Action(data.token(),s==null?1:s.slot(),s==null?"":s.version(),draft,players,resume,cancel));Minecraft.getInstance().setScreen(null);
    }
    @Override public void onClose(){send(false,true);}
    @Override public void tick(){if(!connected())Minecraft.getInstance().setScreen(null);}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void render(GuiGraphics g,int mx,int my,float partial){
        g.fill(0,0,width,height,DeviceUi.BG);var p=layout.panel();DeviceUi.panel(g,font,p.x(),p.y(),p.width(),p.height(),data.mode()==0?"MD / 加入设置":data.mode()==1?"MD / 卡带进度":"MD / 选择存档",data.title());
        if(width>=320&&height>=240){String info=data.mode()==0?"本局不存档；只设置是否允许 2P。":data.slots().get(selected).occupied()?(data.slots().get(selected).compatible()?"已有兼容进度，可继续或从头开始。":"这个槽属于其他游戏/格式；替换需确认。") :"空槽位 · 首次成功保存后建立进度";
            DeviceUi.text(g,font,info,layout.left(),layout.rowY(1)+6,layout.bodyWidth(),DeviceUi.MUTED);
            if(data.mode()!=0)DeviceUi.text(g,font,"存档名称",layout.left(),layout.rowY(2)+6,layout.labelWidth(),DeviceUi.TEXT);
            DeviceUi.status(g,font,data.message().isBlank()?(data.mode()==2?"MD 个人总共 3 槽，不是每款游戏 3 槽。":"允许后朋友可直接领取空闲 2P。" ):data.message(),layout.left(),layout.statusY(),layout.bodyWidth(),false);
        }else DeviceUi.text(g,font,"请降低GUI缩放或放大窗口",layout.left(),layout.rowY(0),layout.bodyWidth(),DeviceUi.MUTED);
        super.render(g,mx,my,partial);
    }
}
