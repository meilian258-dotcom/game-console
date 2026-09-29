package cn.piq.fcarcade.client;

import cn.piq.fcarcade.FcNetwork;
import cn.piq.fcarcade.access.PlayerContentPolicy;
import cn.piq.fcarcade.client.ui.DeviceUi;
import cn.piq.fcarcade.client.ui.DeviceLayout;
import cn.piq.fcarcade.rom.NesCompatibility;
import cn.piq.fcarcade.rom.RomCatalogEntry;
import cn.piq.fcarcade.rom.RomDescriptor;
import cn.piq.fcarcade.rom.RomSaveMode;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RomLibraryScreen extends cn.piq.fcarcade.client.ui.DeviceScreen {
    private static BlockPos rememberedBlockPos;
    private static int rememberedPage;
    private static String rememberedFocus = "";

    private final BlockPos blockPos;
    private final String selectedSha256;
    private final boolean leaderboardEnabled;
    private final int capabilities;
    private final List<Entry> entries;
    private int page, pageSize = 6, focused = -1;
    private DeviceLayout.Browser menu;
    private Component hoveredText;

    RomLibraryScreen(
            BlockPos blockPos,
            String selectedSha256,
            boolean leaderboardEnabled,
            List<RomDescriptor> localRoms,
            List<RomCatalogEntry> serverRoms, int capabilities
    ) {
        super(Component.translatable("screen.piq_fc_arcade.library_title"));
        CartridgeScreenCompat.prepare();
        this.blockPos = blockPos.immutable();
        this.selectedSha256 = selectedSha256;
        this.leaderboardEnabled = leaderboardEnabled;
        this.capabilities = capabilities;

        Map<String, EntryBuilder> merged = new LinkedHashMap<>();
        for (RomCatalogEntry server : serverRoms) {
            merged.computeIfAbsent(server.sha256(), ignored -> new EntryBuilder())
                    .server = server;
        }
        for (RomDescriptor local : localRoms) {
            merged.computeIfAbsent(local.sha256(), ignored -> new EntryBuilder())
                    .local = local;
        }
        entries = merged.values().stream()
                .map(EntryBuilder::build)
                .sorted(Comparator.comparing(
                        Entry::displayName,
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
        boolean sameMachine = rememberedBlockPos != null && rememberedBlockPos.equals(this.blockPos);
        page = sameMachine ? rememberedPage : 0;
        String focusSha = sameMachine ? rememberedFocus : selectedSha256;
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).sha256().equals(focusSha)) focused = i;
        rememberPage();
    }

    @Override
    protected void init() {
        clearWidgets();
        menu = DeviceLayout.browser(width, height, 2);
        var p = menu.panel();
        if (!menu.supported()) {
            button("返回", new DeviceLayout.Rect(p.x()+10,p.bottom()-28,p.width()-20,20),this::onClose,true,DeviceUi.Tone.QUIET);
            return;
        }
        int previousFirst = page * pageSize;
        pageSize = menu.rows();
        page = (focused >= 0 ? focused : previousFirst) / pageSize;
        page = FcMenuLayout.clampPage(page, entries.size(), pageSize);
        int start = page * pageSize, end = Math.min(entries.size(), start + pageSize);
        if (focused < start || focused >= end) focused = start;
        rememberPage();
        for (int index = start; index < end; index++) {
            int selected = index;Entry entry = entries.get(index);var r=menu.row(index-start);
            Button row=DeviceUi.row(font,entry.displayName(),sourceLabel(entry),
                    r.x(),r.y(),r.width(),r.height(),()->{focused=selected;rebuildWidgets();},
                    index==focused,entry.sha256().equals(selectedSha256),true);
            row.setTooltip(net.minecraft.client.gui.components.Tooltip.create(entryLabel(entry)));addRenderableWidget(row);
        }
        var t=menu.toolbar();
        button("ROM 目录",column(t,0,5,0),this::openRomFolder,true,DeviceUi.Tone.QUIET);
        button("刷新",column(t,1,5,0),()->{FcNetwork.requestLibrary(blockPos);onClose();},true,DeviceUi.Tone.QUIET);
        button("存档库",column(t,2,5,0),()->{FcNetwork.requestSaveCatalog(blockPos);onClose();},has(PlayerContentPolicy.ADMIN),DeviceUi.Tone.QUIET);
        button("设置",column(t,3,5,0),()->{FcNetwork.requestSettings(blockPos);onClose();},has(PlayerContentPolicy.ADMIN),DeviceUi.Tone.QUIET);
        boolean dedicatedAppearance=minecraft.level!=null&&minecraft.level.getBlockEntity(blockPos) instanceof cn.piq.fcarcade.world.DualCabinetBlockEntity;
        button(dedicatedAppearance?"配套外观":"外观",column(t,4,5,0),()->{FcNetwork.requestSkinLibrary(blockPos);onClose();},!dedicatedAppearance&&has(PlayerContentPolicy.ADMIN),DeviceUi.Tone.QUIET);
        Entry entry=focusedEntry();boolean stored=entry!=null&&entry.server()!=null&&has(PlayerContentPolicy.ADMIN);
        button(entry==null?"存档方式":Component.translatable(entry.saveMode().translationKey()).getString(),column(t,0,4,24),
                ()->FcNetwork.setRomSaveMode(blockPos,entry.sha256(),entry.saveMode().next()),stored,DeviceUi.Tone.NORMAL);
        button(entry!=null&&entry.maxPlayers()==2?"双人游戏":"单人游戏",column(t,1,4,24),
                ()->FcNetwork.setRomPlayerMode(blockPos,entry.sha256(),entry.maxPlayers()==2?1:2),stored,DeviceUi.Tone.NORMAL);
        button("重命名",column(t,2,4,24),()->rename(entry),stored,DeviceUi.Tone.NORMAL);
        button("删除…",column(t,3,4,24),()->confirmDelete(entry),stored,DeviceUi.Tone.DANGER);
        button(entry!=null&&entry.server()==null?"上传并使用":"使用所选游戏",menu.primary(),()->choose(entry),
                entry!=null&&entry.compatible()&&has(entry.server()!=null?PlayerContentPolicy.SERVER_ROM_USE:PlayerContentPolicy.ROM_UPLOAD),DeviceUi.Tone.PRIMARY);
        var n=menu.navigation();
        button("上一页",column(n,0,4,0),()->changePage(-1),page>0,DeviceUi.Tone.QUIET);
        button("下一页",column(n,1,4,0),()->changePage(1),(page+1)*pageSize<entries.size(),DeviceUi.Tone.QUIET);
        button(leaderboardEnabled?"排行：开":"排行：关",column(n,2,4,0),
                ()->{FcNetwork.setArcadeLeaderboardEnabled(blockPos,!leaderboardEnabled);onClose();},has(PlayerContentPolicy.ADMIN),DeviceUi.Tone.QUIET);
        button("返回",column(n,3,4,0),this::onClose,true,DeviceUi.Tone.QUIET);
    }

    private static DeviceLayout.Rect column(DeviceLayout.Rect rect,int index,int count,int dy){
        int cell=(rect.width()-4*(count-1))/count;
        return new DeviceLayout.Rect(rect.x()+index*(cell+4),rect.y()+dy,index==count-1?rect.width()-index*(cell+4):cell,20);
    }
    private void button(String label,DeviceLayout.Rect r,Runnable action,boolean active,DeviceUi.Tone tone){
        addRenderableWidget(DeviceUi.button(font,label,r.x(),r.y(),r.width(),r.height(),action,active,tone));
    }
    private Entry focusedEntry() { return focused >= 0 && focused < entries.size() ? entries.get(focused) : null; }
    private void changePage(int delta) { page += delta; focused = page * pageSize; rememberPage(); rebuildWidgets(); }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0,0,width,height,DeviceUi.BG);
        hoveredText = null;
        var panel=menu.panel();
        String current=entries.stream().filter(e->e.sha256().equals(selectedSha256)).map(Entry::displayName).findFirst().orElse("未装载游戏");
        DeviceUi.panel(graphics,font,panel.x(),panel.y(),panel.width(),panel.height(),"FC / 机柜游戏库","当前游戏 · "+current);
        if(!menu.supported()){
            line(graphics,Component.literal("请放大游戏窗口以显示完整菜单"),panel.x()+10,panel.y()+45,panel.width()-20,DeviceUi.MUTED,mouseX,mouseY);
        }else{
            var l=menu.list();var d=menu.details();var s=menu.status();
            DeviceUi.section(graphics,l.x(),l.y(),l.width(),l.height());DeviceUi.section(graphics,d.x(),d.y(),d.width(),d.height());
            Entry entry=focusedEntry();
            line(graphics,Component.literal(entry==null?"游戏库为空":entry.displayName()),d.x()+6,d.y()+6,d.width()-12,DeviceUi.TEXT,mouseX,mouseY);
            if(menu.split()&&entry!=null){
                line(graphics,entryLabel(entry),d.x()+6,d.y()+24,d.width()-12,DeviceUi.MUTED,mouseX,mouseY);
                if(d.height()>100)line(graphics,Component.literal("SHA "+entry.sha256().substring(0,12)),d.x()+6,d.y()+42,d.width()-12,DeviceUi.MUTED,mouseX,mouseY);
                if(d.height()>125)line(graphics,Component.literal("运行：本地输入同步"),d.x()+6,d.y()+62,d.width()-12,DeviceUi.MUTED,mouseX,mouseY);
            }
            String status=(page+1)+" / "+FcMenuLayout.pageCount(entries.size(),pageSize)+" 页 · "+
                    (entry!=null&&!entry.compatible()?NesCompatibility.unsupportedReason(entry.mapper()):entry!=null&&entry.server()==null?"本地文件 · 使用时上传到服务器":"选择条目后点击使用");
            DeviceUi.status(graphics,font,status,s.x(),s.y(),s.width(),false);
            if(mouseX>=s.x()&&mouseX<s.right()&&mouseY>=s.y()&&mouseY<s.bottom())
                hoveredText=Component.literal(status+"\n文件来源与运行模式是两回事。运行：本地输入同步。\n游戏选项仅作用于所选条目；删除需再次确认。");
        }
        super.render(graphics, mouseX, mouseY, partialTick);
        if (hoveredText != null) graphics.renderTooltip(font, hoveredText, mouseX, mouseY);
    }

    private void line(GuiGraphics graphics, Component text, int x, int y, int width, int color, int mouseX, int mouseY) {
        Component hovered = FcMenuUi.line(graphics, font, text, x, y, width, color, mouseX, mouseY);
        if (hovered != null) hoveredText = hovered;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void choose(Entry entry) {
        if (entry == null || !entry.compatible()) return;
        if(!has(entry.server()!=null?PlayerContentPolicy.SERVER_ROM_USE:PlayerContentPolicy.ROM_UPLOAD))return;
        ClientRomTransfers.select(blockPos, entry.sha256(), entry.server() != null);
    }
    private boolean has(int flag){return (capabilities&flag)!=0;}

    private void rememberPage() {
        rememberedBlockPos = blockPos;
        rememberedPage = page;
        if (focusedEntry() != null) rememberedFocus = focusedEntry().sha256();
    }

    private void confirmDelete(Entry entry) {
        if (minecraft == null || entry.server() == null) return;
        minecraft.setScreen(new FcRomDeleteScreen(
                confirmed -> {
                    if (confirmed) {
                        minecraft.setScreen(null);
                        FcNetwork.deleteRom(blockPos, entry.sha256());
                    } else {
                        minecraft.setScreen(this);
                    }
                },
                Component.translatable(
                        "screen.piq_fc_arcade.delete_rom_title"),
                Component.translatable(
                        "screen.piq_fc_arcade.delete_rom_message",
                        entry.displayName()),
                Component.translatable(
                        "screen.piq_fc_arcade.delete_rom_confirm"),
                Component.translatable("gui.cancel")));
    }

    private void rename(Entry entry) {
        if (minecraft == null || entry.server() == null) return;
        rememberPage();
        minecraft.setScreen(new RomRenameScreen(
                this,
                blockPos,
                entry.sha256(),
                entry.displayName()));
    }

    private Component entryLabel(Entry entry) {
        String marker = entry.sha256().equals(selectedSha256) ? "▶ " : "";
        MutableComponent label = Component.literal(marker + entry.displayName()
                + " · Mapper " + entry.mapper()
                + " · ");
        if (entry.local() != null && entry.server() != null) {
            label.append(Component.literal("本地文件 · 服务器已有副本").withStyle(ChatFormatting.GREEN));
        } else if (entry.local() != null) {
            label.append(Component.translatable(
                    "screen.piq_fc_arcade.location_local"))
                    .append(Component.literal(" · "))
                    .append(Component.translatable(
                            "screen.piq_fc_arcade.upload_game")
                            .withStyle(ChatFormatting.GOLD));
        } else {
            label.append(Component.translatable(
                    "screen.piq_fc_arcade.location_server"));
        }
        if (!entry.compatible()) {
            label.append(Component.literal(" · "))
                    .append(Component.literal(NesCompatibility.unsupportedReason(entry.mapper())));
        }
        return label;
    }

    private void openRomFolder() {
        ClientFcDirectories.openRomDirectory();
    }

    private static String sourceLabel(Entry entry) {
        return entry.local()!=null ? entry.server()!=null ? "本地 / 已共享" : "本地" : "服务器";
    }

    private record Entry(
            String sha256,
            String displayName,
            int mapper,
            int maxPlayers,
            RomSaveMode saveMode,
            boolean compatible,
            RomDescriptor local,
            RomCatalogEntry server
    ) {
    }

    private static final class EntryBuilder {
        private RomDescriptor local;
        private RomCatalogEntry server;

        private Entry build() {
            String sha256 = local != null ? local.sha256() : server.sha256();
            String displayName = server != null
                    ? server.fileName()
                    : local.fileName();
            int mapper = local != null ? local.header().mapper() : server.mapper();
            int maxPlayers = server == null ? 1 : server.maxPlayers();
            RomSaveMode saveMode =
                    server == null ? RomSaveMode.NONE : server.saveMode();
            boolean compatible = NesCompatibility.isMapperSupported(mapper);
            return new Entry(
                    sha256,
                    displayName,
                    mapper,
                    maxPlayers,
                    saveMode,
                    compatible,
                    local,
                    server);
        }
    }

}
