package cn.piq.fcarcade.client;

import cn.piq.fcarcade.network.ModTrafficCounter;
import cn.piq.fcarcade.network.ModTrafficProbe;
import cn.piq.fcarcade.network.ModTrafficSession;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/** Only a counter and optional overlay; never blocks input, decodes ROMs, or polls other mods. */
@EventBusSubscriber(modid="piq_fc_arcade",value=Dist.CLIENT)
public final class NetworkDiagnosticsClient {
    private static Connection connection;
    private static ModTrafficCounter counter;
    private static boolean memoryConnection;
    private static boolean showHud;
    private static final ModTrafficSession SESSION=new ModTrafficSession();
    private static final Map<String,Supplier<List<NetworkDiagnosticsView.Device>>> DEVICES=new LinkedHashMap<>();
    private NetworkDiagnosticsClient() {}
    @SubscribeEvent public static void login(ClientPlayerNetworkEvent.LoggingIn event) { attach(); }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        connection=null;counter=SESSION.connect(null);memoryConnection=false;ModTrafficProbe.clientCollector(null);
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        attach();if(counter!=null)counter.sample(); // Keep a recent one-second window even while the HUD is hidden.
    }
    private static void attach() {
        var listener=Minecraft.getInstance().getConnection();
        var active=listener==null||!listener.getConnection().isConnected()?null:listener.getConnection();
        if(connection==active)return;
        connection=active;counter=SESSION.connect(active);
        memoryConnection=active!=null&&active.isMemoryConnection();
        // An in-process channel does not encode payloads or use the network. Do not fabricate traffic.
        ModTrafficProbe.clientCollector(active,memoryConnection?null:counter);
    }
    public static boolean showHud() { return showHud; }
    /** Session-local toggle; deliberately does not silently rewrite the user's client configuration. */
    public static void toggleHud() { showHud=!showHud; }
    public static void resetCounters(){attach();if(counter!=null)counter.reset();}
    /** Addons report their own admitted session; this registry never polls inputs or starts a core. */
    public static void registerDevices(String id,Supplier<List<NetworkDiagnosticsView.Device>> provider) {
        if(DEVICES.size()>=8||DEVICES.putIfAbsent(Objects.requireNonNull(id),Objects.requireNonNull(provider))!=null)
            throw new IllegalArgumentException("Duplicate/too many diagnostic providers");
    }
    private static List<NetworkDiagnosticsView.Device> devices() {
        var result=new ArrayList<NetworkDiagnosticsView.Device>();
        for(var provider:DEVICES.values())try {
            var devices=provider.get();if(devices!=null)for(var device:devices){if(result.size()>=64)break;if(device!=null)result.add(device);}
        }catch(RuntimeException|LinkageError ignored){/* Diagnostics must not interrupt gameplay. */}
        return List.copyOf(result);
    }
    public static List<String> lines() {
        attach();
        if(counter==null)return List.of("尚未连接服务器。");
        return NetworkDiagnosticsView.lines(counter.sample(),memoryConnection,devices());
    }
    @SubscribeEvent public static void render(RenderGuiEvent.Post event) {
        var mc=Minecraft.getInstance();
        if(!showHud||mc.level==null||mc.player==null||mc.screen!=null||mc.options.hideGui)return;
        var lines=lines();var g=event.getGuiGraphics();int width=0;
        for(var line:lines)width=Math.max(width,mc.font.width(line));
        width=Math.min(width,g.guiWidth()-16);int y=Math.max(8,g.guiHeight()-70-lines.size()*11);
        g.fill(5,y-4,13+width,y+lines.size()*11,0x99000000);
        for(String line:lines){g.drawString(mc.font,mc.font.plainSubstrByWidth(line,width),9,y,0xffffff,true);y+=11;}
    }
}
