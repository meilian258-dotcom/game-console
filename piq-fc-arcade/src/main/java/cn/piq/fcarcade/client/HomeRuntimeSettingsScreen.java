package cn.piq.fcarcade.client;

import cn.piq.fcarcade.client.ui.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Shared content-card preferences, subordinate to the same family-console device settings page. */
final class HomeRuntimeSettingsScreen extends DeviceScreen {
    private final Screen parent;
    private final ResourceLocation system;
    private final String label;
    private int left,top,w;
    HomeRuntimeSettingsScreen(Screen parent,ResourceLocation system,String label){super(Component.literal("本机设置 · "+label));this.parent=parent;this.system=system;this.label=label;}
    protected void init(){
        DeviceUi.prepare();w=Math.max(1,Math.min(400,width-24));left=(width-w)/2;top=Math.max(8,(height-210)/2);
        if(width<320||height<230){button("返回（请降低 GUI 缩放）",44,this::onClose,true);return;}
        button("控制设置…",44,()->minecraft.setScreen(new cn.piq.retro.client.ControlSettingsScreen(this,PrivateHomeClient.settingsProfile(system),label)),true);
        button("运行环境 / 复制诊断…",72,()->minecraft.setScreen(new cn.piq.fcarcade.client.runtime.RuntimeEnvironmentScreen(this)),true);
        button("切换下次开机运行器（仅本机）",100,()->{PrivateHomeClient.cycleCartridgeRuntime(system);rebuildWidgets();},PrivateHomeClient.cartridgeRuntimeEditable());
        button("返回设备设置",176,this::onClose,true);
    }
    private void button(String text,int y,Runnable action,boolean active){addRenderableWidget(DeviceUi.button(font,text,left+10,top+y,w-20,20,action,active,DeviceUi.Tone.NORMAL));}
    public void onClose(){minecraft.setScreen(parent);}
    public boolean isPauseScreen(){return false;}
    public void render(GuiGraphics g,int mx,int my,float partial){
        g.fill(0,0,width,height,DeviceUi.BG);DeviceUi.panel(g,font,left,top,w,210,"本机设置 · "+label,"仅影响本机；不修改服务器权限或其他玩家");
        if(width>=320&&height>=230){
            g.drawWordWrap(font,Component.literal("JNI 为支持平台上的默认运行器。正常关机并完成保存后才能切换；不同核心/运行器存档隔离。"),left+10,top+128,w-20,DeviceUi.MUTED);
        }super.render(g,mx,my,partial);
    }
}
