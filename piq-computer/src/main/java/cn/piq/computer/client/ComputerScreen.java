package cn.piq.computer.client;

import cn.piq.computer.*;
import cn.piq.computer.net.ComputerNetwork;
import cn.piq.computer.world.ComputerEntity;
import cn.piq.fcarcade.client.ui.DeviceScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Assembly management only. Keyboard/mouse control never opens a Screen. */
final class ComputerScreen extends DeviceScreen {
    private final ComputerNetwork.Open target;
    private final Object connection;
    private long sequence;
    private final Button[] slots=new Button[8];
    ComputerScreen(ComputerNetwork.Open target){super(Component.literal("电脑 · 装配"));this.target=target;connection=net.minecraft.client.Minecraft.getInstance().getConnection();}
    @Override protected void init(){
        for(var part:Assembly.Part.values())slots[part.ordinal()]=addRenderableWidget(Button.builder(Component.literal(part.label),b->send(part.ordinal())).bounds(width/2-145+(part.ordinal()%2)*150,48+(part.ordinal()/2)*27,140,22).build());
        addRenderableWidget(Button.builder(Component.literal("本机程序设置"),b->{var pc=current();if(pc!=null)minecraft.setScreen(new ComputerProgramScreen(this,pc));}).bounds(width/2-145,height-29,140,20).build());
        addRenderableWidget(Button.builder(Component.literal("关闭"),b->onClose()).bounds(width/2+5,height-29,140,20).build());
    }
    @Override public boolean isPauseScreen(){return false;}
    private ComputerEntity current(){return minecraft.getConnection()==connection?ComputerClient.current(target):null;}
    private void send(int slot){if(current()!=null)PacketDistributor.sendToServer(new ComputerNetwork.Command(target.dimension(),target.pos(),target.id(),target.token(),sequence++,6,slot,0,0,0));}
    @Override public void tick(){
        var pc=current();if(pc==null){onClose();return;}
        for(var part:Assembly.Part.values()){var b=slots[part.ordinal()];b.active=!pc.powered&&pc.panelOpen&&Assembly.removable(pc.installed,part);b.setMessage(Component.literal(part.label+(Assembly.has(pc.installed,part)?" · 取下":" · 未安装")));}
    }
    @Override public void onClose(){minecraft.setScreen(null);}
    @Override public void render(GuiGraphics g,int mx,int my,float dt){
        g.fill(0,0,width,height,0xe8101820);g.drawCenteredString(font,title,width/2,10,0xffffff);var pc=current();
        if(pc!=null){
            g.drawCenteredString(font,pc.powered?"运行中 · 可以开盖，拆装请先关机":pc.panelOpen?"侧板已打开":"Shift + 右键机箱开合侧板",width/2,30,0x9ee5c2);
            int yy=164;for(var line:font.split(Component.literal("手持零件右键安装；在此取下零件。右键键鼠直接操作电视，Esc 退出。游戏文件只在本机程序设置中选择。"),Math.min(320,width-24))){if(yy>height-40)break;g.drawString(font,line,width/2-Math.min(320,width-24)/2,yy,0xc4c9cf,false);yy+=11;}
        }
        super.render(g,mx,my,dt);
    }
}
