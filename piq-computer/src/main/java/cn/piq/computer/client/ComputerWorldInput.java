package cn.piq.computer.client;

import cn.piq.computer.*;
import cn.piq.computer.net.ComputerNetwork;
import cn.piq.fcarcade.client.cabinet.CabinetClientOwner;
import cn.piq.fcarcade.home.*;
import cn.piq.fcarcade.layout.*;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.Point;
import cn.piq.fcarcade.world.*;
import java.util.*;
import net.minecraft.client.*;
import net.minecraft.client.player.Input;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/** Explicit world-space mouse/keyboard capture. No Screen, post-process blur or virtual preview. */
@EventBusSubscriber(modid=ComputerMod.ID,value=Dist.CLIENT)
public final class ComputerWorldInput {
    private static final Object OWNER=new Object();
    private static final InputCapture CAPTURE=new InputCapture();
    private static final ComputerPointer POINTER=new ComputerPointer();
    private static boolean fixedMode,locked;
    private static ComputerNetwork.Open target;private static Object connection;
    private static long sequence;private static int ticks,x=320,y=240,buttons;private static boolean onScreen;
    public static boolean active(){return CAPTURE.active();}
    public static boolean controlling(){return active()&&(!fixedMode||locked);}
    public static boolean controlling(UUID computer){return controlling()&&target!=null&&target.id().equals(computer);}
    public static boolean fixed(){return active()&&fixedMode&&locked;}
    public static void enter(ComputerNetwork.Open m){
        stop();var mc=Minecraft.getInstance();target=m;connection=mc.getConnection();sequence=0;ticks=0;
        if(mc.screen!=null||!mc.isWindowActive()||ComputerClient.current(m)==null||!CabinetClientOwner.acquire(OWNER)){send(5,0);target=null;return;}
        fixedMode=ComputerPrograms.fixedPointer();locked=false;POINTER.reset();
        CAPTURE.begin(keysDown(),buttonsDown());clearWorld();ComputerPrograms.begin(m);
        mc.player.displayClientMessage(Component.literal(fixedMode?"电脑操作 · 瞄准电视画面后右键固定 · Esc 退出":"电脑操作 · 鼠标瞄准电视并点击 · Esc 退出"),true);
    }
    private static boolean valid(){
        var mc=Minecraft.getInstance();var pc=target==null?null:ComputerClient.current(target);
        return pc!=null&&mc.getConnection()==connection&&mc.isWindowActive()&&mc.screen==null&&!mc.isPaused()&&mc.player.isAlive()&&!mc.player.isSpectator()&&pc.inputReady()&&(pc.operator==null?ticks<20:pc.operator.equals(mc.player.getUUID()));
    }
    private static boolean shared(){return target!=null&&ComputerStreams.shared(target.id());}
    private static boolean localProgram(){return target!=null&&ComputerPrograms.localInput(target.id());}
    private static ProgramBackend localBackend(){return shared()||!localProgram()?null:ComputerPrograms.backend();}
    private static void send(int kind,int value){var mc=Minecraft.getInstance();boolean local=localProgram()&&!shared();if(target!=null&&mc.getConnection()==connection)PacketDistributor.sendToServer(new ComputerNetwork.Command(target.dimension(),target.pos(),target.id(),target.token(),sequence++,kind,value,local?320:x,local?240:y,local?0:controlling()&&CAPTURE.armed()&&onScreen?buttons:8));}
    public static void stop(){if(active())send(5,0);CAPTURE.end();target=null;buttons=0;onScreen=false;locked=false;ComputerPrograms.releaseInput();CabinetClientOwner.release(OWNER);clearWorld();}
    /** Called before vanilla camera turning. Never changes yaw/pitch or teleports the player. */
    public static boolean relativeMotion(double dx,double dy){
        if(!fixed())return false;
        if(!valid()){stop();return true;}
        if(CAPTURE.armed())POINTER.move(dx,dy,ComputerPrograms.pointerSensitivity());
        return true;
    }
    private static void maintain(boolean tick){
        if(active()&&!valid())stop();var mc=Minecraft.getInstance();if(CAPTURE.needsPoll()&&mc.isWindowActive())CAPTURE.sample(keysDown(),buttonsDown());
        if(!active())return;clearWorld();sample();if(tick){ticks++;send(0,0);}
        if(!onScreen)buttons=0;
        var backend=localBackend();if(backend!=null)backend.pointer(x,y,controlling()&&CAPTURE.armed()?buttons:0,controlling()&&CAPTURE.armed()&&onScreen);
    }
    record Display(net.minecraft.core.BlockPos pos,ScreenSurfaceGeometry.Surface surface){}
    private static Display display(){
        var mc=Minecraft.getInstance();var pc=target==null?null:ComputerClient.current(target);var pos=pc==null?null:pc.televisionPos();
        if(pos==null||!mc.level.hasChunkAt(pos)||!(mc.level.getBlockEntity(pos) instanceof HomeTvBlockEntity tv)||!tv.powered()||!HomeHardware.connected(mc.level,pc,tv)||!HomeApplianceService.videoAllowed(mc.level,pos)||!(tv.getBlockState().getBlock() instanceof FcArcadeBlock block))return null;
        var layout=ArcadeStructure.resolve(mc.level,pos);int turns=ComputerRenderer.turns(tv.getBlockState());
        return new Display(pos,ScreenSurfaceGeometry.frame(block.displayStyle(),turns,layout.width(),layout.height(),HomeTvStructure.centered(tv.getBlockState()),ScreenAspectFit.Aspect.FOUR_THREE));
    }
    static Vec3 point(Display d,double u,double v){var q=d.surface.image();return vector(q.upperMaxX()).lerp(vector(q.upperMinX()),u).lerp(vector(q.lowerMaxX()).lerp(vector(q.lowerMinX()),u),v).add(vector(d.surface.translation())).add(d.pos.getX(),d.pos.getY(),d.pos.getZ());}
    private static Vec3 vector(Point p){return new Vec3(p.x(),p.y(),p.z());}
    static Display cursorDisplay(){return fixed()&&onScreen?display():null;}
    static int cursorX(){return x;}static int cursorY(){return y;}
    private static void sample(){
        onScreen=false;var mc=Minecraft.getInstance();var d=display();if(d==null)return;var pos=d.pos;var surface=d.surface;
        var eye=mc.player.getEyePosition();var dir=mc.player.getLookAngle();var offset=surface.translation();
        if(fixed()){
            x=POINTER.x();y=POINTER.y();var end=point(d,x/639.0,y/479.0);
            if(eye.subtract(end).dot(vector(surface.image().normal()))<=1e-9)return;
            onScreen=ComputerScreenRay.visible(mc.level,mc.player,pos,eye,end);return;
        }
        var origin=eye.subtract(pos.getX()+offset.x(),pos.getY()+offset.y(),pos.getZ()+offset.z());
        var mapped=ScreenRayMapping.hit(surface.image(),p(origin),p(dir),8,640,480);if(mapped.isEmpty())return;
        var pixel=mapped.get();
        if(!ComputerScreenRay.visible(mc.level,mc.player,pos,eye,eye.add(dir.normalize().scale(pixel.distance()))))return;
        x=pixel.x();y=pixel.y();onScreen=true;
    }
    private static Point p(Vec3 v){return new Point(v.x,v.y,v.z);}
    public static boolean key(long window,int key,int scan,int action,int mods){
        var mc=Minecraft.getInstance();if(window!=mc.getWindow().getWindow())return false;
        if(active()&&!valid())stop();if(!CAPTURE.key(key,action))return false;clearWorld();
        if(active()&&key==GLFW.GLFW_KEY_ESCAPE&&action==GLFW.GLFW_PRESS){stop();return true;}
        if(!controlling())return true;
        if(CAPTURE.armed()&&key>=0&&key<=512){var backend=localBackend();if(backend!=null)backend.key(key,action!=GLFW.GLFW_RELEASE);else if(shared()||!localProgram())send(action==0?2:1,key);}
        return true;
    }
    public static boolean character(long window,int codepoint){if(window!=Minecraft.getInstance().getWindow().getWindow())return false;if(!active())return false;if(!valid()){stop();return true;}if(controlling()&&CAPTURE.armed()){var backend=localBackend();if(backend!=null)backend.text(codepoint);else if(shared()||!localProgram())send(3,codepoint);}return true;}
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void tick(ClientTickEvent.Pre e){maintain(true);}
    @SubscribeEvent public static void frame(RenderFrameEvent.Pre e){maintain(false);ComputerPrograms.maintain(true);}
    @SubscribeEvent(priority=EventPriority.HIGHEST,receiveCanceled=true) public static void mouse(InputEvent.MouseButton.Pre e){
        if(active()&&!valid())stop();if(!CAPTURE.button(e.getButton(),e.getAction()))return;e.setCanceled(true);clearWorld();
        if(active()&&fixedMode&&!locked){
            sample();if(CAPTURE.armed()&&e.getButton()==GLFW.GLFW_MOUSE_BUTTON_RIGHT&&e.getAction()==GLFW.GLFW_PRESS&&onScreen){
                POINTER.position(x,y);locked=true;buttons=0;ComputerPrograms.releaseInput();CAPTURE.begin(keysDown(),buttonsDown());
                Minecraft.getInstance().player.displayClientMessage(Component.literal("视角已固定 · 移动鼠标操作光标 · Esc 退出"),true);
            }
            return;
        }
        if(CAPTURE.armed()&&e.getButton()<3){sample();if(e.getAction()==GLFW.GLFW_PRESS&&onScreen)buttons|=1<<e.getButton();else buttons&=~(1<<e.getButton());var backend=localBackend();if(backend!=null)backend.pointer(x,y,buttons,onScreen);else if(shared()||!localProgram())send(0,0);}
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void scroll(InputEvent.MouseScrollingEvent e){if(active())e.setCanceled(true);}
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void interaction(InputEvent.InteractionKeyMappingTriggered e){if(active()){e.setSwingHand(false);e.setCanceled(true);}}
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void movement(MovementInputUpdateEvent e){if(active())zero(e.getInput());}
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void opening(ScreenEvent.Opening e){if(active()&&e.getNewScreen()!=null)stop();}
    @SubscribeEvent public static void hud(RenderGuiEvent.Post e){var mc=Minecraft.getInstance();if(!active()||mc.screen!=null)return;String text=!CAPTURE.armed()?"请松开按键和鼠标":fixed()?"固定视角 · 鼠标控制光标 · Esc 退出":fixedMode?"瞄准电视画面后右键固定 · Esc 退出":onScreen?"电脑操作 · Esc 退出":"请瞄准电视 · Esc 退出";e.getGuiGraphics().drawString(mc.font,text,8,8,0xffffff,true);}
    @SubscribeEvent public static void crosshair(RenderGuiLayerEvent.Pre e){if(fixed()&&e.getName().equals(net.neoforged.neoforge.client.gui.VanillaGuiLayers.CROSSHAIR))e.setCanceled(true);}
    private static Set<Integer> keysDown(){var set=new HashSet<Integer>();long w=Minecraft.getInstance().getWindow().getWindow();for(int k=32;k<=GLFW.GLFW_KEY_LAST;k++)if(GLFW.glfwGetKey(w,k)==GLFW.GLFW_PRESS)set.add(k);return set;}
    private static Set<Integer> buttonsDown(){var set=new HashSet<Integer>();long w=Minecraft.getInstance().getWindow().getWindow();for(int k=0;k<=GLFW.GLFW_MOUSE_BUTTON_LAST;k++)if(GLFW.glfwGetMouseButton(w,k)==GLFW.GLFW_PRESS)set.add(k);return set;}
    private static void zero(Input i){i.leftImpulse=i.forwardImpulse=0;i.up=i.down=i.left=i.right=i.jumping=i.shiftKeyDown=false;}
    private static void clearWorld(){var mc=Minecraft.getInstance();KeyMapping.releaseAll();for(var k:mc.options.keyMappings)while(k.consumeClick()){}if(mc.player!=null){zero(mc.player.input);mc.player.setSprinting(false);}if(mc.gameMode!=null)mc.gameMode.stopDestroyBlock();}
}
