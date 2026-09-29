package cn.piq.flashbox.client;

import java.util.HashSet;
import java.util.Set;
import cn.piq.fcarcade.client.ui.DeviceScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Explicit local-file preview and two configurable runtime input ports, not remote multiplayer. */
final class FlashBoxScreen extends DeviceScreen {
    private EditBox path;
    private Button worldControl;
    private String draft=FlashBoxClient.lastPath();
    private final Set<Integer> held=new HashSet<>();
    private int left,viewX,viewY,viewW,viewH;
    private boolean mouseHeld,focusLost,armed,wasActive;
    FlashBoxScreen() { super(Component.literal("Flash 播放盒 · 本机功能原型")); }
    @Override protected void init() {
        if(path!=null)draft=path.getValue();
        release();
        left=Math.max(8,(width-Math.min(620,width-16))/2);
        int area=width-left*2;
        path=new EditBox(font,left,32,Math.max(100,area-144),20,Component.literal("本机 SWF 完整路径"));
        path.setMaxLength(2048);path.setValue(draft);addRenderableWidget(path);
        addRenderableWidget(Button.builder(Component.literal("加载 SWF"),b->{
            path.setFocused(false);setFocused(null);release();FlashBoxClient.start(path.getValue());
        }).bounds(left+area-138,32,68,20).build());
        addRenderableWidget(Button.builder(Component.literal("停止"),b->{release();FlashBoxClient.stop("已停止本机预览");})
                .bounds(left+area-66,32,66,20).build());
        viewH=Math.max(80,Math.min(height-136,(int)(area*.75)));
        viewW=viewH*4/3;viewX=(width-viewW)/2;viewY=58;
        addRenderableWidget(Button.builder(Component.literal("返回看电视 / Esc"),b->onClose()).bounds(width-142,height-25,132,20).build());
        worldControl=addRenderableWidget(Button.builder(Component.literal("看电视操作"),b->{release();FlashWorldInput.enter();})
                .bounds(width-282,height-25,132,20).build());
        worldControl.active=FlashBoxClient.canControlTelevision();
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(null); }
    @Override public void removed() {
        if(path!=null)FlashBoxClient.rememberPath(path.getValue());
        release();FlashBoxClient.menuRemoved();
    }
    @Override public void tick() {
        if (!FlashBoxClient.valid()) { onClose();return; }
        worldControl.active=FlashBoxClient.canControlTelevision();
        boolean lost=!minecraft.isWindowActive();
        boolean active=FlashBoxClient.active();
        if(active && !wasActive)release();
        wasActive=active;
        if(lost && !focusLost) release();
        focusLost=lost;
        if(!lost && !armed && allMappedKeysReleased())armed=true;
    }
    private boolean allMappedKeysReleased() {
        long window=minecraft.getWindow().getWindow();
        for(int key:new int[]{GLFW.GLFW_KEY_LEFT,GLFW.GLFW_KEY_RIGHT,GLFW.GLFW_KEY_UP,GLFW.GLFW_KEY_DOWN,
                GLFW.GLFW_KEY_SPACE,GLFW.GLFW_KEY_A,GLFW.GLFW_KEY_D,GLFW.GLFW_KEY_W,GLFW.GLFW_KEY_S,GLFW.GLFW_KEY_LEFT_SHIFT})
            if(GLFW.glfwGetKey(window,key)!=GLFW.GLFW_RELEASE)return false;
        return true;
    }
    private boolean acceptsKeys() { return FlashBoxClient.active() && FlashBoxClient.ownsInput() && path!=null && !path.isFocused() && minecraft.isWindowActive(); }
    private int mask(int left,int right,int up,int down,int action) {
        return (held.contains(left)?1:0)|(held.contains(right)?2:0)|(held.contains(up)?4:0)|(held.contains(down)?8:0)|(held.contains(action)?16:0);
    }
    private void send() {
        FlashBoxClient.keys(mask(GLFW.GLFW_KEY_LEFT,GLFW.GLFW_KEY_RIGHT,GLFW.GLFW_KEY_UP,GLFW.GLFW_KEY_DOWN,GLFW.GLFW_KEY_SPACE),
                mask(GLFW.GLFW_KEY_A,GLFW.GLFW_KEY_D,GLFW.GLFW_KEY_W,GLFW.GLFW_KEY_S,GLFW.GLFW_KEY_LEFT_SHIFT));
    }
    private static boolean mapped(int key) {
        return key==GLFW.GLFW_KEY_LEFT || key==GLFW.GLFW_KEY_RIGHT || key==GLFW.GLFW_KEY_UP || key==GLFW.GLFW_KEY_DOWN
                || key==GLFW.GLFW_KEY_SPACE || key==GLFW.GLFW_KEY_A || key==GLFW.GLFW_KEY_D || key==GLFW.GLFW_KEY_W
                || key==GLFW.GLFW_KEY_S || key==GLFW.GLFW_KEY_LEFT_SHIFT;
    }
    private void release() { armed=false;held.clear();FlashBoxClient.keys(0,0);if(mouseHeld)FlashBoxClient.pointer(0,0,false);mouseHeld=false; }
    @Override public boolean keyPressed(int key,int scan,int mods) {
        if(acceptsKeys() && mapped(key)) { if(armed && held.add(key))send();return true; }
        return super.keyPressed(key,scan,mods);
    }
    @Override public boolean keyReleased(int key,int scan,int mods) {
        if(held.remove(key)) { send();return true; }
        return super.keyReleased(key,scan,mods);
    }
    private boolean inside(double x,double y) { return x>=viewX && y>=viewY && x<viewX+viewW && y<viewY+viewH; }
    private void pointer(double x,double y,boolean down) {
        FlashBoxClient.pointer(Math.max(0,Math.min(639,(int)((x-viewX)*640/viewW))),
                Math.max(0,Math.min(479,(int)((y-viewY)*480/viewH))),down);
    }
    @Override public boolean mouseClicked(double x,double y,int button) {
        if(button==0 && inside(x,y) && FlashBoxClient.active()) {
            path.setFocused(false);setFocused(null);mouseHeld=true;pointer(x,y,true);return true;
        }
        release();return super.mouseClicked(x,y,button);
    }
    @Override public boolean mouseReleased(double x,double y,int button) {
        if(button==0 && mouseHeld) { mouseHeld=false;pointer(x,y,false);return true; }
        return super.mouseReleased(x,y,button);
    }
    @Override public void mouseMoved(double x,double y) { if(inside(x,y) && FlashBoxClient.active())pointer(x,y,mouseHeld);super.mouseMoved(x,y); }
    @Override public void render(GuiGraphics graphics,int mx,int my,float partial) {
        graphics.fill(0,0,width,56,0xda101820);
        graphics.drawString(font,title,left,8,0xffe6d49a,false);
        graphics.drawString(font,"仅本机预览 · 文件不上传 · 两组键位不等于跨电脑联机",left,21,0xffcccccc,false);
        graphics.fill(viewX-1,viewY-1,viewX+viewW+1,viewY+viewH+1,0xff000000);
        var texture=FlashBoxClient.texture();
        if(texture!=null)graphics.blit(texture,viewX,viewY,viewW,viewH,0,0,640,480,640,480);
        else graphics.drawCenteredString(font,"选择 SWF 后点击加载；请先打开电视电源",width/2,viewY+viewH/2,0xffdddddd);
        int footer=viewY+viewH+5;
        graphics.fill(0,footer-2,width,height,0xda101820);
        graphics.drawString(font,"P1：方向键 + 空格   P2：WASD + 左Shift   鼠标：点预览画面",left,footer,0xffeeeeee,false);
        graphics.drawString(font,"看电视操作：锁住人物并控制游戏；Esc仅返回观看；停止才结束。",left,footer+11,0xffe6bd72,false);
        var status=font.split(Component.literal(FlashBoxClient.status()),Math.max(100,width-2*left));
        for(int i=0;i<Math.min(2,status.size());i++)graphics.drawString(font,status.get(i),left,footer+24+i*10,0xffa7c8df,false);
        super.render(graphics,mx,my,partial);
    }
}
