package cn.piq.fcarcade.client;

import cn.piq.fcarcade.FcNetwork;
import cn.piq.fcarcade.LeaderboardPanelConfigPayload;
import cn.piq.fcarcade.LeaderboardPanelConfigUpdatePayload;
import cn.piq.fcarcade.client.ui.DeviceUi;
import cn.piq.fcarcade.client.ui.DeviceFormLayout;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class LeaderboardPanelScreen extends cn.piq.fcarcade.client.ui.DeviceScreen {
    private final LeaderboardPanelConfigPayload payload;
    private boolean enabled;
    private Button enabledButton;
    private EditBox intervalSeconds;
    private EditBox scalePercent;
    private EditBox offsetX;
    private EditBox offsetY;
    private EditBox depth;
    private Component error;
    private DeviceFormLayout layout;

    LeaderboardPanelScreen(LeaderboardPanelConfigPayload payload) {
        super(Component.translatable(
                "screen.piq_fc_arcade.leaderboard_panel_title"));
        this.payload = payload;
        this.enabled = payload.enabled();
    }

    @Override protected void init(){
        layout=DeviceFormLayout.of(width,height,6);int x=layout.fieldX(),w=layout.fieldWidth();
        if(width<320||height<240){addRenderableWidget(DeviceUi.button(font,"返回",layout.left(),layout.footerY(),layout.bodyWidth(),20,this::onClose,true,DeviceUi.Tone.QUIET));return;}
        enabledButton=addRenderableWidget(DeviceUi.button(font,enabledLabel().getString(),x,layout.rowY(0),w,20,
                ()->{enabled=!enabled;enabledButton.setMessage(enabledLabel());},true,DeviceUi.Tone.NORMAL));
        intervalSeconds=keep(intervalSeconds,integerField(x,layout.rowY(1),w,payload.intervalSeconds()));
        scalePercent=keep(scalePercent,integerField(x,layout.rowY(2),w,payload.scalePercent()));
        offsetX=keep(offsetX,decimalField(x,layout.rowY(3),w,payload.offsetXHundredths()));
        offsetY=keep(offsetY,decimalField(x,layout.rowY(4),w,payload.offsetYHundredths()));
        depth=keep(depth,decimalField(x,layout.rowY(5),w,payload.depthHundredths()));
        addRenderableWidget(intervalSeconds);addRenderableWidget(scalePercent);addRenderableWidget(offsetX);addRenderableWidget(offsetY);addRenderableWidget(depth);
        int bw=(layout.bodyWidth()-6)/2;
        addRenderableWidget(DeviceUi.button(font,"取消",layout.left(),layout.footerY(),bw,20,this::onClose,true,DeviceUi.Tone.QUIET));
        addRenderableWidget(DeviceUi.button(font,"保存设置",layout.left()+bw+6,layout.footerY(),layout.bodyWidth()-bw-6,20,this::save,true,DeviceUi.Tone.PRIMARY));
        setInitialFocus(intervalSeconds);
    }
    private static EditBox keep(EditBox previous,EditBox replacement){if(previous!=null)replacement.setValue(previous.getValue());return replacement;}
    @Override public void render(GuiGraphics g,int mx,int my,float partial){
        g.fill(0,0,width,height,DeviceUi.BG);var p=layout.panel();
        DeviceUi.panel(g,font,p.x(),p.y(),p.width(),p.height(),"FC / 排行榜面板","调整显示参数；保存后应用");
        if(width<320||height<240){DeviceUi.text(g,font,"请放大窗口或降低 GUI 缩放",layout.left(),layout.panel().y()+45,layout.bodyWidth(),DeviceUi.MUTED);super.render(g,mx,my,partial);return;}
        String[] labels={"leaderboard_enabled","leaderboard_interval","leaderboard_scale","leaderboard_offset_x","leaderboard_offset_y","leaderboard_depth"};
        for(int i=0;i<labels.length;i++)DeviceUi.text(g,font,Component.translatable("screen.piq_fc_arcade."+labels[i]).getString(),layout.left(),layout.rowY(i)+6,layout.labelWidth(),DeviceUi.TEXT);
        DeviceUi.status(g,font,error==null?Component.translatable("screen.piq_fc_arcade.leaderboard_panel_hint").getString():error.getString(),layout.left(),layout.statusY(),layout.bodyWidth(),false);
        super.render(g,mx,my,partial);
        if(error!=null&&mx>=layout.left()&&mx<layout.left()+layout.bodyWidth()&&my>=layout.statusY()&&my<layout.statusY()+18)g.renderTooltip(font,error,mx,my);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void label(GuiGraphics graphics, int x, int y, String suffix) {
        graphics.drawString(
                font,
                Component.translatable("screen.piq_fc_arcade." + suffix),
                x,
                y + 6,
                0xFFFFFF);
    }

    private Component enabledLabel() {
        return Component.translatable(enabled
                ? "screen.piq_fc_arcade.leaderboard_enabled"
                : "screen.piq_fc_arcade.leaderboard_disabled");
    }

    private EditBox integerField(int x, int y, int width, int value) {
        EditBox field = new EditBox(font, x, y, width, 20, Component.empty());
        field.setFilter(text -> text.matches("\\d{0,3}"));
        field.setValue(Integer.toString(value));
        return field;
    }

    private EditBox decimalField(int x, int y, int width, int hundredths) {
        EditBox field = new EditBox(font, x, y, width, 20, Component.empty());
        field.setFilter(text -> text.matches("-?\\d{0,2}(\\.\\d{0,2})?"));
        field.setValue(String.format(java.util.Locale.ROOT, "%.2f", hundredths / 100.0));
        return field;
    }

    private void save() {
        try {
            LeaderboardPanelConfigUpdatePayload update =
                    new LeaderboardPanelConfigUpdatePayload(
                            payload.blockPos(),
                            enabled,
                            parseInt(intervalSeconds, 1, 60),
                            parseInt(scalePercent, 25, 300),
                            parseHundredths(offsetX, -200, 200),
                            parseHundredths(offsetY, -200, 200),
                            parseHundredths(depth, -25, 100));
            error = null;
            FcNetwork.updateLeaderboardPanel(update);
            onClose();
        } catch (IllegalArgumentException invalid) {
            error = Component.translatable(
                    "screen.piq_fc_arcade.leaderboard_invalid_value");
        }
    }

    private static int parseInt(EditBox field, int minimum, int maximum) {
        int value = Integer.parseInt(field.getValue());
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException();
        }
        return value;
    }

    private static int parseHundredths(
            EditBox field,
            int minimum,
            int maximum
    ) {
        int value = (int) Math.round(Double.parseDouble(field.getValue()) * 100.0);
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException();
        }
        return value;
    }
}
