package cn.piq.fcarcade.client;

import cn.piq.fcarcade.ArcadeSettingsPayload;
import cn.piq.fcarcade.FcNetwork;
import cn.piq.fcarcade.config.ArcadeGlobalSettings;
import cn.piq.fcarcade.client.ui.DeviceUi;
import cn.piq.fcarcade.client.ui.DeviceFormLayout;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class ArcadeSettingsScreen extends cn.piq.fcarcade.client.ui.DeviceScreen {
    private final ArcadeSettingsPayload payload;
    private EditBox viewDistance;
    private EditBox audioDistance;
    private EditBox audioVolumePercent;
    private EditBox retentionDays;
    private Component error;
    private DeviceFormLayout layout;

    ArcadeSettingsScreen(ArcadeSettingsPayload payload) {
        super(Component.translatable(
                "screen.piq_fc_arcade.global_settings_title"));
        this.payload = payload;
    }

    @Override protected void init(){
        layout=DeviceFormLayout.of(width,height,4);int x=layout.fieldX(),w=layout.fieldWidth();
        if(width<320||height<240){addRenderableWidget(DeviceUi.button(font,"返回",layout.left(),layout.footerY(),layout.bodyWidth(),20,this::onClose,true,DeviceUi.Tone.QUIET));return;}
        viewDistance=keep(viewDistance,numericField(x,layout.rowY(0),w,Integer.toString(payload.settings().viewDistance())));
        audioDistance=keep(audioDistance,numericField(x,layout.rowY(1),w,Integer.toString(payload.settings().audioDistance())));
        audioVolumePercent=keep(audioVolumePercent,numericField(x,layout.rowY(2),w,Integer.toString(payload.settings().audioVolumePercent())));
        retentionDays=keep(retentionDays,numericField(x,layout.rowY(3),w,Integer.toString(payload.settings().saveRetentionDays())));
        addRenderableWidget(viewDistance);addRenderableWidget(audioDistance);addRenderableWidget(audioVolumePercent);addRenderableWidget(retentionDays);
        int bw=(layout.bodyWidth()-12)/3;
        addRenderableWidget(DeviceUi.button(font,"取消",layout.left(),layout.footerY(),bw,20,this::onClose,true,DeviceUi.Tone.QUIET));
        addRenderableWidget(DeviceUi.button(font,"控制设置",layout.left()+bw+6,layout.footerY(),bw,20,
                ()->minecraft.setScreen(new cn.piq.retro.client.ControlSettingsScreen(this)),true,DeviceUi.Tone.NORMAL));
        addRenderableWidget(DeviceUi.button(font,"保存设置",layout.left()+2*(bw+6),layout.footerY(),layout.bodyWidth()-2*(bw+6),20,this::save,true,DeviceUi.Tone.PRIMARY));
        setInitialFocus(viewDistance);
    }
    private static EditBox keep(EditBox previous,EditBox replacement){if(previous!=null)replacement.setValue(previous.getValue());return replacement;}
    @Override public void render(GuiGraphics g,int mx,int my,float partial){
        g.fill(0,0,width,height,DeviceUi.BG);var p=layout.panel();
        DeviceUi.panel(g,font,p.x(),p.y(),p.width(),p.height(),"FC / 设备设置","修改后保存；取消不会提交");
        if(width<320||height<240){DeviceUi.text(g,font,"请放大窗口或降低 GUI 缩放",layout.left(),layout.panel().y()+45,layout.bodyWidth(),DeviceUi.MUTED);super.render(g,mx,my,partial);return;}
        String[] labels={"setting_view_distance","setting_audio_distance","setting_audio_volume","setting_save_retention"};
        for(int i=0;i<labels.length;i++)DeviceUi.text(g,font,Component.translatable("screen.piq_fc_arcade."+labels[i]).getString(),layout.left(),layout.rowY(i)+6,layout.labelWidth(),DeviceUi.TEXT);
        DeviceUi.status(g,font,error==null?Component.translatable("screen.piq_fc_arcade.setting_retention_hint").getString():error.getString(),layout.left(),layout.statusY(),layout.bodyWidth(),false);
        super.render(g,mx,my,partial);
        if(error!=null&&mx>=layout.left()&&mx<layout.left()+layout.bodyWidth()&&my>=layout.statusY()&&my<layout.statusY()+18)g.renderTooltip(font,error,mx,my);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private EditBox numericField(
            int x,
            int y,
            int width,
            String value
    ) {
        EditBox field = new EditBox(
                font,
                x,
                y,
                width,
                20,
                Component.empty());
        field.setFilter(text -> text.matches("\\d{0,4}"));
        field.setValue(value);
        return field;
    }

    private void save() {
        try {
            ArcadeGlobalSettings settings = new ArcadeGlobalSettings(
                    parse(viewDistance),
                    parse(audioDistance),
                    parse(audioVolumePercent),
                    parse(retentionDays));
            error = null;
            FcNetwork.updateSettings(payload.blockPos(), settings);
            onClose();
        } catch (IllegalArgumentException invalid) {
            error = Component.literal(invalid.getMessage());
        }
    }

    private static int parse(EditBox field) {
        if (field.getValue().isBlank()) {
            throw new IllegalArgumentException("设置值不能为空");
        }
        return Integer.parseInt(field.getValue());
    }
}
