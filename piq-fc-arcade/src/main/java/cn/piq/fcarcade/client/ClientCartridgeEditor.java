package cn.piq.fcarcade.client;

import cn.piq.fcarcade.home.*;
import cn.piq.fcarcade.access.PlayerContentPolicy;
import cn.piq.fcarcade.client.ui.DeviceUi;
import cn.piq.fcarcade.client.ui.DeviceLayout;
import cn.piq.fcarcade.client.ui.CartridgeWorkbenchLayout;
import cn.piq.fcarcade.client.ui.CartridgeSaveSettingsLayout;
import cn.piq.fcarcade.rom.NesCompatibility;
import cn.piq.fcarcade.rom.RomCatalogEntry;
import cn.piq.fcarcade.rom.RomDescriptor;
import cn.piq.fcarcade.rom.RomRepository;
import cn.piq.fcarcade.rom.RomSaveMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A single non-pausing, non-blurring screen. No file enumeration, read or decode in init/render/tick. */
public final class ClientCartridgeEditor extends cn.piq.fcarcade.client.ui.DeviceScreen {
    private static final Minecraft MC = Minecraft.getInstance();
    private static boolean registered;
    private static final FcMenuState.DismissedTokens DISMISSED = new FcMenuState.DismissedTokens();
    private final CartridgeEditBinding target;
    private final String originalCover;
    private final Object connection;
    private List<RomCatalogEntry> serverRoms;
    private List<LocalRom> localRoms = List.of();
    private List<LocalCover> localCovers = List.of();
    private List<String> serverCovers = List.of();
    private List<Entry> entries = List.of();
    private List<Entry> filteredRoms = List.of();
    private List<LocalCover> filteredCovers = List.of();
    private String query = "";
    private String romSha, coverSha, draftTitle, status = "正在后台扫描本地游戏库…";
    private EditBox titleBox, searchBox;
    private DeviceLayout.Browser menu;
    private CartridgeWorkbenchLayout workbench;
    private Component hoveredText;
    private String chosenRomSha = "", chosenCoverFile = "", currentCardTitle = "";
    private boolean titleDirty;
    private RomSaveMode cardSaveMode=RomSaveMode.NONE;
    private final FcMenuState.PageAnchor romPage = new FcMenuState.PageAnchor();
    private final FcMenuState.PageAnchor coverPage = new FcMenuState.PageAnchor();
    private int page, pageSize = 4, capabilities, pendingPermission;
    private boolean coversTab, scanning, busy, saveSettings;
    private volatile boolean closed;
    private volatile int revision;
    private Upload upload;
    private long activity;

