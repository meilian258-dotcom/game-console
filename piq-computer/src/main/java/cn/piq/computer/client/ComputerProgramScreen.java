package cn.piq.computer.client;
import cn.piq.computer.ProgramKind;
import cn.piq.computer.world.ComputerEntity;
import cn.piq.fcarcade.client.ui.DeviceScreen;
import java.util.EnumMap;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;

/** Local setup only; gameplay remains on the in-world TV. */
final class ComputerProgramScreen extends DeviceScreen {
    private final Screen parent;private final ComputerEntity computer;
    private final EnumMap<ProgramKind,String> drafts=new EnumMap<>(ProgramKind.class);
    private ProgramKind kind;private EditBox path;private Button apply,stop,handoff,engine;
    ComputerProgramScreen(Screen parent,ComputerEntity pc){super(Component.literal("电脑 · 选择程序"));this.parent=parent;computer=pc;kind=ComputerPrograms.selection(pc);}
    @Override protected void init(){
        int left=width/2-150;
        for(var k:ProgramKind.values()){int x=left+k.ordinal()*102;addRenderableWidget(Button.builder(Component.literal((kind==k?"✓ ":"")+k.label),b->{remember();kind=k;rebuildWidgets();}).bounds(x,35,96,20).build());}
        path=new EditBox(font,left,78,243,20,Component.literal("本机文件完整路径"));path.setMaxLength(2048);path.setValue(drafts.getOrDefault(kind,ComputerPrograms.path(computer,kind)));path.setEditable(kind!=ProgramKind.HARDWARE);path.setResponder(v->drafts.put(kind,v));addRenderableWidget(path);
        addRenderableWidget(Button.builder(Component.literal("浏览…"),b->{remember();minecraft.setScreen(new ProgramFileScreen(this,kind,path.getValue(),value->{drafts.put(kind,value);minecraft.setScreen(this);}));}).bounds(left+248,78,52,20).build()).active=kind!=ProgramKind.HARDWARE;
        apply=addRenderableWidget(Button.builder(Component.literal("使用此程序"),b->{if(ComputerPrograms.configure(computer,kind,path.getValue()))onClose();}).bounds(left,106,145,20).build());
        stop=addRenderableWidget(Button.builder(Component.literal("结束当前程序"),b->ComputerPrograms.stop()).bounds(left+155,106,145,20).build());
        addRenderableWidget(Button.builder(Component.literal("鼠标："+(ComputerPrograms.fixedPointer()?"固定视角":"准星操作")),b->{remember();ComputerPrograms.inputOptions(!ComputerPrograms.fixedPointer(),ComputerPrograms.pointerSensitivity());rebuildWidgets();}).bounds(left,134,145,20).build());
        addRenderableWidget(Button.builder(Component.literal("灵敏度："+ComputerPrograms.pointerSensitivity()),b->{remember();double v=ComputerPrograms.pointerSensitivity()+.25;ComputerPrograms.inputOptions(ComputerPrograms.fixedPointer(),v>2?.25:v);rebuildWidgets();}).bounds(left+155,134,145,20).build());
        addRenderableWidget(Button.builder(Component.literal("下次启动："+(ComputerPrograms.sharing()?"共享旁观":"仅本机")),b->{remember();ComputerPrograms.streamOptions(!ComputerPrograms.sharing(),ComputerPrograms.streamTier());rebuildWidgets();}).bounds(left,162,145,20).build());
        addRenderableWidget(Button.builder(Component.literal("限速："+(cn.piq.computer.stream.StreamBudget.RATES[ComputerPrograms.streamTier()]/1024)+" KiB/s"),b->{remember();ComputerPrograms.streamOptions(ComputerPrograms.sharing(),(ComputerPrograms.streamTier()+1)%3);rebuildWidgets();}).bounds(left+155,162,145,20).build());
        handoff=addRenderableWidget(Button.builder(Component.literal("交接键鼠：锁定"),b->{ComputerStreams.handoff(!ComputerStreams.handoff());}).bounds(left,190,145,20).build());
        addRenderableWidget(Button.builder(Component.literal("返回"),b->onClose()).bounds(left+155,190,145,20).build());
        engine=null;if(kind==ProgramKind.PVZ)engine=addRenderableWidget(Button.builder(Component.literal("下次启动："+(ComputerPrograms.pvzJni()?"JNI（默认，独立档）":"独立进程（兼容）")),b->{
            remember();if(ComputerPrograms.pvzJni()){ComputerPrograms.pvzJni(false);rebuildWidgets();return;}
            minecraft.setScreen(new ConfirmScreen(accepted->{if(accepted)ComputerPrograms.pvzJni(true);minecraft.setScreen(this);},Component.literal("启用通用 JNI v1 的 PvZ 试验？"),Component.literal("仅 Windows x64。原生故障可能让整个 Minecraft 崩溃；请先备份世界。使用新的独立试验存档，不导入原进程或旧JNI试验进度；FC/SFC/PvZ共用一个JNI活动槽。卡死后可能需要重启客户端。可结束程序后切回独立进程。")));
        }).bounds(left,218,300,20).build());updateButtons();
    }
    private void remember(){if(path!=null)drafts.put(kind,path.getValue());}
    private void updateButtons(){apply.active=!ComputerPrograms.busy()&&!ComputerStreams.remote(computer.hardwareId())&&ComputerPrograms.available(kind);stop.active=ComputerPrograms.busy()&&!ComputerStreams.remote(computer.hardwareId());if(engine!=null)engine.active=apply.active;handoff.active=ComputerStreams.isHost(computer.hardwareId());handoff.setMessage(Component.literal("交接键鼠："+(handoff.active&&ComputerStreams.handoff()?"允许":"锁定")));}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void tick(){if(computer.isRemoved()||minecraft.level!=computer.getLevel()||minecraft.player==null||minecraft.player.distanceToSqr(computer.getBlockPos().getCenter())>64)minecraft.setScreen(null);else updateButtons();}
    @Override public void onClose(){remember();minecraft.setScreen(parent);}
    @Override public void render(GuiGraphics g,int mx,int my,float dt){
        g.fill(0,0,width,height,0xf0101820);g.drawCenteredString(font,title,width/2,15,0xffffff);
        g.drawString(font,kind==ProgramKind.PVZ?"本机 main.pak":kind==ProgramKind.FLASH?"本机 .swf（不超过 32 MiB）":"仅测试硬件和键鼠",width/2-150,63,0xd5ddd9,false);
        String text=switch(kind){case PVZ->ComputerPrograms.available(kind)?"共享：附近自动旁观，允许交接后 Esc 让出键鼠。存档留在运行者端；非双人 Netplay。JNI 不自动提高旁观码率。":"请安装配套 PvZ prototype.11 附属。";case FLASH->"需 Flash 0.1.3 运行器。共享旁观/交接；方向键/空格、WASD/左Shift、鼠标左键。暂不保存进度。";default->"不运行游戏。右键键鼠可在电视上检测输入。";};
        int y=kind==ProgramKind.PVZ?242:218;for(var line:font.split(Component.literal(ComputerStreams.status(computer.hardwareId())),300)){if(y+11>height-8)break;g.drawString(font,line,width/2-150,y,0xe1c77b,false);y+=11;}
        for(var line:font.split(Component.literal(text),300)){if(y+11>height-8)break;g.drawString(font,line,width/2-150,y,0xc6d2df,false);y+=11;}
        for(var line:font.split(Component.literal(ComputerPrograms.status()),300)){if(y+14>height-8)break;g.drawString(font,line,width/2-150,y+8,0xe1c77b,false);y+=11;}
        super.render(g,mx,my,dt);
    }
}
