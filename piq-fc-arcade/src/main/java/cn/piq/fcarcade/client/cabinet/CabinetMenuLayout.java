package cn.piq.fcarcade.client.cabinet;

import cn.piq.fcarcade.client.ui.DeviceLayout;

/** Shared pixel geometry, with page clamping when a resize changes the visible rows. */
final class CabinetMenuLayout {
    record Layout(int x,int y,int width,int rows,int page,int start,int count,int footerY,boolean supported,DeviceLayout.Browser browser){}
    private CabinetMenuLayout(){}
    static Layout create(int width,int height,int entries,int requestedPage){
        if(entries<1||entries>16)throw new IllegalArgumentException("Invalid backend count");
        var b=DeviceLayout.browser(width,height,1);int rows=b.rows();
        int page=Math.max(0,Math.min(requestedPage,(entries-1)/rows));int count=Math.min(rows,entries-page*rows);
        return new Layout(b.list().x()+4,b.list().y()+4,b.list().width()-8,rows,page,page*rows,count,b.navigation().y(),b.supported(),b);
    }
}
