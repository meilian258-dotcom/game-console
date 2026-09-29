package cn.piq.fcarcade.client;

import cn.piq.fcarcade.ArcadeSaveSlotActionPayload;
import cn.piq.fcarcade.ArcadeSaveSlotEntry;
import cn.piq.fcarcade.ArcadeSaveSlotsPayload;
import cn.piq.fcarcade.FcNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import cn.piq.fcarcade.client.ui.DeviceConfirmScreen;
import cn.piq.fcarcade.client.ui.DeviceUi;
import cn.piq.fcarcade.client.ui.DeviceFormLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

final class ArcadeSaveSlotsScreen extends cn.piq.fcarcade.client.ui.DeviceScreen {
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(ZoneId.systemDefault());

    private final ArcadeSaveSlotsPayload payload;
    private final java.util.UUID homeToken;
    private final boolean gunSave;
    private final boolean cartridge;
    private final net.minecraft.network.Connection source;
    private boolean sent;
    private final String[] names = new String[3];
    private final int[] players = new int[3];
    private final EditBox[] nameFields = new EditBox[3];
    private int selectedSlot;
    private DeviceFormLayout layout;

    ArcadeSaveSlotsScreen(ArcadeSaveSlotsPayload payload) {
        this(payload,null,false);
    }
    ArcadeSaveSlotsScreen(ArcadeSaveSlotsPayload payload,java.util.UUID homeToken,boolean gunSave) {
        this(payload,homeToken,gunSave,false);
    }
    ArcadeSaveSlotsScreen(ArcadeSaveSlotsPayload payload,java.util.UUID homeToken,boolean gunSave,boolean cartridge) {
        super(Component.translatable(
                "screen.piq_fc_arcade.save_slots_title"));
        this.payload = payload;
        this.homeToken=homeToken;this.gunSave=gunSave;
        this.cartridge=cartridge;
        var connection=net.minecraft.client.Minecraft.getInstance().getConnection();this.source=connection==null?null:connection.getConnection();
        for (int index = 0; index < 3; index++) {
            ArcadeSaveSlotEntry slot = payload.slots().get(index);
            names[index] = slot.name();
            players[index] = slot.players();
        }
    }

    @Override protected void init(){
        layout=DeviceFormLayout.of(width,height,5);int left=layout.left(),w=layout.bodyWidth(),third=(w-8)/3;
        if(width<320||height<240){addRenderableWidget(DeviceUi.button(font,"返回",layout.left(),layout.footerY(),layout.bodyWidth(),20,this::onClose,true,DeviceUi.Tone.QUIET));return;}
        for(int i=0;i<(cartridge?1:3);i++){
            final int index=i;var slot=payload.slots().get(i);
            addRenderableWidget(DeviceUi.button(font,cartridge?"这张卡带的进度":(i==selectedSlot?"> ":"")+"槽位 "+(i+1)+(slot.occupied()?" · 已有":" · 空"),left+i*(third+4),layout.rowY(0),cartridge?w:i==2?w-2*(third+4):third,20,
                    ()->{selectedSlot=index;rebuildWidgets();},true,i==selectedSlot?DeviceUi.Tone.PRIMARY:DeviceUi.Tone.NORMAL));
        }
        int index=selectedSlot;var slot=payload.slots().get(index);boolean editable=!slot.occupied()||isCurrentRom(slot);
        EditBox name=new EditBox(font,layout.fieldX(),layout.rowY(2),layout.fieldWidth(),20,Component.literal("存档名称"));
        name.setMaxLength(32);name.setValue(names[index]);name.setResponder(value->names[index]=value);name.setEditable(editable);
        nameFields[index]=name;addRenderableWidget(name);
        addRenderableWidget(DeviceUi.button(font,modeLabel(index).getString(),left,layout.rowY(3),third,20,
                ()->{players[index]=players[index]==1?2:1;rebuildWidgets();},editable,DeviceUi.Tone.NORMAL));
        addRenderableWidget(DeviceUi.button(font,"应用信息",left+third+4,layout.rowY(3),third,20,
                ()->send(index,ArcadeSaveSlotActionPayload.RENAME,true),slot.occupied()&&isCurrentRom(slot),DeviceUi.Tone.NORMAL));
        addRenderableWidget(DeviceUi.button(font,"删除存档…",left+2*(third+4),layout.rowY(3),w-2*(third+4),20,
                ()->confirmDelete(index),slot.occupied()&&isCurrentRom(slot),DeviceUi.Tone.DANGER));
        int half=(w-6)/2;
        addRenderableWidget(DeviceUi.button(font,!slot.occupied()?"开始游戏":isCurrentRom(slot)?"继续游戏":"替换为当前游戏…",
                left,layout.rowY(4),half,20,()->play(index),true,DeviceUi.Tone.PRIMARY));
        addRenderableWidget(DeviceUi.button(font,"从头开始…",left+half+6,layout.rowY(4),w-half-6,20,
                ()->confirmRestart(index),slot.occupied()&&isCurrentRom(slot),DeviceUi.Tone.DANGER));
        addRenderableWidget(DeviceUi.button(font,"按键设置",left,layout.footerY(),half,20,
                ()->{if(minecraft!=null)minecraft.setScreen(new KeyBindsScreen(this,minecraft.options));},true,DeviceUi.Tone.QUIET));
        addRenderableWidget(DeviceUi.button(font,"取消",left+half+6,layout.footerY(),w-half-6,20,this::onClose,true,DeviceUi.Tone.QUIET));
    }

