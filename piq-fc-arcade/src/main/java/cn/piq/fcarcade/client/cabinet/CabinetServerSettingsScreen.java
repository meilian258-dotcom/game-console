package cn.piq.fcarcade.client.cabinet;

import cn.piq.fcarcade.cabinet.*;
import cn.piq.fcarcade.client.ui.*;
import cn.piq.fcarcade.registry.ModItems;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;

/** Explicit whole-server apply; terminal remains held and server rechecks OP for every write. */
public final class CabinetServerSettingsScreen extends DeviceScreen {
    private final Screen parent;
    private final InteractionHand hand;
    private final Connection connection;
    private CabinetServerNetwork.State state;
    private UUID nonce;
    private boolean pending,immediate;
    private int wait,cooldown,x,y,w;
    private String seconds="60",range="16",message="正在读取全服街机规则…";
    private static final int HEIGHT=244;
    private CabinetServerSettingsScreen(Screen parent,InteractionHand hand){
        super(Component.literal("全服街机规则"));this.parent=parent;this.hand=hand;
        connection=Minecraft.getInstance().getConnection().getConnection();
    }
    public static void open(Screen parent,InteractionHand hand){
        var mc=Minecraft.getInstance();if(mc.getConnection()==null)return;
        var page=new CabinetServerSettingsScreen(parent,hand);mc.setScreen(page);page.request(false);
    }
    private boolean valid(){return minecraft!=null&&minecraft.getConnection()!=null&&minecraft.getConnection().getConnection()==connection&&connection.isConnected()
        &&minecraft.player!=null&&minecraft.player.isAlive()&&!minecraft.player.isSpectator()&&minecraft.player.getItemInHand(hand).is(ModItems.ADMIN_TERMINAL.get());}
    private boolean editable(){return valid()&&!pending&&cooldown==0&&state!=null&&state.editable();}
    private void request(boolean save){
        if(!valid()||pending||cooldown>0||save&&!editable())return;
        var rules=CabinetServerRules.DEFAULT;
        if(save)try{rules=new CabinetServerRules(immediate,Integer.parseInt(seconds),Integer.parseInt(range));}
        catch(IllegalArgumentException bad){message="倒计时填 0～3600 秒；距离填 1～128 格。";return;}
        nonce=UUID.randomUUID();pending=true;wait=0;message=save?"正在统一应用到全部街机…":"正在读取…";
        CabinetServerNetwork.send(new CabinetServerNetwork.Request(nonce,hand.ordinal(),save,state==null?0:state.revision(),rules));rebuildWidgets();
    }
    void receive(Connection source,CabinetServerNetwork.State value){
        if(source!=connection||!valid()||!pending||!value.nonce().equals(nonce))return;
        pending=false;cooldown=6;state=value;immediate=value.rules().immediateOnExit();
        seconds=Integer.toString(value.rules().idleSeconds());range=Integer.toString(value.rules().range());message=value.message();rebuildWidgets();
    }
    private void button(String title,int bx,int by,int bw,Runnable action,boolean enabled){addRenderableWidget(DeviceUi.button(font,title,bx,by,bw,20,action,enabled,DeviceUi.Tone.NORMAL));}
    @Override protected void init(){
        DeviceUi.prepare();w=Math.max(1,Math.min(440,width-24));x=(width-w)/2;y=Math.max(8,(height-HEIGHT)/2);
        if(width<320||height<260){button("返回",x+10,Math.max(36,height-30),w-20,this::onClose,true);return;}
        button("最后一人右键退出立即关机："+(immediate?"开":"关"),x+10,y+46,w-20,()->{immediate=!immediate;rebuildWidgets();},editable());
        var idle=new EditBox(font,x+w-96,y+82,86,20,Component.literal("无人占席倒计时，0为不关机"));
        idle.setMaxLength(4);idle.setFilter(s->s.matches("[0-9]*"));idle.setValue(seconds);idle.setResponder(s->seconds=s);idle.setEditable(editable());addRenderableWidget(idle);
        var distance=new EditBox(font,x+w-96,y+114,86,20,Component.literal("显示与旁观距离"));
        distance.setMaxLength(3);distance.setFilter(s->s.matches("[0-9]*"));distance.setValue(range);distance.setResponder(s->range=s);distance.setEditable(editable());addRenderableWidget(distance);
        int col=(w-28)/3;
        button("保存全服设置",x+10,y+214,col,()->request(true),editable());
        button("刷新",x+14+col,y+214,col,()->request(false),!pending&&cooldown==0);
        button("返回",x+18+col*2,y+214,col,this::onClose,!pending);
    }
    @Override public void tick(){
        if(!valid()){onClose();return;}
        if(cooldown>0&&--cooldown==0)rebuildWidgets();
        if(pending&&++wait>100){pending=false;nonce=null;message="等待超时，请刷新确认，不会自动重试保存。";rebuildWidgets();}
    }
    @Override public void render(GuiGraphics g,int mx,int my,float dt){
        g.fill(0,0,width,height,DeviceUi.BG);DeviceUi.panel(g,font,x,y,w,HEIGHT,"管理终端 / 全服街机","仅街机 · 统一覆盖全部新旧机器");
        if(width<320||height<260)DeviceUi.text(g,font,"请放大窗口或降低 GUI 缩放",x+10,y+48,w-20,DeviceUi.TEXT);
        else{
            DeviceUi.text(g,font,"无人占席倒计时（秒，0=禁用）：",x+10,y+88,w-116,DeviceUi.TEXT);
            DeviceUi.text(g,font,"显示 / 旁观距离（格）：",x+10,y+120,w-116,DeviceUi.TEXT);
            DeviceUi.text(g,font,"立即关机关闭 + 倒计时0 = 退出后不自动关机。",x+10,y+146,w-20,DeviceUi.MUTED);
            DeviceUi.text(g,font,"有人入席取消倒计时；无按键不算退出。",x+10,y+160,w-20,DeviceUi.MUTED);
            DeviceUi.text(g,font,"旁观退出缓冲4格；超距不关机，主持可继续运行。",x+10,y+174,w-20,DeviceUi.MUTED);
            DeviceUi.text(g,font,message,x+10,y+196,w-20,DeviceUi.TEXT);
        }
        super.render(g,mx,my,dt);
    }
    @Override public void onClose(){if(minecraft!=null)minecraft.setScreen(valid()?parent:null);}
    @Override public boolean isPauseScreen(){return false;}
}
