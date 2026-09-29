package cn.piq.sfchome.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

/** Same owner-consent questions as FC, without pausing the emulator/world behind the prompt. */
final class SfcJoinScreen extends Screen {
    final long session;final int epoch;final UUID token;
    private final String question,accept,decline;
    private final Consent consent;
    private int panelWidth,panelHeight,panelX,panelY,scroll,maxScroll,lineStep;
    SfcJoinScreen(long session,int epoch,UUID token,String title,String question,String accept,String decline,Consumer<Boolean> answer){
        this(session,epoch,token,title,question,accept,decline,()->true,answer);
    }
    SfcJoinScreen(long session,int epoch,UUID token,String title,String question,String accept,String decline,BooleanSupplier current,Consumer<Boolean> answer){
        super(Component.literal(title));this.session=session;this.epoch=epoch;this.token=token;this.question=question;this.accept=accept;this.decline=decline;consent=new Consent(current,answer);
    }
    private void choose(boolean yes){if(!consent.answer(yes))return;if(minecraft!=null&&minecraft.screen==this)minecraft.setScreen(null);}
    void expire(){consent.expire();if(minecraft!=null&&minecraft.screen==this)minecraft.setScreen(null);}
    @Override public void tick(){if(!consent.current())expire();}
    @Override protected void init(){
        panelWidth=Math.max(120,Math.min(360,width-24));
        lineStep=font.lineHeight+3;
        int lines=font.split(Component.literal(question),panelWidth-24).size();
        panelHeight=Math.min(height-24,Math.max(128,78+lines*lineStep));
        panelX=(width-panelWidth)/2;panelY=(height-panelHeight)/2;
        maxScroll=Math.max(0,lines-Math.max(1,(panelHeight-88)/lineStep));
        scroll=Math.min(scroll,maxScroll);
        int bw=(panelWidth-28)/2,y=panelY+panelHeight-30;
        addRenderableWidget(Button.builder(Component.literal(accept),button->choose(true)).bounds(panelX+10,y,bw,20).build());
        addRenderableWidget(Button.builder(Component.literal(decline),button->choose(false)).bounds(panelX+18+bw,y,bw,20).build());
    }
    @Override public void onClose(){choose(false);}
    @Override public void removed(){consent.answer(false);}
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
        // Vanilla Screen renders the background before widgets; never repaint it after text.
        super.render(g,mx,my,dt);
        g.drawCenteredString(font,title,width/2,panelY+10,0xFFFFFF);
        g.enableScissor(panelX+10,panelY+36,panelX+panelWidth-10,panelY+panelHeight-50);
        int y=panelY+38-scroll*lineStep;
        for(var text:font.split(Component.literal(question),panelWidth-24)){
            g.drawString(font,text,panelX+12,y,0xFFFFFF,false);y+=lineStep;
        }
        g.disableScissor();
        if(maxScroll>0)g.drawCenteredString(font,"滚轮 / PgUp PgDn  "+(scroll+1)+" / "+(maxScroll+1),width/2,panelY+panelHeight-44,0xA0A0A0);
    }
    /** Execute one answer only while the captured session is still current. */
    static final class Consent {
        private final BooleanSupplier current;private final Consumer<Boolean> callback;private boolean answered;
        Consent(BooleanSupplier current,Consumer<Boolean> callback){this.current=java.util.Objects.requireNonNull(current);this.callback=java.util.Objects.requireNonNull(callback);}
        boolean current(){return !answered&&current.getAsBoolean();}
        boolean answer(boolean yes){if(answered)return false;answered=true;if(current.getAsBoolean())callback.accept(yes);return true;}
        void expire(){answered=true;}
    }
}
