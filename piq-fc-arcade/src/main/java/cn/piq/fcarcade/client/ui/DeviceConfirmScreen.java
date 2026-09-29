package cn.piq.fcarcade.client.ui;

import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Compact vanilla-style confirmation; background is rendered before every foreground label. */
public class DeviceConfirmScreen extends Screen {
    private final Consumer<Boolean> callback;
    private final Component message,yes,no;
    private final Runnable dismiss;
    private boolean answered;
    private DeviceLayout.Rect panel;
    private int scroll,maxScroll,lineStep;
    public DeviceConfirmScreen(Consumer<Boolean> callback,Component title,Component message){
        this(callback,title,message,Component.translatable("gui.yes"),Component.translatable("gui.no"));
    }
    public DeviceConfirmScreen(Consumer<Boolean> callback,Component title,Component message,Component yes,Component no){
        this(callback,title,message,yes,no,null);
    }
    /** Optional dismiss callback for two-action dialogs where false is a destructive action, not cancellation. */
    public DeviceConfirmScreen(Consumer<Boolean> callback,Component title,Component message,Component yes,Component no,Runnable dismiss){
        super(title);this.callback=callback;this.message=message;this.yes=yes;this.no=no;this.dismiss=dismiss;
    }
    @Override protected void init(){
        int w=Math.max(120,Math.min(360,width-24));
        int lines=font.split(message,w-24).size();
        int h=Math.min(height-24,Math.max(128,78+lines*(font.lineHeight+3)));
        panel=new DeviceLayout.Rect((width-w)/2,(height-h)/2,w,h);
        lineStep=font.lineHeight+3;
        maxScroll=Math.max(0,lines-Math.max(1,(h-88)/lineStep));
        scroll=Math.min(scroll,maxScroll);
        int bw=(w-28)/2,y=panel.bottom()-30;
        addRenderableWidget(Button.builder(yes,button->answer(true)).bounds(panel.x()+10,y,bw,20).build());
        addRenderableWidget(Button.builder(no,button->answer(false)).bounds(panel.x()+18+bw,y,bw,20).build());
    }
    private void answer(boolean value){if(!answered){answered=true;callback.accept(value);}}
    @Override public void onClose(){if(dismiss==null)answer(false);else if(!answered){answered=true;dismiss.run();}}
    @Override public boolean isPauseScreen(){return false;}
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){
        if(maxScroll>0&&vertical!=0){scroll=Math.max(0,Math.min(maxScroll,scroll+(vertical>0?-1:1)));return true;}
        return super.mouseScrolled(x,y,horizontal,vertical);
    }
    @Override public boolean keyPressed(int key,int scan,int modifiers){
        if(maxScroll>0&&(key==266||key==267)){scroll=Math.max(0,Math.min(maxScroll,scroll+(key==266?-5:5)));return true;}
        return super.keyPressed(key,scan,modifiers);
    }
    @Override public void render(GuiGraphics g,int mx,int my,float dt){
        // Screen.render draws the background (including optional blur), then buttons.
        // Like vanilla ConfirmScreen, all title/body text must be drawn afterwards.
        super.render(g,mx,my,dt);
        g.drawCenteredString(font,title,width/2,panel.y()+10,0xFFFFFF);
        g.enableScissor(panel.x()+10,panel.y()+36,panel.right()-10,panel.bottom()-50);
        int y=panel.y()+38-scroll*lineStep;
        for(var line:font.split(message,panel.width()-24)){
            g.drawString(font,line,panel.x()+12,y,0xFFFFFF,false);y+=lineStep;
        }
        g.disableScissor();
        if(maxScroll>0)g.drawCenteredString(font,"滚轮 / PgUp PgDn  "+(scroll+1)+" / "+(maxScroll+1),width/2,panel.bottom()-44,0xA0A0A0);
    }
}
