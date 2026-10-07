package cn.piq.fcarcade.client.cabinet;

import cn.piq.fcarcade.cabinet.*;
import cn.piq.fcarcade.client.ui.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import java.util.Objects;
import java.util.UUID;

/** Compact vanilla controls; a mode belongs to the cabinet, never to individual guests. */
final class CabinetSyncSettingsScreen extends DeviceScreen {
    private final Screen parent;private final CabinetTarget target;private final ResourceLocation backend;private final Connection connection;private final UUID debugToken;
    private CabinetSyncNetwork.Setting setting;private boolean pending=true,diagnostics,requested,timedOut;private int age,waiting,cooldown;
    private String status="正在查询服务器设置…";
    private int x,y,w,h;
    private boolean powerPage;
    CabinetSyncSettingsScreen(Screen parent,CabinetTarget target,ResourceLocation backend){
        this(parent,target,backend,null);
    }
    CabinetSyncSettingsScreen(Screen parent,CabinetTarget target,ResourceLocation backend,UUID debugToken){
        super(Component.literal(debugToken!=null?"设备调试":"同步与网络"));this.parent=parent;this.target=target;this.backend=backend;this.debugToken=debugToken;
        connection=Minecraft.getInstance().getConnection().getConnection();
    }
    @Override protected void init(){
        DeviceUi.prepare();w=Math.min(400,width-24);h=Math.min(224,height-24);x=(width-w)/2;y=(height-h)/2;
        CabinetSyncNetwork.setSettingsSink(this::receive);
        if(width<320||height<240){addRenderableWidget(DeviceUi.button(font,"返回",x+10,y+h-30,w-20,20,this::onClose,true,DeviceUi.Tone.NORMAL));return;}
        if(!requested){requested=true;pending=true;waiting=0;if(backend.equals(CabinetBackends.NES)){pending=false;status="FC 街机仅支持本地输入同步。";}else CabinetSyncNetwork.requestMode(target,backend,-1,debugToken);}
        if(diagnostics){
            addRenderableWidget(DeviceUi.button(font,"返回同步设置",x+10,y+h-30,w-20,20,()->{diagnostics=false;rebuildWidgets();},true,DeviceUi.Tone.NORMAL));return;
        }
        if(powerPage){initPowerPage();return;}
        int third=(w-28)/3;
        boolean editable=!pending&&!timedOut&&cooldown==0&&setting!=null&&setting.editable()&&!backend.equals(CabinetBackends.NES);
        int current=backend.equals(CabinetBackends.NES)?1:setting==null?-1:setting.mode();
        addRenderableWidget(DeviceUi.button(font,(current==0?"✓ ":"")+"玩家托管",x+10,y+44,third,20,()->apply(0),editable&&setting.playerMedia()&&current!=0,DeviceUi.Tone.NORMAL));
        addRenderableWidget(DeviceUi.button(font,(current==1?"✓ ":"")+"本地同步",x+14+third,y+44,third,20,()->apply(1),editable&&setting.supported()&&current!=1,DeviceUi.Tone.NORMAL));
        var hostedButton=addRenderableWidget(DeviceUi.button(font,(current==2?"✓ ":"")+"服务器托管",x+18+third*2,y+44,third,20,()->apply(2),editable&&setting.hosted()&&current!=2,DeviceUi.Tone.NORMAL));
        if(setting!=null)hostedButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(setting.hostedReason())));
        if(CabinetNetplay.supported(backend)){
            int col=(w-26)/2;
            addRenderableWidget(DeviceUi.button(font,(current==3?"✓ ":"")+"Netplay（实验）",x+10,y+68,col,20,()->apply(3),editable&&current!=3,DeviceUi.Tone.NORMAL));
            var save=addRenderableWidget(DeviceUi.button(font,"存档："+(setting==null?"查询中":new String[]{"不保存","个人","机器"}[setting.saveMode()]),x+16+col,y+68,col,20,()->apply(4+(setting.saveMode()+1)%3),editable,DeviceUi.Tone.NORMAL));
            save.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("仅 Netplay：个人档属于开机玩家，机器档属于通讯线主柜；按游戏隔离，每 30 秒及正常关机保存，开机自动读取。完整状态含币数和游戏设置；切换策略不迁移旧档。")));
        }
        if(CabinetCoinPolicy.supported(backend.toString())){
            int column=(w-26)/2;
            addRenderableWidget(DeviceUi.button(font,"投币："+(setting==null?"查询中":setting.coinRequired()?"实体投币":"免费按键"),x+10,y+h-102,column,20,this::applyCoin,
                !pending&&!timedOut&&cooldown==0&&setting!=null&&setting.coinEditable(),DeviceUi.Tone.NORMAL));
            var power=addRenderableWidget(DeviceUi.button(font,"关机 / 屏幕距离…",x+16+column,y+h-102,column,20,this::openPower,setting!=null&&!pending,DeviceUi.Tone.NORMAL));
            power.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("查看全服街机规则；管理员通过管理终端统一设置。")));
        }
        addRenderableWidget(DeviceUi.button(font,"本机传画面质量："+CabinetMediaTuning.quality().label,x+10,y+h-78,w-20,20,()->{CabinetMediaTuning.cycleQuality();rebuildWidgets();},true,DeviceUi.Tone.NORMAL));
        int half=(w-26)/2;
        addRenderableWidget(DeviceUi.button(font,"查看音画诊断",x+10,y+h-54,half,20,()->{diagnostics=true;rebuildWidgets();},true,DeviceUi.Tone.NORMAL));
        addRenderableWidget(DeviceUi.button(font,"托管帧率 / 网络",x+16+half,y+h-54,half,20,()->cn.piq.fcarcade.client.NetworkDiagnosticsScreen.open(this),!pending&&!timedOut&&cooldown==0&&(setting!=null||backend.equals(CabinetBackends.NES)),DeviceUi.Tone.NORMAL));
        addRenderableWidget(DeviceUi.button(font,"刷新状态",x+10,y+h-30,half,20,this::refresh,
            !pending&&!timedOut&&cooldown==0&&!backend.equals(CabinetBackends.NES),DeviceUi.Tone.NORMAL));
        addRenderableWidget(DeviceUi.button(font,"返回",x+16+half,y+h-30,half,20,this::onClose,true,DeviceUi.Tone.NORMAL));
    }
    private void refresh(){if(pending||timedOut||cooldown>0)return;pending=true;waiting=0;status="正在查询服务器设置…";CabinetSyncNetwork.requestMode(target,backend,-1,debugToken);rebuildWidgets();}
    private void apply(int mode){if(pending||timedOut||cooldown>0||setting==null||!setting.editable())return;pending=true;waiting=0;status="等待服务器确认…";CabinetSyncNetwork.requestMode(target,backend,mode,debugToken);rebuildWidgets();}
    private void applyCoin(){if(pending||timedOut||cooldown>0||setting==null||!setting.coinEditable()||debugToken==null)return;pending=true;waiting=0;status="正在保存整组投币规则…";CabinetSyncNetwork.requestCoinMode(target,backend,!setting.coinRequired(),debugToken);rebuildWidgets();}
    private void openPower(){if(setting==null||pending)return;powerPage=true;rebuildWidgets();}
    private void initPowerPage(){
        addRenderableWidget(DeviceUi.button(font,"返回",x+10,y+h-30,w-20,20,()->{powerPage=false;rebuildWidgets();},!pending,DeviceUi.Tone.NORMAL));
    }
    private void receive(CabinetSyncNetwork.Setting value){
        if(minecraft.screen!=this||minecraft.getConnection()==null||minecraft.getConnection().getConnection()!=connection||!value.target().equals(target)||!value.backend().equals(backend)
            ||!Objects.equals(value.debugToken(),debugToken))return;
        setting=value;pending=false;timedOut=false;waiting=0;cooldown=4;status=value.reason().isBlank()?"已保存，下次开机生效。":value.reason();rebuildWidgets();
    }
    @Override public void tick(){
        age++;if(minecraft.getConnection()==null||minecraft.getConnection().getConnection()!=connection||!target.matches(minecraft.level)||age>2400){onClose();return;}
        if(cooldown>0&&--cooldown==0)rebuildWidgets();
        if(pending&&++waiting>100){pending=false;timedOut=true;status="设置未确认，请关闭后重开。";rebuildWidgets();}
        // Read-only refresh while waiting. Do not force-close saving/host workers or replay changes.
        if(!pending&&!timedOut&&cooldown==0&&age%40==0&&age<1100&&setting!=null&&!setting.editable()
                &&minecraft.player!=null&&minecraft.player.hasPermissions(2)&&!backend.equals(CabinetBackends.NES))refresh();
    }
    @Override public void removed(){CabinetSyncNetwork.setSettingsSink(null);}
    @Override public void onClose(){if(minecraft.getConnection()!=null&&minecraft.getConnection().getConnection()==connection&&target.matches(minecraft.level))minecraft.setScreen(parent);else minecraft.setScreen(null);}
    @Override public void render(GuiGraphics g,int mx,int my,float partial){
        var metadata=CabinetBackends.find(backend);
        String targetText="目标："+target.anchor().getX()+", "+target.anchor().getY()+", "+target.anchor().getZ()
            +" · "+(metadata==null?backend.toString():metadata.displayName());
        g.fill(0,0,width,height,DeviceUi.BG);DeviceUi.panel(g,font,x,y,w,h,
            (powerPage?"关机与屏幕显示":diagnostics?"音画诊断":debugToken!=null?"设备调试":"同步与网络")+" · "+(target.dual()?"双人街机":"单人街机"),setting!=null&&!setting.gameInfo().isBlank()?"游戏 · "+setting.gameInfo():targetText);
        if(width<320||height<240){DeviceUi.text(g,font,"请降低 GUI 缩放或放大窗口",x+10,y+48,w-20,DeviceUi.MUTED);super.render(g,mx,my,partial);return;}
        var diagnosticLines=diagnostics?CabinetMediaTuning.diagnostics():java.util.List.<String>of();
        if(powerPage){
            var rules=CabinetClientSettings.rules();
            DeviceUi.text(g,font,"全服统一规则（此页只读）",x+10,y+48,w-20,DeviceUi.TEXT);
            DeviceUi.text(g,font,"最后一人右键退出立即关机："+(rules.immediateOnExit()?"开":"关"),x+10,y+72,w-20,DeviceUi.TEXT);
            DeviceUi.text(g,font,"无人占席倒计时："+(rules.idleSeconds()==0?"关闭（0秒）":rules.idleSeconds()+"秒"),x+10,y+94,w-20,DeviceUi.TEXT);
            DeviceUi.text(g,font,"显示 / 旁观距离："+rules.range()+"格；退出缓冲4格",x+10,y+116,w-20,DeviceUi.TEXT);
            DeviceUi.text(g,font,"修改入口：管理终端 → 街机全服",x+10,y+142,w-20,DeviceUi.MUTED);
            DeviceUi.text(g,font,"有人入席取消计时；无按键不算退出。",x+10,y+158,w-20,DeviceUi.MUTED);
        }else if(diagnostics){
            int row=y+46;for(String line:diagnosticLines){if(row>=y+h-62)break;DeviceUi.text(g,font,line,x+10,row,w-20,DeviceUi.TEXT);row+=13;}
            DeviceUi.text(g,font,"核心为近 1 秒墙钟耗时；音画流为创建以来平均",x+10,y+h-56,w-20,DeviceUi.MUTED);
            DeviceUi.text(g,font,"合并不丢按键边沿；核心过慢仍会延迟，网络另计",x+10,y+h-44,w-20,DeviceUi.MUTED);
        }else{
            if(CabinetNetplay.supported(backend)){
                DeviceUi.text(g,font,"Netplay：最多 "+CabinetNetplay.maxPlayers(backend)+" 席、"+(CabinetCoinPolicy.supported(backend.toString())?"支持实体投币、":"")+"存档策略见上方。",x+10,y+96,w-20,DeviceUi.MUTED);
            }else if(CabinetBackends.hostSnapshotSync(backend)&&setting!=null&&setting.mode()==1){
                DeviceUi.text(g,font,"本地输入同步：kof97 / mslug2，最多两席。",x+10,y+72,w-20,DeviceUi.MUTED);
                DeviceUi.text(g,font,"同步完成后即可操作；旁观接收音画。",x+10,y+84,w-20,DeviceUi.MUTED);
                DeviceUi.text(g,font,"切换模式需管理员权限，且机器空闲。",x+10,y+96,w-20,DeviceUi.MUTED);
            }else{
                DeviceUi.text(g,font,"玩家托管：主持电脑运行，其他玩家接收音画。",x+10,y+72,w-20,DeviceUi.MUTED);
                DeviceUi.text(g,font,"本地同步：玩家各自在本机运行；旁观接收音画。",x+10,y+84,w-20,DeviceUi.MUTED);
                DeviceUi.text(g,font,"服务器托管：服务器运行，玩家只接收音画。",x+10,y+96,w-20,DeviceUi.MUTED);
            }
            CabinetUi.paragraph(g,font,status,x+10,y+110,w-20,CabinetCoinPolicy.supported(backend.toString())?1:Math.max(1,(h-190)/10),pending?DeviceUi.MUTED:DeviceUi.TEXT);
        }
        super.render(g,mx,my,partial);
        if(mx>=x+8&&mx<x+w-8&&my>=y+8&&my<y+38)
            g.renderTooltip(font,Component.literal((setting==null?"":setting.gameInfo()+" · ")+targetText+" · "+target.dimension()),mx,my);
        else if(diagnostics&&mx>=x+10&&mx<x+w-10&&my>=y+46&&my<y+h-62){
            int line=(my-y-46)/13;
            if(line<diagnosticLines.size())g.renderTooltip(font,Component.literal(diagnosticLines.get(line)),mx,my);
        }
        else if(!diagnostics&&!powerPage&&mx>=x+8&&mx<x+w-8&&my>=y+110&&my<y+h-80)
            g.renderTooltip(font,Component.literal(status),mx,my);
    }
    @Override public boolean isPauseScreen(){return false;}
}
