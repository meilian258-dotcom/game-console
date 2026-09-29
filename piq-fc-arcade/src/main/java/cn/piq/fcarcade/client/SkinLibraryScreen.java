package cn.piq.fcarcade.client;

import cn.piq.fcarcade.FcNetwork;
import cn.piq.fcarcade.SkinLibraryPayload;
import cn.piq.fcarcade.skin.SkinDescriptor;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import cn.piq.fcarcade.client.ui.DeviceUi;
import cn.piq.fcarcade.client.ui.DeviceLayout;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

final class SkinLibraryScreen extends cn.piq.fcarcade.client.ui.DeviceScreen {

    private final BlockPos blockPos;
    private final String selectedSha256;
    private final List<Entry> entries = new ArrayList<>();
    private int page,focused;
    private DeviceLayout.Browser layout;

    SkinLibraryScreen(
            SkinLibraryPayload payload,
            List<ClientSkinLibrary.LocalSkin> localSkins
    ) {
        super(Component.translatable(
                "screen.piq_fc_arcade.skin_library_title"));
        blockPos = payload.blockPos();
        selectedSha256 = payload.selectedSha256();
        entries.add(Entry.builtin());
        payload.serverSkins().forEach(
                descriptor -> entries.add(Entry.server(descriptor)));
        localSkins.forEach(local -> entries.add(Entry.local(local)));
    }

    @Override protected void init(){
        clearWidgets();layout=DeviceLayout.browser(width,height,1);if(!layout.supported())return;
        int rows=layout.rows();page=FcMenuLayout.clampPage(page,entries.size(),rows);focused=Math.max(0,Math.min(focused,entries.size()-1));
        var t=layout.toolbar();int half=(t.width()-6)/2;
        addRenderableWidget(DeviceUi.button(font,"打开皮肤文件夹",t.x(),t.y(),half,20,this::openFolder,true,DeviceUi.Tone.QUIET));
        addRenderableWidget(DeviceUi.button(font,"刷新列表",t.x()+half+6,t.y(),t.width()-half-6,20,()->{FcNetwork.requestSkinLibrary(blockPos);onClose();},true,DeviceUi.Tone.QUIET));
        for(int i=page*rows;i<Math.min(entries.size(),(page+1)*rows);i++){
            final int index=i;var entry=entries.get(i);var r=layout.row(i-page*rows);
            boolean current=(entry.defaultSkin||entry.server!=null)&&entry.sha256.equals(selectedSha256);
            var row=DeviceUi.row(font,entry.name,current?"当前":entry.defaultSkin?"内置":entry.server!=null?"服务器":"本地",
                    r.x(),r.y(),r.width(),r.height(),()->{focused=index;rebuildWidgets();},i==focused,current,true);
            row.setTooltip(net.minecraft.client.gui.components.Tooltip.create(label(entry)));addRenderableWidget(row);
        }
        var r=layout.primary();var entry=entries.get(focused);
        addRenderableWidget(DeviceUi.button(font,dedicatedAppearance()?"仅支持配套外观":entry.local!=null?"上传并应用外观":"应用所选外观",r.x(),r.y(),r.width(),r.height(),
                ()->choose(entry),!dedicatedAppearance()&&entry.compatible(),DeviceUi.Tone.PRIMARY));
        var nav=layout.navigation();int col=(nav.width()-8)/3;
        addRenderableWidget(DeviceUi.button(font,"上一页",nav.x(),nav.y(),col,20,()->{page--;rebuildWidgets();},page>0,DeviceUi.Tone.QUIET));
        addRenderableWidget(DeviceUi.button(font,"下一页",nav.x()+col+4,nav.y(),col,20,()->{page++;rebuildWidgets();},(page+1)*rows<entries.size(),DeviceUi.Tone.QUIET));
        addRenderableWidget(DeviceUi.button(font,"返回",nav.x()+2*(col+4),nav.y(),nav.width()-2*(col+4),20,this::onClose,true,DeviceUi.Tone.QUIET));
    }

