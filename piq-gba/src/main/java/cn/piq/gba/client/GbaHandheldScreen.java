package cn.piq.gba.client;

import cn.piq.fcarcade.client.ui.*;
import cn.piq.fcarcade.home.content.ContentCardData;
import cn.piq.gba.item.*;
import cn.piq.retro.client.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** Settings never select a private file behind the physical cartridge's back. */
public final class GbaHandheldScreen extends DeviceScreen {
    private final GbaHandheldClient.Binding binding;
    private int x,y,w;
    GbaHandheldScreen(GbaHandheldClient.Binding binding,boolean ignored){super(Component.literal("GBA 掌机设置"));this.binding=binding;}
    @Override protected void init(){
        if(width<250||height<232){button("返回（请降低 GUI 缩放）",10,Math.max(20,height-32),Math.max(40,width-20),this::onClose,true);return;}
        DeviceUi.prepare();w=Math.min(340,width-20);x=(width-w)/2;y=Math.max(4,(height-222)/2);
        int inner=w-20,half=(inner-4)/2;
        button("键盘 / 位置锁",x+10,y+66,half,()->minecraft.setScreen(new ControlSettingsScreen(this,KeyboardConfig.Profile.SFC,"GBA（共享 SFC）")),true);
        button("实体手柄",x+14+half,y+66,half,()->minecraft.setScreen(GamepadInput.settings(this,GamepadInput.ProfileKind.SFC,"GBA（共享 SFC）")),true);
        button(GbaHandheldClient.openingOrRunning()?"关闭掌机":"启动卡带",x+10,y+92,inner,()->{GbaHandheldClient.request(GbaHandheldNetwork.POWER);onClose();},true);
        button("退出卡带（先关机）",x+10,y+118,inner,()->{GbaHandheldClient.request(GbaHandheldNetwork.EJECT);onClose();},!GbaHandheldClient.openingOrRunning());
        button(GbaJniChoice.enabled()?"运行：JNI 默认":"运行：进程兼容",x+10,y+144,half,()->GbaJniChoice.choose(this),!GbaHandheldClient.openingOrRunning());
        button("同步说明",x+14+half,y+144,half,()->minecraft.setScreen(new GbaHandheldSyncInfoScreen(this,binding)),true);
        button("返回（不断电）",x+10,y+190,inner,this::onClose,true);
    }
    private void button(String label,int x,int y,int w,Runnable r,boolean enabled){addRenderableWidget(DeviceUi.button(font,label,x,y,w,20,r,enabled,DeviceUi.Tone.NORMAL));}
    @Override public void tick(){if(!binding.current())minecraft.setScreen(null);}
    @Override public void onClose(){minecraft.setScreen(null);}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void render(GuiGraphics g,int mx,int my,float partial){
        if(width<250||height<232){g.fill(0,0,width,height,DeviceUi.BG);g.drawCenteredString(font,title,width/2,8,DeviceUi.TEXT);super.render(g,mx,my,partial);return;}
        g.fill(0,0,width,height,DeviceUi.BG);DeviceUi.panel(g,font,x,y,w,222,"GBA 掌机设置","右键举起/放下 · Shift＋右键开关");
        var card=GbaCartridgeSlot.card(binding.original);String title=ContentCardData.title(card);
        DeviceUi.text(g,font,card.isEmpty()?"尚未插卡":title.isBlank()?"空白 GBA 卡带":title,x+10,y+46,w-20,DeviceUi.TEXT);
        DeviceUi.text(g,font,"卡带右键老式电脑拷卡；放下只释放操作，不关机",x+10,y+171,w-20,DeviceUi.MUTED);
        super.render(g,mx,my,partial);
    }
}
