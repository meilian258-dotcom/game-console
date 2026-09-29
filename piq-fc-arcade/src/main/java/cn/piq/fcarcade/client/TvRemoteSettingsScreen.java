package cn.piq.fcarcade.client;

import cn.piq.fcarcade.home.HomeTvBlockEntity;
import cn.piq.fcarcade.home.TvRemoteNetwork;
import cn.piq.fcarcade.home.TvRemoteSettingsPolicy;
import cn.piq.fcarcade.client.ui.DeviceUi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/** Acknowledged per-TV options, not a save-game or server configuration screen. */
public final class TvRemoteSettingsScreen extends cn.piq.fcarcade.client.ui.DeviceScreen {
    private final Connection connection;
    private TvRemoteNetwork.Setting setting;
    private String status;
    private boolean pending, timedOut;
    private int waitTicks, age, cooldown, left, top, panelWidth;
    private TvRemoteSettingsLayout layout;
    private TvRemoteSettingsScreen(TvRemoteNetwork.Setting setting, Connection connection) {
        super(Component.literal("遥控器 · 电视设置")); this.setting=setting; this.connection=connection; status=setting.reason();
    }
    @EventBusSubscriber(modid="piq_fc_arcade",bus=EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
    public static final class Setup {
        @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
            event.enqueueWork(() -> TvRemoteNetwork.clientSink(new TvRemoteNetwork.ClientSink() {
                public boolean accepts(Connection source) { var c=Minecraft.getInstance().getConnection(); return c!=null&&c.getConnection()==source; }
                public void setting(TvRemoteNetwork.Setting value) {
                    var mc=Minecraft.getInstance();
                    if(mc.level==null||mc.getConnection()==null||!mc.level.dimension().location().equals(value.dimension()))return;
                    if(value.open()) {
                        if(mc.screen==null)mc.setScreen(new TvRemoteSettingsScreen(value,mc.getConnection().getConnection()));
                    } else if(mc.screen instanceof TvRemoteSettingsScreen screen)screen.receive(value);
                }
            }));
        }
    }
    @Override protected void init() {
        DeviceUi.prepare(); layout=TvRemoteSettingsLayout.fit(width,height); panelWidth=layout.width(); left=layout.left(); top=layout.top();
        if(layout.compact()){
            addRenderableWidget(Button.builder(Component.literal("关闭"),b->onClose())
                .bounds(left,Math.max(30,height-28),panelWidth,20).build());return;
        }
        int x=left+10, half=(panelWidth-26)/2, right=x+half+6;
        toggle("扫描线",setting.scanlines(),TvRemoteSettingsPolicy.SCANLINES,x,top+TvRemoteSettingsLayout.DISPLAY_ROW,half,true);
        toggle("开关机动画",setting.animation(),TvRemoteSettingsPolicy.ANIMATION,right,top+TvRemoteSettingsLayout.DISPLAY_ROW,half,true);
        toggle("无信号提示音",setting.tone(),TvRemoteSettingsPolicy.NO_SIGNAL_TONE,x,top+TvRemoteSettingsLayout.SOUND_ROW,half,true);
        toggle("静音",setting.muted(),TvRemoteSettingsPolicy.MUTE,right,top+TvRemoteSettingsLayout.SOUND_ROW,half,true);
        button("−",x,top+TvRemoteSettingsLayout.VOLUME_ROW,30,()->apply(TvRemoteSettingsPolicy.VOLUME,Math.max(0,setting.volume()-10)),setting.volume()>0);
        button("+",left+panelWidth-40,top+TvRemoteSettingsLayout.VOLUME_ROW,30,()->apply(TvRemoteSettingsPolicy.VOLUME,Math.min(100,setting.volume()+10)),setting.volume()<100);
        button("个人键盘方案 / 实体手柄设置…",x,top+TvRemoteSettingsLayout.INPUT_ROW,panelWidth-20,
            ()->minecraft.setScreen(new cn.piq.retro.client.ControlSettingsScreen(this)),true);
        button("刷新",x,top+TvRemoteSettingsLayout.FOOTER_ROW,half,()->apply(TvRemoteSettingsPolicy.REFRESH,0),true);
        var close=Button.builder(Component.literal("关闭"),b->onClose()).bounds(right,top+TvRemoteSettingsLayout.FOOTER_ROW,half,20).build(); addRenderableWidget(close);
    }
    private Button button(String label,int x,int y,int w,Runnable run,boolean enabled) {
        var b=Button.builder(Component.literal(label),v->run.run()).bounds(x,y,w,20).build();
        b.active=enabled&&!pending&&!timedOut&&cooldown==0; addRenderableWidget(b); return b;
    }
    private Button toggle(String label,boolean value,int action,int x,int y,int w,boolean enabled) {
        return button(label+"："+(value?"开":"关"),x,y,w,()->apply(action,value?0:1),enabled);
    }
    private void apply(int action,int value) {
        if(pending||timedOut||cooldown>0||!current())return;
        pending=true;waitTicks=0;status="等待服务器确认…";
        TvRemoteNetwork.request(setting.token(),setting.revision(),action,value);rebuildWidgets();
    }
    private void receive(TvRemoteNetwork.Setting value) {
        if(!current()||!setting.token().equals(value.token())||!setting.hardware().equals(value.hardware())
            ||!setting.television().equals(value.television())||!setting.dimension().equals(value.dimension()))return;
        if(value.revision()<setting.revision())return;
        setting=value;status=value.reason();pending=false;timedOut=false;waitTicks=0;cooldown=4;rebuildWidgets();
    }
    private boolean current() {
        return minecraft!=null&&minecraft.getConnection()!=null&&minecraft.getConnection().getConnection()==connection&&connection.isConnected()
            &&minecraft.level!=null&&minecraft.level.dimension().location().equals(setting.dimension())&&minecraft.level.hasChunkAt(setting.television())
            &&minecraft.level.getBlockEntity(setting.television()) instanceof HomeTvBlockEntity tv&&tv.hardwareId().equals(setting.hardware());
    }
    @Override public void tick() {
        if(!current()||++age>1200){onClose();return;}
        if(cooldown>0&&--cooldown==0)rebuildWidgets();
        if(pending&&++waitTicks>100){pending=false;timedOut=true;status="未获确认，请关闭并重新瞄准电视打开。";rebuildWidgets();}
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){minecraft.setScreen(null);}
    @Override public void render(GuiGraphics g,int mx,int my,float partial) {
        g.fill(0,0,width,height,DeviceUi.BG);
        DeviceUi.panel(g,font,left,top,panelWidth,TvRemoteSettingsLayout.HEIGHT,"遥控器 · 电视设置",
            "目标电视 "+setting.television().getX()+", "+setting.television().getY()+", "+setting.television().getZ());
        if(layout.compact()){g.drawWordWrap(font,Component.literal("请降低GUI缩放或放大窗口。"),left,top+30,panelWidth,DeviceUi.TEXT);super.render(g,mx,my,partial);return;}
        g.drawCenteredString(font,"电视音量："+setting.volume()+"%"+(setting.muted()?"（静音）":""),width/2,top+TvRemoteSettingsLayout.VOLUME_ROW+6,DeviceUi.TEXT);
        var lines=font.split(Component.literal(status),panelWidth-20);
        for(int i=0;i<Math.min(3,lines.size());i++)g.drawString(font,lines.get(i),left+10,top+TvRemoteSettingsLayout.STATUS_ROW+i*9,DeviceUi.MUTED,false);
        super.render(g,mx,my,partial);
    }
}
