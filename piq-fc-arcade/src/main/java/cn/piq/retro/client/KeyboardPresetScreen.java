package cn.piq.retro.client;

import cn.piq.fcarcade.client.ui.DeviceUi;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.function.Consumer;

/** Explicit scheme names, rather than an opaque 'current settings' toggle. */
final class KeyboardPresetScreen extends Screen {
    private final Screen parent;
    private final KeyboardConfig.Profile profile;
    private final KeyboardConfig.Preset selected;
    private final Consumer<KeyboardConfig.Preset> apply;
    private ControlPanelLayout box;
    KeyboardPresetScreen(Screen parent, KeyboardConfig.Profile profile, KeyboardConfig.Preset selected,
                         Consumer<KeyboardConfig.Preset> apply) {
        super(Component.literal(ControlLabels.system(profile.name())+" · 选择键盘方案"));
        this.parent=parent;this.profile=profile;this.selected=selected;this.apply=apply;
    }
    @Override protected void init() {
        box=ControlPanelLayout.of(width,height);
        if(box.usable()) {
            int i=0;
            for(var preset:KeyboardPresentation.choices()) {
                var b=addRenderableWidget(DeviceUi.button(font,(preset==selected?"> ":"")+KeyboardPresentation.name(preset,profile),
                        box.innerX(),box.top()+35+i++*25,box.innerWidth(),20,
                        ()->{apply.accept(preset);onClose();},true,DeviceUi.Tone.NORMAL));
                b.setTooltip(Tooltip.create(Component.literal(KeyboardPresentation.description(preset)+"\n"
                        +KeyboardPresentation.scopeDescription(preset)+"\n只修改 "+ControlLabels.system(profile.name())+" 的草稿，回上一页保存才生效。")));
            }
        }
        addRenderableWidget(DeviceUi.button(font,"返回",box.innerX(),Math.max(22,box.footer()),box.innerWidth(),20,this::onClose,true,DeviceUi.Tone.NORMAL));
    }
    @Override public void onClose(){minecraft.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void renderBackground(GuiGraphics g,int x,int y,float dt){
        g.fill(0,0,width,height,DeviceUi.BG);
        DeviceUi.panel(g,font,box.left(),box.top(),box.width(),box.height(),title.getString(),"");
    }
    @Override public void render(GuiGraphics g,int x,int y,float dt){
        super.render(g,x,y,dt);
        DeviceUi.text(g,font,box.usable()?"只修改 "+ControlLabels.system(profile.name())+" 的方案；回上一页保存才生效":"请放大窗口或降低 GUI 缩放",box.innerX(),box.footer()-15,box.innerWidth(),DeviceUi.MUTED);
    }
}
