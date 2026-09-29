package cn.piq.retro.client;

import cn.piq.fcarcade.client.ui.DeviceUi;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.List;
import java.util.function.Consumer;

/** Explicit device list; unmapped joysticks are visible, but cannot be selected. */
final class GamepadDeviceScreen extends Screen {
    private final Screen parent;
    private final String current;
    private final Consumer<String> choose;
    private ControlPanelLayout box;
    private List<GamepadInput.Device> devices=List.of();
    private String message="自动只选择标准手柄，不接管其他玩家";
    private int page,pageSize;
    GamepadDeviceScreen(Screen parent,String current,Consumer<String> choose){super(Component.literal("选择实体手柄"));this.parent=parent;this.current=current;this.choose=choose;refresh();}
    private void refresh(){try{devices=GamepadInput.devices();}catch(RuntimeException|LinkageError failure){devices=List.of();message="无法读取设备；请检查 USB / 蓝牙连接";}}
    @Override protected void init(){
        clearWidgets();box=ControlPanelLayout.of(width,height);int x=box.innerX(),w=box.innerWidth();
        if(!box.usable()){addRenderableWidget(DeviceUi.button(font,"返回",x,Math.max(22,height-28),w,20,this::onClose,true,DeviceUi.Tone.NORMAL));return;}
        addRenderableWidget(DeviceUi.row(font,"自动选择","推荐",x,box.top()+44,w,22,()->select(""),current.isEmpty(),false,true));
        pageSize=Math.max(1,(box.height()-146)/24);page=Math.min(page,Math.max(0,(devices.size()-1)/pageSize));
        for(int i=page*pageSize;i<Math.min(devices.size(),(page+1)*pageSize);i++){
            var d=devices.get(i);int y=box.top()+72+(i-page*pageSize)*24;
            addRenderableWidget(DeviceUi.row(font,(d.slot()+1)+" · "+d.name(),d.mapped()?"可用":"无映射",x,y,w,22,()->select(d.key()),current.equals(d.key()),false,d.mapped()));
        }
        int pager=box.footer()-38;
        addRenderableWidget(DeviceUi.button(font,"上一页",x,pager,64,20,()->{page--;init();},page>0,DeviceUi.Tone.NORMAL));
        addRenderableWidget(DeviceUi.button(font,"下一页",x+w-64,pager,64,20,()->{page++;init();},(page+1)*pageSize<devices.size(),DeviceUi.Tone.NORMAL));
        int half=(w-6)/2;
        addRenderableWidget(DeviceUi.button(font,"刷新",x,box.footer(),half,20,()->{refresh();init();},true,DeviceUi.Tone.NORMAL));
        addRenderableWidget(DeviceUi.button(font,"返回",x+half+6,box.footer(),w-half-6,20,this::onClose,true,DeviceUi.Tone.NORMAL));
    }
    private void select(String key){choose.accept(key);onClose();}
    @Override public void onClose(){minecraft.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void renderBackground(GuiGraphics g,int x,int y,float dt){g.fill(0,0,width,height,DeviceUi.BG);DeviceUi.panel(g,font,box.left(),box.top(),box.width(),box.height(),title.getString(),"点击列表选择；回到上一页保存后生效");}
    @Override public void render(GuiGraphics g,int x,int y,float dt){
        super.render(g,x,y,dt);
        if(!box.usable()){DeviceUi.text(g,font,"请放大窗口或降低 GUI 缩放",box.innerX(),box.top()+30,box.innerWidth(),DeviceUi.TEXT);return;}
        if(devices.isEmpty())DeviceUi.text(g,font,"暂无设备，请连接后刷新",box.innerX(),box.top()+77,box.innerWidth(),DeviceUi.MUTED);
        DeviceUi.text(g,font,message,box.innerX(),box.footer()-13,box.innerWidth(),DeviceUi.MUTED);
        if(y>=box.footer()-16&&y<box.footer())g.renderTooltip(font,Component.literal(message),x,y);
    }
}
