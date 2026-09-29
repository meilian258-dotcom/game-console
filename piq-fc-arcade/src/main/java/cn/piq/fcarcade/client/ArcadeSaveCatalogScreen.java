package cn.piq.fcarcade.client;

import cn.piq.fcarcade.ArcadeSaveCatalogEntry;
import cn.piq.fcarcade.ArcadeSaveCatalogPayload;
import cn.piq.fcarcade.FcNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import cn.piq.fcarcade.client.ui.DeviceConfirmScreen;
import cn.piq.fcarcade.client.ui.DeviceUi;
import cn.piq.fcarcade.client.ui.DeviceLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

final class ArcadeSaveCatalogScreen extends cn.piq.fcarcade.client.ui.DeviceScreen {
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(ZoneId.systemDefault());

    private final ArcadeSaveCatalogPayload payload;
    private int page,focused;
    private DeviceLayout.Browser layout;

    ArcadeSaveCatalogScreen(ArcadeSaveCatalogPayload payload) {
        super(Component.translatable(
                "screen.piq_fc_arcade.player_saves_title"));
        this.payload = payload;
    }

    @Override protected void init(){
        layout=DeviceLayout.browser(width,height,1);if(!layout.supported())return;
        int count=payload.entries().size(),rows=layout.rows();page=FcMenuLayout.clampPage(page,count,rows);focused=Math.max(0,Math.min(focused,count-1));
        for(int i=page*rows;i<Math.min(count,(page+1)*rows);i++){
            final int index=i;var entry=payload.entries().get(i);var r=layout.row(i-page*rows);
            var row=DeviceUi.row(font,entry.romName(),entry.legacy()?"旧版":entry.owner(),r.x(),r.y(),r.width(),r.height(),()->{focused=index;rebuildWidgets();},i==focused,false,true);
            row.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(entry.romName()+"\n"+entry.owner()+" · "+entry.slotName())));addRenderableWidget(row);
        }
        var r=layout.primary();var entry=selected();
        addRenderableWidget(DeviceUi.button(font,"删除所选存档…",r.x(),r.y(),r.width(),r.height(),()->{if(entry!=null)confirmDelete(entry);},entry!=null,DeviceUi.Tone.DANGER));
        var nav=layout.navigation();int col=(nav.width()-8)/3;
        addRenderableWidget(DeviceUi.button(font,"上一页",nav.x(),nav.y(),col,20,()->{page--;rebuildWidgets();},page>0,DeviceUi.Tone.QUIET));
        addRenderableWidget(DeviceUi.button(font,"下一页",nav.x()+col+4,nav.y(),col,20,()->{page++;rebuildWidgets();},(page+1)*rows<count,DeviceUi.Tone.QUIET));
        addRenderableWidget(DeviceUi.button(font,"返回",nav.x()+2*(col+4),nav.y(),nav.width()-2*(col+4),20,this::onClose,true,DeviceUi.Tone.QUIET));
    }
    private ArcadeSaveCatalogEntry selected(){return focused>=0&&focused<payload.entries().size()?payload.entries().get(focused):null;}
    @Override public void render(GuiGraphics g,int mx,int my,float partial){
        g.fill(0,0,width,height,DeviceUi.BG);var p=layout.panel();
        DeviceUi.panel(g,font,p.x(),p.y(),p.width(),p.height(),"FC / 存档资料库","浏览归属与进度 · 删除需要再次确认");
        if(layout.supported()){
            var t=layout.toolbar();var l=layout.list();var d=layout.details();var s=layout.status();
            DeviceUi.text(g,font,"存档列表 / 选择后查看详情",t.x(),t.y()+6,t.width(),DeviceUi.MUTED);
            DeviceUi.section(g,l.x(),l.y(),l.width(),l.height());DeviceUi.section(g,d.x(),d.y(),d.width(),d.height());var e=selected();
            DeviceUi.text(g,font,e==null?"暂无玩家存档":e.romName(),d.x()+6,d.y()+6,d.width()-12,DeviceUi.TEXT);
            if(layout.split()&&e!=null){
                DeviceUi.text(g,font,(e.legacy()?"旧版 · 归属未知":e.owner())+" · "+e.players()+" 人",d.x()+6,d.y()+24,d.width()-12,DeviceUi.MUTED);
                DeviceUi.text(g,font,e.slotName(),d.x()+6,d.y()+42,d.width()-12,DeviceUi.MUTED);
                if(d.height()>115)DeviceUi.text(g,font,TIME_FORMAT.format(Instant.ofEpochMilli(e.modifiedEpochMillis())),d.x()+6,d.y()+62,d.width()-12,DeviceUi.MUTED);
                if(d.height()>138)DeviceUi.text(g,font,formatBytes(e.fileBytes()),d.x()+6,d.y()+80,d.width()-12,DeviceUi.MUTED);
            }
            DeviceUi.status(g,font,(page+1)+" / "+FcMenuLayout.pageCount(payload.entries().size(),layout.rows())+" 页 · "+payload.entries().size()+" 个存档",s.x(),s.y(),s.width(),false);
        }else DeviceUi.text(g,font,"请放大窗口或降低 GUI 缩放；Esc 返回",p.x()+10,p.y()+44,p.width()-20,DeviceUi.MUTED);
        super.render(g,mx,my,partial);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KiB";
        return String.format("%.1f MiB", bytes / (1024.0 * 1024.0));
    }

    private void confirmDelete(ArcadeSaveCatalogEntry entry) {
        if (minecraft == null) return;
        minecraft.setScreen(new DeviceConfirmScreen(
                confirmed -> {
                    if (confirmed) {
                        FcNetwork.deleteSave(
                                payload.blockPos(),
                                entry.storageId());
                        minecraft.setScreen(null);
                    } else {
                        minecraft.setScreen(this);
                    }
                },
                Component.translatable(
                        "screen.piq_fc_arcade.delete_save_title"),
                Component.translatable(
                        "screen.piq_fc_arcade.delete_save_message",
                        entry.slotName().isBlank()
                                ? entry.romName()
                                : entry.slotName()),
                Component.translatable(
                        "screen.piq_fc_arcade.delete_save_confirm"),
                Component.translatable("gui.cancel")));
    }
}
