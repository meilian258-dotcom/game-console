package cn.piq.fcarcade.client;

import cn.piq.fcarcade.home.ExternalHomeConsoleBlockEntity;
import cn.piq.fcarcade.home.HomeConsoleBlockEntity;
import cn.piq.fcarcade.home.HomeSyncNetwork;
import cn.piq.fcarcade.home.HomeSyncSaveHints;
import cn.piq.fcarcade.client.ui.DeviceUi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/** Vanilla settings page; opening or closing never powers off or returns a controller. */
public final class HomeSyncSettingsScreen extends cn.piq.fcarcade.client.ui.DeviceScreen {
    /** Small per-device actions; the common settings layout and server mode authority remain shared. */
    public interface DeviceActions {
        void open(net.minecraft.client.gui.screens.Screen parent,net.minecraft.core.BlockPos console);
        String footer(net.minecraft.core.BlockPos console);
        default boolean choosesSecondPortAtStartup(){return false;}
    }
    private static final java.util.Map<net.minecraft.resources.ResourceLocation,DeviceActions> ACTIONS=new java.util.HashMap<>();
    public static void registerDeviceActions(net.minecraft.resources.ResourceLocation system,DeviceActions actions){if(ACTIONS.putIfAbsent(system,java.util.Objects.requireNonNull(actions))!=null)throw new IllegalArgumentException("Duplicate device settings actions");}
    public static void openRuntimeSettings(net.minecraft.client.gui.screens.Screen parent,net.minecraft.resources.ResourceLocation system,String label){Minecraft.getInstance().setScreen(new HomeRuntimeSettingsScreen(parent,system,label));}
    private DeviceActions actions(){return ACTIONS.get(deviceSystem());}
    private final Connection connection;
    private HomeSyncNetwork.Setting setting;
    private String status;
    private boolean pending,timedOut;
    private int waiting,age,cooldown,left,top,panelWidth;
    private HomeSyncSettingsLayout layout;
    private static final String[] LABELS = { "玩家音画串流", "本地输入同步", "服务端托管音画", "RetroArch Netplay（实验）", "FC JNI Netplay（试验 / 本机确认）" };
    private HomeSyncSettingsScreen(HomeSyncNetwork.Setting value,Connection source) {
        super(Component.literal("设备设置 · "+value.system()));setting = value;connection = source;status = value.reason();
    }
    @EventBusSubscriber(modid="piq_fc_arcade",bus=EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
    public static final class Setup {
        @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
            event.enqueueWork(() -> HomeSyncNetwork.clientSink(new HomeSyncNetwork.ClientSink() {
                public boolean accepts(Connection source) { var c=Minecraft.getInstance().getConnection();return c!=null&&c.getConnection()==source; }
                public void setting(HomeSyncNetwork.Setting value) {
                    var mc=Minecraft.getInstance();if(mc.getConnection()==null||mc.level==null||!mc.level.dimension().location().equals(value.dimension()))return;
                    if(value.open()) {
                        // Never replace a cartridge, invitation or running-game dialog with a delayed open.
                        if(mc.screen==null||!value.debugTool()&&mc.screen instanceof ChatScreen)
                            mc.setScreen(new HomeSyncSettingsScreen(value,mc.getConnection().getConnection()));
                    } else if(mc.screen instanceof HomeSyncSettingsScreen screen)screen.receive(value);
                }
            }));
        }
    }
    @Override protected void init() {
        DeviceUi.prepare();
        layout=HomeSyncSettingsLayout.fit(width,height);panelWidth=layout.width();left=layout.left();top=layout.top();
        if(layout.compact()){addRenderableWidget(Button.builder(Component.literal("关闭"),b->onClose()).bounds(left,Math.max(35,height-32),panelWidth,20).build());return;}
        for(int mode=0;mode<5;mode++) {
            final int selected=mode;
            boolean privateOnly=diagnosticsOnly();
            String label=(setting.mode()==mode&&!privateOnly?"✓ ":"")+LABELS[mode];
            if(privateOnly&&mode==4)label="私人单人 · 本机设置…";
            else if(privateOnly)label+="（尚未接入）";
            else if((setting.supported()&(1<<mode))==0)label+="（不可用）";
            var button=Button.builder(Component.literal(label),b->{
                if(privateOnly&&selected==4){if(ready()&&current())openLocalSettings();}
                else if(selected==4)JniNetplayConsent.confirm(this,()->{if(current()&&setting.mode()!=4)apply(4,-1,-1);});
                else apply(selected,-1,-1);
            })
                .bounds(left+10,top+HomeSyncSettingsLayout.MODE_ROW+mode*HomeSyncSettingsLayout.MODE_STEP,panelWidth-20,16).build();
            button.active=ready()&&(privateOnly&&mode==4||(setting.supported()&(1<<mode))!=0
                    &&(setting.editable()&&setting.mode()!=mode||mode==4&&setting.mode()==4&&!JniNetplayConsent.allowed()));
            button.setTooltip(Tooltip.create(Component.literal(privateOnly
                ?(mode==4?"打开已可用的本机控制、运行环境与运行器设置；不会开关机或切换公共模式。"
                        :"本附属尚未接入此公共运行方式；不是权限不足，也不能通过此按钮解锁。当前仅私人单人，附近玩家不能旁观。")
                :(setting.supported()&(1<<mode))==0
                ?status
                :(mode==0?"由开机玩家运行游戏，向其他玩家发送音画。":mode==1?"各客户端运行游戏，同步操作数据。":mode==2?"由服务器运行游戏，向玩家发送音画。":mode==4?"FC 普通双手柄 JNI 回滚，原生崩溃可影响整个 MC。Windows x64默认允许，参与和旁观跟随房间；个人/卡带独立JNI档，不与原 Netplay 混接。":"RetroArch Netplay 同步操作与状态；Windows x64。按卡带策略保存，开机恢复；网络页可手动保存。")
                    +"\n管理员关机后可修改；已借手柄无需归还。")));
            addRenderableWidget(button);
        }
        int half=(panelWidth-26)/2,right=left+16+half;
        var occupancy=Button.builder(Component.literal("使用者文字："+(setting.occupancySupported()?(setting.occupancy()?"开":"关"):"不支持")),
            b->apply(-1,setting.occupancy()?0:1,-1)).bounds(left+10,top+HomeSyncSettingsLayout.ADVANCED_ROW,half,20).build();
        occupancy.active=ready()&&setting.editable()&&setting.occupancySupported();
        occupancy.setTooltip(Tooltip.create(Component.literal(setting.occupancySupported()
            ?"需 OP2；显示或隐藏使用者标牌，不影响游戏画面。":"此机型不支持使用者标牌。")));
        addRenderableWidget(occupancy);
        boolean fc=minecraft.level==null||!(minecraft.level.getBlockEntity(setting.console()) instanceof ExternalHomeConsoleBlockEntity);
        boolean startupSecond=fc||actions()!=null&&actions().choosesSecondPortAtStartup();
        var approval=Button.builder(Component.literal(diagnosticsOnly()?"2P：尚未接入":startupSecond?"2P：开局选择":"加入需同意："+(setting.approval()?"开":"关")),
            b->apply(-1,-1,setting.approval()?0:1)).bounds(right,top+HomeSyncSettingsLayout.ADVANCED_ROW,half,20).build();
        approval.active=!diagnosticsOnly()&&!startupSecond&&ready()&&setting.editable();
        approval.setTooltip(Tooltip.create(Component.literal(diagnosticsOnly()?"此附属尚未接入公共多席位；不会借出无法操作的 2P 手柄。":startupSecond?"开机玩家决定是否允许 2P；本局不再弹出申请。":"需 OP2；加入仍受席位、游戏人数和交互权限限制。")));
        addRenderableWidget(approval);
        int footer=(panelWidth-44)/5;
        var refresh=Button.builder(Component.literal("刷新"),b->apply(-1,-1,-1))
            .bounds(left+10,top+HomeSyncSettingsLayout.FOOTER_ROW,footer,20).build();
        refresh.active=ready();addRenderableWidget(refresh);
        var performance=Button.builder(Component.literal("性能"),b->FcPerformanceScreen.open(this,setting.console())).bounds(left+16+footer,top+HomeSyncSettingsLayout.FOOTER_ROW,footer,20).build();
        performance.setTooltip(Tooltip.create(Component.literal("查看本机 FC 性能，不需要管理员权限。")));
        performance.active=fc;addRenderableWidget(performance);
        var network=Button.builder(Component.literal("网络"),b->NetworkDiagnosticsScreen.open(this)).bounds(left+22+2*footer,top+HomeSyncSettingsLayout.FOOTER_ROW,footer,20).build();
        network.active=ready();addRenderableWidget(network);
        addRenderableWidget(Button.builder(Component.literal(diagnosticsOnly()?"本机设置":"私人模式"),b->{
            if(actions()!=null)actions().open(this,setting.console());else if(diagnosticsOnly())openLocalSettings();else PrivateHomeClient.open();
        }).bounds(left+28+3*footer,top+HomeSyncSettingsLayout.FOOTER_ROW,footer,20).build());
        addRenderableWidget(Button.builder(Component.literal("关闭"),b->onClose()).bounds(left+34+4*footer,top+HomeSyncSettingsLayout.FOOTER_ROW,footer,20).build());
    }
    private boolean ready(){return !pending&&!timedOut&&cooldown==0;}
    private void openLocalSettings(){minecraft.setScreen(new HomeRuntimeSettingsScreen(this,deviceSystem(),setting.system()));}
    private boolean diagnosticsOnly(){return minecraft.level!=null&&minecraft.level.getBlockEntity(setting.console()) instanceof ExternalHomeConsoleBlockEntity c
            &&cn.piq.fcarcade.home.HomeSystems.privateDeviceSettings(c.systemId());}
    private net.minecraft.resources.ResourceLocation deviceSystem(){
        return minecraft.level!=null&&minecraft.level.getBlockEntity(setting.console()) instanceof ExternalHomeConsoleBlockEntity c?c.systemId():cn.piq.fcarcade.home.HomeSystems.NES_SYSTEM;
    }
    private void apply(int mode,int occupancy,int approval) {
        if(!ready()||!current()||(mode>=0||occupancy>=0||approval>=0)&&!setting.editable()
            ||mode>=0&&(setting.supported()&(1<<mode))==0||occupancy>=0&&!setting.occupancySupported())return;
        pending=true;waiting=0;status="等待服务器确认…";
        HomeSyncNetwork.request(setting.token(),setting.revision(),mode,occupancy,approval);rebuildWidgets();
    }
    private void receive(HomeSyncNetwork.Setting value) {
        if(!current()||!setting.token().equals(value.token())||!setting.hardware().equals(value.hardware())
                ||!setting.console().equals(value.console())||!setting.dimension().equals(value.dimension()))return;
        if(value.revision()<setting.revision())return;
        setting=value;pending=false;timedOut=false;waiting=0;cooldown=4;status=value.reason();rebuildWidgets();
    }
    private boolean current() {
        if(minecraft==null||minecraft.getConnection()==null||minecraft.getConnection().getConnection()!=connection||!connection.isConnected()
                ||minecraft.level==null||!minecraft.level.dimension().location().equals(setting.dimension())||!minecraft.level.hasChunkAt(setting.console()))return false;
        var be=minecraft.level.getBlockEntity(setting.console());
        return be instanceof HomeConsoleBlockEntity fc&&fc.hardwareId().equals(setting.hardware())
                ||be instanceof ExternalHomeConsoleBlockEntity external&&external.hardwareId().equals(setting.hardware());
    }
    @Override public void tick() {
        if(!current()||++age>1200){onClose();return;}
        if(cooldown>0&&--cooldown==0)rebuildWidgets();
        if(pending&&++waiting>100){pending=false;timedOut=true;status="保存未确认，请关闭后重开。";rebuildWidgets();}
    }
    @Override public void onClose() { minecraft.setScreen(null); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void render(GuiGraphics g,int mx,int my,float partial) {
        g.fill(0,0,width,height,DeviceUi.BG);
        String target="目标坐标："+setting.console().getX()+", "+setting.console().getY()+", "+setting.console().getZ();
        DeviceUi.panel(g,font,left,top,panelWidth,HomeSyncSettingsLayout.HEIGHT,"设备设置 · "+setting.system(),target);
        if(layout.compact()){g.drawWordWrap(font,Component.literal("请降低GUI缩放或放大窗口。"),left,top+43,panelWidth,DeviceUi.TEXT);super.render(g,mx,my,partial);return;}
        var lines=font.split(Component.literal(status),panelWidth-20);
        for(int i=0;i<Math.min(3,lines.size());i++)g.drawString(font,lines.get(i),left+10,top+HomeSyncSettingsLayout.STATUS_ROW+i*10,DeviceUi.TEXT,false);
        var hint=font.split(Component.literal(footerHint()),panelWidth-20);
        for(int i=0;i<Math.min(2,hint.size());i++)g.drawString(font,hint.get(i),left+10,top+HomeSyncSettingsLayout.HINT_ROW+i*9,DeviceUi.MUTED,false);
        super.render(g,mx,my,partial);
        if(mx>=left+8&&mx<left+panelWidth-8&&my>=top+8&&my<top+38)
            g.renderTooltip(font,Component.literal(setting.system()+" · "+setting.dimension()+" · "+target),mx,my);
        else if(mx>=left+8&&mx<left+panelWidth-8&&my>=top+HomeSyncSettingsLayout.STATUS_ROW&&my<top+HomeSyncSettingsLayout.HINT_ROW)
            g.renderTooltip(font,Component.literal(status),mx,my);
        else if(mx>=left+8&&mx<left+panelWidth-8&&my>=top+HomeSyncSettingsLayout.HINT_ROW&&my<top+HomeSyncSettingsLayout.FOOTER_ROW)
            g.renderTooltip(font,Component.literal(footerHint()),mx,my);
    }
    private String footerHint(){if(actions()!=null)return actions().footer(setting.console());return diagnosticsOnly()?PrivateHomeClient.cartridgeRuntimeLabel(deviceSystem(),setting.console())
            +" · 本机个人进度；尚无服务器卡带档/公共旁观。":HomeSyncSaveHints.footer(setting.system(),setting.mode());}
}
