package cn.piq.fcarcade.client.cabinet;

import cn.piq.fcarcade.access.PlayerContentPolicy;
import cn.piq.fcarcade.cabinet.CabinetGameManifest;
import cn.piq.fcarcade.cabinet.CabinetGameProfile;
import cn.piq.fcarcade.cabinet.CabinetGameNetwork;
import cn.piq.fcarcade.cabinet.CabinetLibraryPage;
import cn.piq.fcarcade.cabinet.CabinetNetwork;
import cn.piq.fcarcade.client.rom.LocalRomLibrary;
import cn.piq.fcarcade.client.ui.DeviceLayout;
import cn.piq.fcarcade.client.ui.DeviceUi;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** One lease-bound library: local upload and server selection have distinct capabilities and paths. */
final class CabinetSetupScreen extends cn.piq.fcarcade.client.ui.DeviceScreen {
    private final CabinetBackend provider;
    private final CabinetNetwork.Launch launch;
    private final Connection connection;
    private final String mode;
    private DeviceLayout.Browser layout;
    private List<CabinetGameManifest> server=List.of();
    private List<LocalRomLibrary.Entry> local=List.of();
    private List<Row> rows=List.of();
    private EditBox search;
    private UUID pending;
    private int capabilities,offset,total,page,revision;
    private String query="",focus="",status="正在读取服务器目录…";
    private boolean initialized,localTab,closed,scanning,selecting,loading,serverLoaded;
    private long requestAt;
    private final java.util.Map<String,CabinetGameProfile> profiles=new java.util.HashMap<>();
    private boolean canEdit,editing;
    private Row editGame;
    private CabinetGameProfile editProfile=CabinetGameProfile.EMPTY;
    private EditBox editName;
    private record Row(String id,String name,String details,Path path,CabinetGameManifest game) {}
    CabinetSetupScreen(CabinetBackend provider,CabinetNetwork.Launch launch,String mode){
        super(Component.literal("方块电玩 · 街机游戏库"));this.provider=provider;this.launch=launch;this.mode=mode;
        this.connection=Minecraft.getInstance().getConnection().getConnection();
    }
    static void receive(Connection source,CabinetGameNetwork.LibraryReply reply){
        var mc=Minecraft.getInstance();
        if(mc.screen instanceof CabinetSetupScreen screen)screen.reply(source,reply);
    }
    private boolean current(){return !closed&&minecraft!=null&&minecraft.screen==this&&minecraft.getConnection()!=null
            &&minecraft.getConnection().getConnection()==connection&&connection.isConnected()&&CabinetClientBackends.current();}
    private boolean has(int bit){return (capabilities&bit)!=0;}
    private boolean busy(){return pending!=null||scanning||loading;}
    @Override protected void init(){
        if(editing){initEditor();return;}
        DeviceUi.prepare();if(search!=null)query=search.getValue();search=null;
        layout=DeviceLayout.browser(width,height,2);if(!layout.supported())return;
        var bar=layout.toolbar();int col=(bar.width()-12)/4;
        button(localTab?"服务器游戏":"> 服务器游戏",new DeviceLayout.Rect(bar.x(),bar.y(),col,20),()->tab(false),!busy()&&localTab);
        button(localTab?"> 本地游戏":"本地游戏",new DeviceLayout.Rect(bar.x()+col+4,bar.y(),col,20),()->tab(true),!busy()&&!localTab);
        button("刷新",new DeviceLayout.Rect(bar.x()+2*(col+4),bar.y(),col,20),()->{if(localTab)scan(false);else request(offset,"");},!busy());
        button("ROM 目录",new DeviceLayout.Rect(bar.x()+3*(col+4),bar.y(),bar.width()-3*(col+4),20),()->scan(true),!busy());
        search=new EditBox(font,bar.x(),bar.y()+24,bar.width(),20,Component.literal("搜索当前目录页"));search.setMaxLength(128);search.setValue(query);search.setHint(Component.literal("搜索当前目录页…"));
        search.setResponder(value->{query=value;page=0;rebuildWidgets();});search.setEditable(!busy());addRenderableWidget(search);
        rows=(localTab?local.stream().map(e->new Row(e.path().toString(),e.fileName(),"本地文件 · "+size(e.bytes())+" · 上传入库后可由管理员标注",e.path(),null))
                :server.stream().map(e->{var profile=profiles.getOrDefault(e.contentId(),CabinetGameProfile.EMPTY);return new Row(e.contentId(),profile.label(e.files().getFirst().name()),e.files().getFirst().name()+" · "+size(e.size())+" · "+profile.summary(),null,e);}))
                .filter(r->(r.name+" "+r.details).toLowerCase(Locale.ROOT).contains(query.strip().toLowerCase(Locale.ROOT))).toList();
        int pages=Math.max(1,(rows.size()+layout.rows()-1)/layout.rows());page=Math.min(page,pages-1);
        for(int i=page*layout.rows();i<Math.min(rows.size(),(page+1)*layout.rows());i++){
            Row row=rows.get(i);var rect=layout.row(i-page*layout.rows());
            var profile=profiles.getOrDefault(row.id,CabinetGameProfile.EMPTY);
            String badge=localTab?"本地":(profile.players()==0?"?人":profile.players()+"人")+" · "+profile.orientation().label();
            var b=DeviceUi.row(font,row.name,badge,rect.x(),rect.y(),rect.width(),rect.height(),()->{focus=row.id;rebuildWidgets();},row.id.equals(focus),false,!busy());
            b.setTooltip(Tooltip.create(Component.literal(row.details+"\n运行："+mode)));addRenderableWidget(b);
        }
        Row chosen=chosen();var primary=layout.primary();int half=(primary.width()-4)/2;
        button(localTab?"上传并使用":"使用服务器游戏",new DeviceLayout.Rect(primary.x(),primary.y(),half,primary.height()),this::choose,!busy()&&chosen!=null&&has(localTab?PlayerContentPolicy.ROM_UPLOAD:PlayerContentPolicy.SERVER_ROM_USE));
        button("游戏资料",new DeviceLayout.Rect(primary.x()+half+4,primary.y(),primary.width()-half-4,primary.height()),this::edit,!busy()&&!localTab&&chosen!=null&&canEdit);
        var nav=layout.navigation();int third=(nav.width()-8)/3;
        button("上一页",new DeviceLayout.Rect(nav.x(),nav.y(),third,20),()->turn(-1),!busy()&&(page>0||!localTab&&offset>0));
        button("下一页",new DeviceLayout.Rect(nav.x()+third+4,nav.y(),third,20),()->turn(1),!busy()&&(page+1<pages||!localTab&&offset+server.size()<total));
        button("关闭",new DeviceLayout.Rect(nav.x()+2*(third+4),nav.y(),nav.width()-2*(third+4),20),this::onClose,true);
        if(!initialized){initialized=true;request(0,"");}
    }
    private void button(String text,DeviceLayout.Rect r,Runnable action,boolean enabled){
        var b=DeviceUi.button(font,text,r.x(),r.y(),r.width(),r.height(),action,enabled,DeviceUi.Tone.NORMAL);
        b.setTooltip(Tooltip.create(Component.literal(text)));addRenderableWidget(b);
    }
    private Row chosen(){return rows.stream().filter(r->r.id.equals(focus)).findFirst().orElse(null);}
    private void edit(){
        if(!current()||busy()||localTab||!canEdit||chosen()==null)return;
        editGame=chosen();editProfile=profiles.getOrDefault(editGame.id,CabinetGameProfile.EMPTY);editName=null;editing=true;status="服务器统一资料 · 仅管理员可修改";rebuildWidgets();
    }
    private DeviceLayout.Rect editorPanel(){int w=Math.min(480,width-24),h=Math.min(264,height-24);return new DeviceLayout.Rect((width-w)/2,(height-h)/2,w,h);}
    private void initEditor(){
        DeviceUi.prepare();var p=editorPanel();if(p.width()<260||p.height()<248)return;
        String name=editName==null?editProfile.name():editName.getValue();
        editName=new EditBox(font,p.x()+12,p.y()+62,p.width()-24,20,Component.literal("自定义游戏名"));editName.setMaxLength(64);editName.setValue(name);editName.setHint(Component.literal("留空使用 ROM 文件名"));editName.setEditable(!busy());addRenderableWidget(editName);
        int row=p.y()+89;
        button("支持人数："+(editProfile.players()==0?"未标注":editProfile.players()+" 人"),new DeviceLayout.Rect(p.x()+12,row,p.width()-24,20),()->{editProfile=new CabinetGameProfile(editProfile.name(),(editProfile.players()+1)%5,editProfile.orientation(),editProfile.aspect(),editProfile.revision());rebuildWidgets();},!busy());
        button("屏幕标注："+editProfile.orientation().label(),new DeviceLayout.Rect(p.x()+12,row+26,p.width()-24,20),()->{var values=CabinetGameProfile.Orientation.values();editProfile=new CabinetGameProfile(editProfile.name(),editProfile.players(),values[(editProfile.orientation().ordinal()+1)%values.length],editProfile.aspect(),editProfile.revision());rebuildWidgets();},!busy());
        button("显示比例："+editProfile.aspect().label(),new DeviceLayout.Rect(p.x()+12,row+52,p.width()-24,20),()->{var values=CabinetGameProfile.Aspect.values();editProfile=new CabinetGameProfile(editProfile.name(),editProfile.players(),editProfile.orientation(),values[(editProfile.aspect().ordinal()+1)%values.length],editProfile.revision());rebuildWidgets();},!busy());
        int half=(p.width()-28)/2;
        button("保存到服务器",new DeviceLayout.Rect(p.x()+12,p.bottom()-34,half,20),this::saveProfile,!busy()&&canEdit);
        button("返回列表",new DeviceLayout.Rect(p.x()+16+half,p.bottom()-34,half,20),this::backFromEditor,!busy());
    }
    private void backFromEditor(){editing=false;editName=null;rebuildWidgets();}
    private void saveProfile(){
        if(!current()||busy()||!editing||!canEdit||editName==null)return;
        if(System.nanoTime()-requestAt<300_000_000L){status="操作过快，请稍候再试";rebuildWidgets();return;}
        try{
            var edited=new CabinetGameProfile(editName.getValue(),editProfile.players(),editProfile.orientation(),editProfile.aspect(),editProfile.revision());
            pending=UUID.randomUUID();selecting=false;requestAt=System.nanoTime();status="正在保存服务器游戏资料…";
            PacketDistributor.sendToServer(new CabinetGameNetwork.LibraryRequest(pending,launch.lease(),launch.backend(),0,editGame.id,edited));rebuildWidgets();
        }catch(IllegalArgumentException invalid){status="名称不能包含控制字符、格式代码，最多 64 字符";rebuildWidgets();}
    }
    @Override protected void rebuildWidgets(){
        if(editing){super.rebuildWidgets();return;}
        boolean focused=search!=null&&search.isFocused();int cursor=search==null?0:search.getCursorPosition();
        super.rebuildWidgets();
        if(focused&&search!=null&&!busy()){setInitialFocus(search);search.moveCursorTo(cursor,false);}
    }
    private void tab(boolean value){if(busy()||localTab==value)return;localTab=value;query="";search=null;page=0;focus="";rebuildWidgets();if(value)scan(false);else request(offset,"");}
    private void turn(int direction){
        if(busy())return;int pages=Math.max(1,(rows.size()+layout.rows()-1)/layout.rows());
        if(direction<0&&page==0&&!localTab&&offset>0){request(Math.max(0,offset-CabinetLibraryPage.SIZE),"");return;}
        if(direction>0&&page+1>=pages&&!localTab&&offset+server.size()<total){request(offset+CabinetLibraryPage.SIZE,"");return;}
        page=Math.max(0,Math.min(pages-1,page+direction));rebuildWidgets();
    }
    private void request(int start,String content){
        if(!current()||pending!=null)return;
        if(System.nanoTime()-requestAt<300_000_000L){status="操作过快，请稍候再试";rebuildWidgets();return;}
        pending=UUID.randomUUID();selecting=!content.isEmpty();requestAt=System.nanoTime();status=selecting?"正在应用服务器游戏…":"正在读取服务器目录…";
        PacketDistributor.sendToServer(new CabinetGameNetwork.LibraryRequest(pending,launch.lease(),launch.backend(),start,content));rebuildWidgets();
    }
    private void reply(Connection source,CabinetGameNetwork.LibraryReply message){
        if(!current()||source!=connection||pending==null||!pending.equals(message.request())||!launch.lease().equals(message.lease())||!launch.backend().equals(message.backend()))return;
        pending=null;capabilities=message.capabilities();canEdit=message.canEdit();status=message.message();boolean selected=selecting;selecting=false;
        if(!has(PlayerContentPolicy.SERVER_ROM_USE))server=List.of();
        if(message.success()&&selected){CabinetClientBackends.startConfiguredServer(launch);return;}
        if(message.success()){serverLoaded=true;server=message.games();profiles.clear();for(int i=0;i<server.size();i++)profiles.put(server.get(i).contentId(),message.profiles().get(i));offset=message.offset();total=message.total();page=0;focus=editing?editGame.id:"";editing=false;editName=null;}
        rebuildWidgets();
    }
    private void scan(boolean open){
        if(!current()||busy())return; // Browsing one's own disk is not permission to upload it.
        scanning=true;int task=++revision;status="正在扫描本地 ROM…";rebuildWidgets();
        if(!LocalRomLibrary.submit(()->{try{
            Path root=LocalRomLibrary.prepare(provider.romDirectory());var result=LocalRomLibrary.scan(root,provider.romExtensions(),provider.romExcludedNames());
            minecraft.execute(()->{if(!current()||revision!=task)return;scanning=false;
                local=result.entries();status="本地 "+local.size()+" 项 · 选择后上传";if(open)Util.getPlatform().openFile(root.toFile());rebuildWidgets();});
        }catch(Exception e){minecraft.execute(()->{if(current()&&revision==task){scanning=false;status="本地目录不可用："+e.getMessage();rebuildWidgets();}});}})){
            scanning=false;status="文件任务繁忙，请稍后刷新";rebuildWidgets();
        }
    }
    private void choose(){
        Row chosen=chosen();if(!current()||busy()||chosen==null)return;
        if(!localTab){if(has(PlayerContentPolicy.SERVER_ROM_USE))request(0,chosen.id);return;}
        if(!has(PlayerContentPolicy.ROM_UPLOAD))return;
        loading=true;int task=++revision;status="正在校验本地文件…";rebuildWidgets();
        if(!LocalRomLibrary.submit(()->{try{LocalRomLibrary.validateFile(chosen.path);minecraft.execute(()->{
            if(!current()||revision!=task)return;loading=false;if(has(PlayerContentPolicy.ROM_UPLOAD))CabinetClientBackends.start(chosen.path);});
        }catch(Exception error){minecraft.execute(()->{if(current()&&revision==task){loading=false;status="无法打开游戏："+error.getMessage();rebuildWidgets();}});}})){
            loading=false;status="文件任务繁忙，请稍后重试";rebuildWidgets();
        }
    }
    @Override public void tick(){
        if(!current()){onClose();return;}
        if(pending!=null&&System.nanoTime()-requestAt>15_000_000_000L){pending=null;selecting=false;status="服务器目录请求超时，尚未确认读取结果；请刷新重试";rebuildWidgets();}
    }
    @Override public void onClose(){if(closed)return;if(editing&&current()&&!busy()){backFromEditor();return;}closed=true;revision++;CabinetClientBackends.stop(null,true);if(minecraft.screen==this)minecraft.setScreen(null);}
    @Override public void removed(){closed=true;revision++;}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void render(GuiGraphics g,int mx,int my,float dt){
        if(editing){
            g.fill(0,0,width,height,DeviceUi.BG);var p=editorPanel();DeviceUi.panel(g,font,p.x(),p.y(),p.width(),p.height(),"街机游戏资料",editGame.game.files().getFirst().name());
            if(p.width()<260||p.height()<248)DeviceUi.text(g,font,"请放大窗口或降低 GUI 缩放；Esc 返回",p.x()+12,p.y()+50,p.width()-24,DeviceUi.MUTED);
            else{
                DeviceUi.text(g,font,"显示名称（不改 ROM 文件名）",p.x()+12,p.y()+48,p.width()-24,DeviceUi.TEXT);
                DeviceUi.text(g,font,"人数/横竖屏是标注；不自动修改 PGM、DIP 或旋转",p.x()+12,p.y()+170,p.width()-24,DeviceUi.MUTED);
                DeviceUi.text(g,font,status,p.x()+12,p.y()+190,p.width()-24,DeviceUi.MUTED);
            }
            super.render(g,mx,my,dt);return;
        }
        g.fill(0,0,width,height,DeviceUi.BG);if(layout==null)return;var p=layout.panel();
        DeviceUi.panel(g,font,p.x(),p.y(),p.width(),p.height(),title.getString(),"来源："+(localTab?"本地文件":"服务器文件")+" · 运行："+mode);
        if(!layout.supported()){DeviceUi.text(g,font,"请放大窗口或降低 GUI 缩放；Esc 返回",p.x()+10,p.y()+44,p.width()-20,DeviceUi.MUTED);super.render(g,mx,my,dt);return;}
        var list=layout.list();var detail=layout.details();DeviceUi.section(g,list.x(),list.y(),list.width(),list.height());DeviceUi.section(g,detail.x(),detail.y(),detail.width(),detail.height());
        if(rows.isEmpty())DeviceUi.text(g,font,busy()?"正在读取…":localTab?"本地目录暂无匹配文件":!serverLoaded?"尚未取得服务器目录，请查看下方提示":"服务器暂无匹配文件",list.x()+8,list.y()+8,list.width()-16,DeviceUi.MUTED);
        Row chosen=chosen();DeviceUi.text(g,font,chosen==null?"选择一个游戏":chosen.name,detail.x()+8,detail.y()+6,detail.width()-16,DeviceUi.TEXT);
        if(detail.height()>65){DeviceUi.text(g,font,chosen==null?"文件来源与运行模式独立":chosen.details,detail.x()+8,detail.y()+22,detail.width()-16,DeviceUi.MUTED);
            DeviceUi.text(g,font,localTab?"确认后上传游戏及配套 BIOS":"已有服务器文件，不重复上传",detail.x()+8,detail.y()+38,detail.width()-16,DeviceUi.MUTED);}
        var s=layout.status();DeviceUi.status(g,font,(localTab?"本地":!serverLoaded?"服务器目录未读取":total==0?"服务器 0 项":("目录 "+(offset+1)+"–"+(offset+server.size())+" / "+total))+" · "+status,s.x(),s.y(),s.width(),busy());
        super.render(g,mx,my,dt);
        if(mx>=s.x()&&mx<s.right()&&my>=s.y()&&my<s.bottom())g.renderTooltip(font,Component.literal(status+"\n上传与使用服务器游戏分别授权；不会因此改变运行模式。"),mx,my);
    }
    private static String size(long bytes){return bytes>=1024*1024?String.format(Locale.ROOT,"%.1f MiB",bytes/(1024d*1024)):Math.max(1,bytes/1024)+" KiB";}
}
