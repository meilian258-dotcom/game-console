package cn.piq.fcarcade.cabinet;

import java.util.List;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Real MC NBT/SavedData methods, with no fabricated server, world, chunk or protection-event claim. */
public final class CabinetLinksDataProbe {
    private static int assertions;
    private static void require(boolean value,String reason){assertions++;if(!value)throw new AssertionError(reason);}
    private static CabinetLinkLedger.End end(int x){return new CabinetLinkLedger.End("minecraft:overworld",x,64,0,UUID.randomUUID(),true);}
    private static CabinetLinkLedger.Pair pair(int x){return new CabinetLinkLedger.Pair(UUID.randomUUID(),end(x),end(x+2));}
    private static CompoundTag save(CabinetLinks.Data data){return data.save(new CompoundTag(),RegistryAccess.EMPTY);}
    private static CabinetLinks.Data load(CompoundTag tag){return CabinetLinks.Data.load(tag,RegistryAccess.EMPTY);}
    private static CompoundTag oneRow(){var data=new CabinetLinks.Data();require(data.ledger.restore(pair(0)),"fixture restore");return save(data).getList("Pairs",Tag.TAG_COMPOUND).getCompound(0);}
    private static CompoundTag document(CompoundTag... rows){var result=new CompoundTag();var list=new ListTag();for(var row:rows)list.add(row);result.put("Pairs",list);return result;}
    private static void empty(CompoundTag document,String reason){require(load(document).ledger.snapshot().isEmpty(),reason);}
    private static void roundTrip(){
        var data=new CabinetLinks.Data();require(data.ledger.snapshot().isEmpty(),"empty initial");
        for(int i=0;i<128;i++)require(data.ledger.restore(pair(i*40)),"128 legal pairs");
        require(!data.ledger.restore(pair(10000)),"129 rejected");var tag=save(data);
        require(tag.getList("Pairs",Tag.TAG_COMPOUND).size()==128,"bounded serialized rows");
        var restored=load(tag);require(restored.ledger.snapshot().equals(data.ledger.snapshot()),"all exact UUID dimensions coordinates seat order round trip");
        for(var p:restored.ledger.snapshot()){
            require(restored.ledger.find(p.primary()).equals(p),"primary find");require(restored.ledger.find(p.secondary()).equals(p),"secondary find");
            require(p.other(p.primary()).equals(p.secondary()),"forward peer");require(p.other(p.secondary()).equals(p.primary()),"reverse peer");
        }
        // Reading/saving topology has no live entity requirement: persistence itself does not erase an unloaded endpoint.
        var reloadedAgain=load(save(restored));require(reloadedAgain.ledger.snapshot().equals(restored.ledger.snapshot()),"offline save/load preserves line identities");
        tag.getList("Pairs",Tag.TAG_COMPOUND).clear();require(restored.ledger.snapshot().size()==128,"loaded records detach from mutable NBT");
        var serialized=save(restored);serialized.getList("Pairs",Tag.TAG_COMPOUND).getCompound(0).getCompound("Primary").putInt("X",12345);
        require(restored.ledger.snapshot().equals(data.ledger.snapshot()),"saving returns no mutable ledger aliases");
    }
    private static void invalidRows(){
        empty(new CompoundTag(),"missing list");var wrongList=new CompoundTag();wrongList.putString("Pairs","not a list");empty(wrongList,"wrong list type");
        var onlyInts=new ListTag();onlyInts.add(net.minecraft.nbt.IntTag.valueOf(1));wrongList.put("Pairs",onlyInts);empty(wrongList,"wrong list member type");
        empty(document(new CompoundTag()),"missing whole row");
        for(String field:List.of("Id","Primary","Secondary")){var row=oneRow();row.remove(field);empty(document(row),"missing "+field);}
        for(String which:List.of("Primary","Secondary")){
            for(String field:List.of("Dimension","Id","X","Y","Z","Dual")){
                var row=oneRow();row.getCompound(which).remove(field);empty(document(row),"missing endpoint "+which+" "+field);
            }
            for(String axis:List.of("X","Y","Z")){
                var row=oneRow();row.getCompound(which).putString(axis,"64");empty(document(row),"string coordinate "+axis);
                row=oneRow();row.getCompound(which).putLong(axis,64L);empty(document(row),"long coordinate "+axis);
            }
            for(int length:new int[]{0,1,3,5,8}){var row=oneRow();row.getCompound(which).putIntArray("Id",new int[length]);empty(document(row),"bad UUID length "+length);}
            for(String dimension:List.of("","overworld","Minecraft:overworld","bad?namespace:value","minecraft:"+"a".repeat(129))){
                var row=oneRow();row.getCompound(which).putString("Dimension",dimension);empty(document(row),"invalid dimension syntax");
            }
            var row=oneRow();row.getCompound(which).putBoolean("Dual",false);empty(document(row),"single cabinet schema");
        }
        var row=oneRow();row.putIntArray("Id",new int[]{1,2});empty(document(row),"bad pair UUID");
        row=oneRow();row.putString("Id",UUID.randomUUID().toString());empty(document(row),"string pair UUID");
        row=oneRow();row.getCompound("Secondary").putString("Dimension","minecraft:the_nether");empty(document(row),"cross dimension");
        row=oneRow();row.getCompound("Secondary").putInt("X",17);empty(document(row),"over 16 blocks");
        row=oneRow();row.getCompound("Primary").putInt("X",Integer.MIN_VALUE);row.getCompound("Secondary").putInt("X",Integer.MAX_VALUE);empty(document(row),"overflow distance");
        row=oneRow();row.put("Secondary",row.getCompound("Primary").copy());empty(document(row),"same endpoint");
        row=oneRow();row.getCompound("Secondary").putInt("X",0);empty(document(row),"same place another UUID");
        row=oneRow();row.getCompound("Secondary").putUUID("Id",row.getCompound("Primary").getUUID("Id"));empty(document(row),"same UUID another place");
    }
    private static void duplicatesAndBounds(){
        var first=oneRow();var duplicate=first.copy();
        require(load(document(first,duplicate)).ledger.snapshot().size()==1,"exact duplicate ignored");
        var reversed=first.copy();reversed.putUUID("Id",UUID.randomUUID());reversed.put("Primary",first.getCompound("Secondary").copy());reversed.put("Secondary",first.getCompound("Primary").copy());
        require(load(document(first,reversed)).ledger.snapshot().size()==1,"reverse duplicate ignored");
        var samePairId=oneRow();samePairId.putUUID("Id",first.getUUID("Id"));samePairId.getCompound("Primary").putInt("X",40);samePairId.getCompound("Secondary").putInt("X",42);
        require(load(document(first,samePairId)).ledger.snapshot().size()==1,"pair ID collision ignored");
        var collision=oneRow();collision.getCompound("Primary").putUUID("Id",first.getCompound("Primary").getUUID("Id"));collision.getCompound("Primary").putInt("X",40);collision.getCompound("Secondary").putInt("X",42);
        require(load(document(first,collision)).ledger.snapshot().size()==1,"endpoint UUID collision ignored");
        var distinct=oneRow();distinct.getCompound("Primary").putInt("X",40);distinct.getCompound("Secondary").putInt("X",42);
        var repaired=load(document(new CompoundTag(),first,new CompoundTag(),distinct));require(repaired.ledger.snapshot().size()==2,"bad row does not discard later valid rows");
        require(repaired.ledger.snapshot().getFirst().id().equals(first.getUUID("Id")),"first valid row order retained");
        var huge=new ListTag();var tag=new CompoundTag();for(int i=0;i<511;i++)huge.add(new CompoundTag());huge.add(first.copy());huge.add(distinct.copy());tag.put("Pairs",huge);
        var bounded=load(tag);require(bounded.ledger.snapshot().size()==1,"at most 512 rows examined");require(bounded.ledger.snapshot().getFirst().id().equals(first.getUUID("Id")),"index511 accepted index512 ignored");
        huge=new ListTag();for(int i=0;i<512;i++)huge.add(new CompoundTag());huge.add(first.copy());tag.put("Pairs",huge);empty(tag,"valid row beyond scan budget ignored");
        var oversized=new CabinetLinks.Data();var rows=new ListTag();
        for(int i=0;i<140;i++){var d=new CabinetLinks.Data();require(d.ledger.restore(pair(i*40)),"oversize fixture");rows.add(save(d).getList("Pairs",Tag.TAG_COMPOUND).getCompound(0));}
        tag=new CompoundTag();tag.put("Pairs",rows);oversized=load(tag);require(oversized.ledger.snapshot().size()==128,"NBT pair capacity128");
        require(save(oversized).getList("Pairs",Tag.TAG_COMPOUND).size()==128,"resave bounded document");
    }
    public static void main(String[] args){
        roundTrip();invalidRows();duplicatesAndBounds();
        System.out.println("{\"ok\":true,\"assertions\":"+assertions+",\"actual_minecraft_nbt_saved_data\":true,\"holder_lookup\":\"RegistryAccess.EMPTY\",\"world_or_chunk_started\":false,\"network_or_core_started\":false,\"scope\":\"production SavedData serialization; no live chunk-unload or protection-event simulation\"}");
    }
}
