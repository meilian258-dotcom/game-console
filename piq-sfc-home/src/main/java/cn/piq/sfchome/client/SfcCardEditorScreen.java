// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.client;

import cn.piq.fcarcade.client.rom.LocalRomLibrary;
import cn.piq.fcarcade.client.ui.DeviceUi;
import cn.piq.fcarcade.client.ui.DeviceLayout;
import cn.piq.fcarcade.client.ui.CartridgeWorkbenchLayout;
import cn.piq.fcarcade.home.CartridgeCoverCodec;
import cn.piq.fcarcade.home.CartridgeLimits;
import cn.piq.sfchome.net.SfcHomeNetwork;
import cn.piq.sfchome.net.SfcEditorPermissions;
import cn.piq.sfchome.net.SfcEditorStatus;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.PacketDistributor;
import java.nio.file.Path;
import java.util.*;

/** Computer-authorized editor. Filesystem work never runs in init, render or tick. */
final class SfcCardEditorScreen extends cn.piq.fcarcade.client.ui.DeviceScreen {
    private enum Phase { IDLE, READING, WAIT_UPLOAD, UPLOADING, FINISHING, WRITING, RENAMING, REFRESHING }
    private final Object connection=Minecraft.getInstance().getConnection();
    private final SfcCardLibrary library;
    private final SfcCardLibrary covers=new SfcCardLibrary("");
    private final SfcEditorWork scanWork=new SfcEditorWork(),importWork=new SfcEditorWork();
    private final Path directory=LocalRomLibrary.sfcDirectory(FMLPaths.GAMEDIR.get());
    private final Path coverDirectory=cn.piq.retro.storage.ConsoleStorage.root(FMLPaths.GAMEDIR.get()).resolve("piq-sfc-home/covers");
    private final List<Button> rowButtons=new ArrayList<>();
    private SfcHomeNetwork.Editor data;
    private DeviceLayout.Browser layout;
    private CartridgeWorkbenchLayout workbench;
    private EditBox titleField,searchField;
    private Button writeButton,romFolderButton,coverFolderButton,refreshButton,previousButton,nextButton;
    private Button gamesTabButton,coversTabButton,playersButton,saveNameButton,clearCoverButton,restoreCoverButton,saveManagerButton;
    private String status="",serverStatus="",libraryStatus="",uploadSha="",uploadName="",searchQuery="";
    private byte[] upload;
    private SfcUploadTitle uploadTitle;
    private int uploadOffset;
    private Phase phase=Phase.IDLE;
    private boolean closed,scanning,initialScan,coverTab,uploadCover;
    private long actionAt;

    SfcCardEditorScreen(SfcHomeNetwork.Editor data){
        super(Component.literal("SFC / 卡带工作台"));this.data=data;
        serverStatus=SfcEditorStatus.remember("",data.message());status=SfcEditorStatus.directory(data.message())?"选择文件后确认写入":data.message();
        library=new SfcCardLibrary(data.title());library.labels(this::displayName);serverRows();
    }
    UUID token(){return data.token();}
    private boolean active(){return !closed&&minecraft!=null&&minecraft.screen==this&&minecraft.getConnection()==connection&&connection!=null;}
    private boolean busy(){return phase!=Phase.IDLE;}
    private boolean canBrowse(){return SfcEditorPermissions.browse(data.capabilities());}
    private boolean canUpload(boolean cover){return SfcEditorPermissions.upload(data.capabilities(),cover);}
    private boolean canAdmin(){return SfcEditorPermissions.admin(data.capabilities());}
    private boolean canWrite(SfcCardLibrary.Row row){return row!=null&&SfcEditorPermissions.selection(data.capabilities(),row.local(),coverTab);}
    private String uploadDenied(boolean cover){return cover?"管理员未开放封面上传":"管理员未开放 ROM 上传；可选服务器库中的游戏写卡";}
    private SfcCardLibrary shownLibrary(){return coverTab?covers:library;}
    private String displayName(SfcCardLibrary.Row row){return SfcWorkbenchDisplay.name(row,data.currentRom(),data.title());}
    private SfcCardLibrary.Row visibleSelection(){var selected=shownLibrary().selected();return selected!=null&&shownLibrary().filtered().contains(selected)?selected:null;}
    private void serverRows(){
        library.server(data.catalog().stream().map(r->SfcCardLibrary.Row.server(r.sha256(),r.fileName(),r.size())).toList(),data.currentRom());
        covers.server(data.covers().stream().map(sha->SfcCardLibrary.Row.server(sha,"服务器封面 · "+sha.substring(0,Math.min(12,sha.length())),0)).toList(),data.coverSha());
    }

