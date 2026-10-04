package cn.piq.fcarcade.client.watch;

import cn.piq.fcarcade.client.ui.DeviceScreen;
import cn.piq.fcarcade.client.ui.DeviceUi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;

/** Local read-only subscriptions, not a device mode, controller, power or save editor. */
public final class WatchManagementScreen extends DeviceScreen {
    private static final int HEIGHT=224,ROWS=3;
    private final Screen parent;
    private final Connection connection;
    private WatchClient.ManagementView view;
    private int page,left,top,panelWidth,age;
    private WatchManagementScreen(Screen parent,Connection connection){super(Component.literal("旁观管理"));this.parent=parent;this.connection=connection;}
    public static void open(Screen parent){
        var mc=Minecraft.getInstance();if(mc.level==null||mc.getConnection()==null)return;
        mc.setScreen(new WatchManagementScreen(parent,mc.getConnection().getConnection()));
    }
    private boolean current(){return minecraft!=null&&minecraft.level!=null&&minecraft.getConnection()!=null
            &&minecraft.getConnection().getConnection()==connection&&connection.isConnected();}
    @Override protected void init(){
        DeviceUi.prepare();panelWidth=Math.max(1,Math.min(420,width-24));left=(width-panelWidth)/2;top=Math.max(8,(height-HEIGHT)/2);
        view=WatchClient.managementView();page=Math.max(0,Math.min(page,pages()-1));
        if(width<320||height<240){addRenderableWidget(DeviceUi.button(font,"返回",left,Math.max(35,height-32),panelWidth,20,this::onClose,true,DeviceUi.Tone.NORMAL));return;}
        for(int i=0;i<ROWS;i++){
            int index=page*ROWS+i;if(index>=view.watches().size())break;
            var row=view.watches().get(index);int y=top+44+i*31;
            var action=DeviceUi.button(font,row.paused()?"恢复":"暂停",left+panelWidth-73,y+4,63,20,()->{
                if(current()){if(row.paused())WatchClient.resumeWatch(row.handle());else WatchClient.pauseWatch(row.handle());rebuildWidgets();}
            },row.actionable(),DeviceUi.Tone.NORMAL);
            action.setTooltip(Tooltip.create(Component.literal(row.paused()?"恢复本连接对这一局的自动发现，不保证立即接入。":"暂停本连接对这一局的自动旁观；不关主机，不影响其他玩家。")));
            addRenderableWidget(action);
        }
        int half=(panelWidth-26)/2;
        addRenderableWidget(DeviceUi.button(font,"上一页",left+10,top+172,half,18,()->{page--;rebuildWidgets();},page>0,DeviceUi.Tone.NORMAL));
        addRenderableWidget(DeviceUi.button(font,"下一页",left+16+half,top+172,half,18,()->{page++;rebuildWidgets();},page+1<pages(),DeviceUi.Tone.NORMAL));
        addRenderableWidget(DeviceUi.button(font,"全部恢复自动旁观",left+10,top+196,half,20,()->{if(current()){WatchClient.resumeAllWatches();rebuildWidgets();}},
                view.watches().stream().anyMatch(WatchClient.ManagedWatch::paused),DeviceUi.Tone.NORMAL));
        addRenderableWidget(DeviceUi.button(font,"返回",left+16+half,top+196,half,20,this::onClose,true,DeviceUi.Tone.NORMAL));
    }
    private int pages(){return Math.max(1,(view.watches().size()+ROWS-1)/ROWS);}
    @Override public void tick(){if(!current()){minecraft.setScreen(null);return;}if(++age%5==0)rebuildWidgets();}
    @Override public void onClose(){minecraft.setScreen(current()?parent:null);}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void render(GuiGraphics g,int mx,int my,float partial){
        g.fill(0,0,width,height,DeviceUi.BG);
        DeviceUi.panel(g,font,left,top,panelWidth,HEIGHT,"旁观管理 · "+(page+1)+" / "+pages(),"本连接 / 这一局暂停；重连或来源重开后重新发现");
        if(width<320||height<240){g.drawWordWrap(font,Component.literal("请降低 GUI 缩放或放大窗口。"),left,top+43,panelWidth,DeviceUi.TEXT);super.render(g,mx,my,partial);return;}
        if(view.watches().isEmpty())DeviceUi.text(g,font,"当前没有旁观、准备中或暂停的来源。",left+10,top+51,panelWidth-20,DeviceUi.MUTED);
        for(int i=0;i<ROWS;i++){
            int index=page*ROWS+i;if(index>=view.watches().size())break;
            var row=view.watches().get(index);int y=top+44+i*31;DeviceUi.section(g,left+10,y,panelWidth-89,28);
            DeviceUi.text(g,font,label(row)+" · "+row.mode(),left+14,y+4,panelWidth-99,DeviceUi.TEXT);
            DeviceUi.text(g,font,row.state()+(row.paused()?" · 已暂停":"")+(row.nativeHeld()?" · 占 JNI 槽":""),left+14,y+16,panelWidth-99,DeviceUi.MUTED);
        }
        String slots="JNI 可用 "+view.nativeFree()+" / 4 · 关闭中 "+view.closing()+" · 待确认 "+view.pending();
        DeviceUi.text(g,font,slots,left+10,top+140,panelWidth-20,DeviceUi.TEXT);
        String status=view.nativeFree()==0?"原生槽已满：可先暂停一路，等关闭释放后再试操作。":view.status();
        DeviceUi.text(g,font,status,left+10,top+153,panelWidth-20,DeviceUi.MUTED);
        super.render(g,mx,my,partial);
        if(mx>=left+10&&mx<left+panelWidth-10&&my>=top+138&&my<top+168)
            g.renderTooltip(font,font.split(Component.literal(slots+"\n"+status+"\n"+view.status()+"\n已在操作的会话不会被旁观管理关闭。关闭期间仍计入真实原生上限；不自动改串流。"),Math.min(360,width-24)),mx,my);
        for(int i=0;i<ROWS;i++){
            int index=page*ROWS+i;if(index>=view.watches().size())break;
            if(mx>=left+10&&mx<left+panelWidth-79&&my>=top+44+i*31&&my<top+72+i*31){
                var row=view.watches().get(index);
                g.renderTooltip(font,font.split(Component.literal(label(row)+"\n"+row.descriptor().dimension()+"\n"+row.mode()+" · "+row.state()+"\n来源 "+row.handle().source().source()+"\n主持代次 "+row.handle().source().hostLease()),Math.min(360,width-24)),mx,my);
            }
        }
    }
    private static String label(WatchClient.ManagedWatch row){return row.descriptor().provider()+" @ "+row.descriptor().origin().pos().toShortString();}
}