    private ClientCartridgeEditor(CartridgeNetwork.Reply reply) {
        super(Component.literal("FC / 卡带工作台"));
        target = reply.target(); connection = MC.getConnection(); serverRoms = reply.catalog(); serverCovers = reply.covers();
        capabilities = reply.capabilities();
        cardSaveMode=reply.cardSaveMode();
        romSha = reply.romSha(); coverSha = reply.coverSha(); originalCover = coverSha; draftTitle = reply.title(); currentCardTitle = reply.title(); chosenRomSha = romSha;
    }
    public static void register() {
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.addListener(ClientCartridgeEditor::clientTick);
        NeoForge.EVENT_BUS.addListener(ClientCartridgeEditor::logout);
    }
    private static void clientTick(ClientTickEvent.Post ignored) { ClientCartridgeCovers.update(); }
    private static void logout(ClientPlayerNetworkEvent.LoggingOut ignored) {
        if (MC.screen instanceof ClientCartridgeEditor editor) editor.cancel();
        ClientCartridgeCovers.clearOnDisconnect();
    }
    public static void receive(CartridgeNetwork.Reply reply) {
        if (MC.getConnection() == null || MC.player == null) return;
        if (reply.operation() == CartridgeNetwork.OPEN) {
            if ((reply.capabilities() & PlayerContentPolicy.BROWSE) == 0) {
                CartridgeNetwork.send(CartridgeNetwork.request(CartridgeNetwork.CANCEL, reply.target()));
                if (MC.screen instanceof ClientCartridgeEditor editor && editor.target.equals(reply.target())) editor.onClose();
                return;
            }
            if (DISMISSED.contains(reply.target().token())) {
                CartridgeNetwork.send(CartridgeNetwork.request(CartridgeNetwork.CANCEL, reply.target()));
                return;
            }
            if (MC.screen instanceof ClientCartridgeEditor editor && editor.target.equals(reply.target()) && !editor.closed) {
                if (!editor.current()) { editor.onClose(); return; }
                editor.updateCapabilities(reply.capabilities());
                editor.cardSaveMode=reply.cardSaveMode();
                editor.serverRoms = reply.catalog(); editor.serverCovers = reply.covers(); editor.romSha = reply.romSha(); editor.coverSha = reply.coverSha();
                editor.currentCardTitle = reply.title(); editor.mergeEntries(); editor.status = reply.message(); editor.rebuildWidgets(); return;
            }
            ItemStack held = MC.player.getItemInHand(reply.target().hand() == 0 ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND);
            int actualSlot = reply.target().hand() == 0 ? MC.player.getInventory().selected : 40;
            if (!FcCartridgeData.isCartridge(held) || !DISMISSED.permitsOpen(reply.target().token(),
                    reply.target().cartridgeId(), reply.target().slot(), FcCartridgeData.id(held), actualSlot, MC.player.isAlive())) {
                DISMISSED.dismiss(reply.target().token());
                CartridgeNetwork.send(CartridgeNetwork.request(CartridgeNetwork.CANCEL, reply.target())); return;
            }
            CartridgeScreenCompat.prepare();
            ClientCartridgeEditor editor = new ClientCartridgeEditor(reply);
            MC.setScreen(editor); editor.scanLocal(); return;
        }
        if (!(MC.screen instanceof ClientCartridgeEditor editor) || !editor.target.equals(reply.target()) || editor.closed) return;
        editor.updateCapabilities(reply.capabilities());
        editor.cardSaveMode=reply.cardSaveMode();
        editor.serverCovers = reply.covers();
        if (reply.operation() == CartridgeNetwork.CLOSED) {
            editor.status = reply.message(); editor.cancel(); MC.setScreen(null);
            MC.gui.setOverlayMessage(Component.literal(reply.message()), false); return;
        }
        if (reply.operation() == CartridgeNetwork.UPLOAD_READY) {
            if (editor.upload != null && editor.upload.hash.equals(reply.hash())) {
                editor.upload.ready = true; editor.activity = System.nanoTime();
            }
            return;
        }
        editor.romSha = reply.romSha(); editor.coverSha = reply.coverSha(); editor.currentCardTitle = reply.title(); editor.status = reply.message();
        editor.serverCovers = reply.covers();
        if (!reply.catalog().isEmpty()) {
            editor.serverRoms = reply.catalog(); editor.mergeEntries();
        }
        editor.busy = false; editor.upload = null; editor.pendingPermission = 0; editor.rebuildWidgets();
    }
    private boolean has(int permission) { return (capabilities & permission) != 0; }
    private void updateCapabilities(int value) {
        capabilities = value;
        if (pendingPermission != 0 && !has(pendingPermission)) {
            revision++; pendingPermission = 0; upload = null; busy = false;
            status = "上传权限已关闭，本次操作已取消；卡带原内容保留。";
        }
    }
    @Override protected void init() {
        if (titleBox != null) draftTitle = titleBox.getValue();
        if (searchBox != null) query = searchBox.getValue();
        clearWidgets();
        filteredRoms = entries.stream().filter(entry -> Search.matches(entry.name, query)).toList();
        filteredCovers = coverEntries().stream().filter(cover -> Search.matches(cover.fileName, query)).toList();
        menu = DeviceLayout.browser(width, height, 2);
        var panel = menu.panel();
        if (!menu.supported()) {
            button("关闭", new DeviceLayout.Rect(panel.x(), panel.bottom()-20, panel.width(),20), this::onClose,true);
            return;
        }
        if (saveSettings) {
            // Keep the authorized editor session alive; replacing Screen would cancel it.
            titleBox=null;searchBox=null;
            var layout=new CartridgeSaveSettingsLayout(panel);
            RomCatalogEntry game=currentGame();
            RomSaveMode[] modes={RomSaveMode.NONE,RomSaveMode.MACHINE,RomSaveMode.PLAYER};
            for(int i=0;i<modes.length;i++){
                RomSaveMode mode=modes[i];
                boolean selected=cardSaveMode==mode;
                button(saveModeName(mode)+(selected?" · 当前":""),layout.choice(i),()->setSaveMode(mode),
                        saveModeEditable()&&!selected);
            }
            button("返回卡带工作台",layout.back(),()->{saveSettings=false;rebuildWidgets();},true);
            return;
        }
        workbench = CartridgeWorkbenchLayout.of(menu);
        var name = workbench.name();
        titleBox = new EditBox(font,name.x(),name.y(),name.width(),name.height(),Component.literal("卡带名称"));
        titleBox.setMaxLength(CartridgeLimits.MAX_TITLE);titleBox.setValue(draftTitle);titleBox.setEditable(!busy&&has(PlayerContentPolicy.BROWSE));
        titleBox.setHint(Component.literal("卡带名称"));
        titleBox.setResponder(value->{draftTitle=value;titleDirty=true;});addRenderableWidget(titleBox);
        button("保存名称",workbench.saveName(),()->write(romSha,coverSha),!busy&&has(PlayerContentPolicy.BROWSE));
        RomCatalogEntry game=currentGame();
        Button players=button(game==null?"人数：—":game.maxPlayers()==2?"人数：双人":"人数：单人",
            workbench.players(),this::togglePlayers,!busy && !scanning && game != null && has(PlayerContentPolicy.ADMIN));
        players.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("设置当前卡带的游戏人数。需 OP；同一游戏共用，下次开机生效。")));
        button(coversTab?"游戏库":"> 游戏库",workbench.gamesTab(),()->{coversTab=false;rebuildWidgets();},!busy&&coversTab);
        button(coversTab?"> 封面":"封面",workbench.coversTab(),()->{coversTab=true;rebuildWidgets();},!busy&&!coversTab);
        var search = workbench.search();
        searchBox = new EditBox(font,search.x(),search.y(),search.width(),search.height(),Component.literal("搜索名称"));
        searchBox.setMaxLength(128);searchBox.setValue(query);searchBox.setHint(Component.literal("搜索名称…"));searchBox.setEditable(!busy);
        searchBox.setResponder(value->{query=value;pageAnchor().reset();rebuildWidgets();});addRenderableWidget(searchBox);
        button("刷新",workbench.refresh(),this::refreshLibrary,!busy&&!scanning);
        pageSize=menu.rows();int count=coversTab?filteredCovers.size():filteredRoms.size();
        page = pageAnchor().page(pageKeys(), pageSize);
        for(int i=page*pageSize;i<Math.min(count,(page+1)*pageSize);i++){
            var row=menu.row(i-page*pageSize);
            if(coversTab){
                LocalCover cover=filteredCovers.get(i);
                addRenderableWidget(DeviceUi.row(font,cover.fileName,cover.server()?"服务器":"本地",row.x(),row.y(),row.width(),row.height(),
                    ()->{coverPage.focus(cover.fileName);chosenCoverFile=cover.fileName;rebuildWidgets();},
                    cover.fileName.equals(chosenCoverFile),false,!busy&&!scanning));
            }else{
                Entry entry=filteredRoms.get(i);
                String badge=entry.local!=null?(entry.server?"本地 / 已共享":"仅本地"):"服务器";
                if(!NesCompatibility.isMapperSupported(entry.mapper))badge="不支持 · M"+entry.mapper;
                addRenderableWidget(DeviceUi.row(font,entry.name,badge,row.x(),row.y(),row.width(),row.height(),
                    ()->preview(entry),entry.hash.equals(chosenRomSha),entry.hash.equals(romSha),!busy&&!scanning));
            }
        }
        if(coversTab){
            button("清空",workbench.clearCover(),()->writeCover(""),!busy&&!coverSha.isEmpty());
            button("恢复",workbench.restoreCover(),()->writeCover(originalCover),!busy&&!originalCover.isEmpty()&&!originalCover.equals(coverSha));
        }else{
            var left=workbench.clearCover();var right=workbench.restoreCover();
            left=new DeviceLayout.Rect(left.x(),left.y(),left.width()+4,left.height());
            right=new DeviceLayout.Rect(right.x()+4,right.y(),right.width()-4,right.height());
            Button saveMode=button("存档设置",left,()->{saveSettings=true;status="仅设置这张卡带，已有进度保留。";rebuildWidgets();},saveModeEditable());
            Button saveLibrary=button("存档库",right,()->CartridgeSaveScreen.open("fc",target.token(),target.cartridgeId(),target.hand(),target.slot(),romSha),!busy&&!scanning&&!romSha.isEmpty());
            saveLibrary.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("查看、重命名或删除存档。")));
            saveMode.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
                    game==null?"请先写入游戏":("当前："+saveModeName(cardSaveMode)+"\n仅影响这张卡带。"))));
        }
        var action=menu.primary();Entry selected=selectedRom();LocalCover selectedCover=selectedCover();
        String label=coversTab?(selectedCover!=null&&selectedCover.server()?"应用服务器封面":has(PlayerContentPolicy.COVER_UPLOAD)?"上传并应用封面":"封面上传未授权")
                :selected!=null&&!selected.server?(has(PlayerContentPolicy.ROM_UPLOAD)?"上传并写入卡带":"本地 ROM 上传未授权"):"写入服务器游戏";
        addRenderableWidget(DeviceUi.button(font,label,action.x(),action.y(),action.width(),action.height(),
            ()->{if(coversTab){LocalCover chosen=selectedCover();if(chosen!=null&&canSelectCover(chosen)){if(chosen.server())writeCover(chosen.hash);else uploadCover(chosen);}}else{Entry chosen=selectedRom();if(chosen!=null)select(chosen);}},
            !busy&&!scanning&&has(PlayerContentPolicy.BROWSE)&&(coversTab?selectedCover!=null&&canSelectCover(selectedCover)
                    :selected!=null&&NesCompatibility.isMapperSupported(selected.mapper)&&has(selected.server?PlayerContentPolicy.SERVER_ROM_USE:PlayerContentPolicy.ROM_UPLOAD)),DeviceUi.Tone.PRIMARY));
        button("ROM 目录",workbench.romFolder(),ClientFcDirectories::openRomDirectory,true);
        button("封面目录",workbench.coverFolder(),ClientFcDirectories::openCoverDirectory,true);
        button("上一页",workbench.previous(),()->movePage(-1),page>0&&!busy);
        button("下一页",workbench.next(),()->movePage(1),(page+1)*pageSize<count&&!busy);
        button("关闭",workbench.close(),this::onClose,true);
    }
    /** Screen.rebuildWidgets clears focus before init, so capture it outside that call. */
    @Override protected void rebuildWidgets(){
        boolean searchFocused=searchBox!=null&&searchBox.isFocused(),titleFocused=titleBox!=null&&titleBox.isFocused();
        int searchCursor=searchBox==null?0:searchBox.getCursorPosition(),titleCursor=titleBox==null?0:titleBox.getCursorPosition();
        super.rebuildWidgets();
        if(!busy&&searchFocused&&searchBox!=null&&children().contains(searchBox)){setInitialFocus(searchBox);searchBox.moveCursorTo(searchCursor,false);}
        else if(!busy&&titleFocused&&titleBox!=null&&children().contains(titleBox)){setInitialFocus(titleBox);titleBox.moveCursorTo(titleCursor,false);}
    }
    private Button button(String text,DeviceLayout.Rect rect,Runnable action,boolean active){
        return addRenderableWidget(DeviceUi.button(font,text,rect.x(),rect.y(),rect.width(),rect.height(),action,active,DeviceUi.Tone.NORMAL));
    }
    private void refreshLibrary(){scanLocal();send(CartridgeNetwork.request(CartridgeNetwork.REFRESH,target));}
    private Entry selectedRom(){return filteredRoms.stream().filter(entry->entry.hash.equals(chosenRomSha)).findFirst().orElse(null);}
    private LocalCover selectedCover(){return filteredCovers.stream().filter(cover->cover.fileName.equals(chosenCoverFile)).findFirst().orElse(null);}
    private boolean canSelectCover(LocalCover cover){return has(cover.server()?PlayerContentPolicy.SERVER_COVER_USE:PlayerContentPolicy.COVER_UPLOAD);}
    private List<LocalCover> coverEntries(){
        var rows=new ArrayList<LocalCover>();
        if(has(PlayerContentPolicy.SERVER_COVER_USE))for(String sha:serverCovers)rows.add(new LocalCover("服务器封面 · "+sha.substring(0,Math.min(12,sha.length())),sha));
        rows.addAll(localCovers);return rows;
    }
    /** Pure local-name matching, independently testable without initializing this Minecraft screen. */
    static final class Search {
        static boolean matches(String name,String query){return name.toLowerCase(Locale.ROOT).contains(query.strip().toLowerCase(Locale.ROOT));}
    }
    private void preview(Entry entry){
        romPage.focus(entry.hash);chosenRomSha=entry.hash;
        status=NesCompatibility.isMapperSupported(entry.mapper)?"Mapper "+entry.mapper+" · 先选择，再确认写入":NesCompatibility.unsupportedReason(entry.mapper);
        if(!titleDirty){String name=entry.name.replaceFirst("(?i)\\.nes$","");draftTitle=name.substring(0,Math.min(80,name.length()));titleBox=null;}
        rebuildWidgets();
    }
    private FcMenuState.PageAnchor pageAnchor() { return coversTab ? coverPage : romPage; }
    private List<String> pageKeys() {
        return coversTab ? filteredCovers.stream().map(LocalCover::fileName).toList() : filteredRoms.stream().map(Entry::hash).toList();
    }
    private void movePage(int delta) { pageAnchor().move(pageKeys(), pageSize, delta); rebuildWidgets(); }
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partialTick){
        if(menu==null)return;
        graphics.fill(0,0,width,height,DeviceUi.BG);var panel=menu.panel();
        String current=romSha.isEmpty()?"空白卡带":currentCardTitle.isBlank()?currentGame()==null?"已写入游戏":currentGame().fileName():currentCardTitle;
        DeviceUi.panel(graphics,font,panel.x(),panel.y(),panel.width(),panel.height(),saveSettings?"FC / 存档设置":"FC / 卡带工作台","当前卡带 · "+current);
        hoveredText=null;
        if(!menu.supported()){
            DeviceUi.text(graphics,font,"减小 GUI 缩放或放大窗口；Esc 关闭",panel.x()+10,panel.y()+44,panel.width()-20,DeviceUi.MUTED);
        }else if(saveSettings){
            var layout=new CartridgeSaveSettingsLayout(panel);
            String[] descriptions={"每次从头开始，不保存进度。","进度跟随这张卡带，换机器、换玩家也能继续。","每位玩家使用自己的存档，卡带不携带个人进度。"};
            for(int i=0;i<descriptions.length;i++)
                DeviceUi.text(graphics,font,descriptions[i],panel.x()+12,layout.descriptionY(i),panel.width()-24,DeviceUi.MUTED);
            var hint=layout.status();
            DeviceUi.status(graphics,font,status,hint.x(),hint.y(),hint.width(),busy);
            if(mouseX>=hint.x()&&mouseX<hint.right()&&mouseY>=hint.y()&&mouseY<hint.bottom())hoveredText=Component.literal(status+"\n切换方式不删除已有存档；光枪与普通手柄进度分开保存。");
        }else{
            var list=menu.list();var detail=menu.details();
            DeviceUi.section(graphics,list.x(),list.y(),list.width(),list.height());
            DeviceUi.section(graphics,detail.x(),detail.y(),detail.width(),detail.height());
            int count=coversTab?filteredCovers.size():filteredRoms.size();
            if(count==0)DeviceUi.text(graphics,font,scanning?"正在扫描…":!query.isBlank()?"没有匹配名称":coversTab?"封面目录中还没有 PNG":"目录中还没有游戏",list.x()+8,list.y()+8,list.width()-16,DeviceUi.MUTED);
            Entry selected=selectedRom();LocalCover selectedCover=selectedCover();
            String chosen=coversTab?selectedCover==null?"选择一张封面":selectedCover.fileName:selected==null?"在游戏库中选择":selected.name;
            String heading=coversTab?"待应用封面":"待写入游戏";
            String origin=coversTab?selectedCover==null?"尚未选择":selectedCover.server()?"服务器封面 · 无需上传":has(PlayerContentPolicy.COVER_UPLOAD)?"本地 PNG · 上传已授权":"本地 PNG · 上传未授权"
                    :selected==null?"尚未选择":selected.server?(selected.local!=null?"本地及服务器均有 · ":"服务器游戏库 · ")+(has(PlayerContentPolicy.SERVER_ROM_USE)?"可直接写卡":"使用未授权"):has(PlayerContentPolicy.ROM_UPLOAD)?"仅本地 ROM · 可上传":"仅本地 ROM · 上传未授权";
            int bottom=workbench.clearCover().y()-2;
            int textY=detail.y()+3,textWidth=detail.width()-16;
            int lines=Math.max(0,(bottom-textY+3)/12);
            if(lines>=3){
                DeviceUi.text(graphics,font,heading,detail.x()+8,textY,textWidth,DeviceUi.MUTED);
                line(graphics,"名称："+chosen,detail.x()+8,textY+12,textWidth,DeviceUi.TEXT,mouseX,mouseY);
                line(graphics,"来源："+origin,detail.x()+8,textY+24,textWidth,DeviceUi.MUTED,mouseX,mouseY);
            }else if(lines>=2){
                line(graphics,heading+" · "+chosen,detail.x()+8,textY,textWidth,DeviceUi.TEXT,mouseX,mouseY);
                line(graphics,"来源："+origin,detail.x()+8,textY+12,textWidth,DeviceUi.MUTED,mouseX,mouseY);
            }else if(lines==1)line(graphics,heading+" · "+chosen,detail.x()+8,textY,textWidth,DeviceUi.TEXT,mouseX,mouseY);
            if(mouseX>=detail.x()&&mouseX<detail.right()&&mouseY>=detail.y()&&mouseY<bottom)
                hoveredText=Component.literal(heading+"\n名称："+chosen+"\n来源："+origin+"\n选择不会修改卡带；确认后点击写入。");
            var statusBox=menu.status();
            String specs="PNG · 建议 512×256（2:1）· 最大 2048×2048 / 8 MiB";
            String visibleStatus=!coversTab&&selected!=null&&!NesCompatibility.isMapperSupported(selected.mapper)
                    ?NesCompatibility.unsupportedReason(selected.mapper):status;
            String footer=(page+1)+" / "+FcMenuLayout.pageCount(count,pageSize)+" 页 · "+visibleStatus;
            if(coversTab){
                DeviceUi.text(graphics,font,footer,statusBox.x(),statusBox.y(),statusBox.width(),DeviceUi.TEXT);
                DeviceUi.text(graphics,font,specs,statusBox.x(),statusBox.y()+9,statusBox.width(),DeviceUi.MUTED);
            }else DeviceUi.status(graphics,font,footer,statusBox.x(),statusBox.y(),statusBox.width(),busy||scanning);
            if(mouseX>=statusBox.x()&&mouseX<statusBox.right()&&mouseY>=statusBox.y()&&mouseY<statusBox.bottom())hoveredText=Component.literal(coversTab?specs+"\n其他比例会居中裁切为 512×256；透明处合成白底。\n"+status:visibleStatus);
        }
        super.render(graphics,mouseX,mouseY,partialTick);
        if(hoveredText!=null)graphics.renderTooltip(font,hoveredText,mouseX,mouseY);
    }
    private void line(GuiGraphics graphics, String text, int x, int y, int width, int color, int mouseX, int mouseY) {
        Component hovered = FcMenuUi.line(graphics, font, Component.literal(text), x, y, width, color, mouseX, mouseY);
        if (hovered != null) hoveredText = hovered;
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void tick() {
        if (!current()) { cancel(); if (MC.screen == this) MC.setScreen(null); return; }
        if (busy && System.nanoTime() - activity > CartridgeLimits.TRANSFER_TIMEOUT_NANOS) {
            status = "处理超时，已取消；请重新打开卡带编辑器"; onClose(); return;
        }
        if (upload == null || !upload.ready || upload.finished || !has(pendingPermission)) return;
        for (int part = 0; part < CartridgeLimits.CHUNKS_PER_TICK && upload.offset < upload.bytes.length; part++) {
            int end = Math.min(upload.offset + CartridgeLimits.CHUNK_BYTES, upload.bytes.length);
            send(new CartridgeNetwork.Request(CartridgeNetwork.CHUNK, target, upload.hash, "", "", "", 0, upload.offset,
                    Arrays.copyOfRange(upload.bytes, upload.offset, end)));
            upload.offset = end;
        }
        status = "上传 " + upload.offset * 100L / upload.bytes.length + "% · 请保持卡带在手中";
        if (upload.offset == upload.bytes.length) {
            send(new CartridgeNetwork.Request(CartridgeNetwork.FINISH, target, upload.hash, "", "", "", 0, 0, new byte[0]));
            upload.finished = true; status = "上传完成，服务器正在验证并写入…";
        }
    }
    private boolean current() {
        if (closed || !has(PlayerContentPolicy.BROWSE) || MC.screen != this || MC.getConnection() != connection || MC.player == null || !MC.player.isAlive()) return false;
        ItemStack held = MC.player.getItemInHand(target.hand() == 0 ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND);
        int slot = target.hand() == 0 ? MC.player.getInventory().selected : 40;
        return slot == target.slot() && FcCartridgeData.isCartridge(held) && target.cartridgeId().equals(FcCartridgeData.id(held));
    }
    private void send(CartridgeNetwork.Request request) { if (!closed && MC.getConnection() == connection) CartridgeNetwork.send(request); }
    private void cancel() {
        if (closed) return;
        DISMISSED.dismiss(target.token());
        try { send(CartridgeNetwork.request(CartridgeNetwork.CANCEL, target)); }
        finally { closed = true; revision++; upload = null; busy = false; pendingPermission = 0; }
    }
    @Override public void onClose() { cancel(); MC.setScreen(null); }
    @Override public boolean keyPressed(int key,int scan,int modifiers) {
        if(key==org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE&&saveSettings){saveSettings=false;rebuildWidgets();return true;}
        return super.keyPressed(key,scan,modifiers);
    }
    @Override public void removed() { cancel(); super.removed(); }
    private RomCatalogEntry currentGame() {
        return serverRoms.stream().filter(game -> game.sha256().equals(romSha)).findFirst().orElse(null);
    }
    private boolean saveModeEditable(){
        RomCatalogEntry game=currentGame();
        return !busy&&!scanning&&has(PlayerContentPolicy.BROWSE)&&game!=null
                &&CartridgeComputerBinding.permitsSaveModeSetting(cardSaveMode.id(),game.sha256(),romSha,true,false);
    }
    private static String saveModeName(RomSaveMode mode){
        return switch(mode){case NONE->"不存档";case MACHINE->"卡带存档";case PLAYER->"个人存档";};
    }
    private void setSaveMode(RomSaveMode mode){
        if(!current()||!saveSettings||!saveModeEditable())return;
        RomCatalogEntry game=currentGame();
        if(cardSaveMode==mode)return;
        busy=true;activity=System.nanoTime();status="正在保存…";
        send(new CartridgeNetwork.Request(CartridgeNetwork.SET_SAVE_MODE,target,game.sha256(),"","","",mode.id(),0,new byte[0]));
        rebuildWidgets();
    }
    private void togglePlayers() {
        if (!current() || busy || scanning || !has(PlayerContentPolicy.ADMIN)) return;
        RomCatalogEntry game = currentGame();
        if (game == null) return;
        busy = true; activity = System.nanoTime(); status = "正在保存游戏人数设置…";
        send(new CartridgeNetwork.Request(CartridgeNetwork.SET_PLAYERS, target, game.sha256(),
                "", "", "", game.maxPlayers() == 2 ? 1 : 2, 0, new byte[0]));
        rebuildWidgets();
    }
    private void write(String rom, String cover) {
        if (!current() || busy) return;
        try {
            draftTitle = CartridgeLimits.cleanTitle(titleBox.getValue()); busy = true; activity = System.nanoTime();
            status = "正在写入卡带…";
            send(new CartridgeNetwork.Request(CartridgeNetwork.WRITE, target, rom, cover, draftTitle, "", 0, 0, new byte[0]));
            rebuildWidgets();
        } catch (IllegalArgumentException error) { status = message(error); busy = false; }
    }
    /** Cover actions apply to the written cartridge, never the selected ROM's unsaved title draft. */
    private void writeCover(String cover) {
        if (!current() || busy) return;
        busy = true; activity = System.nanoTime(); status = "正在更新当前卡带封面…";
        send(new CartridgeNetwork.Request(CartridgeNetwork.WRITE, target, romSha, cover, currentCardTitle, "", 0, 0, new byte[0]));
        rebuildWidgets();
    }
    private void select(Entry entry) {
        if (busy || !current()) return;
        if (!NesCompatibility.isMapperSupported(entry.mapper)) { status = NesCompatibility.unsupportedReason(entry.mapper); rebuildWidgets(); return; }
        if (titleBox.getValue().isBlank()) {
            String name = entry.name.replaceFirst("(?i)\\.nes$", ""); titleBox.setValue(name.substring(0, Math.min(80, name.length())));
        }
        if (entry.server) { if(has(PlayerContentPolicy.SERVER_ROM_USE))write(entry.hash, coverSha); return; }
        if (!has(PlayerContentPolicy.ROM_UPLOAD)) { status = "此游戏仅在本地，服务器未授权 ROM 上传。"; rebuildWidgets(); return; }
        pendingPermission = PlayerContentPolicy.ROM_UPLOAD;
        busy = true; activity = System.nanoTime(); draftTitle = titleBox.getValue(); status = "后台校验本地 ROM…";
        int task = ++revision; String desiredTitle = draftTitle; LocalRom local = entry.local; rebuildWidgets();
        if (!ClientCartridgeIo.submit(() -> {
            try {
                if (closed || revision != task) return;
                Path file = ClientRomLibrary.root().resolve(local.fileName).normalize();
                if (!file.startsWith(ClientRomLibrary.root().toAbsolutePath().normalize()) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("本地 ROM 文件不再存在");
                RomDescriptor rom = new RomRepository(ClientRomLibrary.root()).load(local.fileName);
                NesCompatibility.requireSupported(rom.header());
                if (!rom.sha256().equals(local.hash)) throw new IOException("ROM 已变化，请刷新目录后重试");
                byte[] bytes = rom.bytes();
                MC.execute(() -> { if (current() && revision == task) beginUpload(false, local.fileName, rom.sha256(), desiredTitle, bytes); });
            } catch (Exception error) { localFailure(task, error); }
        })) localFailure(task, new IOException("本地后台队列已满，请稍后重试"));
    }
    private void uploadCover(LocalCover local) {
        if (busy || !current() || !has(PlayerContentPolicy.COVER_UPLOAD)) return;
        pendingPermission = PlayerContentPolicy.COVER_UPLOAD;
        busy = true; activity = System.nanoTime(); status = "后台检查并裁切封面 PNG…"; int task = ++revision; rebuildWidgets();
        if (!ClientCartridgeIo.submit(() -> {
            try {
                if (closed || revision != task) return;
                Path root = coversRoot().toAbsolutePath().normalize(), file = root.resolve(local.fileName).normalize();
                if (!file.startsWith(root) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("封面文件不再存在");
                byte[] bytes;
                try (var input = Files.newInputStream(file)) { bytes = input.readNBytes(CartridgeLimits.MAX_SOURCE_COVER_BYTES + 1); }
                byte[] png = CartridgeCoverCodec.prepare(bytes); String hash = RomRepository.sha256(png);
                MC.execute(() -> { if (current() && revision == task) beginUpload(true, local.fileName, hash, "", png); });
            } catch (Exception error) { localFailure(task, error); }
        })) localFailure(task, new IOException("本地后台队列已满，请稍后重试"));
    }
    private void beginUpload(boolean cover, String name, String hash, String title, byte[] bytes) {
        int permission = cover ? PlayerContentPolicy.COVER_UPLOAD : PlayerContentPolicy.ROM_UPLOAD;
        if (!current() || !has(permission)) { busy = false; pendingPermission = 0; rebuildWidgets(); return; }
        pendingPermission = permission;
        upload = new Upload(hash, bytes); activity = System.nanoTime(); status = "等待服务器授权上传…";
        String safeName = name.replaceAll("\\p{Cntrl}", "_");
        if (safeName.length() > 128) safeName = safeName.substring(0, 128);
        send(new CartridgeNetwork.Request(cover ? CartridgeNetwork.START_COVER : CartridgeNetwork.START_ROM,
                target, hash, "", title, safeName, bytes.length, 0, new byte[0]));
    }
    private void localFailure(int task, Exception error) {
        String failure = message(error);
        MC.execute(() -> { if (current() && revision == task) { busy = false; scanning = false; pendingPermission = 0; status = failure; rebuildWidgets(); } });
    }
    private void scanLocal() {
        if (scanning || busy || closed) return;
        scanning = true; int task = ++revision; status = "正在读取游戏和封面…"; rebuildWidgets();
        if (!ClientCartridgeIo.submit(() -> {
            try {
                Path romRoot = ClientFcDirectories.prepareRomDirectory(), coverRoot = ClientFcDirectories.prepareCoverDirectory();
                List<LocalRom> roms = new ArrayList<>(); long total = 0;
                List<Path> files;
                try (var stream = Files.list(romRoot)) { files = stream.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)
                        && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".nes")).limit(256).toList(); }
                for (Path file : files) {
                    if (closed || revision != task) return;
                    try {
                        long size = Files.size(file);
                        if (size <= 0 || size > RomRepository.MAX_ROM_BYTES || size > 128L * 1024 * 1024 - total) continue;
                        total += size; // Charge attempted reads too, even if the header is rejected.
                        RomDescriptor rom = new RomRepository(romRoot).load(file.getFileName().toString());
                        roms.add(new LocalRom(rom.fileName(), rom.sha256(), rom.header().mapper()));
                    } catch (IOException | IllegalArgumentException ignored) { }
                }
                List<LocalCover> covers = new ArrayList<>();
                try (var stream = Files.list(coverRoot)) {
                    for (Path file : stream.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)
                            && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")).limit(256).toList()) {
                        if (closed || revision != task) return;
                        try {
                            if (Files.size(file) > CartridgeLimits.MAX_SOURCE_COVER_BYTES) continue;
                            try (var input = Files.newInputStream(file)) {
                                CartridgeCoverCodec.dimensions(input.readNBytes(33), CartridgeLimits.MAX_SOURCE_COVER_BYTES, 2048);
                            }
                            covers.add(new LocalCover(file.getFileName().toString()));
                        } catch (IOException ignored) { }
                    }
                }
                MC.execute(() -> {
                    if (!current() || revision != task) return;
                    localRoms = List.copyOf(roms); localCovers = covers.stream().sorted(Comparator.comparing(LocalCover::fileName)).toList();
                    scanning = false; mergeEntries(); status = "扫描完成 · 先选择，再确认写入"; rebuildWidgets();
                });
            } catch (Exception error) { localFailure(task, error); }
        })) localFailure(task, new IOException("本地后台队列已满，请稍后刷新"));
    }
    private void mergeEntries() {
        Map<String, Entry> merged = new LinkedHashMap<>();
        for (RomCatalogEntry rom : serverRoms) merged.put(rom.sha256(), new Entry(rom.sha256(), rom.fileName(), true, null, rom.mapper()));
        for (LocalRom rom : localRoms) {
            Entry server = merged.get(rom.hash);
            merged.put(rom.hash, new Entry(rom.hash, server == null ? rom.fileName : server.name, server != null, rom, rom.mapper));
        }
        entries = merged.values().stream().sorted(Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER)).toList();
    }
    private static Path coversRoot() { return ClientFcDirectories.coverDirectory(); }
    private static String message(Exception error) {
        String message = error.getMessage() == null ? "本地文件处理失败" : error.getMessage();
        return message.replaceAll("\\p{Cntrl}", " ").substring(0, Math.min(180, message.length()));
    }
    private record LocalRom(String fileName, String hash, int mapper) {}
    private record LocalCover(String fileName,String hash) {
        LocalCover(String fileName){this(fileName,"");}
        boolean server(){return !hash.isEmpty();}
    }
    private record Entry(String hash, String name, boolean server, LocalRom local, int mapper) {}
    private static final class Upload {
        final String hash; final byte[] bytes; int offset; boolean ready, finished;
        Upload(String hash, byte[] bytes) { this.hash = hash; this.bytes = bytes; }
    }
}
