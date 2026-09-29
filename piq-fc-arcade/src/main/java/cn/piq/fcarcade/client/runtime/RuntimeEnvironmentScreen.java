package cn.piq.fcarcade.client.runtime;

import cn.piq.fcarcade.client.ui.DeviceNotices;
import cn.piq.fcarcade.client.ui.DeviceScreen;
import cn.piq.fcarcade.client.ui.DeviceUi;
import cn.piq.fcarcade.runtime.RuntimeCatalog;
import cn.piq.fcarcade.runtime.RuntimeInstaller;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local-only diagnostics. No server token, ROM upload, core load or permission is acquired here. */
public final class RuntimeEnvironmentScreen extends DeviceScreen {
    private final Screen parent;
    private final Set<RuntimeCatalog.RuntimeId> required;
    private RuntimePanelLayout box;
    private RuntimeInstaller.Report report;
    private volatile RuntimeInstaller.Progress progress;
    private AtomicBoolean cancelled;
    private boolean busy, checked, installing, returnAfterCancel;

    public RuntimeEnvironmentScreen(Screen parent) {
        super(Component.literal("方块电玩 · 运行环境"));
        this.parent = parent;
        required = RuntimeEnvironmentClient.required();
    }

    @Override protected void init() {
        DeviceUi.prepare();box = RuntimePanelLayout.of(width, height);
        if (!box.supported()) {
            addRenderableWidget(Button.builder(Component.literal("返回"), b -> onClose())
                    .bounds(Math.max(0,width/2-40),Math.max(24,height-28),80,20).build());return;
        }
        int w = box.buttonWidth(), y = box.footer();
        button("重新检查",0,y,()->start(false),!busy);
        boolean installable = report != null && report.outcome() == RuntimeInstaller.Outcome.AVAILABLE;
        button(outsideWorld() ? "一键补齐" : "退出世界后安装",1,y,()->start(true),
                !busy && outsideWorld() && RuntimeInstaller.supportedPlatform() && installable)
                .setTooltip(Tooltip.create(Component.literal("街机 v4 helper 需 Native 0.1.1 内置来源，旧离线包不能补齐；GBA 仍用原离线包。只补缺失文件，不覆盖冲突文件。请先退出世界。ROM 与 BIOS 不在此安装。")));
        button("复制诊断",2,y,this::copyDiagnostics,true);
        button("游戏目录",0,y+24,()->Util.getPlatform().openFile(minecraft.gameDirectory),true);
        button("控制设置",1,y+24,()->minecraft.setScreen(new cn.piq.retro.client.ControlSettingsScreen(this)),!busy);
        button(busy ? "取消并返回" : "返回",2,y+24,this::onClose,true);
        if (!checked && !busy) start(false);
    }

    private Button button(String text,int col,int y,Runnable action,boolean enabled) {
        return addRenderableWidget(DeviceUi.button(font,text,box.buttonX(col),y,box.buttonWidth(),20,
                action,enabled,DeviceUi.Tone.NORMAL));
    }

    private void start(boolean install) {
        if (busy || install && !outsideWorld()) return;
        busy=true;installing=install;checked=true;progress=null;cancelled=new AtomicBoolean();var token=cancelled;
        var mc=minecraft;var root=mc.gameDirectory.toPath();
        // A daemon never blocks client shutdown; the installer owns rollback and only its own staging files.
        Thread worker=new Thread(()->{
            RuntimeInstaller.Report result=null;Throwable failed=null;
            try {
                var service=new RuntimeInstaller(root);
                result=install ? service.install(required,token::get,p->progress=p)
                        : service.inspect(required,token::get,p->progress=p);
            } catch (Exception ex) { failed=ex; }
            var completed=result;var error=failed;
            mc.execute(()->{
                if(token!=cancelled)return;
                busy=false;installing=false;report=completed;
                if(install&&completed!=null)cn.piq.fcarcade.runtime.RuntimeStartupState.recordReport(root,completed);
                if(error!=null)DeviceNotices.record("运行环境","运行环境检查失败",error);
                if(returnAfterCancel&&mc.screen==this){mc.setScreen(parent);return;}
                if(mc.screen==this)rebuildWidgets();
            });
        },"PIQ runtime environment");worker.setDaemon(true);worker.start();
        rebuildWidgets();
    }

    private String status(RuntimeCatalog.RuntimeId id) {
        if(!required.contains(id))return "未安装此附属模组";
        if(report==null)return busy?"检查中…":"点击重新检查";
        return report.runtimes().stream().filter(r->r.id()==id).map(r->switch(r.state()) {
            case READY -> "已就绪"; case MISSING -> "缺少文件";
            case CONFLICT -> "已有不同版本 · 不覆盖"; case UNSAFE -> "路径检查未通过";
        })
                .findFirst().orElse("未检查");
    }

