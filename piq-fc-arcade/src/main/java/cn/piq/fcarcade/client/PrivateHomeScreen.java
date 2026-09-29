package cn.piq.fcarcade.client;

import cn.piq.fcarcade.client.ui.DeviceUi;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;
import cn.piq.retro.libretro.LibretroRuntimes;

/** Explicit local file selection; no server catalog, upload control or synchronized cartridge writes. */
final class PrivateHomeScreen extends cn.piq.fcarcade.client.ui.DeviceScreen {
    private PrivateHomeClient.Target target;
    private EditBox path;
    private String draft="", message="";
    // Resolve capability/platform before selecting JNI; old providers stay on their existing path.
    private LibretroRuntimes.Backend backend;
    private int left,top,w;
    PrivateHomeScreen(){super(Component.literal("家用机 · 仅自己玩"));target=PrivateHomeClient.find();backend=PrivateHomeClient.defaultBackend(target);}
    @Override protected void init(){
        if(path!=null)draft=path.getValue();
        w=Math.min(430,width-24);left=(width-w)/2;top=Math.max(8,(height-234)/2);
        if(width<320||height<246){addRenderableWidget(Button.builder(Component.literal("关闭"),b->onClose()).bounds(left,height-30,w,20).build());return;}
        path=new EditBox(font,left+10,top+104,w-20,20,Component.literal("本机 ROM 完整路径"));path.setMaxLength(2048);path.setValue(draft);path.setEditable(!PrivateHomeClient.active()&&!PrivateHomeClient.busy());addRenderableWidget(path);
        var start=Button.builder(Component.literal("仅在本机启动 / 续玩"),b->{
            String result=PrivateHomeClient.start(target,path.getValue(),backend);
            if(result==null)onClose();else message=result;
        }).bounds(left+10,top+132,(w-26)/2,20).build();
        start.active=target!=null&&!PrivateHomeClient.active()&&!PrivateHomeClient.busy();addRenderableWidget(start);
        var stop=Button.builder(Component.literal("结束并保存到本机"),b->{PrivateHomeClient.stop("已结束私人游戏");rebuildWidgets();})
                .bounds(left+16+(w-26)/2,top+132,(w-26)/2,20).build();stop.active=PrivateHomeClient.active();addRenderableWidget(stop);
        var runtime=Button.builder(Component.literal("本次私人启动："+(backend==LibretroRuntimes.Backend.JNI_TRIAL?"JNI（默认，独立档）":"独立进程（兼容）")),b->{
            if(backend==LibretroRuntimes.Backend.JNI_TRIAL){backend=LibretroRuntimes.Backend.PROCESS;message="已切回独立进程，使用原私人存档。";rebuildWidgets();return;}
            String unavailable=LibretroRuntimes.jniUnavailableReason();if(!unavailable.isEmpty()){message=unavailable;return;}
            var confirmedTarget=target;
            minecraft.setScreen(new ConfirmScreen(accepted->{
                if(accepted&&target==confirmedTarget){backend=LibretroRuntimes.Backend.JNI_TRIAL;message="仅本次私人启动，独立试验档；不会自动导入原进度。";}
                minecraft.setScreen(this);
            },Component.literal("恢复本次 JNI 私人运行？"),Component.literal("仅 Windows x64。原生故障可能让整个 Minecraft 崩溃，请先备份世界。继续使用 JNI 独立存档；同一客户端仅一个 JNI 会话，未安全退出时不会强杀或自动换后端。旧进程档保留，切换后端不转换进度。重新打开页面按已适配机型默认选择 JNI。")));
        }).bounds(left+10,top+158,w-20,20).build();
        runtime.active=target!=null&&target.provider().supportsJniTrial()&&!PrivateHomeClient.active()&&!PrivateHomeClient.busy();
        runtime.setTooltip(Tooltip.create(Component.literal("仅本次私人游戏；JNI 原生故障可能使整个 Minecraft 崩溃。旧进程存档保留，切换不迁移进度。")));
        addRenderableWidget(runtime);
        addRenderableWidget(Button.builder(Component.literal("重新识别手柄"),b->{target=PrivateHomeClient.find();backend=PrivateHomeClient.defaultBackend(target);message="";rebuildWidgets();}).bounds(left+10,top+204,(w-26)/2,20).build());
        addRenderableWidget(Button.builder(Component.literal("关闭 / 继续游戏"),b->onClose()).bounds(left+16+(w-26)/2,top+204,(w-26)/2,20).build());
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){minecraft.setScreen(null);}
    @Override public void render(GuiGraphics g,int mx,int my,float dt){
        g.fill(0,0,width,height,DeviceUi.BG);
        DeviceUi.panel(g,font,left,top,w,234,"家用机 · 仅自己玩",PrivateHomeClient.targetLabel(target));
        if(width<320||height<246){g.drawWordWrap(font,Component.literal("请放大窗口或降低 GUI 缩放。"),left,top+40,w,DeviceUi.TEXT);super.render(g,mx,my,dt);return;}
        g.drawWordWrap(font,Component.literal("仅自己可见，其他玩家不能加入。\n游戏数据和存档留在本机，不上传。\n手柄借还仍与服务器同步。"),left+10,top+42,w-20,DeviceUi.TEXT);
        g.drawString(font,"本机 ROM：粘贴完整路径",left+10,top+88,DeviceUi.MUTED,false);
        var status=Component.literal(message.isEmpty()?PrivateHomeClient.status():message);
        var lines=font.split(status,w-20);
        for(int i=0;i<Math.min(2,lines.size());i++)g.drawString(font,lines.get(i),left+10,top+182+10*i,DeviceUi.TEXT,false);
        super.render(g,mx,my,dt);
        if(mx>=left+10&&mx<left+w-10&&my>=top+182&&my<top+201)g.renderTooltip(font,status,mx,my);
    }
}
