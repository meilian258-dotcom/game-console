import cn.piq.fcarcade.client.ui.DeviceNoticePolicy;
import cn.piq.fcarcade.client.ui.DeviceNotices;
import cn.piq.fcarcade.client.ui.DeviceNoticesClient;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import java.nio.file.Path;
import java.util.List;

/** Uses final production classes and real NeoForge events; no Minecraft instance/window/core is started. */
public final class DeviceNotices37Probe {
    private static int assertions;
    private static void check(boolean value,String name){assertions++;if(!value)throw new AssertionError(name);}
    private static void event(Component message,boolean overlay,boolean canceled,String name){
        var event=new ClientChatReceivedEvent.System(message,overlay);
        DeviceNoticesClient.systemMessage(event);
        check(event.isCanceled()==canceled,name);
        check(event.getMessage()==message,name+" unchanged original component");
    }
    public static void main(String[] args)throws Exception{
        Path expected=Path.of(args[0]).toRealPath();
        for(Class<?> type:List.of(DeviceNoticePolicy.class,DeviceNotices.class,DeviceNoticesClient.class))
            check(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(expected),"final JAR origin "+type.getSimpleName());
        DeviceNotices.clear();
        for(String key:List.of("controller_taken","controller_returned","session_joined","session_left","control_started",
                "appliance_console_on","appliance_console_off","appliance_tv_on","appliance_tv_off","appliance_volume",
                "appliance_console_hint","appliance_tv_hint","zapper_bind_hint","zapper_stand_taken","zapper_stand_returned"))
            for(boolean overlay:new boolean[]{true,false}) event(Component.translatable("message.piq_fc_arcade."+key),overlay,true,key);
        check(DeviceNotices.snapshot().isEmpty(),"routine never diagnosed as errors");
        for(String key:List.of("message.other_mod.controller_taken","message.other_mod.run_failed","message.piq_fc_arcade.join_request_denied",
                "message.piq_fc_arcade.controller_p2_approval","message.piq_fc_arcade.unknown_future_error_failed"))
            for(boolean overlay:new boolean[]{true,false}) event(Component.translatable(key),overlay,false,"unknown/invitation "+key);
        for(String text:List.of("[SFC] 已借出 P2 手柄；不会自动开机，请按主机电源","P1 手柄已归还。","已加入街机 P4；再次右键退出",
                "主机已关机；手柄仍保留","主机已重置","掌机已关闭；电池存档正在本机后台收尾",
                "请到主机或光枪支架归还光枪。","光枪已领取或等待主机批准；批准后可射击，并在 P1 空闲时操作游戏按键。"))
            event(Component.literal(text),true,true,"routine literal "+text);
        for(String text:List.of("[SFC] 申请已发给运行宿主；批准后领取手柄并同步当前进度",
                "正在上传缺失游戏文件：10% · 右键本机可取消","其他模组正常状态","", "手柄已归还。 未保存成功",
                "已向主机玩家申请 光枪；再次点击取消。","运行宿主拒绝了加入申请"))
            event(Component.literal(text),true,false,"unknown/workflow literal "+text);
        event(Component.literal("[SFC] 输入序列或频率异常，手柄已归还；主机继续"),true,true,"known SFC error moved to diagnosis");
        for(String key:List.of("home_wire_selected","home_wire_connected","home_wire_disconnected","home_wire_selection_cleared",
                "zapper_stand_selected","zapper_stand_connected","zapper_stand_disconnected","furniture.folded","furniture.riding"))
            event(Component.translatable("message.piq_fc_arcade."+key),true,true,"routine cable/furniture "+key);
        for(String text:List.of("已选主街机；60 秒内右键另一台单人或双人街机，按两柜实际席位连接。",
                "已连接 4 席：副柜从 P3 开始；两台机柜外观各自保留。", "街机通讯线已断开；两台外观保持不变。"))
            event(Component.literal(text),true,true,"routine cable "+text);
        event(Component.translatable("message.piq_fc_arcade.controller_taken").append(" additional diagnostic"),true,false,"composed message preserved");
        event(Component.literal("P1 手柄已归还。"),false,false,"explicit literal chat response preserved");
        event(Component.translatable("message.piq_fc_arcade.run_failed","Missing piqneogeo_libretro.dll"),true,true,"known runtime error summarized");
        check(DeviceNotices.last()!=null,"known error recorded");
        event(Component.translatable("message.piq_fc_arcade.run_failed","diagnostic command response"),false,false,"chat error remains visible");
        check(DeviceNotices.summarize("NoSuchFileException: /private/neogeo.zip").contains("BIOS"),"BIOS zip distinct");
        check(DeviceNotices.summarize("Missing piqneogeo_libretro.dll").contains("运行环境"),"dll never BIOS");
        check(DeviceNotices.summarize("缺少 piqneogeo_libretro.dll").contains("运行环境"),"Chinese dll never BIOS");
        check(DeviceNotices.summarize("AccessDeniedException: /private/data").contains("权限"),"permission summary");
        check(DeviceNotices.summarize("同步 TimeoutException").contains("同步超时"),"sync timeout summary");
        check(!DeviceNotices.summarize("/private/data/neogeo.zip missing").contains("private"),"UI no private path");
        for(String control:List.of("NONE","CONTROLLER_ONE","CONTROLLER_TWO","VOLUME_UP","VOLUME_DOWN","NEW_CONTROL"))
            for(boolean power:new boolean[]{true,false})check(DeviceNoticePolicy.buttonLabel(control,power)==null,"no hover "+control);
        check(DeviceNoticePolicy.buttonLabel("POWER",false).equals("右键 · 开机"),"off targets on");
        check(DeviceNoticePolicy.buttonLabel("POWER",true).equals("右键 · 关机"),"on targets off");
        check(DeviceNoticePolicy.buttonLabel("RESET",false).equals("右键 · 重置"),"reset target");
        DeviceNotices.clear();var first=DeviceNotices.record("FC","Probe error",new IllegalStateException("full root cause"));
        check(first.detail().contains("java.lang.IllegalStateException: full root cause"),"original Throwable detail");
        check(first.detail().contains("DeviceNotices37Probe.main"),"original stack in diagnostic");
        var old=DeviceNotices.snapshot();boolean immutable=false;try{old.clear();}catch(UnsupportedOperationException expectedFailure){immutable=true;}
        check(immutable,"immutable snapshot");
        DeviceNotices.record("FC","duplicate");var repeated=DeviceNotices.record("FC","duplicate");
        check(DeviceNotices.snapshot().size()==2&&repeated==DeviceNotices.last(),"two-second duplicate coalescing");
        for(int i=0;i<40;i++)DeviceNotices.record("SFC","Probe failure "+i);
        check(DeviceNotices.snapshot().size()==32,"bounded 32 memory");
        check(DeviceNotices.last().detail().equals("Probe failure 39"),"newest first");
        check(old.size()==1,"snapshot detached");
        DeviceNotices.clear();check(DeviceNotices.last()==null,"clear memory");
        System.out.println("{\"ok\":true,\"assertions\":"+assertions+",\"actual_neoforge_system_events\":true,\"final_jar_origin_verified\":true,\"minecraft_started\":false}");
    }
}
