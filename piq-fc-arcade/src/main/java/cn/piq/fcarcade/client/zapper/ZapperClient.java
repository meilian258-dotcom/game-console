package cn.piq.fcarcade.client.zapper;

import cn.piq.fcarcade.ArcadeZapperInputPayload;
import cn.piq.fcarcade.FcNetwork;
import cn.piq.fcarcade.client.ClientArcadeEvents;
import cn.piq.fcarcade.home.ZapperBinding;
import cn.piq.fcarcade.home.ZapperData;
import cn.piq.fcarcade.home.HomeZapperAim;
import cn.piq.fcarcade.home.HomeZapperItem;
import cn.piq.fcarcade.layout.ZapperPoseLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import java.util.UUID;
import java.util.LinkedHashMap;

/** One authorized local gun, no second owner/core and no spectator input capability. */
public final class ZapperClient {
    private static final ZapperInputState INPUT=new ZapperInputState();
    private static boolean registered,cleared=true;
    private static Connection source;
    private static ZapperBinding binding;
    private static int sequence;
    private static double triggerVisual;
    private static long visualTime;
    private static final LinkedHashMap<UUID,RemoteAnimation> REMOTE=new LinkedHashMap<>(16,.75F,true);
    private static Connection visualConnection;
    private static final class RemoteAnimation {double value;long time;final int epoch;RemoteAnimation(int epoch){this.epoch=epoch;}}
    private ZapperClient() {}
    public static void register(IEventBus modBus) {
        if(registered)return;registered=true;ZapperItemRenderer.register(modBus);
        FcNetwork.setZapperSink(new FcNetwork.ZapperSink(){
            public boolean acceptsConnection(Connection connection){var c=Minecraft.getInstance().getConnection();return connection!=null&&connection.isConnected()&&c!=null&&c.getConnection()==connection;}
            public void start(ZapperBinding next){ZapperClient.start(next);}
            public void stop(UUID lease){ZapperClient.stop(lease);}
        });
        NeoForge.EVENT_BUS.addListener(ZapperClient::tick);
        NeoForge.EVENT_BUS.addListener(ZapperClient::frame);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST,ZapperClient::mouse);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST,ZapperClient::interaction);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST,ZapperClient::screen);
        NeoForge.EVENT_BUS.addListener(ZapperClient::logout);
    }
    public static void start(ZapperBinding next) {
        var mc=Minecraft.getInstance();var c=mc.getConnection();
        if(next==null||c==null||!c.getConnection().isConnected()||mc.level==null
                ||!mc.level.dimension().location().equals(next.dimension()))return;
        if(source==c.getConnection()&&binding!=null&&binding.lease().equals(next.lease())) {
            if(binding.sessionId()!=next.sessionId()||next.epoch()<binding.epoch())return;
            if(binding.equals(next))return;
        }
        clear();binding=next;source=c.getConnection();sequence=0;INPUT.clear();cleared=false;
        triggerVisual=0;visualTime=System.nanoTime();clear();
        if(mc.gameMode!=null)mc.gameMode.stopDestroyBlock();
        mc.options.keyAttack.setDown(false);
        while(mc.options.keyAttack.consumeClick()){} // Discard only pre-loan attack clicks.
    }
    public static void stop(UUID lease) {if(binding!=null&&binding.lease().equals(lease))forget();}
    private static void forget(){binding=null;source=null;sequence=0;INPUT.clear();cleared=true;triggerVisual=0;visualTime=0;}
    private static boolean connectionCurrent(){var c=Minecraft.getInstance().getConnection();return source!=null&&source.isConnected()&&c!=null&&c.getConnection()==source;}
    private static boolean authorized(){var mc=Minecraft.getInstance();return binding!=null&&connectionCurrent()&&mc.player!=null
            &&mc.player.isAlive()&&!mc.player.isSpectator()&&mc.player.getMainHandItem().getItem()instanceof HomeZapperItem&&ZapperData.matches(mc.player.getMainHandItem(),binding)
            &&ClientArcadeEvents.authorizedZapper(binding);}
    private static boolean focused(){var mc=Minecraft.getInstance();return mc.screen==null&&mc.isWindowActive()&&!mc.isPaused()
            &&mc.player!=null&&mc.getCameraEntity()==mc.player;}
    /** Read-only local presentation query, never a remote gun or new input sample. */
    public static boolean sampledTrigger(ZapperBinding expected) {
        return INPUT.visualTrigger(expected!=null&&binding!=null&&binding.equals(expected)&&authorized()&&focused());
    }
    private static boolean physicalDown(){return GLFW.glfwGetMouseButton(Minecraft.getInstance().getWindow().getWindow(),GLFW.GLFW_MOUSE_BUTTON_LEFT)==GLFW.GLFW_PRESS;}
    private static void sample(boolean down) {
        if(!authorized()||!focused()){clear();return;}
        boolean trigger=INPUT.sample(true,down);
        var aim=HomeZapperAim.sample(Minecraft.getInstance().player,binding);
        send(aim.isPresent()?aim.get().x():0,aim.isPresent()?aim.get().y():0,aim.isEmpty(),trigger,false);
        cleared=false;
    }
    private static void send(int x,int y,boolean offscreen,boolean trigger,boolean force) {
        if(binding==null||!connectionCurrent()||sequence==Integer.MAX_VALUE)return;
        FcNetwork.sendZapperInput(new ArcadeZapperInputPayload(binding.sessionId(),binding.epoch(),binding.lease(),
                sequence++,x,y,offscreen,trigger,force));
    }
    private static void clear(){INPUT.clear();if(!cleared){send(0,0,true,false,true);cleared=true;}}
    private static void tick(ClientTickEvent.Post event) {
        if(binding==null)return;if(!connectionCurrent()){forget();return;}sample(physicalDown());
    }
    private static void frame(RenderFrameEvent.Pre event) {
        if(binding==null)return;if(!connectionCurrent()){forget();return;}
        if(!authorized()||!focused())clear();
        long now=System.nanoTime();triggerVisual=ZapperPoseLayout.advanceTrigger(triggerVisual,INPUT.trigger(),visualTime==0?0:now-visualTime);visualTime=now;
    }
    private static void mouse(InputEvent.MouseButton.Pre event) {
        if(event.getButton()!=GLFW.GLFW_MOUSE_BUTTON_LEFT||!authorized()||!focused())return;
        if(event.getAction()!=GLFW.GLFW_PRESS&&event.getAction()!=GLFW.GLFW_RELEASE)return;
        if(event.isCanceled()){clear();return;}
        event.setCanceled(true);sample(event.getAction()==GLFW.GLFW_PRESS);
        var mc=Minecraft.getInstance();mc.options.keyAttack.setDown(false);if(mc.gameMode!=null)mc.gameMode.stopDestroyBlock();
    }
    private static void interaction(InputEvent.InteractionKeyMappingTriggered event) {
        if(event.isAttack()&&authorized()&&focused()){event.setCanceled(true);event.setSwingHand(false);}
    }
    private static void screen(ScreenEvent.Opening event){if(event.getNewScreen()!=null)clear();}
    private static void logout(ClientPlayerNetworkEvent.LoggingOut event){forget();REMOTE.clear();visualConnection=null;}
    static double trigger(ItemStack stack) {
        if(binding!=null&&ZapperData.matches(stack,binding))return authorized()&&focused()?triggerVisual:0;
        var receipt=ZapperData.binding(stack);var connection=Minecraft.getInstance().getConnection();
        if(receipt==null||connection==null||!connection.getConnection().isConnected())return 0;
        if(visualConnection!=connection.getConnection()){REMOTE.clear();visualConnection=connection.getConnection();}
        var animation=REMOTE.get(receipt.lease());
        if(animation==null||animation.epoch!=receipt.epoch()) {
            if(REMOTE.size()>=64)REMOTE.remove(REMOTE.keySet().iterator().next());
            animation=new RemoteAnimation(receipt.epoch());REMOTE.put(receipt.lease(),animation);
        }
        long now=System.nanoTime();
        animation.value=ZapperPoseLayout.advanceTrigger(animation.value,ClientArcadeEvents.visualZapperTrigger(receipt),animation.time==0?0:now-animation.time);
        animation.time=now;return animation.value;
    }
}
