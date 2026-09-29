package cn.piq.fcarcade.client;

import cn.piq.fcarcade.client.ui.DeviceScreen;
import cn.piq.fcarcade.client.ui.DeviceUi;
import cn.piq.fcarcade.config.AdminTerminalItem;
import cn.piq.fcarcade.config.AdminTerminalNetwork;
import cn.piq.fcarcade.config.AdminTerminalPolicy;
import cn.piq.fcarcade.registry.ModItems;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/** Small server-authorized admin hub; no device operation is inferred from merely opening it. */
public final class AdminTerminalScreen extends DeviceScreen {
    private final Connection connection;
    private final InteractionHand hand;
    private AdminTerminalNetwork.State state;
    private UUID nonce;
    private boolean pending;
    private net.minecraft.client.gui.components.EditBox retentionDays;
    private int tab,left,top,w,waited,cooldown;
    private String message="正在读取…";
    private static final int HEIGHT=244;
    private static final String[] TABS={"玩家权限","新机默认","管理工具","FC个人存档","街机全服"};
    private static final String[] OPTIONS={"可自定义玩家","选用服务器游戏","选用服务器封面","上传本机游戏","上传本机封面"};
    private static final String[] MODES={"自动（FC JNI优先）","玩家串流","本地同步","服务器托管"};
    private AdminTerminalScreen(Connection connection,InteractionHand hand){super(Component.literal("管理终端"));this.connection=connection;this.hand=hand;}
    public static void open(InteractionHand hand){
        var mc=Minecraft.getInstance();if(mc.getConnection()==null||mc.level==null||mc.player==null)return;
        if(mc.screen instanceof AdminTerminalScreen)return;
        var page=new AdminTerminalScreen(mc.getConnection().getConnection(),hand);mc.setScreen(page);page.request(AdminTerminalPolicy.READ,0);
    }
    @EventBusSubscriber(modid="piq_fc_arcade",bus=EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
    public static final class Setup {
        @SubscribeEvent public static void setup(FMLClientSetupEvent event){event.enqueueWork(()->{
            AdminTerminalItem.clientOpen(AdminTerminalScreen::open);
            AdminTerminalNetwork.clientSink(new AdminTerminalNetwork.ClientSink(){
                public boolean accepts(Connection source){var current=Minecraft.getInstance().getConnection();return current!=null&&current.getConnection()==source;}
                public void receive(AdminTerminalNetwork.State value){if(Minecraft.getInstance().screen instanceof AdminTerminalScreen page)page.receive(value);}
            });
        });}
    }
    private boolean valid(){return minecraft!=null&&minecraft.getConnection()!=null&&minecraft.getConnection().getConnection()==connection&&connection.isConnected()
        &&minecraft.player!=null&&minecraft.player.isAlive()&&!minecraft.player.isSpectator()&&minecraft.player.getItemInHand(hand).is(ModItems.ADMIN_TERMINAL.get());}
    private boolean editable(){return state!=null&&state.editable()&&!pending&&cooldown==0;}
    private void request(int action,int value){
        if(!valid()||pending||cooldown>0||action!=AdminTerminalPolicy.READ&&!editable())return;
        nonce=UUID.randomUUID();pending=true;waited=0;message="正在处理…";
        AdminTerminalNetwork.send(new AdminTerminalNetwork.Request(nonce,hand.ordinal(),action,value,
            state==null?0:state.options(),state==null?-1:state.mode(),state==null?16:state.range(),state==null?0:state.retention()));rebuildWidgets();
    }
    private void receive(AdminTerminalNetwork.State value){
        if(!valid()||!pending||!value.nonce().equals(nonce))return;
        state=value;pending=false;cooldown=6;message=value.message();rebuildWidgets();
    }
    @Override public void tick(){
        if(!valid()){onClose();return;}
        if(cooldown>0&&--cooldown==0)rebuildWidgets();
        if(pending&&++waited>100){pending=false;nonce=null;message="等待超时，请刷新重试。";rebuildWidgets();}
    }
    private Button button(String label,int x,int y,int width,Runnable action,boolean enabled,String tip){
        var b=DeviceUi.button(font,label,x,y,width,20,action,enabled,DeviceUi.Tone.NORMAL);
        if(tip!=null)b.setTooltip(Tooltip.create(Component.literal(tip)));return addRenderableWidget(b);
    }
    @Override protected void init(){
        DeviceUi.prepare();w=Math.max(1,Math.min(430,width-24));left=(width-w)/2;top=Math.max(8,(height-HEIGHT)/2);
        if(width<320||height<260){button("关闭",left,Math.max(36,height-30),w,this::onClose,true,null);return;}
        int third=(w-36)/5;
        for(int i=0;i<TABS.length;i++){int selected=i;button(TABS[i],left+10+i*(third+4),top+46,third,()->{tab=selected;rebuildWidgets();},tab!=i,null);}
        if(tab==0){
            for(int i=0;i<OPTIONS.length;i++){
                int bit=1<<i;boolean yes=state!=null&&(state.options()&bit)!=0;
                String value=state==null?"—":i==0?(yes?"全体":"仅授权名单"):(yes?"允许":"禁止");
                String tip=i==0?"普通玩家的自定义权限；OP不受此限制。":i<=2?"允许选用服务器已有内容，不含上传权限。":"允许上传本机文件。";
                button(OPTIONS[i]+"："+value,left+10,top+76+i*22,w-20,()->request(AdminTerminalPolicy.ACCESS,state.options()^bit),editable(),tip);
            }
        }else if(tab==1){
            int half=(w-24)/2;
            for(int i=0;i<4;i++){
                int mode=i-1;String title=(state!=null&&state.mode()==mode?"✓ ":"")+MODES[i];
                button(title,left+10+(i%2)*(half+4),top+92+(i/2)*24,half,()->request(AdminTerminalPolicy.MODE,mode),
                    editable()&&(mode<0||(state.supported()&(1<<mode))!=0),"仅新机器采用；自动时FC优先JNI Netplay（普通双手柄），其他机型保持原模式。旧机器及其存档不迁移。");
            }
            int slot=(w-32)/4;int[] ranges={8,16,32,64};
            for(int i=0;i<ranges.length;i++){int range=ranges[i];button((state!=null&&state.range()==range?"✓ ":"")+range+" 格",left+10+i*(slot+4),top+164,slot,
                ()->request(AdminTerminalPolicy.RANGE,range),editable(),"新家庭机旁观范围；街机请用“街机全服”统一设置。不改变区块加载。");}
        }else if(tab==2){
            button("单机设置 / 指令菜单",left+10,top+76,w-20,()->request(AdminTerminalPolicy.TARGET,0),editable(),"关闭终端，在聊天栏点击指令并回车；需瞄准有权操作的空闲设备。");
            button("本机网络 / 托管帧率",left+10,top+100,w-20,()->NetworkDiagnosticsScreen.open(this),editable(),null);
            button("查看全服流量",left+10,top+124,w-20,()->request(AdminTerminalPolicy.TRAFFIC,0),editable(),"结果发送到聊天栏；统计本模组压缩前载荷。");
            int half=(w-24)/2;
            button("开启流量监控",left+10,top+148,half,()->request(AdminTerminalPolicy.TRAFFIC,1),editable(),"每5秒向自己发送统计。");
            button("关闭流量监控",left+14+half,top+148,half,()->request(AdminTerminalPolicy.TRAFFIC,2),editable(),null);
        }else if(tab==4){
            button("全服街机关机与旁观设置…",left+10,top+96,w-20,()->cn.piq.fcarcade.client.cabinet.CabinetServerSettingsScreen.open(this,hand),editable(),"统一覆盖已有和新放置街机；不改FC/SFC家用机、GBA或电脑。");
        }else{
            retentionDays=new net.minecraft.client.gui.components.EditBox(font,left+10,top+110,w-110,20,Component.literal("未游玩天数"));
            retentionDays.setMaxLength(4);retentionDays.setFilter(v->v.matches("[0-9]{0,4}"));
            retentionDays.setValue(Integer.toString(state==null?0:state.retention()));retentionDays.setEditable(editable());addRenderableWidget(retentionDays);
            button("保存",left+w-94,top+110,84,()->{
                try{int days=Integer.parseInt(retentionDays.getValue());if(days>3650)throw new NumberFormatException();request(AdminTerminalPolicy.RETENTION,days);}
                catch(NumberFormatException invalid){message="请输入 0～3650 天，0 为关闭。";}
            },editable(),"只清理个人存档；卡带存档永久保留。");
        }
        int half=(w-24)/2;
        button("刷新",left+10,top+214,half,()->request(AdminTerminalPolicy.READ,0),!pending&&cooldown==0,null);
        button("关闭",left+14+half,top+214,half,this::onClose,true,null);
    }
    @Override public void render(GuiGraphics g,int mx,int my,float dt){
        g.fill(0,0,width,height,DeviceUi.BG);
        DeviceUi.panel(g,font,left,top,w,HEIGHT,"方块电玩 / 管理终端","服务器管理 · 需 OP");
        if(width<320||height<260)DeviceUi.text(g,font,"请放大窗口或降低 GUI 缩放",left+10,top+50,w-20,DeviceUi.TEXT);
        else{
            if(tab==1){
                DeviceUi.text(g,font,"运行方式",left+10,top+76,w-20,DeviceUi.MUTED);
                DeviceUi.text(g,font,"新家庭机旁观范围"+(state==null?"":"："+state.range()+" 格"),left+10,top+150,w-20,DeviceUi.MUTED);
            }
            if(tab==3){
                DeviceUi.text(g,font,"玩家多久未游玩后自动清理（天）",left+10,top+80,w-20,DeviceUi.TEXT);
                DeviceUi.text(g,font,"0 = 关闭（默认）",left+10,top+142,w-20,DeviceUi.MUTED);
                DeviceUi.text(g,font,"只清理个人存档；卡带进度不清理。",left+10,top+162,w-20,DeviceUi.MUTED);
            }
            if(tab==4){
                DeviceUi.text(g,font,"立即退出关机、无人倒计时、显示与旁观距离",left+10,top+78,w-20,DeviceUi.TEXT);
                DeviceUi.text(g,font,"OP统一设置，全部新旧街机即时采用。",left+10,top+138,w-20,DeviceUi.MUTED);
                DeviceUi.text(g,font,"不扫描或加载远处机器，不影响家庭机和电脑。",left+10,top+158,w-20,DeviceUi.MUTED);
            }
            String note=!message.isBlank()?message:tab==0?"修改即时保存。":tab==1?"仅影响新机器，已有机器不变。":tab==3?"正在使用的存档不会清理。":"指令结果显示在聊天栏。";
            DeviceUi.text(g,font,note,left+10,top+198,w-20,DeviceUi.MUTED);
        }
        super.render(g,mx,my,dt);
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){if(minecraft!=null)minecraft.setScreen(null);}
}