    @Override protected void init(){
        DeviceUi.prepare();
        int titleCursor=titleField==null?-1:titleField.getCursorPosition(),searchCursor=searchField==null?-1:searchField.getCursorPosition();
        layout=DeviceLayout.browser(width,height,2);rowButtons.clear();
        titleField=searchField=null;writeButton=romFolderButton=coverFolderButton=refreshButton=previousButton=nextButton=null;
        if(!layout.supported())return;
        workbench=CartridgeWorkbenchLayout.of(layout);var name=workbench.name();
        titleField=new EditBox(font,name.x(),name.y(),name.width(),name.height(),Component.literal("卡带名称草稿"));
        titleField.setMaxLength(128);titleField.setValue(library.title());titleField.setResponder(library::title);addRenderableWidget(titleField);
        if(titleCursor>=0)titleField.moveCursorTo(Math.min(titleCursor,library.title().length()),false);
        titleField.setTooltip(Tooltip.create(Component.literal("卡带名称草稿；点击保存名称后才修改当前卡带")));
        saveNameButton=action("保存名称",workbench.saveName(),()->setting(SfcHomeNetwork.SAVE_NAME,0),DeviceUi.Tone.NORMAL);
        playersButton=action("",workbench.players(),()->setting(SfcHomeNetwork.SET_PLAYERS,SfcWorkbenchDisplay.nextPlayers(data.maxPlayers())),DeviceUi.Tone.NORMAL);
        gamesTabButton=action("游戏库",workbench.gamesTab(),()->tab(false),DeviceUi.Tone.NORMAL);
        coversTabButton=action("封面",workbench.coversTab(),()->tab(true),DeviceUi.Tone.NORMAL);
        var search=workbench.search();searchField=new EditBox(font,search.x(),search.y(),search.width(),search.height(),Component.literal("搜索名称或文件名"));
        searchField.setMaxLength(128);searchField.setValue(searchQuery);searchField.setHint(Component.literal("搜索名称…"));
        if(searchCursor>=0)searchField.moveCursorTo(Math.min(searchCursor,searchQuery.length()),false);
        searchField.setResponder(value->{searchQuery=value;library.query(value);covers.query(value);rebuildRows();});addRenderableWidget(searchField);
        searchField.setTooltip(Tooltip.create(Component.literal("搜索当前列表；切换游戏库和封面时保留搜索词")));
        refreshButton=action("刷新",workbench.refresh(),this::refresh,DeviceUi.Tone.QUIET);
        romFolderButton=action("ROM 目录",workbench.romFolder(),()->scanLocal(true,false),DeviceUi.Tone.NORMAL);
        coverFolderButton=action("封面目录",workbench.coverFolder(),()->scanLocal(true,true),DeviceUi.Tone.NORMAL);
        romFolderButton.setTooltip(Tooltip.create(Component.literal(directory+"\n只列出直接子文件，不扫描子文件夹")));
        coverFolderButton.setTooltip(Tooltip.create(Component.literal(coverDirectory+"\n只列出直接 PNG 文件，不扫描子文件夹")));
        clearCoverButton=restoreCoverButton=saveManagerButton=null;
        if(coverTab){
            clearCoverButton=action("清空",workbench.clearCover(),()->setting(SfcHomeNetwork.CLEAR_COVER,0),DeviceUi.Tone.DANGER);
            restoreCoverButton=action("恢复",workbench.restoreCover(),()->setting(SfcHomeNetwork.RESTORE_COVER,0),DeviceUi.Tone.NORMAL);
            restoreCoverButton.setTooltip(Tooltip.create(Component.literal("恢复本次打开电脑时的封面；不改变 ROM、名称或人数")));
        }else{
            var a=workbench.clearCover();var b=workbench.restoreCover();
            saveManagerButton=action("Netplay 存档："+new String[]{"不保存","个人","卡带"}[data.saveMode()],new DeviceLayout.Rect(a.x(),a.y(),b.right()-a.x(),a.height()),()->setting(SfcHomeNetwork.SET_SAVE_MODE,(data.saveMode()+1)%3),DeviceUi.Tone.NORMAL);
            saveManagerButton.setTooltip(Tooltip.create(Component.literal("仅 Netplay：个人档属于开机玩家和游戏；卡带档随实体卡带，换主机可继续。每 30 秒及正常关机保存，开机自动读取；与旧本机备份隔离。点击切换，不迁移或删除原档。")));
        }
        previousButton=action("上一页",workbench.previous(),()->{shownLibrary().turn(-1,layout.rows());rebuildRows();},DeviceUi.Tone.QUIET);
        nextButton=action("下一页",workbench.next(),()->{shownLibrary().turn(1,layout.rows());rebuildRows();},DeviceUi.Tone.QUIET);
        writeButton=action("请选择游戏",layout.primary(),this::writeSelected,DeviceUi.Tone.PRIMARY);
        action("关闭",workbench.close(),this::onClose,DeviceUi.Tone.NORMAL);
        rebuildRows();
        if(!initialScan){initialScan=true;scanLocal(false);}
    }
    private Button action(String label,DeviceLayout.Rect rect,Runnable callback,DeviceUi.Tone tone){return addRenderableWidget(DeviceUi.button(font,label,rect.x(),rect.y(),rect.width(),rect.height(),callback,true,tone));}
    private void tab(boolean covers){if(coverTab==covers||busy()||scanning)return;coverTab=covers;rebuildWidgets();scanLocal(false);}
    private void rebuildRows(){
        for(Button button:rowButtons)removeWidget(button);rowButtons.clear();
        if(layout==null||!layout.supported()||writeButton==null)return;
        SfcCardLibrary library=shownLibrary();
        int row=0;
        for(SfcCardLibrary.Row entry:library.visible(layout.rows())){
            var rect=layout.row(row++);
            Button button=DeviceUi.row(font,displayName(entry),entry.local()?"本地待上传":"服务器库",rect.x(),rect.y(),rect.width(),rect.height(),()->{library.select(entry);if(!coverTab)titleField.setValue(library.title());rebuildRows();},library.selected(entry),!entry.local()&&entry.hash().equals(data.currentRom()),true);
            button.setTooltip(Tooltip.create(Component.literal(displayName(entry)+"\n"+(coverTab?(entry.local()?"本地 PNG：规范为 512×256 后上传并应用":"服务器封面：直接应用，无需上传"):entry.local()?"本地文件：上传并写卡":"服务器库：直接写卡，不在这里下载 ROM 文件")+(entry.bytes()>0?"\n"+entry.bytes()+" 字节":"")+"\n"+SfcWorkbenchDisplay.original(entry))));
            rowButtons.add(addRenderableWidget(button));
        }
        controls();
    }
    private void controls(){
        if(writeButton==null)return;
        SfcCardLibrary.Row selected=visibleSelection();
        writeButton.active=!busy()&&canWrite(selected);
        writeButton.setMessage(Component.literal(selected==null?(coverTab?"请选择封面":"请选择游戏"):coverTab?"应用所选封面":"写入所选游戏"));
        writeButton.setTooltip(Tooltip.create(Component.literal(selected!=null&&!canWrite(selected)?selected.local()?uploadDenied(coverTab):"管理员未授权使用服务器已有"+(coverTab?"封面":"ROM"):selected==null?"点击当前列表选择文件":displayName(selected)+"\n"+(coverTab?(selected.local()?"校验 PNG 后上传并应用":"应用服务器已有封面，无需上传"):selected.local()?"一次操作自动上传并写卡；保留名称草稿":"使用服务器库，无需再次上传"))));
        romFolderButton.active=!busy()&&!scanning&&canUpload(false);
        coverFolderButton.active=!busy()&&!scanning&&canUpload(true);
        refreshButton.active=!busy()&&!scanning&&canBrowse();
        romFolderButton.setTooltip(Tooltip.create(Component.literal(canUpload(false)?directory+"\n只列出直接子文件，不扫描子文件夹":uploadDenied(false))));
        coverFolderButton.setTooltip(Tooltip.create(Component.literal(canUpload(true)?coverDirectory+"\n只列出直接 PNG 文件，不扫描子文件夹":uploadDenied(true))));
        gamesTabButton.active=!busy()&&!scanning&&coverTab;coversTabButton.active=!busy()&&!scanning&&!coverTab;
        gamesTabButton.setMessage(Component.literal(coverTab?"游戏库":"> 游戏库"));coversTabButton.setMessage(Component.literal(coverTab?"> 封面":"封面"));
        saveNameButton.active=!busy()&&canBrowse();playersButton.active=!busy()&&canAdmin();
        playersButton.setMessage(Component.literal(SfcWorkbenchDisplay.players(data.maxPlayers())));
        playersButton.setTooltip(Tooltip.create(Component.literal(canAdmin()?"设置可加入人数；是否需要申请由主机设置决定。游戏本身也需要支持双人。":"人数设置仅管理员可修改；名称可保存到自己手中的卡带")));
        if(clearCoverButton!=null){clearCoverButton.active=!busy()&&canBrowse()&&!data.coverSha().isEmpty();restoreCoverButton.active=!busy()&&canBrowse();}
        if(saveManagerButton!=null)saveManagerButton.active=!busy()&&canBrowse();if(saveManagerButton!=null)saveManagerButton.setMessage(Component.literal("Netplay 存档："+new String[]{"不保存","个人","卡带"}[data.saveMode()]));
        previousButton.active=!busy()&&shownLibrary().page(layout.rows())>0;
        nextButton.active=!busy()&&shownLibrary().page(layout.rows())+1<shownLibrary().pages(layout.rows());
        titleField.setEditable(!busy()&&canBrowse());searchField.setEditable(!busy());
        for(Button button:rowButtons)button.active=!busy();
    }
    private void scanLocal(boolean open){
        scanLocal(open,coverTab);
    }
    private void scanLocal(boolean open,boolean scanCovers){
        if(!active()||scanning||busy())return;
        if(!canUpload(scanCovers)){(scanCovers?covers:library).local(List.of());libraryStatus=uploadDenied(scanCovers);rebuildRows();return;}
        scanning=true;libraryStatus="正在扫描本地目录…";controls();
        int ticket=scanWork.begin();Minecraft mc=minecraft;Path scanDirectory=scanCovers?coverDirectory:directory;
        boolean submitted=LocalRomLibrary.submit(()->{
            try {
                Path prepared=LocalRomLibrary.prepare(scanDirectory);
                LocalRomLibrary.Scan result=LocalRomLibrary.scan(prepared,scanCovers?Set.of(".png"):Set.of(".sfc",".smc"),Set.of());
                mc.execute(()->{
                    if(!active()||!scanWork.accepts(ticket))return;
                    if(!canUpload(scanCovers)){scanning=false;(scanCovers?covers:library).local(List.of());libraryStatus=uploadDenied(scanCovers);rebuildRows();return;}
                    scanning=false;
                    (scanCovers?covers:library).local(result.entries().stream().map(e->SfcCardLibrary.Row.local(e.path(),e.fileName(),e.bytes())).toList());
                    libraryStatus="本地 "+result.entries().size()+" 个"+(result.skipped()>0?" · 忽略 "+result.skipped()+" 项":"")+(result.limited()?" · 目录过大，已截断":"");
                    rebuildRows();
                    if(open)try{Util.getPlatform().openFile(prepared.toFile());}catch(RuntimeException error){status="无法打开文件夹："+errorText(error);}
                });
            } catch(Exception error){mc.execute(()->{if(active()&&scanWork.accepts(ticket)){scanning=false;libraryStatus="本地目录不可用："+errorText(error);controls();}});}
        });
        if(!submitted){scanning=false;libraryStatus="文件任务繁忙，请稍后刷新";controls();}
    }
    private void refresh(){
        if(!active()||busy()||!canBrowse())return;
        scanLocal(false);phase=Phase.REFRESHING;actionAt=System.nanoTime();status="正在刷新服务器目录…";controls();
        send(SfcHomeNetwork.REFRESH,"","",0,0,new byte[0]);
    }
    private void send(int op,String sha,String name,int total,int offset,byte[] bytes){
        if(active())PacketDistributor.sendToServer(new SfcHomeNetwork.EditorAction(token(),op,sha,name,total,offset,bytes));
    }
    private void writeSelected(){
        if(!active()||busy())return;
        SfcCardLibrary.Row selected=shownLibrary().selected();if(selected==null||!shownLibrary().filtered().contains(selected)||!canWrite(selected))return;
        if(selected.local()){importFile(selected.path());return;}
        phase=Phase.WRITING;actionAt=System.nanoTime();status="正在写入…";controls();
        send(coverTab?SfcHomeNetwork.USE_COVER:SfcHomeNetwork.WRITE,selected.hash(),coverTab?"":library.title().strip(),0,0,new byte[0]);
    }
    private record Imported(byte[] bytes,String sha){}
    private void setting(int operation,int players){
        if(!active()||busy()||!canBrowse()||operation==SfcHomeNetwork.SET_PLAYERS&&!canAdmin())return;
        phase=Phase.WRITING;actionAt=System.nanoTime();status="正在保存设置…";controls();
        send(operation,data.currentRom(),operation==SfcHomeNetwork.SAVE_NAME?library.title().strip():"",players,0,new byte[0]);
    }
    private void importFile(Path chosen){
        if(!active()||busy()||!canUpload(coverTab))return;
        phase=Phase.READING;actionAt=System.nanoTime();status=coverTab?"正在校验 PNG 封面…":"正在校验本地 ROM…";controls();
        int ticket=importWork.begin();Minecraft mc=minecraft;String draft=library.title().strip();boolean importingCover=coverTab;
        boolean submitted=LocalRomLibrary.submit(()->{
            try {
                LocalRomLibrary.validateFile(chosen);
                byte[] bytes=importingCover?CartridgeCoverCodec.prepare(SfcClientFiles.readBounded(chosen,CartridgeLimits.MAX_SOURCE_COVER_BYTES)):SfcClientFiles.importRom(chosen);
                LocalRomLibrary.validateFile(chosen);
                Imported imported=new Imported(bytes,SfcClientFiles.hash(bytes));
                mc.execute(()->{
                    if(!active()||!importWork.accepts(ticket)||phase!=Phase.READING)return;
                    if(!canUpload(importingCover)){phase=Phase.IDLE;status=uploadDenied(importingCover);controls();return;}
                    upload=imported.bytes();uploadSha=imported.sha();uploadName=chosen.getFileName().toString();
                    if(uploadName.length()>128)uploadName=uploadName.substring(0,120)+(importingCover?".png":".sfc");
                    uploadTitle=importingCover?null:new SfcUploadTitle(uploadSha,draft);uploadOffset=0;uploadCover=importingCover;
                    phase=Phase.WAIT_UPLOAD;status="请求上传许可…";actionAt=System.nanoTime();controls();
                    send(importingCover?SfcHomeNetwork.COVER_START:SfcHomeNetwork.UPLOAD_START,uploadSha,uploadName,upload.length,0,new byte[0]);
                });
            } catch(Exception error){mc.execute(()->{if(active()&&importWork.accepts(ticket)){phase=Phase.IDLE;status="导入失败："+errorText(error);controls();}});}
        });
        if(!submitted){phase=Phase.IDLE;status="文件任务繁忙，请稍后重试";controls();}
    }
    private static String errorText(Throwable error){return error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();}
    void update(SfcHomeNetwork.Editor message){
        if(!active()||!token().equals(message.token()))return;
        if(message.message().endsWith("编辑已取消")||message.message().equals("授权失效，旧卡带未改动")||message.message().equals("已切换编辑目标")){
            SfcHomeClient.toast(phase==Phase.RENAMING?"ROM 已写入，但名称未完成；请重新打开电脑":"SFC 写卡已取消，请放好 ROM 后重新打开电脑");
            onClose();return;
        }
        data=message;serverRows();
        if(!canBrowse()){SfcHomeClient.toast("服务器游戏库授权已撤销，请联系管理员");onClose();return;}
        if(!canUpload(false))library.local(List.of());if(!canUpload(true))covers.local(List.of());
        serverStatus=SfcEditorStatus.remember(serverStatus,message.message());
        if(SfcEditorStatus.directory(message.message())){
            if(phase==Phase.REFRESHING){phase=Phase.IDLE;status="服务器目录刷新已返回；详情见状态栏";}
            rebuildRows();return;
        }
        if(message.message().equals("PERMISSIONS_UPDATED")){
            if(phase==Phase.READING&&!canUpload(coverTab)){importWork.begin();phase=Phase.IDLE;status=uploadDenied(coverTab);}
            else if((phase==Phase.WAIT_UPLOAD||phase==Phase.UPLOADING)&&!canUpload(uploadCover)){phase=Phase.IDLE;upload=null;uploadTitle=null;status="上传权限已关闭，未继续发送文件";}
            else if(phase==Phase.FINISHING&&!canUpload(uploadCover))status="上传权限已关闭，等待服务器确认；已提交文件可能已入库";
            else if(!busy())status="服务器权限已更新";
            rebuildRows();return;
        }
        if(message.message().equals("UPLOAD_READY")){
            if(phase==Phase.WAIT_UPLOAD&&upload!=null&&canUpload(uploadCover)){phase=Phase.UPLOADING;actionAt=System.nanoTime();status="上传中…";}
            else if(!canUpload(uploadCover)){phase=Phase.IDLE;upload=null;uploadTitle=null;status=uploadDenied(uploadCover);}
            controls();return;
        }
        status=message.message();
        if(phase==Phase.FINISHING&&uploadTitle!=null&&uploadTitle.afterUpload(message.message(),message.currentRom(),message.title())){
            upload=null;phase=Phase.RENAMING;status="ROM 已写入，正在应用名称草稿…";actionAt=System.nanoTime();controls();
            send(SfcHomeNetwork.WRITE,uploadSha,uploadTitle.draft(),0,0,new byte[0]);return;
        }
        if(phase==Phase.RENAMING&&!message.message().equals("写入完成"))status="ROM 已写入；名称未完成："+message.message();
        phase=Phase.IDLE;upload=null;uploadTitle=null;rebuildRows();
    }
    @Override public void tick(){
        if(!active()){if(!closed)cancel();return;}
        if(busy()&&System.nanoTime()-actionAt>90_000_000_000L){onClose();SfcHomeClient.toast("SFC 写卡会话超时，请重新打开电脑");return;}
        if(phase!=Phase.UPLOADING||upload==null)return;
        if(!canUpload(uploadCover)){phase=Phase.IDLE;upload=null;uploadTitle=null;status=uploadDenied(uploadCover);controls();return;}
        if(uploadOffset<upload.length){
            int end=Math.min(upload.length,uploadOffset+SfcHomeNetwork.CHUNK);
            send(SfcHomeNetwork.UPLOAD_CHUNK,uploadSha,uploadName,upload.length,uploadOffset,Arrays.copyOfRange(upload,uploadOffset,end));
            uploadOffset=end;status="上传中 "+(100L*end/upload.length)+"%";
        } else {
            phase=Phase.FINISHING;
            send(SfcHomeNetwork.UPLOAD_FINISH,uploadSha,uploadName,upload.length,uploadOffset,new byte[0]);status="服务器校验并写卡中…";
        }
    }
    private void cancel(){
        if(closed)return;
        closed=true;scanWork.close();importWork.close();upload=null;uploadTitle=null;
        // Replaced screens release the old authorization, never on a different connection.
        if(minecraft!=null&&minecraft.getConnection()==connection&&connection!=null)
            PacketDistributor.sendToServer(new SfcHomeNetwork.EditorAction(token(),SfcHomeNetwork.CANCEL,"","",0,0,new byte[0]));
    }
    @Override public void onClose(){cancel();super.onClose();}
    @Override public void removed(){cancel();}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void render(GuiGraphics g,int mouseX,int mouseY,float delta){
        g.fill(0,0,width,height,DeviceUi.BG);
        if(layout==null)return;
        if(!layout.supported()){g.drawCenteredString(font,Component.literal("界面空间不足，请减小 GUI 缩放；Esc 关闭"),width/2,height/2,0xffffff);return;}
        var panel=layout.panel();int x=panel.x(),y=panel.y(),w=panel.width();
        DeviceUi.panel(g,font,x,y,w,panel.height(),title.getString(),"当前卡带 · "+SfcWorkbenchDisplay.current(data.title()));
        var list=layout.list();var details=layout.details();DeviceUi.section(g,list.x(),list.y(),list.width(),list.height());DeviceUi.section(g,details.x(),details.y(),details.width(),details.height());
        if(shownLibrary().filtered().isEmpty())DeviceUi.text(g,font,scanning?"正在扫描…":!canUpload(coverTab)?uploadDenied(coverTab):"没有匹配文件；打开文件夹后刷新",list.x()+8,list.y()+8,list.width()-16,DeviceUi.MUTED);
        var selected=visibleSelection();String selectedName=selected==null?"尚未选择":displayName(selected);
        String heading=coverTab?"待应用封面":"待写入游戏",source=coverTab&&selected!=null?(selected.local()?"本地 PNG · 上传后使用":"服务器 PNG · 直接应用"):SfcWorkbenchDisplay.source(selected);
        int textY=details.y()+3,textBottom=workbench.clearCover().y()-2;
        int lines=Math.max(0,(textBottom-textY-font.lineHeight)/12+1);
        String[] detailLines=SfcWorkbenchDisplay.details(heading,selectedName,source,lines);
        for(int i=0;i<detailLines.length;i++)DeviceUi.text(g,font,detailLines[i],details.x()+6,textY+i*12,details.width()-12,i==0?DeviceUi.TEXT:DeviceUi.MUTED);
        String page=(shownLibrary().page(layout.rows())+1)+" / "+shownLibrary().pages(layout.rows())+" 页 · "+shownLibrary().filtered().size()+" 项";
        var line=layout.status();DeviceUi.status(g,font,page+" · "+status+" · "+serverStatus+" · "+libraryStatus,line.x(),line.y(),line.width(),busy()||scanning);
        super.render(g,mouseX,mouseY,delta);
        if(mouseX>=details.x()&&mouseX<details.right()&&mouseY>=details.y()&&mouseY<textBottom)
            g.renderTooltip(font,Component.literal(heading+"\n名称："+selectedName+"\n来源："+source+(selected==null?"":"\n"+selected.bytes()+" 字节\n"+SfcWorkbenchDisplay.original(selected))),mouseX,mouseY);
        if(mouseX>=line.x()&&mouseX<line.right()&&mouseY>=line.y()&&mouseY<line.bottom())
            g.renderTooltip(font,Component.literal(page+"\n"+status+"\n"+serverStatus+"\n"+libraryStatus),mouseX,mouseY);
    }
}
