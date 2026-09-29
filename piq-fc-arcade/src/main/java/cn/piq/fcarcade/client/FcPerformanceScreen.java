package cn.piq.fcarcade.client;

import cn.piq.fcarcade.client.ui.DeviceScreen;
import cn.piq.fcarcade.client.ui.DeviceUi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class FcPerformanceScreen extends DeviceScreen {
    private final Screen parent;
    private final Object connection, level;
    private HomeSyncSettingsLayout layout;
    private boolean copied;
    private static final String NOTE = "近 1 秒实际模拟帧数；单帧耗时含核心调用与进程通信，不是 CPU 占用率。";
    private static final String QUEUE_NOTE = "待模拟是已收到的输入帧，不是网络延迟；音频待取不含声卡缓冲。私人模式开菜单会暂停，请用悬浮窗测试。";
    private FcPerformanceScreen(Screen parent) {
        super(Component.literal("FC 性能")); this.parent = parent;
        var mc = Minecraft.getInstance(); connection = mc.getConnection(); level = mc.level;
    }
    static void open(Screen parent) { open(parent, null); }
    static void open(Screen parent, net.minecraft.core.BlockPos target) {
        Minecraft.getInstance().setScreen(new FcPerformanceScreen(parent));
        FcPerformanceClient.refresh(); FcPerformanceClient.prefer(target);
    }
    @Override protected void init() {
        DeviceUi.prepare(); layout = HomeSyncSettingsLayout.fit(width, height);
        int x = layout.left(), y = layout.top(), w = layout.width();
        if (layout.compact()) {
            addRenderableWidget(DeviceUi.button(font,"返回",x,Math.max(35,height-32),w,20,this::onClose,true,DeviceUi.Tone.NORMAL)); return;
        }
        int half = (w - 26) / 2;
        addRenderableWidget(DeviceUi.button(font,"悬浮窗："+(FcPerformanceClient.hud()?"开":"关"),x+10,y+148,half,20,
                ()->{FcPerformanceClient.hud(!FcPerformanceClient.hud());rebuildWidgets();},true,DeviceUi.Tone.NORMAL));
        addRenderableWidget(DeviceUi.button(font,"切换设备",x+16+half,y+148,half,20,FcPerformanceClient::next,true,DeviceUi.Tone.NORMAL));
        int third = (w - 32) / 3;
        addRenderableWidget(DeviceUi.button(font,copied?"已复制":"复制数据",x+10,y+194,third,20,()->{
            minecraft.keyboardHandler.setClipboard("方块电玩 · FC 性能（本机）\n"+String.join("\n",FcPerformanceClient.lines())+"\n"+NOTE+"\n"+QUEUE_NOTE);
            copied=true; rebuildWidgets();},true,DeviceUi.Tone.NORMAL));
        addRenderableWidget(DeviceUi.button(font,"网络",x+16+third,y+194,third,20,()->NetworkDiagnosticsScreen.open(this),true,DeviceUi.Tone.NORMAL));
        addRenderableWidget(DeviceUi.button(font,"返回",x+22+2*third,y+194,third,20,this::onClose,true,DeviceUi.Tone.NORMAL));
    }
    @Override public void tick() {
        if (minecraft.getConnection()!=connection || minecraft.level!=level || level==null) minecraft.setScreen(null);
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(minecraft.level==level&&minecraft.getConnection()==connection?parent:null); FcPerformanceClient.refresh(); }
    @Override public void render(GuiGraphics g, int mx, int my, float partial) {
        int x=layout.left(),y=layout.top(),w=layout.width();
        g.fill(0,0,width,height,DeviceUi.BG);
        DeviceUi.panel(g,font,x,y,w,HomeSyncSettingsLayout.HEIGHT,"FC 性能","本机监控 · "+FcPerformanceClient.count()+" 个会话 · 每 0.25 秒刷新");
        if (layout.compact()) DeviceUi.text(g,font,"请降低 GUI 缩放或放大窗口。",x,y+45,w,DeviceUi.TEXT);
        else {
            var lines=FcPerformanceClient.lines();
            for(int i=0;i<Math.min(8,lines.size());i++) DeviceUi.text(g,font,lines.get(i),x+10,y+45+i*12,w-20,DeviceUi.TEXT);
            DeviceUi.text(g,font,"单帧含进程通信；不是 CPU 占用率。",x+10,y+174,w-20,DeviceUi.MUTED);
            DeviceUi.text(g,font,"私人模式请开悬浮窗，关闭菜单后测试。",x+10,y+183,w-20,DeviceUi.MUTED);
        }
        super.render(g,mx,my,partial);
        if(!layout.compact()&&mx>=x+10&&mx<x+w-10) {
            if(my>=y+174&&my<y+194) g.renderTooltip(font,Component.literal(NOTE+"\n"+QUEUE_NOTE),mx,my);
            else if(my>=y+45&&my<y+141) {
                var lines=FcPerformanceClient.lines();int i=(my-y-45)/12;
                if(i<lines.size())g.renderTooltip(font,Component.literal(lines.get(i)),mx,my);
            }
        }
    }
}
