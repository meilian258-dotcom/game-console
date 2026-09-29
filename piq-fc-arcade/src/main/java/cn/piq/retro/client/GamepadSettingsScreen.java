package cn.piq.retro.client;

import cn.piq.fcarcade.client.ui.DeviceUi;
import cn.piq.retro.input.GamepadState;
import cn.piq.retro.input.GamepadState.Control;
import cn.piq.retro.input.InputProfile;
import cn.piq.retro.input.RetroButtons;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Device overview and separate mapping view. Only Save commits this local draft. */
final class GamepadSettingsScreen extends Screen {
    private final Screen parent;
    private final String revision;
    private GamepadConfig draft;
    private List<GamepadInput.Device> devices=List.of();
    private final List<BindingRow> rows=new ArrayList<>();
    private GamepadInput.ProfileKind kind;
    private final String deviceLabel;
    private ControlPanelLayout box;
    private int page,pageSize,ticks;
    private boolean mapping,captureNeutral;
    private RetroButtons.Button capturing;
    private String message="只控制自己的席位；不会自动分配给联机 P2";
    private String feedback="未检测到手柄";
    private GamepadState latest;
    private record BindingRow(Button widget,RetroButtons.Button logical) { }

    GamepadSettingsScreen(Screen parent) {
        this(parent,GamepadInput.ProfileKind.valueOf(KeyboardInput.settingsProfile().name()),"");
    }
    GamepadSettingsScreen(Screen parent,GamepadInput.ProfileKind initialProfile,String deviceLabel) {
        super(Component.literal("实体手柄 / 控制器设置"));this.parent=parent;
        this.kind=java.util.Objects.requireNonNull(initialProfile);this.deviceLabel=java.util.Objects.requireNonNull(deviceLabel);
        var loaded=GamepadInput.settingsSnapshot();draft=loaded.config();revision=loaded.revision();
        if(!loaded.warning().isEmpty())message=loaded.warning();refresh();
    }
    private void refresh(){try{devices=GamepadInput.devices();}catch(RuntimeException|LinkageError failure){devices=List.of();message="设备读取失败；键盘仍可用";}}
    @Override protected void init(){
        clearWidgets();rows.clear();box=ControlPanelLayout.of(width,height);
        int x=box.innerX(),w=box.innerWidth(),half=(w-6)/2;
        if(!box.usable()){button("返回",x,Math.max(24,height-28),w,this::onClose);return;}
        if(mapping){initMapping(x,w,half);return;}
        int y=box.top()+(box.compact()?34:44);
        button("手柄输入："+(draft.enabled()?"开启":"关闭"),x,y,half,()->{draft=draft.enabled(!draft.enabled());init();})
                .setTooltip(Tooltip.create(Component.literal("新配置默认开启。仅在取得机器控制权、没有菜单且操作方案允许时传递输入。")));
        button("刷新设备",x+half+6,y,w-half-6,()->{refresh();message="已刷新；设备连接不等于已取得游戏控制权";init();});
        button(deviceName(),x,y+24,w,()->minecraft.setScreen(new GamepadDeviceScreen(this,draft.deviceKey(),key->{draft=draft.device(key);capturing=null;latest=null;message="设备选择已暂存，保存后生效";refresh();})));
        button("死区 −",x,y+48,70,()->adjustDeadzone(-5));button("死区 +",x+w-70,y+48,70,()->adjustDeadzone(5));
        int tab=(w-8)/3;
        for(var system:GamepadInput.ProfileKind.values())button((system==kind?"[ ":"")+ControlLabels.system(system.name())+(system==kind?" ]":""),
                x+system.ordinal()*(tab+4),y+72,tab,()->{kind=system;page=0;capturing=null;init();}).active=system!=kind;
        button("查看 / 修改 "+ControlLabels.system(kind.name())+" 按键映射…",x,y+96,w,()->{mapping=true;capturing=null;page=0;init();});
        button("取消",x,box.footer(),half,this::onClose);button("保存手柄设置",x+half+6,box.footer(),w-half-6,this::save);
    }
    private void initMapping(int x,int w,int half){
        var logical=logicalButtons();pageSize=Math.max(2,Math.min(6,(box.height()-128)/22))*2;
        page=Math.min(page,Math.max(0,(logical.length-1)/pageSize));
        for(int i=page*pageSize;i<Math.min(logical.length,(page+1)*pageSize);i++){
            var target=logical[i];int local=i-page*pageSize;
            Button b=button(logicalName(target)+" ← "+(capturing==target?"请按手柄键…":bindings(target)),x+local%2*(half+6),box.top()+46+local/2*22,half,()->{
                capturing=target;captureNeutral=false;message="先松开所有按钮并居中，再按实体键；Esc 取消";init();
            });
            b.setTooltip(Tooltip.create(Component.literal(logicalName(target)+" ← "+bindings(target)+"\n左键重新绑定；右键清空。方向默认同时支持左摇杆。")));rows.add(new BindingRow(b,target));
        }
        int pager=box.footer()-40;
        button("上一页",x,pager,64,()->{page--;capturing=null;init();}).active=page>0;
        button("下一页",x+w-64,pager,64,()->{page++;capturing=null;init();}).active=page+1<Math.max(1,(logical.length+pageSize-1)/pageSize);
        button("还原本系统映射",x,box.footer(),half,()->{draft=draft.profile(kind.name(),InputProfile.defaults());capturing=null;message="已暂存默认映射；返回后保存生效";init();});
        button("返回设备设置",x+half+6,box.footer(),w-half-6,()->{mapping=false;capturing=null;init();});
    }
    private Button button(String label,int x,int y,int w,Runnable action){return addRenderableWidget(DeviceUi.button(font,label,x,y,w,20,action,true,DeviceUi.Tone.NORMAL));}
    private void adjustDeadzone(int delta){int n=Math.max(5,Math.min(75,Math.round(draft.deadzoneEnter()*100)+delta));draft=draft.deadzone(n/100f,Math.max(0,n/100f-.07f));capturing=null;message="漂移时增大，迟钝时减小；保存后生效";init();}
    private String deviceName(){return draft.deviceKey().isEmpty()?"设备：自动选择（点击可指定）":devices.stream().filter(d->d.key().equals(draft.deviceKey())).findFirst().map(d->"设备 "+(d.slot()+1)+"："+d.name()+(d.mapped()?"":" · 无映射")).orElse("指定设备未连接（点击重新选择）");}
    private RetroButtons.Button[] logicalButtons(){return kind!=GamepadInput.ProfileKind.NES?RetroButtons.Button.values():new RetroButtons.Button[]{RetroButtons.Button.A,RetroButtons.Button.B,RetroButtons.Button.SELECT,RetroButtons.Button.START,RetroButtons.Button.UP,RetroButtons.Button.DOWN,RetroButtons.Button.LEFT,RetroButtons.Button.RIGHT};}
    private String logicalName(RetroButtons.Button b){return switch(b){
        case UP->"上";case DOWN->"下";case LEFT->"左";case RIGHT->"右";case START->"开始";case SELECT->kind==GamepadInput.ProfileKind.ARCADE?"投币":"选择";
        default->{if(kind!=GamepadInput.ProfileKind.ARCADE)yield b.name();yield switch(b){case B->"按键 1";case Y->"按键 2";case A->"按键 3";case X->"按键 4";case L->"按键 5";case R->"按键 6";default->b.name();};}
    };}
    private String bindings(RetroButtons.Button b){var controls=draft.profiles().get(kind.name()).bindings().getOrDefault(b,Set.of());return controls.isEmpty()?"未绑定":String.join(" / ",controls.stream().sorted().map(GamepadSettingsScreen::controlName).toList());}
    @Override public void tick(){
        if(minecraft==null)return;
        if (!minecraft.isWindowActive()) { captureNeutral = false; latest=null;feedback="窗口失焦，测试已暂停"; return; }
        try{
            if(++ticks%20==0)refresh();var sample=GamepadInput.sample(draft.deviceKey());
            if(latest==null||sample==null||!latest.device().equals(sample.device()))captureNeutral=false;
            latest=sample;
            if(sample==null){feedback="未连接 / 无标准映射；键盘仍可用";captureNeutral=false;return;}
            var pressed=captureControl(sample);feedback=pressed==null?"已连接 · 松键/居中可用":"检测到："+controlName(pressed);
            if(capturing==null)return;
            if(!captureNeutral){if(pressed==null && centered(sample, draft.deadzoneExit()))captureNeutral=true;return;}
            if(pressed!=null){draft=draft.profile(kind.name(),draft.profiles().get(kind.name()).with(capturing,Set.of(pressed)));message=logicalName(capturing)+" ← "+controlName(pressed)+"；返回后保存";capturing=null;init();}
        }catch(RuntimeException|LinkageError failure){capturing=null;latest=null;feedback="无法读取设备；请检查系统连接";}
    }
    private static boolean centered(GamepadState s,float exit){return s.buttons()==0&&Math.hypot(s.leftX(),s.leftY())<=exit&&Math.hypot(s.rightX(),s.rightY())<=exit&&s.leftTrigger()<=.35&&s.rightTrigger()<=.35;}
    private static Control captureControl(GamepadState s){
        for(Control c:Control.values())if(c.physicalButton()&&(s.buttons()&c.mask())!=0&&c!=Control.GUIDE)return c;
        if(s.leftTrigger()>=.65)return Control.LEFT_TRIGGER;if(s.rightTrigger()>=.65)return Control.RIGHT_TRIGGER;
        if(s.leftX()<=-.65)return Control.LEFT_STICK_LEFT;if(s.leftX()>=.65)return Control.LEFT_STICK_RIGHT;
        if(s.leftY()<=-.65)return Control.LEFT_STICK_UP;if(s.leftY()>=.65)return Control.LEFT_STICK_DOWN;
        if(s.rightX()<=-.65)return Control.RIGHT_STICK_LEFT;if(s.rightX()>=.65)return Control.RIGHT_STICK_RIGHT;
        if(s.rightY()<=-.65)return Control.RIGHT_STICK_UP;if(s.rightY()>=.65)return Control.RIGHT_STICK_DOWN;return null;
    }
    static String controlName(Control c){return switch(c){
        case SOUTH->"下方面键";case EAST->"右方面键";case WEST->"左方面键";case NORTH->"上方面键";
        case LEFT_BUMPER->"左肩 L/LB";case RIGHT_BUMPER->"右肩 R/RB";case LEFT_TRIGGER->"左扳机 LT/L2";case RIGHT_TRIGGER->"右扳机 RT/R2";
        case SELECT->"选择 / Back";case START->"开始 / Start";case GUIDE->"系统键";case LEFT_STICK_CLICK->"左杆按下";case RIGHT_STICK_CLICK->"右杆按下";
        case DPAD_UP->"十字上";case DPAD_DOWN->"十字下";case DPAD_LEFT->"十字左";case DPAD_RIGHT->"十字右";
        case LEFT_STICK_UP->"左杆上";case LEFT_STICK_DOWN->"左杆下";case LEFT_STICK_LEFT->"左杆左";case LEFT_STICK_RIGHT->"左杆右";
        case RIGHT_STICK_UP->"右杆上";case RIGHT_STICK_DOWN->"右杆下";case RIGHT_STICK_LEFT->"右杆左";case RIGHT_STICK_RIGHT->"右杆右";
    };}
    @Override public boolean mouseClicked(double x,double y,int b){if(b==1)for(var row:rows)if(row.widget().isMouseOver(x,y)){draft=draft.profile(kind.name(),draft.profiles().get(kind.name()).with(row.logical(),Set.of()));capturing=null;message=logicalName(row.logical())+" 已清空；返回后保存";init();return true;}return super.mouseClicked(x,y,b);}
    @Override public boolean keyPressed(int key,int scan,int modifiers){if(key==256&&capturing!=null){capturing=null;message="已取消本次绑定";init();return true;}if(key==256&&mapping){mapping=false;capturing=null;init();return true;}return super.keyPressed(key,scan,modifiers);}
    private void save() {
        if(capturing!=null){message="请先完成或取消按键绑定";return;}
        try{GamepadInput.save(draft, revision);onClose();}catch(Exception failure){message="未保存："+failure.getMessage();}
    }
    @Override public void onClose() { capturing = null; minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void renderBackground(GuiGraphics g,int x,int y,float dt){
        g.fill(0, 0, width, height,DeviceUi.BG);
        DeviceUi.panel(g,font,box.left(),box.top(),box.width(),box.height(),mapping?ControlLabels.system(kind.name())+" · 手柄按键映射":deviceLabel.isEmpty()?title.getString():deviceLabel+" · 实体手柄",mapping?"游戏动作 ← 实体位置；与键帽字母无关":box.compact()?"":"默认开启 · 自动识别 · 仅控制本机玩家");
    }
    @Override public void render(GuiGraphics g,int x,int y,float dt){
        super.render(g,x,y,dt);
        if(!box.usable()){g.drawCenteredString(font,"请放大窗口或降低 GUI 缩放",width/2,box.top()+34,0xFFFFFFFF);return;}
        if(mapping){g.drawCenteredString(font,(page+1)+" / "+Math.max(1,(logicalButtons().length+pageSize-1)/pageSize),width/2,box.footer()-34,0xFFFFFFFF);}
        else{
            int base=box.top()+(box.compact()?34:44);
            DeviceUi.text(g,font,Math.round(draft.deadzoneEnter()*100)+"% / 回中 "+Math.round(draft.deadzoneExit()*100)+"%",box.innerX()+76,base+54,box.innerWidth()-152,DeviceUi.TEXT);
            DeviceUi.section(g,box.innerX(),base+121,box.innerWidth(),box.compact()?22:48);
            DeviceUi.text(g,font,feedback,box.innerX()+5,base+125,box.innerWidth()-10,latest==null?DeviceUi.MUTED:0xFF216526);
            if(!box.compact()){
                String name=latest==null?"Xbox / PS / NS：取决于系统的标准映射":devices.stream().filter(d->d.key().equals(latest.device().id())).findFirst().map(GamepadInput.Device::name).orElse("设备已连接");
                DeviceUi.text(g,font,name,box.innerX()+5,base+139,box.innerWidth()-10,DeviceUi.TEXT);
                String axes=latest==null?"此处只测试手柄，不向游戏发送操作":String.format(java.util.Locale.ROOT,"左杆 X %.2f / Y %.2f   LT %.2f / RT %.2f",latest.leftX(),latest.leftY(),latest.leftTrigger(),latest.rightTrigger());
                DeviceUi.text(g,font,axes,box.innerX()+5,base+153,box.innerWidth()-10,DeviceUi.MUTED);
            }
        }
        DeviceUi.text(g,font,message,box.innerX(),box.footer()-13,box.innerWidth(),DeviceUi.MUTED);
        if(y>=box.footer()-16&&y<box.footer()-1&&x>=box.innerX()&&x<box.innerX()+box.innerWidth())g.renderTooltip(font,Component.literal(message),x,y);
    }
}
