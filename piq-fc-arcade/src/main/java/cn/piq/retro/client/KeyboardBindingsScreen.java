package cn.piq.retro.client;

import cn.piq.fcarcade.client.ui.DeviceUi;
import cn.piq.retro.client.KeyboardConfig.Profile;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Nested draft editor; confirming returns a draft to the parent, not to disk. */
final class KeyboardBindingsScreen extends Screen {
    private final Screen parent;
    private final KeyboardConfig original;
    private final Profile profile;
    private final List<Integer> keys;
    private final Consumer<KeyboardConfig> apply;
    private final List<Row> rows = new ArrayList<>();
    private ControlPanelLayout box;
    private int capture = -1, page, pageSize;
    private String message = "左键改键，右键清空；先暂存，再回上一页保存";
    private record Row(Button widget, int bit) { }
    KeyboardBindingsScreen(Screen parent, KeyboardConfig original, Profile profile, List<Integer> keys, Consumer<KeyboardConfig> apply) {
        super(Component.literal(ControlLabels.system(profile.name()) + " · 自定义键盘"));
        this.parent=parent;this.original=original;this.profile=profile;this.keys=new ArrayList<>(keys);this.apply=apply;
    }
    @Override protected void init() {
        clearWidgets(); rows.clear(); box=ControlPanelLayout.of(width,height);
        int x=box.innerX(),w=box.innerWidth(),half=(w-6)/2;
        if(!box.usable()){capture=-1;button("返回",x,Math.max(22,height-28),w,this::onClose);return;}
        pageSize=Math.max(2,Math.min(6,(box.height()-112)/24))*2;
        page=Math.min(page,Math.max(0,(keys.size()-1)/pageSize));
        String[] labels=ControlLabels.nativeButtons(profile.name());
        for(int bit=page*pageSize;bit<Math.min(keys.size(),(page+1)*pageSize);bit++) {
            final int current=bit;int row=bit-page*pageSize;
            Button b=button(labels[bit]+"："+(capture==bit?"按一个键…":KeyboardInput.keyName(keys.get(bit))),
                    x+row%2*(half+6),box.top()+45+row/2*24,half,()->{capture=current;message="按键绑定中；Esc 取消；右键对应项目清空";init();});
            b.setTooltip(Tooltip.create(Component.literal(labels[bit]+" ← "+KeyboardInput.keyName(keys.get(bit))+"\n不能重复或占用位置锁、设置键与 Esc；功能键如占用 W，会优先游戏而不能前进")));
            rows.add(new Row(b,bit));
        }
        int pages=Math.max(1,(keys.size()+pageSize-1)/pageSize),pager=box.footer()-38;
        button("上一页",x,pager,66,()->{page--;capture=-1;init();}).active=page>0;
        button("下一页",x+w-66,pager,66,()->{page++;capture=-1;init();}).active=page+1<pages;
        button("取消",x,box.footer(),half,this::onClose);
        button("暂存自定义",x+half+6,box.footer(),w-half-6,()->{
            if(capture>=0){message="请完成或取消正在绑定的按键";return;}
            try { apply.accept(original.withCustom(profile,List.copyOf(keys)));onClose(); }
            catch(IllegalArgumentException e){message="不能暂存："+e.getMessage();}
        });
    }
    private Button button(String label,int x,int y,int w,Runnable action){return addRenderableWidget(DeviceUi.button(font,label,x,y,w,20,action,true,DeviceUi.Tone.NORMAL));}
    @Override public boolean keyPressed(int key,int scan,int modifiers){
        if(capture>=0){if(key!=256)keys.set(capture,key);capture=-1;message=key==256?"已取消本次改键":"修改已保留在草稿中；请暂存";init();return true;}
        return super.keyPressed(key,scan,modifiers);
    }
    @Override public boolean mouseClicked(double x,double y,int button){
        if(button==1)for(var row:rows)if(row.widget.isMouseOver(x,y)){keys.set(row.bit,-1);capture=-1;message="已清空该项；请暂存";init();return true;}
        return super.mouseClicked(x,y,button);
    }
    @Override public void onClose(){capture=-1;minecraft.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void renderBackground(GuiGraphics g,int x,int y,float dt){
        g.fill(0,0,width,height,DeviceUi.BG);DeviceUi.panel(g,font,box.left(),box.top(),box.width(),box.height(),title.getString(),"功能名称 → 实际键盘按键");
    }
    @Override public void render(GuiGraphics g,int x,int y,float dt){
        super.render(g,x,y,dt);
        if(!box.usable()){DeviceUi.text(g,font,"请放大窗口或降低 GUI 缩放",box.innerX(),box.top()+30,box.innerWidth(),DeviceUi.TEXT);return;}
        DeviceUi.text(g,font,message,box.innerX(),box.footer()-13,box.innerWidth(),DeviceUi.MUTED);
        String pageText=(page+1)+" / "+Math.max(1,(keys.size()+pageSize-1)/pageSize);
        g.drawCenteredString(font,pageText,width/2,box.footer()-32,0xFFFFFFFF);
        if(y>=box.footer()-16&&y<box.footer())g.renderTooltip(font,Component.literal(message),x,y);
    }
}
