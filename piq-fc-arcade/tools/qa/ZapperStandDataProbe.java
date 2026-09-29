package cn.piq.fcarcade.home;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Actual production SavedData/NBT, not a mocked server or an interaction simulation. */
public final class ZapperStandDataProbe {
    private static int assertions;
    private static void check(boolean v,String why){assertions++;if(!v)throw new AssertionError(why);}
    private static ZapperStandLinks.End end(int x){return new ZapperStandLinks.End("minecraft:overworld",x,64,0,UUID.randomUUID());}
    private static ZapperStandLinks.Link link(int x){return new ZapperStandLinks.Link(UUID.randomUUID(),end(x),end(x+16));}
    private static CompoundTag save(ZapperStandService.Data d){return d.save(new CompoundTag(),RegistryAccess.EMPTY);}
    private static ZapperStandService.Data load(CompoundTag t){return ZapperStandService.Data.load(t,RegistryAccess.EMPTY);}
    private static CompoundTag row(){return ZapperStandService.Data.writeLink(link(0));}
    private static CompoundTag document(CompoundTag... rows){var t=new CompoundTag();var list=new ListTag();for(var r:rows)list.add(r);t.put("Links",list);return t;}
    private static void empty(CompoundTag t,String why){check(load(t).links.snapshot().isEmpty(),why);}
    private static void roundTrip(){
        for(int y:new int[]{-2048,2047}){
            var l=new ZapperStandLinks.Link(UUID.randomUUID(),new ZapperStandLinks.End("minecraft:overworld",0,y,0,UUID.randomUUID()),new ZapperStandLinks.End("minecraft:overworld",1,y,0,UUID.randomUUID()));
            check(ZapperStandService.Data.readLink(ZapperStandService.Data.writeLink(l)).equals(l),"packed signed12 Y boundary "+y);
        }
        var d=new ZapperStandService.Data();check(d.links.snapshot().isEmpty(),"empty by default");
        for(int i=0;i<128;i++)check(d.links.restore(link(i*40)),"legal pair "+i);
        check(!d.links.restore(link(10000)),"129th rejected");var saved=save(d);var loaded=load(saved);
        check(saved.getList("Links",Tag.TAG_COMPOUND).size()==128,"bounded serialized count");
        check(loaded.links.snapshot().equals(d.links.snapshot()),"all identities coordinates directions preserved");
        for(var l:loaded.links.snapshot()){
            check(loaded.links.at(l.stand()).equals(l),"stand endpoint exact");
            check(loaded.links.at(l.console()).equals(l),"console endpoint exact");
            check(ZapperStandService.Data.readLink(ZapperStandService.Data.writeLink(l)).equals(l),"row round trip");
        }
        check(load(save(loaded)).links.snapshot().equals(d.links.snapshot()),"persistence needs no loaded world/chunk");
        saved.getList("Links",Tag.TAG_COMPOUND).clear();check(loaded.links.snapshot().size()==128,"no mutable tag alias");
        var another=save(loaded);another.getList("Links",Tag.TAG_COMPOUND).getCompound(0).getCompound("Stand").putLong("Pos",0);
        check(loaded.links.snapshot().equals(d.links.snapshot()),"saving exposes no mutable ledger alias");
    }
    private static void invalid(){
        empty(new CompoundTag(),"missing list");var t=new CompoundTag();t.putString("Links","invalid");empty(t,"string list");
        empty(document(new CompoundTag()),"empty row");
        for(var field:List.of("Id","Stand","Console")){var r=row();r.remove(field);empty(document(r),"missing "+field);}
        for(var which:List.of("Stand","Console")){
            for(var field:List.of("Dim","Pos","Id")){var r=row();r.getCompound(which).remove(field);empty(document(r),"missing endpoint "+field);}
            var r=row();r.putString(which,"bad");empty(document(r),"wrong endpoint type");
            for(var dim:List.of("","overworld","Minecraft:overworld","bad?:world","x:"+"x".repeat(160))){
                r=row();r.getCompound(which).putString("Dim",dim);empty(document(r),"dimension rejected");
            }
            for(int count:new int[]{0,1,3,5,8}){r=row();r.getCompound(which).putIntArray("Id",new int[count]);empty(document(r),"malformed UUID");}
            r=row();r.getCompound(which).putInt("Pos",64);empty(document(r),"packed position must be long");
            r=row();r.getCompound(which).putUUID("Id",new UUID(0,0));empty(document(r),"zero endpoint identity");
        }
        var r=row();r.putUUID("Id",new UUID(0,0));empty(document(r),"zero link identity");
        r=row();r.getCompound("Console").putString("Dim","minecraft:the_nether");empty(document(r),"cross dimension");
        r=row();r.getCompound("Console").putLong("Pos",new BlockPos(17,64,0).asLong());empty(document(r),"over16 distance");
        r=row();r.getCompound("Console").putLong("Pos",new BlockPos(0,64,0).asLong());empty(document(r),"same site distinct identity");
        r=row();r.getCompound("Console").putUUID("Id",r.getCompound("Stand").getUUID("Id"));empty(document(r),"same identity distinct site");
        r=row();r.getCompound("Console").putLong("Pos",new BlockPos(30000001,64,0).asLong());empty(document(r),"world bound");
        var first=row();var dup=first.copy();check(load(document(first,dup)).links.snapshot().size()==1,"duplicate ignores row");
        dup=first.copy();dup.putUUID("Id",UUID.randomUUID());dup.put("Stand",first.getCompound("Console").copy());dup.put("Console",first.getCompound("Stand").copy());
        check(load(document(first,dup)).links.snapshot().size()==1,"reverse duplicate no second connection");
        dup=first.copy();dup.putUUID("Id",UUID.randomUUID());dup.getCompound("Stand").putUUID("Id",UUID.randomUUID());dup.getCompound("Console").putUUID("Id",UUID.randomUUID());
        check(load(document(first,dup)).links.snapshot().size()==1,"new identities at occupied sites rejected");
        var list=new ListTag();for(int i=0;i<512;i++)list.add(new CompoundTag());list.add(first);t=new CompoundTag();t.put("Links",list);empty(t,"scan bounded at512");
        list.remove(0);check(load(t).links.snapshot().size()==1,"row511 accepted");
        list=new ListTag();for(int i=0;i<140;i++)list.add(ZapperStandService.Data.writeLink(link(i*40)));t.put("Links",list);
        check(load(t).links.snapshot().size()==128,"hostile rows respect128 capacity");
        check(load(document(new CompoundTag(),row())).links.snapshot().size()==1,"bad row never hides later valid row");
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("exact production JAR");var jar=Path.of(args[0]).toRealPath();
        for(Class<?> c:List.of(ZapperStandService.Data.class,ZapperStandLinks.class,ZapperStandLinks.End.class,ZapperStandLinks.Link.class,ZapperDock.class,ZapperDock.Loan.class,cn.piq.fcarcade.layout.ZapperStandGeometry.class))
            check(Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(jar),"actual production origin "+c.getName());
        roundTrip();invalid();
        System.out.println("{\"ok\":true,\"assertions\":"+assertions+",\"production_origin\":\"supplied-jar-only\",\"production_compiled\":false,\"actual_minecraft_nbt_saved_data\":true,\"world_or_chunk_started\":false,\"network_or_core_started\":false}");
    }
}
