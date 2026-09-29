package cn.piq.fcarcade.client.cabinet;

import cn.piq.fcarcade.cabinet.*;
import cn.piq.fcarcade.FcNetwork;
import cn.piq.fcarcade.client.ui.DeviceLayout;
import cn.piq.fcarcade.client.ui.DeviceUi;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** The authoritative server catalog; selection is separate from the single-use choose action. */
final class CabinetMenuScreen extends cn.piq.fcarcade.client.ui.DeviceScreen {
    private final CabinetNetwork.Menu menu;
    private int page,age;
    private boolean sent;
    private final Object connection;
    private ResourceLocation focused;
    private CabinetMenuLayout.Layout layout;
    CabinetMenuScreen(CabinetNetwork.Menu menu){super(Component.literal("方块电玩 · 街机配置"));this.menu=menu;focused=menu.selected();connection=net.minecraft.client.Minecraft.getInstance().getConnection();}
    @Override protected void init(){
        DeviceUi.prepare();
        layout=CabinetMenuLayout.create(width,height,menu.entries().size(),page);
        page=layout.page();if(!layout.supported())return;
        var box=layout.browser();
        var toolbar=box.toolbar();
        var appearance=DeviceUi.button(font,menu.target().dual()?"专属模型外观":"机柜外观",toolbar.right()-88,toolbar.y(),88,20,
                this::openAppearance,!menu.target().dual()&&!sent&&minecraft.player!=null&&minecraft.player.hasPermissions(2),DeviceUi.Tone.NORMAL);
        if(menu.target().dual())appearance.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.literal("此机型仅支持配套外观；旧皮肤已保留。")));
        addRenderableWidget(appearance);
        addRenderableWidget(DeviceUi.button(font,"同步 / 网络",toolbar.x(),toolbar.y(),88,20,()->{
            if(!sent&&menu.selected()!=null&&minecraft.getConnection()==connection&&menu.target().matches(minecraft.level))
                minecraft.setScreen(new CabinetSyncSettingsScreen(this,menu.target(),menu.selected()));
        },!sent&&menu.selected()!=null,DeviceUi.Tone.NORMAL));
        addRenderableWidget(DeviceUi.button(font,"运行环境",toolbar.x()+92,toolbar.y(),toolbar.width()-184,20,()->{
            // Leave the expiring server menu instead of reusing its token after a long installation.
            onClose();minecraft.setScreen(new cn.piq.fcarcade.client.runtime.RuntimeEnvironmentScreen(null));
        },!sent,DeviceUi.Tone.NORMAL));
        for(int i=layout.start();i<layout.start()+layout.count();i++){
            var entry=menu.entries().get(i);var r=box.row(i-layout.start());
            addRenderableWidget(DeviceUi.row(font,entry.displayName(),entry.id().equals(menu.selected())?"当前":unavailable(entry)==null?"可用":"不可用",
                    r.x(),r.y(),r.width(),r.height(),()->{focused=entry.id();rebuildWidgets();},
                    entry.id().equals(focused),entry.id().equals(menu.selected()),true));
        }
        var entry=focusedEntry();var r=box.primary();
        addRenderableWidget(DeviceUi.button(font,"配置此模拟器的游戏",r.x(),r.y(),r.width(),r.height(),
                ()->choose(entry),entry!=null&&unavailable(entry)==null&&!sent,DeviceUi.Tone.PRIMARY));
        var n=box.navigation();int w=(n.width()-8)/3;
        addRenderableWidget(DeviceUi.button(font,"上一页",n.x(),n.y(),w,20,()->{page--;rebuildWidgets();},page>0,DeviceUi.Tone.QUIET));
        addRenderableWidget(DeviceUi.button(font,"返回",n.x()+w+4,n.y(),w,20,this::onClose,true,DeviceUi.Tone.QUIET));
        addRenderableWidget(DeviceUi.button(font,"下一页",n.x()+2*(w+4),n.y(),n.width()-2*(w+4),20,
                ()->{page++;rebuildWidgets();},layout.start()+layout.count()<menu.entries().size(),DeviceUi.Tone.QUIET));
    }
    private CabinetBackends.Entry focusedEntry(){return menu.entries().stream().filter(e->e.id().equals(focused)).findFirst().orElse(null);}
    private String unavailable(CabinetBackends.Entry entry){
        var provider=CabinetClientBackends.find(entry.id());
        // A server-hosted cabinet needs the addon UI but no client DLL. Runtime validation belongs
        // to openBackend after its authoritative Assignment; selecting a menu grants no core/input.
        String reason=entry.id().equals(CabinetBackends.NES)?null:provider==null?"客户端未安装":null;
        if(reason==null&&entry.localOnly()&&(minecraft.getSingleplayerServer()==null||minecraft.getSingleplayerServer().isPublished()))reason="仅本机世界可用";
        return reason;
    }
    private void choose(CabinetBackends.Entry entry){
        if(sent||age>=600||minecraft.screen!=this||minecraft.getConnection()!=connection||!menu.target().matches(minecraft.level)||entry==null||unavailable(entry)!=null)return;
        if(!CabinetClientBackends.configure(menu,entry.id()))return;
        sent=true;CabinetNetwork.send(new CabinetNetwork.Choose(menu.token(),entry.id()));minecraft.setScreen(null);
    }
    private void openAppearance(){
        if(menu.target().dual())return;
        if(sent||age>=600||minecraft.screen!=this||minecraft.getConnection()!=connection||!menu.target().matches(minecraft.level)
                ||minecraft.player==null||!minecraft.player.isAlive()||minecraft.player.isSpectator()||!minecraft.player.hasPermissions(2))return;
        var anchor=menu.target().anchor();
        if(minecraft.player.distanceToSqr(anchor.getX()+.5,anchor.getY()+.5,anchor.getZ()+.5)>64)return;
        // Appearance uses the existing server-authorized skin workflow, never a backend choice or lease.
        sent=true;onClose();FcNetwork.requestSkinLibrary(anchor);
    }
    @Override public void onClose(){CabinetClientBackends.cancelConfigure();super.onClose();}
    @Override public void removed(){if(!sent)CabinetClientBackends.cancelConfigure();}
    @Override public void tick(){if(++age>=600||minecraft.getConnection()!=connection||!menu.target().matches(minecraft.level)){CabinetClientBackends.notice("菜单已过期，请重新 Shift 空手右键机柜");onClose();}}
    @Override public void render(GuiGraphics g,int mx,int my,float partial){
        g.fill(0,0,width,height,DeviceUi.BG);var b=layout.browser();var p=b.panel();
        String current=menu.entries().stream().filter(e->e.id().equals(menu.selected())).map(CabinetBackends.Entry::displayName).findFirst().orElse("未选择");
        DeviceUi.panel(g,font,p.x(),p.y(),p.width(),p.height(),"ARCADE / 机柜配置",menu.gameInfo().isBlank()?"当前模拟器 · "+current:"游戏 · "+menu.gameInfo());
        if(b.supported()){
            var list=b.list();var d=b.details();var t=b.toolbar();var s=b.status();
            DeviceUi.section(g,list.x(),list.y(),list.width(),list.height());DeviceUi.section(g,d.x(),d.y(),d.width(),d.height());
            var entry=focusedEntry();String name=entry==null?"请选择模拟器":entry.displayName();
            DeviceUi.text(g,font,name,d.x()+6,d.y()+6,d.width()-12,DeviceUi.TEXT);
            if(b.split()&&entry!=null){
                CabinetUi.paragraph(g,font,entry.id().toString(),d.x()+6,d.y()+24,d.width()-12,2,DeviceUi.MUTED);
                int lines=Math.min(3,Math.max(0,(b.primary().y()-4-(d.y()+52))/10));
                String mode=entry.localOnly()?"本机单人 · 不支持公开 LAN":entry.id().equals(CabinetBackends.NES)?"FC 联机 · 开局允许后直接加入":CabinetBackends.maxPlayers(entry.id())>=4?"两席 / 两柜连线四席 · P1 同意后加入":CabinetBackends.maxPlayers(entry.id())>=2?"两席联机 · P1 同意后加入":CabinetBackends.maxPlayers(entry.id())==1?"服务器单人 · 附近玩家可旁观，不支持通讯线":"此后端未声明多人支持";
                if(CabinetBackends.hostSnapshotSync(entry.id()))mode="旧本地同步限两席；四席可用音画串流 / 配套 FBNeo Netplay。";
                if(lines>0)CabinetUi.paragraph(g,font,mode,d.x()+6,d.y()+52,d.width()-12,lines,DeviceUi.MUTED);
            }
            String reason=entry==null?"请选择左侧条目":unavailable(entry);
            DeviceUi.status(g,font,reason==null?"选择后应用到当前机柜 · 仅管理员可更换":reason,s.x(),s.y(),s.width(),false);
        }else DeviceUi.text(g,font,"请放大窗口或降低 GUI 缩放；Esc 返回",p.x()+10,p.y()+45,p.width()-20,DeviceUi.MUTED);
        super.render(g,mx,my,partial);
        if(mx>=p.x()&&mx<=p.x()+p.width()&&my>=p.y()&&my<p.y()+38&&!menu.gameInfo().isBlank())g.renderTooltip(font,Component.literal(menu.gameInfo()),mx,my);
    }
    @Override public boolean isPauseScreen(){return false;}
}
