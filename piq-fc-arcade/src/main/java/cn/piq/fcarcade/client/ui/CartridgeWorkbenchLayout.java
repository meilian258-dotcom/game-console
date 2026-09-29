package cn.piq.fcarcade.client.ui;

import cn.piq.fcarcade.client.ui.DeviceLayout.Rect;

/** One toolbar/action contract shared by FC and optional cartridge backends. No runtime state. */
public record CartridgeWorkbenchLayout(Rect name, Rect saveName, Rect players,
        Rect gamesTab, Rect coversTab, Rect search, Rect refresh,
        Rect romFolder, Rect coverFolder, Rect previous, Rect next, Rect close,
        Rect clearCover, Rect restoreCover) {
    public static CartridgeWorkbenchLayout of(DeviceLayout.Browser browser) {
        Rect bar=browser.toolbar(),nav=browser.navigation(),detail=browser.details();
        int x=bar.x(),y=bar.y(),w=bar.width();
        Rect name=new Rect(x,y,w-148,20);
        Rect save=new Rect(name.right()+4,y,66,20),players=new Rect(save.right()+4,y,74,20);
        Rect games=new Rect(x,y+24,56,20),covers=new Rect(x+60,y+24,42,20);
        Rect refresh=new Rect(x+w-38,y+24,38,20);
        Rect search=new Rect(covers.right()+4,y+24,refresh.x()-covers.right()-8,20);
        Rect[] footer=new Rect[5];
        for(int i=0;i<5;i++){
            int start=nav.x()+i*(nav.width()+4)/5;
            int end=nav.x()+(i+1)*(nav.width()+4)/5-4;
            footer[i]=new Rect(start,nav.y(),end-start,20);
        }
        int inner=detail.width()-12,half=(inner-4)/2;
        Rect clear=new Rect(detail.x()+6,detail.bottom()-50,half,20);
        Rect restore=new Rect(clear.right()+4,clear.y(),inner-half-4,20);
        return new CartridgeWorkbenchLayout(name,save,players,games,covers,search,refresh,
                footer[0],footer[1],footer[2],footer[3],footer[4],clear,restore);
    }
}
