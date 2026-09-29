// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.gba.client;

import cn.piq.fcarcade.client.cabinet.CabinetGameSelection;
import cn.piq.fcarcade.client.rom.LocalRomLibrary;
import cn.piq.fcarcade.client.rom.LocalRomPickerScreen;
import cn.piq.fcarcade.client.ui.DeviceScreen;
import cn.piq.fcarcade.client.ui.DeviceUi;
import cn.piq.retro.client.ControlSettingsScreen;
import cn.piq.retro.client.GamepadInput;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import java.nio.file.*;
import java.util.*;

/** Compact local-only setup. Playing happens with this page closed, on the held model. */
public final class GbaHandheldScreen extends DeviceScreen {
    private final GbaHandheldClient.Binding binding;
    private boolean quickStart,initialized,busy;
    private int revision;
    private Path selected;
    private String status="正在读取本机游戏选择…";
    private int x,y,w;
    GbaHandheldScreen(GbaHandheldClient.Binding binding,boolean quickStart){super(Component.literal("个人 GBA 掌机"));this.binding=binding;this.quickStart=quickStart;}
    private GbaHandheldScreen(GbaHandheldClient.Binding binding,Path selected){this(binding,false);this.selected=selected;initialized=true;status=selected==null?"请选择游戏；取消选择不会启动模拟器":"已选择；点击开始才会启动并记住选择";}
    @Override protected void init(){
        boolean readNow=!initialized;if(readNow){initialized=true;busy=true;}
        DeviceUi.prepare();w=Math.max(160,Math.min(300,width-20));x=(width-w)/2;y=Math.max(1,(height-238)/2);
        int inner=w-20,half=(inner-4)/2;
        button("选择本机 .gba 游戏…",x+10,y+65,inner,this::picker,!busy);
        addRenderableWidget(DeviceUi.button(font,"键盘 / 位置锁",x+10,y+89,half,20,()->minecraft.setScreen(new ControlSettingsScreen(this,cn.piq.retro.client.KeyboardConfig.Profile.SFC,"GBA（共享 SFC）")),!busy,DeviceUi.Tone.NORMAL))
                .setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("使用共享 SFC 方案。LEGACY 读取开机时有效主键；修改 Minecraft 原按键后重新开掌机生效。其它预设/自定义即时生效。")));
        button("实体手柄设置",x+14+half,y+89,inner-half-4,()->minecraft.setScreen(GamepadInput.settings(this,GamepadInput.ProfileKind.SFC,"GBA（共享 SFC）")),!busy);
        button(GbaHandheldClient.openingOrRunning()?"掌机运行中（关闭设置继续）":"开始所选游戏",x+10,y+113,inner,this::start,!busy&&selected!=null&&!GbaHandheldClient.openingOrRunning());
        button("关闭掌机",x+10,y+137,half,()->{GbaHandheldClient.stop("掌机已关闭；电池存档正在本机后台收尾");rebuildWidgets();},GbaHandheldClient.openingOrRunning());
        button("返回",x+14+half,y+137,inner-half-4,this::onClose,true);
        button(GbaJniChoice.enabled()?"运行：JNI 试验":"运行：独立进程",x+10,y+161,half,()->GbaJniChoice.choose(this),!busy&&!GbaHandheldClient.openingOrRunning());
        button("同步方式说明",x+14+half,y+161,inner-half-4,()->minecraft.setScreen(new GbaHandheldSyncInfoScreen(this,binding)),!busy);
        if(readNow)load();
    }
    private void button(String label,int x,int y,int w,Runnable action,boolean enabled){addRenderableWidget(DeviceUi.button(font,label,x,y,w,20,action,enabled,DeviceUi.Tone.NORMAL));}
    private boolean current(int token){return token==revision&&minecraft!=null&&minecraft.screen==this&&binding.current();}
    private void load(){
        busy=true;int token=++revision;
        if(!LocalRomLibrary.submit(()->{
            Path found=null;String error=null;
            try{found=binding.store().load().map(path->cn.piq.retro.storage.ConsoleStorage.rebind(binding.gameRoot,path)).orElse(null);}catch(Exception failure){error="选择读取失败："+failure.getMessage();}
            var result=found;var message=error;
            minecraft.execute(()->{if(!current(token))return;busy=false;selected=result;
                status=message!=null?message:result==null?"先选择游戏；仅扫描本机 game-console/piq-gba/roms":"已读取上次选择";
                boolean startNow=quickStart&&result!=null&&message==null;quickStart=false;rebuildWidgets();if(startNow)start();
            });
        })){busy=false;status="文件任务繁忙，请重新打开";}
    }
    private void picker(){
        if(busy||!binding.current())return;
        minecraft.setScreen(new LocalRomPickerScreen(Component.literal("选择 GBA 游戏"),binding.root.resolve("roms"),Set.of(".gba"),Set.of("gba_bios.bin"),
                "仅本机文件，不上传。选择后返回掌机设置，再确认开始。电池档与同玩家、同世界/服务器的玩家托管街机共用；服务器托管街机使用独立存档。",
                rom->{if(binding.current())minecraft.setScreen(new GbaHandheldScreen(binding,rom));else minecraft.setScreen(null);},
                ()->{if(binding.current())minecraft.setScreen(new GbaHandheldScreen(binding,selected));else minecraft.setScreen(null);}));
    }
    private void start(){
        if(busy||selected==null||!binding.current()||GbaHandheldClient.openingOrRunning())return;
        busy=true;status="正在校验游戏与固定运行库清单…";Path choice=selected;int token=++revision;rebuildWidgets();
        if(!LocalRomLibrary.submit(()->{
            String hash=null,error=null;
            try{
                CabinetGameSelection.validate(choice,Set.of(".gba"),Set.of("gba_bios.bin"));
                long bytes=Files.size(choice);if(bytes<192||bytes>32L*1024*1024)throw new java.io.IOException("GBA ROM 大小无效");
                Properties properties=new Properties();try(var input=GbaHandheldClient.class.getResourceAsStream("/piq-gba-runtime.properties")){
                    if(input==null)throw new java.io.IOException("缺少固定运行库清单");properties.load(input);
                }
                hash=properties.getProperty("helper.sha256");if(hash==null||!hash.matches("[A-F0-9]{64}"))throw new java.io.IOException("固定 helper SHA 无效");
            }catch(Exception failure){error=failure.getMessage();}
            var helper=hash;var message=error;
            minecraft.execute(()->{if(!current(token))return;busy=false;
                if(message!=null){status="无法开始："+message;rebuildWidgets();return;}
                if(GbaHandheldClient.start(binding,choice,helper)){
                    // Explicit choice only. This writes local configuration, never the held item or a server.
                    if(!LocalRomLibrary.submit(()->{try{binding.store().remember(choice);}catch(Exception failure){minecraft.execute(()->{
                        if(binding.current()&&choice.equals(GbaHandheldClient.currentRom()))cn.piq.fcarcade.client.ui.DeviceNotices.record("GBA","游戏可继续，但未记住本次路径",failure);
                    });}}))
                        GbaHandheldClient.notice("游戏已启动，但选择任务繁忙，未记住本次路径");
                }else{status="未启动；检查状态提示后重试";rebuildWidgets();}
            });
        })){busy=false;status="文件任务繁忙，请稍后再试";rebuildWidgets();}
    }
    @Override public void tick(){if(!binding.current()){revision++;minecraft.setScreen(null);}}
    @Override public void removed(){revision++;busy=false;}
    @Override public void onClose(){revision++;minecraft.setScreen(null);}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void render(GuiGraphics g,int mouseX,int mouseY,float partial){
        g.fill(0,0,width,height,DeviceUi.BG);DeviceUi.panel(g,font,x,y,w,238,"个人 GBA 掌机","只在本机运行 · 主手持有 · 退出自动保存电池档");
        DeviceUi.text(g,font,selected==null?"尚未选择游戏":selected.getFileName().toString(),x+10,y+47,w-20,DeviceUi.TEXT);
        DeviceUi.text(g,font,status,x+10,y+191,w-20,DeviceUi.MUTED);
        DeviceUi.text(g,font,"GBA 使用 SFC 按键方案：A / B / L / R",x+10,y+207,w-20,DeviceUi.MUTED);
        DeviceUi.text(g,font,GbaJniChoice.enabled()?"JNI 独立试验电池档；不覆盖原档":"本地电池档；服务器托管街机存档独立",x+10,y+221,w-20,DeviceUi.MUTED);
        super.render(g,mouseX,mouseY,partial);
        if(mouseX>=x+10&&mouseX<x+w-10&&mouseY>=y+189&&mouseY<y+205)g.renderTooltip(font,Component.literal(status),mouseX,mouseY);
    }
}