    private Component modeLabel(int index) {
        return Component.translatable(
                players[index] == 2
                        ? "screen.piq_fc_arcade.save_slot_two_player"
                        : "screen.piq_fc_arcade.save_slot_one_player");
    }

    private void play(int index) {
        ArcadeSaveSlotEntry slot = payload.slots().get(index);
        if (!slot.occupied()) {
            send(index, ArcadeSaveSlotActionPayload.PLAY, false);
            return;
        }
        if (minecraft == null) return;
        if (!isCurrentRom(slot)) {
            minecraft.setScreen(new DeviceConfirmScreen(
                    replace -> {
                        if (replace) {
                            send(
                                    index,
                                    ArcadeSaveSlotActionPayload.PLAY,
                                    false);
                        } else {
                            minecraft.setScreen(this);
                        }
                    },
                    Component.translatable(
                            "screen.piq_fc_arcade.replace_save_title"),
                    Component.translatable(
                            "screen.piq_fc_arcade.replace_save_message",
                            slot.romName(),
                            payload.romName()),
                    Component.translatable(
                            "screen.piq_fc_arcade.save_slot_replace"),
                    Component.translatable("gui.cancel")));
            return;
        }
        send(index,ArcadeSaveSlotActionPayload.PLAY,true);
    }

    private void confirmRestart(int index){
        if(minecraft==null)return;
        minecraft.setScreen(new DeviceConfirmScreen(confirmed->{
            if(confirmed)send(index,ArcadeSaveSlotActionPayload.PLAY,false);else minecraft.setScreen(this);
        },Component.literal("从头开始？"),Component.literal(cartridge?"下一次成功保存将替换这张卡带的进度。":"将忽略当前进度并从头开始："+names[index]),
                Component.literal("从头开始"),Component.translatable("gui.cancel")));
    }

    private void confirmDelete(int index) {
        if (minecraft == null) return;
        minecraft.setScreen(new DeviceConfirmScreen(
                confirmed -> {
                    if (confirmed) {
                        send(
                                index,
                                ArcadeSaveSlotActionPayload.DELETE,
                                false);
                    } else {
                        minecraft.setScreen(this);
                    }
                },
                Component.translatable(
                        "screen.piq_fc_arcade.delete_save_title"),
                Component.translatable(
                        "screen.piq_fc_arcade.delete_save_message",
                        names[index]),
                Component.translatable(
                        "screen.piq_fc_arcade.delete_save_confirm"),
                Component.translatable("gui.cancel")));
    }

    private void send(int index, int action, boolean resume) {
        var choice=new ArcadeSaveSlotActionPayload(
                payload.blockPos(),
                payload.romSha256(),
                index + 1,
                action,
                names[index],
                players[index],
                resume);
        if(homeToken==null)FcNetwork.actOnSaveSlot(choice);
        else if(!sent&&connected()){sent=true;FcNetwork.actOnHomeSaveSlot(new cn.piq.fcarcade.ArcadeHomeSaveActionPayload(homeToken,choice));}
        if (minecraft != null) minecraft.setScreen(null);
    }
    private boolean connected(){var c=net.minecraft.client.Minecraft.getInstance().getConnection();return source!=null&&source.isConnected()&&c!=null&&c.getConnection()==source;}
    @Override public void onClose(){if(homeToken!=null&&!sent&&connected()){sent=true;FcNetwork.actOnHomeSaveSlot(new cn.piq.fcarcade.ArcadeHomeSaveActionPayload(homeToken,null));}super.onClose();}

    @Override public void render(GuiGraphics g,int mx,int my,float partial){
        g.fill(0,0,width,height,DeviceUi.BG);var p=layout.panel();
        DeviceUi.panel(g,font,p.x(),p.y(),p.width(),p.height(),cartridge?"FC / 继续卡带进度？":gunSave?"FC / 选择存档（已连接光枪）":"FC / 选择存档","当前游戏 · "+payload.romName());
        if(width<320||height<240){DeviceUi.text(g,font,"请放大窗口或降低 GUI 缩放",layout.left(),layout.panel().y()+45,layout.bodyWidth(),DeviceUi.MUTED);super.render(g,mx,my,partial);return;}
        var slot=payload.slots().get(selectedSlot);
        String info=slot.occupied()?slot.romName()+" · "+TIME_FORMAT.format(Instant.ofEpochMilli(slot.modifiedEpochMillis())):"空槽位 · 开始游戏时建立存档";
        DeviceUi.text(g,font,info,layout.left(),layout.rowY(1)+6,layout.bodyWidth(),DeviceUi.MUTED);
        DeviceUi.text(g,font,"存档名称",layout.left(),layout.rowY(2)+6,layout.labelWidth(),DeviceUi.TEXT);
        DeviceUi.status(g,font,cartridge?"进度随卡带保留，不受个人存档自动清理影响。":slot.occupied()&&!isCurrentRom(slot)?"此槽已有其他游戏，请选空槽或确认替换。":gunSave?"光枪存档与普通手柄存档分开保存。":"选择存档后开始游戏。",layout.left(),layout.statusY(),layout.bodyWidth(),false);
        super.render(g,mx,my,partial);
        if(mx>=layout.left()&&mx<layout.left()+layout.bodyWidth()&&my>=layout.rowY(1)&&my<layout.rowY(1)+20)g.renderTooltip(font,Component.literal(info),mx,my);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private boolean isCurrentRom(ArcadeSaveSlotEntry slot) {
        return payload.romSha256().equals(slot.romSha256());
    }
}