    @Override public void render(GuiGraphics g,int mx,int my,float partial){
        g.fill(0,0,width,height,DeviceUi.BG);var p=layout.panel();
        boolean dedicated=dedicatedAppearance();
        String current=entries.stream().filter(e->(e.defaultSkin||e.server!=null)&&e.sha256.equals(selectedSha256)).map(Entry::name).findFirst().orElse("未知");
        DeviceUi.panel(g,font,p.x(),p.y(),p.width(),p.height(),"FC / 机柜外观",dedicated?"双人街机 · 配套外观":"当前外观 · "+current);
        if(layout.supported()){
            var l=layout.list();var d=layout.details();var s=layout.status();
            DeviceUi.section(g,l.x(),l.y(),l.width(),l.height());DeviceUi.section(g,d.x(),d.y(),d.width(),d.height());
            var entry=entries.get(focused);DeviceUi.text(g,font,dedicated?"当前：配套默认贴图":entry.name,d.x()+6,d.y()+6,d.width()-12,DeviceUi.TEXT);
            if(layout.split()){
                DeviceUi.text(g,font,dedicated?"此机型不支持自定义皮肤":entry.defaultSkin?"内置默认外观":entry.server!=null?"服务器已有 · 直接应用":"本地外观 · 首次需要上传",d.x()+6,d.y()+24,d.width()-12,DeviceUi.MUTED);
                if(d.height()>112)DeviceUi.text(g,font,dedicated?"旧皮肤数据已保留":"选好后点击应用",d.x()+6,d.y()+52,d.width()-12,DeviceUi.MUTED);
            }
            DeviceUi.status(g,font,(page+1)+" / "+FcMenuLayout.pageCount(entries.size(),layout.rows())+" 页 · "+(dedicated?"仅支持配套外观":entry.compatible()?"确认后点击应用":"此皮肤格式不兼容"),s.x(),s.y(),s.width(),false);
        }else DeviceUi.text(g,font,"请放大窗口或降低 GUI 缩放；Esc 返回",p.x()+10,p.y()+44,p.width()-20,DeviceUi.MUTED);
        super.render(g,mx,my,partial);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private Component label(Entry entry) {
        boolean selected = (entry.defaultSkin || entry.server != null) && entry.sha256.equals(selectedSha256);
        String marker = selected ? "▶ " : "";
        if (!entry.compatible()) {
            return Component.literal(marker + entry.name + " · ").append(Component.translatable(
                    "screen.piq_fc_arcade.skin_incompatible")).withStyle(ChatFormatting.GRAY);
        }
        Component location;
        if (entry.defaultSkin) {
            location = Component.translatable(
                    "screen.piq_fc_arcade.skin_builtin");
        } else if (entry.server != null) {
            location = Component.translatable(
                    "screen.piq_fc_arcade.location_server")
                    .copy().withStyle(ChatFormatting.GREEN);
        } else {
            location = Component.translatable(
                    "screen.piq_fc_arcade.skin_local_upload")
                    .copy().withStyle(ChatFormatting.GOLD);
        }
        return Component.literal(marker + entry.name + " · ").append(location);
    }

    private void choose(Entry entry) {
        if (dedicatedAppearance() || !entry.compatible()) return;
        if (entry.defaultSkin) {
            FcNetwork.selectSkin(blockPos, "");
            onClose();
        } else if (entry.server != null) {
            FcNetwork.selectSkin(blockPos, entry.server.sha256());
            onClose();
        } else if (entry.local != null) {
            ClientSkinManager.upload(blockPos, entry.local);
        }
    }

    private boolean dedicatedAppearance() {
        return minecraft != null && minecraft.level != null
                && minecraft.level.getBlockEntity(blockPos) instanceof cn.piq.fcarcade.world.DualCabinetBlockEntity;
    }

    private void openFolder() {
        try {
            Files.createDirectories(ClientSkinLibrary.root());
            ClientSkinLibrary.ensureTemplate();
            Util.getPlatform().openFile(ClientSkinLibrary.root().toFile());
        } catch (IOException error) {
            if (minecraft != null) {
                minecraft.gui.setOverlayMessage(
                        Component.translatable(
                                "message.piq_fc_arcade.open_folder_failed",
                                error.getMessage()),
                        false);
            }
        }
    }

    private record Entry(
            String name,
            String sha256,
            boolean defaultSkin,
            SkinDescriptor server,
            ClientSkinLibrary.LocalSkin local
    ) {
        private boolean compatible() {
            return defaultSkin || (server != null && server.compatible()) || (local != null && local.compatible());
        }
        private static Entry builtin() {
            return new Entry(
                    Component.translatable(
                            "screen.piq_fc_arcade.skin_default").getString(),
                    "",
                    true,
                    null,
                    null);
        }

        private static Entry server(SkinDescriptor descriptor) {
            return new Entry(
                    descriptor.name(),
                    descriptor.sha256(),
                    false,
                    descriptor,
                    null);
        }

        private static Entry local(ClientSkinLibrary.LocalSkin local) {
            return new Entry(
                    local.name(),
                    "",
                    false,
                    null,
                    local);
        }
    }
}
