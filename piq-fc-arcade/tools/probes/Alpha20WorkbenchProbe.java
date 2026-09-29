package cn.piq.sfchome.client;

import cn.piq.fcarcade.client.ui.DeviceLayout;
import cn.piq.fcarcade.client.ui.CartridgeWorkbenchLayout;
import java.nio.file.Path;
import java.util.*;

/** Final-JAR-only pure layouts and real display/search state; no Minecraft classpath. */
public final class Alpha20WorkbenchProbe {
    private static int assertions;
    private static void check(boolean value,String reason){assertions++;if(!value)throw new AssertionError(reason);}
    private static void origin(Class<?> type,Path jar)throws Exception{check(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(jar.toRealPath()),"Wrong class origin: "+type.getName());}
    public static void main(String[]args)throws Exception {
        check(args.length==2,"FC20 and merged SFC7 required");Path fc=Path.of(args[0]),sfc=Path.of(args[1]);
        origin(DeviceLayout.class,fc);origin(DeviceLayout.Browser.class,fc);origin(DeviceLayout.Rect.class,fc);origin(CartridgeWorkbenchLayout.class,fc);
        origin(SfcCardLibrary.class,sfc);origin(SfcCardLibrary.Row.class,sfc);origin(SfcWorkbenchDisplay.class,sfc);
        int layouts=0;
        for(int w:new int[]{320,360,400,426,480,512,640,854,960,1280,1920,3840})for(int h:new int[]{240,256,278,300,360,480,720,1080,2160}){
            var b=DeviceLayout.browser(w,h,2);var p=b.panel();var work=CartridgeWorkbenchLayout.of(b);layouts++;
            check(b.supported()&&b.split(),"Compact layout always split when supported");check(p.width()<=420&&p.height()<=260,"Not compact");
            check(p.x()==(w-p.width())/2&&p.y()==(h-p.height())/2,"Not centered");check(p.x()>=16&&p.y()>=16,"Screen edge margin");
            var controls=List.of(work.name(),work.saveName(),work.players(),work.gamesTab(),work.coversTab(),work.search(),work.refresh(),work.romFolder(),work.coverFolder(),work.previous(),work.next(),work.close(),work.clearCover(),work.restoreCover(),b.primary());
            for(int i=0;i<controls.size();i++){var r=controls.get(i);check(r.width()>0&&r.height()==20&&p.contains(r),"Offscreen control");for(int j=i+1;j<controls.size();j++)check(!r.overlaps(controls.get(j)),"Overlapping controls");}
            for(int i=0;i<b.rows();i++){check(b.list().contains(b.row(i)),"List row clipped");check(!b.row(i).overlaps(b.details()),"List/details overlap");}
            check(work.clearCover().bottom()<=b.primary().y()-4,"Cover controls overlap primary");
            check(work.romFolder().y()==work.close().y()&&work.next().right()<work.close().x(),"Footer order");
            for(boolean cover:List.of(false,true)){
                int start=b.details().y()+3,end=(cover?work.clearCover().y():b.primary().y())-2;
                int lines=Math.max(0,(end-start-9)/12+1);String[] text=SfcWorkbenchDisplay.details("待应用封面","filename","本地 PNG",lines);
                check(text.length<=lines,"Text exceeds available lines");if(text.length>0)check(start+(text.length-1)*12+9<=end,"Text crosses action row");
            }
        }
        check(!DeviceLayout.browser(319,240,2).supported()&&!DeviceLayout.browser(320,239,2).supported(),"Too-small UI must not activate");
        var search=Class.forName("cn.piq.fcarcade.client.ClientCartridgeEditor$Search");origin(search,fc);var matches=search.getDeclaredMethod("matches",String.class,String.class);matches.setAccessible(true);
        check((boolean)matches.invoke(null,"PIQ-INFINITY.NES"," infinity "),"Case-insensitive literal search");check((boolean)matches.invoke(null,"超级马里奥.nes","马里奥"),"Chinese search");check(!(boolean)matches.invoke(null,"Contra.nes",".*"),"Search must not execute regex");
        String hash="0123456789abcdef".repeat(4);var row=SfcCardLibrary.Row.server(hash,hash+".sfc",32768);var other=SfcCardLibrary.Row.server("other","游戏.smc",32768);
        var library=new SfcCardLibrary("旧标题");library.labels(r->SfcWorkbenchDisplay.name(r,hash,"当前卡片"));library.server(List.of(row,other),hash);
        check(SfcWorkbenchDisplay.name(row,hash,"当前卡片").equals("当前卡片"),"Current card title");library.query("当前卡");check(library.filtered().equals(List.of(row)),"Display alias searchable");
        library.query("游戏");check(!library.filtered().contains(library.selected())&&library.selected().equals(row),"Hidden selection remains identity but not actionable");
        library.select(other);check(library.title().equals("游戏.smc")&&!library.titleDirty(),"Unedited draft follows selected label");library.title("手改");library.select(row);check(library.title().equals("手改")&&library.titleDirty(),"Manual draft preserved");
        check(row.hash().equals(hash)&&row.name().equals(hash+".sfc")&&SfcWorkbenchDisplay.original(row).contains(hash),"Raw identity/name preserved");
        check(SfcWorkbenchDisplay.name(row,"","x").equals("未命名游戏 · 01234567"),"Unknown hash label does not guess game");
        System.out.println("{\"ok\":true,\"assertions\":"+assertions+",\"layouts\":"+layouts+",\"production_origin\":\"final-jar-only\",\"minecraft_or_native_core_started\":false}");
    }
}
