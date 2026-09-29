package cn.piq.fcarcade.client;

import cn.piq.fcarcade.FcNetwork;
import cn.piq.fcarcade.client.ui.DeviceUi;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

final class RomRenameScreen extends cn.piq.fcarcade.client.ui.DeviceScreen {
    private final Screen parent;
    private final BlockPos blockPos;
    private final String romSha256;
    private final String currentName;
    private EditBox name;
    private Button submitButton;
    private String draft;
    private int left,top,panelWidth;
    RomRenameScreen(Screen parent,BlockPos blockPos,String romSha256,String currentName){
        super(Component.translatable("screen.piq_fc_arcade.rename_rom_title"));
        this.parent=parent;this.blockPos=blockPos.immutable();this.romSha256=romSha256;this.currentName=currentName;draft=currentName;
    }
    @Override protected void init(){
        if(name!=null)draft=name.getValue();
        panelWidth=Math.max(120,Math.min(440,width-24));left=(width-panelWidth)/2;top=(height-156)/2;
        name=new EditBox(font,left+12,top+72,panelWidth-24,20,Component.literal("新名称"));
        name.setMaxLength(80);name.setValue(draft);addRenderableWidget(name);
        int bw=(panelWidth-28)/2;
        addRenderableWidget(DeviceUi.button(font,"取消",left+12,top+112,bw,20,this::onClose,true,DeviceUi.Tone.QUIET));
        submitButton=addRenderableWidget(DeviceUi.button(font,"保存名称",left+16+bw,top+112,bw,20,this::submit,!draft.isBlank(),DeviceUi.Tone.PRIMARY));
        name.setResponder(value->{draft=value;submitButton.active=!value.isBlank();});setInitialFocus(name);
    }
    private void submit(){
        String value=name.getValue().strip();if(value.isBlank())return;
        FcNetwork.renameRom(blockPos,romSha256,value);if(minecraft!=null)minecraft.setScreen(null);
    }
    @Override public void onClose(){if(minecraft!=null)minecraft.setScreen(parent);}
    @Override public void render(GuiGraphics g,int mx,int my,float partial){
        g.fill(0,0,width,height,DeviceUi.BG);
        DeviceUi.panel(g,font,left,top,panelWidth,156,"FC / 重命名游戏","当前名称 · "+currentName);
        DeviceUi.text(g,font,"新名称",left+12,top+54,panelWidth-24,DeviceUi.MUTED);
        super.render(g,mx,my,partial);
    }
    @Override public boolean isPauseScreen(){return false;}
}
