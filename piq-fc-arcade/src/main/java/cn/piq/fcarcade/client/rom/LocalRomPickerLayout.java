// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.client.rom;
import cn.piq.fcarcade.client.ui.DeviceLayout;

/** Pixel-only adapter for the shared device browser; no files, rendering or Minecraft globals. */
public final class LocalRomPickerLayout {
    private LocalRomPickerLayout(){}
    public record Rect(int x,int y,int width,int height){public int right(){return x+width;}public int bottom(){return y+height;}}
    public record Layout(boolean supported,Rect panel,Rect toolbar,Rect search,Rect list,Rect footer,
                         int rows,int page,int pages,int start,Rect details,Rect status,boolean split){
        public Rect row(int offset){return new Rect(list.x()+4,list.y()+4+offset*DeviceLayout.ROW_STEP,list.width()-8,DeviceLayout.ROW_HEIGHT);}
    }
    private static Rect rect(DeviceLayout.Rect r){return new Rect(r.x(),r.y(),r.width(),r.height());}
    public static Layout create(int width,int height,int count,int requestedPage){
        var d=DeviceLayout.browser(width,height,2);int rows=d.rows();
        int pages=Math.max(1,(Math.max(0,count)+rows-1)/rows),page=Math.max(0,Math.min(requestedPage,pages-1));
        var t=d.toolbar();
        return new Layout(d.supported(),rect(d.panel()),new Rect(t.x(),t.y(),t.width(),20),
            new Rect(t.x(),t.y()+24,t.width(),20),rect(d.list()),rect(d.navigation()),rows,page,pages,page*rows,
            rect(d.details()),rect(d.status()),d.split());
    }
}
