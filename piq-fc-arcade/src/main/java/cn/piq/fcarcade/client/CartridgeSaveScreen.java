package cn.piq.fcarcade.client;

import cn.piq.fcarcade.client.ui.DeviceLayout;
import cn.piq.fcarcade.client.ui.DeviceUi;
import cn.piq.fcarcade.home.CartridgeSaveNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/** Independent server-authorized save session. The original editor may safely close after OPEN. */
@EventBusSubscriber(modid="piq_fc_arcade",value=Dist.CLIENT)
public final class CartridgeSaveScreen extends cn.piq.fcarcade.client.ui.DeviceScreen {
    private record Opening(Screen parent,Connection connection,String system,String rom,UUID editorToken,long at) {}
    private static Opening opening;
    private final Connection connection;
    private CartridgeSaveNetwork.Reply data;
    private DeviceLayout.Browser layout;
    private EditBox name;
    private String selected="",draft="",status;
    private int page;
    private boolean closed,busy,confirming;
    private long sentAt,nextActionAt;
    @SubscribeEvent public static void pendingTick(ClientTickEvent.Post event){
        if(opening==null)return;var mc=Minecraft.getInstance();Opening expected=opening;
        boolean same=mc.screen==expected.parent&&mc.getConnection()!=null&&mc.getConnection().getConnection()==expected.connection&&expected.connection.isConnected();
        if(!same||System.nanoTime()-expected.at>=15_000_000_000L){opening=null;if(same)mc.gui.setOverlayMessage(Component.literal("存档目录请求超时，请重试"),false);}
    }
    @EventBusSubscriber(modid="piq_fc_arcade",bus=EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
    public static final class Setup {
        @SubscribeEvent public static void setup(FMLClientSetupEvent event){event.enqueueWork(()->CartridgeSaveNetwork.setClientReceiver(CartridgeSaveScreen::receive));}
    }
    private CartridgeSaveScreen(CartridgeSaveNetwork.Reply data,Connection connection){
        super(Component.literal("卡带 · 存档管理"));this.data=data;this.connection=connection;status=data.message();
    }
    public static void open(String system,UUID editorToken,UUID cardId,int hand,int slot,String rom){
        var mc=Minecraft.getInstance();if(mc.getConnection()==null||mc.screen==null||rom==null||rom.isEmpty())return;
        var source=mc.getConnection().getConnection();if(!source.isConnected())return;
        if(opening!=null&&opening.parent==mc.screen&&opening.connection==source&&System.nanoTime()-opening.at<15_000_000_000L)return;
        opening=new Opening(mc.screen,source,system,rom,editorToken,System.nanoTime());
        CartridgeSaveNetwork.send(new CartridgeSaveNetwork.Open(system,editorToken,cardId,hand,slot));
    }
    private static void receive(CartridgeSaveNetwork.Reply reply){
        var mc=Minecraft.getInstance();if(mc.getConnection()==null)return;Connection source=mc.getConnection().getConnection();
        if(mc.screen instanceof CartridgeSaveScreen screen&&screen.data.token().equals(reply.token())&&screen.connection==source){screen.update(reply);return;}
        Opening expected=opening;
        if(expected!=null&&mc.screen==expected.parent&&source==expected.connection&&System.nanoTime()-expected.at<15_000_000_000L
                &&reply.editorToken().equals(expected.editorToken)&&reply.system().equals(expected.system)&&(reply.closed()?reply.token().equals(expected.editorToken):reply.rom().equals(expected.rom))){
            opening=null;
            if(reply.closed()){mc.gui.setOverlayMessage(Component.literal(reply.message()),false);return;}
            mc.setScreen(new CartridgeSaveScreen(reply,source));return;
        }
        if(!reply.closed())CartridgeSaveNetwork.send(new CartridgeSaveNetwork.Request(reply.token(),CartridgeSaveNetwork.CLOSE,"","","",null));
    }
    private boolean current(){return !closed&&minecraft!=null&&minecraft.screen==this&&minecraft.getConnection()!=null
            &&minecraft.getConnection().getConnection()==connection&&connection.isConnected()&&minecraft.player!=null&&minecraft.player.isAlive();}
    private CartridgeSaveNetwork.Entry selected(){return data.entries().stream().filter(e->e.id().equals(selected)).findFirst().orElse(null);}
    @Override protected void init(){
        DeviceUi.prepare();if(name!=null)draft=name.getValue();name=null;layout=DeviceLayout.browser(width,height,1);
        if(!layout.supported())return;
        if(confirming){
            var r=layout.navigation();int half=(r.width()-4)/2;
            button("确认删除",new DeviceLayout.Rect(r.x(),r.y(),half,20),()->request(CartridgeSaveNetwork.CONFIRM_DELETE,data.pendingId(),data.pendingVersion(),"",data.confirmation()),ready(),DeviceUi.Tone.DANGER);
            button("取消",new DeviceLayout.Rect(r.x()+half+4,r.y(),r.width()-half-4,20),()->{confirming=false;if(ready())request(CartridgeSaveNetwork.REFRESH,"","","",null);else rebuildWidgets();},!busy,DeviceUi.Tone.NORMAL);
            return;
        }
        var t=layout.toolbar();int controls=162;name=new EditBox(font,t.x(),t.y(),Math.max(30,t.width()-controls-8),20,Component.literal("存档名称"));
        name.setMaxLength(32);name.setValue(draft);name.setHint(Component.literal("选择存档后修改名称"));name.setEditable(!busy&&selected()!=null&&selected().canEdit()&&!selected().active());addRenderableWidget(name);
        int x=name.getX()+name.getWidth()+4;
        button("重命名",new DeviceLayout.Rect(x,t.y(),58,20),()->{var e=selected();if(e!=null)request(CartridgeSaveNetwork.RENAME,e.id(),e.version(),name.getValue().strip(),null);},editable(),DeviceUi.Tone.NORMAL);
        button("删除…",new DeviceLayout.Rect(x+62,t.y(),48,20),()->{var e=selected();if(e!=null)request(CartridgeSaveNetwork.PREPARE_DELETE,e.id(),e.version(),"",null);},editable(),DeviceUi.Tone.DANGER);
        button("刷新",new DeviceLayout.Rect(x+114,t.y(),48,20),()->request(CartridgeSaveNetwork.REFRESH,"","","",null),ready(),DeviceUi.Tone.NORMAL);
        page=Math.min(page,Math.max(0,(data.entries().size()-1)/layout.rows()));
        for(int i=page*layout.rows();i<Math.min(data.entries().size(),(page+1)*layout.rows());i++){
            var entry=data.entries().get(i);var r=layout.row(i-page*layout.rows());
            var b=DeviceUi.row(font,entry.name(),entry.active()?"使用中":entry.canEdit()?"可管理":"只读",r.x(),r.y(),r.width(),r.height(),()->{selected=entry.id();draft=entry.name();name=null;rebuildWidgets();},entry.id().equals(selected),false,!busy);
            b.setTooltip(Tooltip.create(Component.literal(details(entry))));addRenderableWidget(b);
        }
        var nav=layout.navigation();int third=(nav.width()-8)/3;
        button("上一页",new DeviceLayout.Rect(nav.x(),nav.y(),third,20),()->{page--;rebuildWidgets();},!busy&&page>0,DeviceUi.Tone.QUIET);
        button("下一页",new DeviceLayout.Rect(nav.x()+third+4,nav.y(),third,20),()->{page++;rebuildWidgets();},!busy&&(page+1)*layout.rows()<data.entries().size(),DeviceUi.Tone.QUIET);
        button("关闭",new DeviceLayout.Rect(nav.x()+2*(third+4),nav.y(),nav.width()-2*(third+4),20),this::onClose,true,DeviceUi.Tone.NORMAL);
    }
    private boolean ready(){return !busy&&(nextActionAt==0||System.nanoTime()>=nextActionAt);}
    private boolean editable(){var row=selected();return ready()&&row!=null&&row.canEdit()&&!row.active();}
    @Override protected void rebuildWidgets(){
        boolean focused=name!=null&&name.isFocused();int cursor=name==null?0:name.getCursorPosition();
        super.rebuildWidgets();
        if(focused&&name!=null&&editable()){setInitialFocus(name);name.moveCursorTo(cursor,false);}
    }
    private void button(String label,DeviceLayout.Rect r,Runnable action,boolean enabled,DeviceUi.Tone tone){
        var b=DeviceUi.button(font,label,r.x(),r.y(),r.width(),r.height(),action,enabled,tone);b.setTooltip(Tooltip.create(Component.literal(label)));addRenderableWidget(b);
    }
    private void request(int operation,String id,String version,String name,UUID confirmation){
        if(!current()||!ready())return;busy=true;sentAt=System.nanoTime();nextActionAt=sentAt+1_050_000_000L;status="等待服务器确认…";
        CartridgeSaveNetwork.send(new CartridgeSaveNetwork.Request(data.token(),operation,id,version,name,confirmation));rebuildWidgets();
    }
    private void update(CartridgeSaveNetwork.Reply reply){
        if(!current())return;
        data=reply;status=reply.message();busy=false;
        if(reply.closed()){closed=true;minecraft.setScreen(null);minecraft.gui.setOverlayMessage(Component.literal(status),false);return;}
        confirming=reply.confirmation()!=null&&!reply.pendingId().isEmpty();
        if(selected()==null){selected="";draft="";name=null;}rebuildWidgets();
    }
    @Override public void tick(){
        if(!current()){onClose();return;}
        if(nextActionAt!=0&&System.nanoTime()>=nextActionAt){nextActionAt=0;rebuildWidgets();}
        if(busy&&System.nanoTime()-sentAt>15_000_000_000L){busy=false;confirming=false;status="请求超时，请刷新查看服务器结果；不要重复删除。";rebuildWidgets();}
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){closeSession();minecraft.setScreen(null);}
    @Override public void removed(){closeSession();}
    private void closeSession(){
        if(closed)return;closed=true;
        if(minecraft!=null&&minecraft.getConnection()!=null&&minecraft.getConnection().getConnection()==connection&&connection.isConnected())
            CartridgeSaveNetwork.send(new CartridgeSaveNetwork.Request(data.token(),CartridgeSaveNetwork.CLOSE,"","","",null));
    }
    private static String details(CartridgeSaveNetwork.Entry e){return e.name()+"\n归属："+e.owner()+"\n来源："+e.source()+"\n更新时间："+DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(e.modified()))+"\n大小："+e.bytes()+" 字节"+(e.active()?"\n正在使用，不能修改":"");}
    @Override public void render(GuiGraphics g,int mx,int my,float dt){
        g.fill(0,0,width,height,DeviceUi.BG);if(layout==null)return;var p=layout.panel();
        DeviceUi.panel(g,font,p.x(),p.y(),p.width(),p.height(),title.getString(),data.system().toUpperCase(java.util.Locale.ROOT)+" · 当前卡带的服务器存档");
        if(!layout.supported()){DeviceUi.text(g,font,"请放大窗口或降低 GUI 缩放；Esc 关闭",p.x()+10,p.y()+45,p.width()-20,DeviceUi.MUTED);super.render(g,mx,my,dt);return;}
        if(confirming){
            var row=data.entries().stream().filter(e->e.id().equals(data.pendingId())).findFirst().orElse(null);
            String warning="确定删除这份存档？\n"+(row==null?"已选存档":details(row))+"\n存档将移入服务器回收目录，不自动恢复。\n只处理这一份，卡带、ROM、封面和其他存档不受影响。";
            var lines=font.split(Component.literal(warning),p.width()-24);int y=p.y()+46;
            for(var line:lines){if(y+font.lineHeight>=layout.navigation().y()-12)break;g.drawString(font,line,p.x()+12,y,DeviceUi.TEXT,false);y+=12;}
        }else{
            var list=layout.list();var detail=layout.details();DeviceUi.section(g,list.x(),list.y(),list.width(),list.height());DeviceUi.section(g,detail.x(),detail.y(),detail.width(),detail.height());
            if(data.entries().isEmpty())DeviceUi.text(g,font,"本次列表未显示存档，详见状态",list.x()+8,list.y()+8,list.width()-16,DeviceUi.MUTED);
            var selected=selected();String text=selected==null?"选择存档查看详情。\n本机私人存档与恢复备份不在此列表。":details(selected);
            var lines=font.split(Component.literal(text),detail.width()-16);int y=detail.y()+7;
            for(var line:lines){if(y+font.lineHeight>=detail.bottom()-7)break;g.drawString(font,line,detail.x()+8,y,DeviceUi.TEXT,false);y+=12;}
            if(selected!=null&&mx>=detail.x()&&mx<detail.right()&&my>=detail.y()&&my<detail.bottom())g.renderTooltip(font,Component.literal(text),mx,my);
        }
        var s=layout.status();String message=nextActionAt!=0&&System.nanoTime()<nextActionAt?"请稍候 1 秒 · "+status:status;
        DeviceUi.status(g,font,message,s.x(),s.y(),s.width(),busy);super.render(g,mx,my,dt);
    }
}
