// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.gba.client;

import cn.piq.gba.bridge.GbaSaveScope;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Pure production gate + actual filesystem store; never initializes Minecraft or an audio device. */
public final class GbaHandheldClientProbe {
    private static int assertions;
    private static void check(boolean value,String why){assertions++;if(!value)throw new AssertionError(why);}
    @FunctionalInterface private interface Checked {void run()throws Exception;}
    private static void rejects(Checked action)throws Exception{boolean rejected=false;try{action.run();}catch(Exception expected){rejected=true;}check(rejected,"Expected rejection");}
    public static void main(String[] args)throws Exception{
        if(args.length>1){
            Path jar=Path.of(args[1]).toRealPath();
            for(Class<?> type:new Class<?>[]{GbaHandheldGate.class,GbaHandheldGate.UseGate.class,GbaHandheldSelectionStore.class,GbaSaveScope.class})
                check(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(jar),"Final JAR production origin");
        }
        Object connection=new String("equal-value"),level=new String("same-level");UUID player=UUID.randomUUID();
        for(int slot=0;slot<9;slot++){
            var gate=new GbaHandheldGate(connection,level,player,slot);
            check(gate.valid(connection,level,player,slot,true,true,false,true,1),"Live held main-hand item");
            check(!gate.valid(new String("equal-value"),level,player,slot,true,true,false,true,1),"Actual connection identity, not equals");
            check(!gate.valid(connection,new String("same-level"),player,slot,true,true,false,true,1),"Actual level identity");
            check(!gate.valid(connection,level,UUID.randomUUID(),slot,true,true,false,true,1),"Player isolation");
            check(!gate.valid(connection,level,player,(slot+1)%9,true,true,false,true,1),"Switching slot terminates");
            check(!gate.valid(connection,level,player,slot,false,true,false,true,1),"Closed connection");
            check(!gate.valid(connection,level,player,slot,true,false,false,true,1),"Death");
            check(!gate.valid(connection,level,player,slot,true,true,true,true,1),"Spectator");
            check(!gate.valid(connection,level,player,slot,true,true,false,false,1),"Item/component mutation");
            check(!gate.valid(connection,level,player,slot,true,true,false,true,0),"Removed");
            check(!gate.valid(connection,level,player,slot,true,true,false,true,2),"Invalid stack count");
        }
        rejects(()->new GbaHandheldGate(connection,level,player,-1));rejects(()->new GbaHandheldGate(connection,level,player,9));
        for(int mask=0;mask<4096;mask++)check(GbaHandheldGate.input(mask)==(mask&0xDFD),"Canonical GBA button routing");
        var use=new GbaHandheldGate.UseGate();
        for(int i=0;i<50;i++){check(use.press(),"New physical press");check(!use.press(),"Same-tick air/block duplicate");use.observe(true);check(!use.press(),"Held right-click repeat");use.observe(false);}
        Path root=Path.of(args[0]).toAbsolutePath();Files.createDirectories(root);
        var a=GbaSaveScope.of("server:example.test:25565",player);var b=GbaSaveScope.of("server:other.test:25565",player);
        var first=new GbaHandheldSelectionStore(root,a);var other=new GbaHandheldSelectionStore(root,b);
        var person=new GbaHandheldSelectionStore(root,GbaSaveScope.of("server:example.test:25565",UUID.randomUUID()));
        check(first.load().isEmpty(),"No remembered ROM");check(!Files.exists(root.resolve("handheld-selection-v1")),"Reading absent choice has no effects");
        Path rom=root.resolve("one.gba");Files.write(rom,new byte[192]);first.remember(rom);
        check(first.load().orElseThrow().equals(rom),"Selection roundtrip");check(other.load().isEmpty(),"Server isolated");check(person.load().isEmpty(),"Player isolated");
        Path second=root.resolve("二号游戏.gba");Files.write(second,new byte[192]);first.remember(second);check(first.load().orElseThrow().equals(second),"Unicode and explicit replacement");
        check(Files.size(rom)==192&&Files.size(second)==192,"Does not edit ROM content");
        Path file=root.resolve("handheld-selection-v1").resolve(a.contextHash()).resolve(player+".txt");
        Files.write(file,new byte[8193]);rejects(first::load);
        Files.writeString(file,"relative.gba",StandardCharsets.UTF_8);rejects(first::load);
        Files.writeString(file,"\n",StandardCharsets.UTF_8);rejects(first::load);
        rejects(()->first.remember(Path.of("relative.gba")));
        try(var files=Files.list(file.getParent())){check(files.noneMatch(p->p.getFileName().toString().endsWith(".tmp")),"Temporary choice files cleaned");}
        System.out.println("{\"ok\":true,\"assertions\":"+assertions+",\"pure_assertions\":"+(assertions-(args.length>1?4:0))+",\"origin_assertions\":"+(args.length>1?4:0)+",\"production_origin\":\""+(args.length>1?"final-jar-only":"source-actual-api")+"\",\"minecraft_or_native_core_started\":false}");
    }
}
