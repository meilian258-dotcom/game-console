package cn.piq.fcarcade.client.cabinet;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** Bound text in pixels rather than characters; keeps long Chinese descriptions inside a 320px GUI. */
final class CabinetUi {
    private CabinetUi(){}
    static String fit(Font font,String text,int width){
        if(width<=0)return "";
        if(font.width(text)<=width)return text;
        if(font.width("…")>width)return font.plainSubstrByWidth(text,width);
        return font.plainSubstrByWidth(text,Math.max(0,width-font.width("…")))+"…";
    }
    static void paragraph(GuiGraphics g,Font font,String text,int x,int y,int width,int maxLines,int color){
        var lines=font.split(Component.literal(CabinetClientBackends.shortText(text,512)),Math.max(1,width));
        for(int i=0;i<Math.min(maxLines,lines.size());i++)g.drawString(font,lines.get(i),x,y+i*(font.lineHeight+1),color,false);
        if(lines.size()>maxLines)g.drawString(font,"…",x+width-font.width("…"),y+(maxLines-1)*(font.lineHeight+1),color,false);
    }
}
