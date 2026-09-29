package cn.piq.fcarcade.client;

import cn.piq.fcarcade.client.ui.DeviceUi;
import cn.piq.fcarcade.network.HostedDiagnosticsNetwork;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/** Separate global-administration page; a stale reply never opens it or edits device settings. */
public final class NetworkDiagnosticsScreen extends cn.piq.fcarcade.client.ui.DeviceScreen {
    private final Screen parent;
    private final Connection connection;
    private UUID nonce;
    private HostedDiagnosticsNetwork.State state;
    private String status="正在读取服务器设置…";
    private boolean pending;
    private boolean settingsTab;
    private int waited,cooldown,age,left,top,panelWidth;
    private static final int HEIGHT=224;
    private static final String SCOPE="统计本机连接的 FC、SFC 家用和共享机柜全部会话。Netplay 含原生按键、握手、状态同步；游戏/资源含ROM、封面和资源目录传输；其他/旧同步含旧同步快照和管理消息。分类累计为上传加下载。Netplay 数据按实际交给连接发送/已解码接收的载荷计，其他按成功编码/解码计，均为 Minecraft 压缩前，不是网卡流量。不含原版、其他模组、TCP开销；不含旧SFC街机core9协议。重新计数只清本机统计，不重启游戏；隐藏悬浮窗/切换维度不清零，断开或换服务器清零。1 KiB=1024 B；视频FPS为各流完整接收帧率之和，非渲染帧率。";
    private NetworkDiagnosticsScreen(Screen parent,Connection connection) {
        super(Component.literal("托管帧率 / 网络"));this.parent=parent;this.connection=connection;
    }
    public static void open(Screen parent) {
        var mc=Minecraft.getInstance();if(mc.getConnection()==null||mc.level==null)return;
        var screen=new NetworkDiagnosticsScreen(parent,mc.getConnection().getConnection());
        mc.setScreen(screen);screen.request(-1);
    }
    @EventBusSubscriber(modid="piq_fc_arcade",bus=EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
    public static final class Setup {
        @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
            event.enqueueWork(()->HostedDiagnosticsNetwork.clientSink(new HostedDiagnosticsNetwork.ClientSink() {
                public boolean accepts(Connection source) { var c=Minecraft.getInstance().getConnection();return c!=null&&c.getConnection()==source; }
                public void receive(HostedDiagnosticsNetwork.State value) {
                    if(Minecraft.getInstance().screen instanceof NetworkDiagnosticsScreen page)page.receive(value);
                }
            }));
        }
    }
    @Override protected void init() {
        DeviceUi.prepare();panelWidth=Math.max(1,Math.min(420,width-24));left=(width-panelWidth)/2;top=Math.max(8,(height-HEIGHT)/2);
        if(width<320||height<240){addRenderableWidget(Button.builder(Component.literal("返回"),b->onClose()).bounds(left,Math.max(35,height-32),panelWidth,20).build());return;}
        int half=(panelWidth-26)/2;
        var traffic=Button.builder(Component.literal("流量监控"),b->{settingsTab=false;rebuildWidgets();}).bounds(left+10,top+39,half,20).build();
        traffic.active=settingsTab;addRenderableWidget(traffic);
        var settings=Button.builder(Component.literal("托管设置"),b->{settingsTab=true;rebuildWidgets();}).bounds(left+16+half,top+39,half,20).build();
        settings.active=!settingsTab;addRenderableWidget(settings);
        if(!settingsTab){
            addRenderableWidget(Button.builder(Component.literal("网络悬浮窗："+(NetworkDiagnosticsClient.showHud()?"开":"关")),b->{NetworkDiagnosticsClient.toggleHud();rebuildWidgets();})
                    .bounds(left+10,top+160,half,20).build());
            addRenderableWidget(Button.builder(Component.literal("重新计数"),b->NetworkDiagnosticsClient.resetCounters())
                    .bounds(left+16+half,top+160,half,20).build());
            addRenderableWidget(Button.builder(Component.literal("Netplay 存档…"),b->NetplaySaveScreen.open(this)).bounds(left+10,top+196,half,20).build());
            addRenderableWidget(Button.builder(Component.literal("返回"),b->onClose()).bounds(left+16+half,top+196,half,20).build());return;
        }
        int slot=(panelWidth-32)/3;
        for(int index=0;index<3;index++) {
            int fps=new int[]{20,30,60}[index];
            var b=Button.builder(Component.literal((state!=null&&state.fps()==fps?"✓ ":"")+fps+" FPS"),v->request(fps))
                    .bounds(left+10+index*(slot+6),top+82,slot,20).build();
            b.active=!pending&&cooldown==0&&state!=null&&state.editable()&&state.fps()!=fps;addRenderableWidget(b);
        }
        var refresh=Button.builder(Component.literal("刷新服务器设置"),b->request(-1)).bounds(left+10,top+196,half,20).build();
        refresh.active=!pending&&cooldown==0;addRenderableWidget(refresh);
        addRenderableWidget(Button.builder(Component.literal("返回"),b->onClose()).bounds(left+16+half,top+196,half,20).build());
    }
    private boolean current() { return minecraft!=null&&minecraft.level!=null&&minecraft.getConnection()!=null&&minecraft.getConnection().getConnection()==connection&&connection.isConnected(); }
    private void request(int desired) {
        if(!current()||pending||cooldown>0||desired!=-1&&(state==null||!state.editable()))return;
        nonce=UUID.randomUUID();pending=true;waited=0;status=desired==-1?"正在读取服务器设置…":"等待服务器确认…";
        HostedDiagnosticsNetwork.request(nonce,state==null?0:state.fps(),desired);rebuildWidgets();
    }
    private void receive(HostedDiagnosticsNetwork.State value) {
        if(!current()||!pending||!value.nonce().equals(nonce))return;
        state=value;status=value.reason();pending=false;cooldown=6;waited=0;rebuildWidgets();
    }
    @Override public void tick() {
        if(!current()||++age>2400){minecraft.setScreen(null);return;}
        if(cooldown>0&&--cooldown==0)rebuildWidgets();
        if(pending&&++waited>100){pending=false;status="保存未确认，请刷新重试。";cooldown=6;rebuildWidgets();}
    }
    @Override public void onClose() { minecraft.setScreen(current()?parent:null); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void render(GuiGraphics g,int mx,int my,float partial) {
        g.fill(0,0,width,height,DeviceUi.BG);
        DeviceUi.panel(g,font,left,top,panelWidth,HEIGHT,"托管帧率 / 网络",settingsTab?"服务器全局设置 · 修改需 OP2 · 默认 20 FPS":"本机连接 · 压缩前载荷 · 可重新计数");
        if(width<320||height<240){g.drawWordWrap(font,Component.literal("请降低GUI缩放或放大窗口。"),left,top+43,panelWidth,DeviceUi.TEXT);super.render(g,mx,my,partial);return;}
        java.util.List<String> lines=java.util.List.of();
        if(settingsTab){
            String active=state==null?"等待服务器…":state.enabled()?"托管功能已启用":"服务器托管未启用；此处只保存视频帧率上限";
            g.drawString(font,font.plainSubstrByWidth(active,panelWidth-20),left+10,top+68,DeviceUi.MUTED,false);
            var wrapped=font.split(Component.literal(status),panelWidth-20);
            for(int i=0;i<Math.min(4,wrapped.size());i++)g.drawString(font,wrapped.get(i),left+10,top+111+i*10,DeviceUi.TEXT,false);
            g.drawWordWrap(font,Component.literal("只调整服务器的视频投递上限，不改变模拟速度、音频或设备同步模式。"),left+10,top+159,panelWidth-20,DeviceUi.MUTED);
        }else{
            lines=NetworkDiagnosticsClient.lines();int y=top+68;
            for(String line:lines){if(y>top+145)break;g.drawString(font,font.plainSubstrByWidth(line,panelWidth-20),left+10,y,DeviceUi.TEXT,false);y+=11;}
            g.drawString(font,font.plainSubstrByWidth("本模组全部会话/传输合计；悬停查看统计范围",panelWidth-20),left+10,top+184,DeviceUi.MUTED,false);
        }
        super.render(g,mx,my,partial);
        if(settingsTab&&mx>=left+8&&mx<left+panelWidth-8&&my>=top+108&&my<top+152)
            g.renderTooltip(font,Component.literal(status),mx,my);
        else if(!settingsTab&&mx>=left+8&&mx<left+panelWidth-8&&my>=top+182&&my<top+194)
            g.renderTooltip(font,font.split(Component.literal(SCOPE),Math.min(340,width-24)),mx,my);
        else if(!settingsTab&&mx>=left+8&&mx<left+panelWidth-8&&my>=top+67&&my<top+155){
            int row=(my-top-67)/11;if(row<lines.size())g.renderTooltip(font,font.split(Component.literal(lines.get(row)),Math.min(340,width-24)),mx,my);
        }
    }
}
