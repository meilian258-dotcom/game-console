package cn.piq.fcarcade.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** Shared pixel-device chrome. Paint only: never reads files, changes focus, or owns a session. */
public final class DeviceUi {
    public static final int BG=0x88000000,PANEL=0xFFC6C6C6,SURFACE=0xFFB4B4B4;
    public static final int TEXT=0xFF303030,MUTED=0xFF505050,ACCENT=0xFF303030,DANGER=0xFF9A2525;
    public enum Tone { NORMAL, PRIMARY, DANGER, QUIET }
    private DeviceUi(){}
    public static void prepare(){cn.piq.fcarcade.client.CartridgeScreenCompat.prepare();}
    public static String fit(Font font,String value,int width){
        if(value==null||width<=0)return "";
        String clean=value.replaceAll("[\\p{Cntrl}]"," ");
        if(font.width(clean)<=width)return clean;
        if(font.width("…")>width)return font.plainSubstrByWidth(clean,width);
        return font.plainSubstrByWidth(clean,Math.max(0,width-font.width("…")))+"…";
    }
    public static void panel(GuiGraphics g,Font font,int x,int y,int w,int h,String title,String subtitle){
        g.fill(x-1,y-1,x+w+1,y+h+1,0xFF101010);g.fill(x,y,x+w,y+h,PANEL);
        g.fill(x,y,x+w-1,y+2,0xFFFFFFFF);g.fill(x,y,x+2,y+h-1,0xFFFFFFFF);
        g.fill(x+w-2,y+2,x+w,y+h,0xFF555555);g.fill(x+2,y+h-2,x+w,y+h,0xFF555555);
        text(g,font,title,x+10,y+11,w-20,TEXT);
        if(subtitle!=null&&!subtitle.isBlank())text(g,font,subtitle,x+10,y+27,w-20,MUTED);
    }
    public static void section(GuiGraphics g,int x,int y,int w,int h){
        if(w<=0||h<=0)return;g.fill(x,y,x+w,y+h,SURFACE);
        g.fill(x,y,x+w,y+1,0xFF777777);g.fill(x,y,x+1,y+h,0xFF777777);
        g.fill(x,y+h-1,x+w,y+h,0xFFE8E8E8);g.fill(x+w-1,y,x+w,y+h,0xFFE8E8E8);
    }
    public static void text(GuiGraphics g,Font font,String value,int x,int y,int width,int color){
        g.drawString(font,fit(font,value,width),x,y,color,false);
    }
    public static void status(GuiGraphics g,Font font,String value,int x,int y,int width,boolean busy){
        text(g,font,(busy?"处理中 · ":"")+value,x+2,y+5,width-4,busy?TEXT:MUTED);
    }
    public static Button button(Font font,String label,int x,int y,int w,int h,Runnable action,boolean enabled,Tone tone){
        Button result=new DeviceButton(font,label,"",x,y,w,h,action,tone,false,false,false);
        result.active=enabled;result.setTooltip(Tooltip.create(Component.literal(label)));return result;
    }
    public static Button row(Font font,String label,String badge,int x,int y,int w,int h,Runnable action,
                             boolean selected,boolean current,boolean enabled){
        Button result=new DeviceButton(font,label,badge,x,y,w,h,action,Tone.QUIET,true,selected,current);
        result.active=enabled;
        result.setTooltip(Tooltip.create(Component.literal((current?"当前使用 · ":"")+label+(badge.isBlank()?"":"\n"+badge))));return result;
    }
    private static final class DeviceButton extends Button {
        private final Font font;private final String label,badge;private final Tone tone;
        private final boolean row,selected,current;
        DeviceButton(Font font,String label,String badge,int x,int y,int w,int h,Runnable action,Tone tone,boolean row,boolean selected,boolean current){
            super(x,y,Math.max(1,w),Math.max(1,h),Component.literal(label),ignored->action.run(),DEFAULT_NARRATION);
            this.font=font;this.label=label;this.badge=badge;this.tone=tone;this.row=row;this.selected=selected;this.current=current;
        }
        @Override public void setMessage(Component message){super.setMessage(message);if(!row)setTooltip(Tooltip.create(message));}
        // Keep vanilla AbstractButton rendering (textures, hover/focus and disabled state).
        // Only the bounded label/row badge is custom; it never paints over the native button.
        @Override public void renderString(GuiGraphics g,Font nativeFont,int color){
            int x=getX(),y=getY(),w=getWidth(),h=getHeight();
            int textY=y+(h-font.lineHeight)/2;
            if(row){
                int badgeWidth=Math.min(w/3,font.width(badge));
                text(g,font,selected?">":current?"·":"",x+5,textY,8,color);
                text(g,font,label,x+17,textY,Math.max(1,w-25-(badgeWidth>0?badgeWidth+8:0)),color);
                if(badgeWidth>0)text(g,font,badge,x+w-badgeWidth-6,textY,badgeWidth,color);
            }else{String shown=fit(font,getMessage().getString(),w-14);g.drawString(font,shown,x+(w-font.width(shown))/2,textY,color,false);}
        }
    }
}