    private String packStatus() {
        if(required.isEmpty())return "安装来源：当前模组不需要";
        if(report!=null&&report.pack()!=RuntimeInstaller.PackState.AVAILABLE&&report.runtimes().stream().flatMap(r->r.files().stream())
                .anyMatch(f->f.relativePath().equals("piq-native-arcade/runtime/piq-native-helper-v4.jar")&&f.state()==RuntimeInstaller.FileState.MISSING))
            return "缺少 v4 helper：请安装配套 Native 0.1.1；旧离线包不能补齐";
        boolean embedded = required.stream().anyMatch(RuntimeInstaller.bundledRuntimeIds()::contains);
        String source = embedded ? "内置资源 / 离线包：" : "离线包：";
        if(report==null)return source+(embedded ? "街机随 JAR 提供；GBA 使用离线包" : "game-console/piq-runtime-packs 文件夹");
        return source+switch(report.pack()) {
            case AVAILABLE -> "已找到（安装时逐项校验）";
            case MISSING -> "未内置的组件需要配套离线包";
            case INVALID -> "来源损坏或版本不匹配 · 查看诊断";
            case UNSAFE -> "路径不安全 · 不会安装";
        };
    }

    private String summary() {
        if(returnAfterCancel&&busy)return "正在取消并清理，请稍候…";
        var p=progress;
        if(busy)return p==null?"正在检查…":(p.totalBytes()>0?Math.min(100,p.completedBytes()*100/p.totalBytes())+"% · ":"")+p.message();
        return report==null?"检查未完成，请复制诊断查看原因":report.summary();
    }

    private void line(GuiGraphics g,String text,int index,int mx,int my) {
        int y=box.row(index);DeviceUi.text(g,font,text,box.innerX(),y,box.innerWidth(),DeviceUi.TEXT);
        if(mx>=box.innerX()&&mx<box.innerX()+box.innerWidth()&&my>=y&&my<y+11)
            g.renderTooltip(font,font.split(Component.literal(text),Math.max(100,Math.min(360,width-32))),mx,my);
    }

    @Override public void render(GuiGraphics g,int mx,int my,float partial) {
        g.fill(0,0,width,height,DeviceUi.BG);
        DeviceUi.panel(g,font,box.x(),box.y(),box.width(),box.height(),title.getString(),"本机检查 · 不包含游戏和 BIOS · 不改服务器");
        if(box.supported()) {
            line(g,"FC / SFC：核心随各自模组内置",0,mx,my);
            line(g,"街机媒体模式："+status(RuntimeCatalog.RuntimeId.MAME),1,mx,my);
            line(g,"街机本地输入同步："+status(RuntimeCatalog.RuntimeId.NEOGEO_SNAPSHOT),2,mx,my);
            line(g,"GBA："+status(RuntimeCatalog.RuntimeId.GBA),3,mx,my);
            line(g,packStatus(),4,mx,my);line(g,summary(),5,mx,my);
            var error=DeviceNotices.last();
            line(g,error==null?"异常详情与文件路径可点「复制诊断」": "最近异常："+error.summary()+" · 可复制诊断",6,mx,my);
        }else DeviceUi.text(g,font,"请放大窗口或降低 GUI 缩放",box.innerX(),box.y()+40,box.innerWidth(),DeviceUi.TEXT);
        super.render(g,mx,my,partial);
    }

    private void copyDiagnostics() {
        StringBuilder text=new StringBuilder("方块电玩 · 运行环境诊断\n");
        text.append("游戏目录：").append(minecraft.gameDirectory.toPath().toAbsolutePath()).append('\n');
        text.append("系统：").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.arch")).append('\n');
        text.append("Java：").append(System.getProperty("java.version")).append('\n');
        text.append("离线包：game-console/piq-runtime-packs/piq-runtime-pack-v1.zip\n");
        text.append("组件相对路径基准：游戏目录/game-console\n");
        text.append("街机 v4 helper：仅从 Native 0.1.1 内置来源补缺；原离线包继续用于原库/GBA，不提供新 helper。\n");
        text.append("JAR 内置组件：").append(RuntimeInstaller.bundledRuntimeIds()).append('\n');
        cn.piq.fcarcade.runtime.RuntimeStartupState.report(minecraft.gameDirectory.toPath()).ifPresent(startup->{
            text.append("最近启动/安装结果：").append(startup.summary()).append('\n');
            for(String detail:startup.details())text.append(detail).append('\n');
        });
        if(report!=null) {
            text.append(report.summary()).append('\n');
            for(var runtime:report.runtimes()) {
                text.append(runtime.id()).append(": ").append(runtime.summary()).append('\n');
                for(var file:runtime.files())text.append(file.relativePath()).append(" [").append(file.state()).append("] ").append(file.detail()).append('\n');
            }
            for(String detail:report.details())text.append(detail).append('\n');
        }
        for(var error:DeviceNotices.snapshot())text.append('\n').append(error.time()).append(' ').append(error.device())
                .append('\n').append(error.detail()).append('\n');
        minecraft.keyboardHandler.setClipboard(text.toString());
    }
    private boolean outsideWorld(){return minecraft.level==null&&minecraft.getConnection()==null&&minecraft.getSingleplayerServer()==null;}
    @Override public void removed(){if(cancelled!=null)cancelled.set(true);}
    @Override public void onClose(){
        if(cancelled!=null)cancelled.set(true);
        // Keep the title-screen navigation inaccessible until installer rollback/cleanup has finished.
        if(busy&&installing){returnAfterCancel=true;return;}
        minecraft.setScreen(parent);
    }
    @Override public boolean isPauseScreen(){return false;}
}
