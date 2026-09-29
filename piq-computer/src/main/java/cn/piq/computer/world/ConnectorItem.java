package cn.piq.computer.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;

/** Reusable wireless pairing tool; a bound receiver is rendered in the PC USB port. */
public final class ConnectorItem extends Item {
    public ConnectorItem(Properties p){super(p);}
    @Override public InteractionResult useOn(UseOnContext c){
        if(!(c.getPlayer() instanceof ServerPlayer p))return InteractionResult.SUCCESS;
        var level=p.serverLevel();var pos=c.getClickedPos();var stack=c.getItemInHand();
        if(!ComputerAccess.near(p,pos))return InteractionResult.FAIL;
        var pc=ComputerBlock.find(level,pos);
        if(pc!=null){
            var selected=pc;
            CustomData.update(DataComponents.CUSTOM_DATA,stack,t->{t.putLong("PC",selected.getBlockPos().asLong());t.putUUID("Id",selected.hardwareId());t.putString("Dimension",level.dimension().location().toString());});
            ComputerEntity.say(p,"USB 接收器主机已选定，再右键键鼠套装配对");return InteractionResult.CONSUME;
        }
        if(!(level.getBlockEntity(pos) instanceof PeripheralEntity e))return InteractionResult.PASS;
        var tag=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
        if(!tag.hasUUID("Id")||!level.dimension().location().toString().equals(tag.getString("Dimension"))){ComputerEntity.say(p,"先右键选择主机");return InteractionResult.CONSUME;}
        pc=ComputerBlock.find(level,BlockPos.of(tag.getLong("PC")));
        if(pc==null||!tag.getUUID("Id").equals(pc.hardwareId())||pc.getBlockPos().distSqr(pos)>64||!ComputerAccess.allowed(p,pc.getBlockPos())){ComputerEntity.say(p,"主机不可连接，请重新选择附近主机");return InteractionResult.CONSUME;}
        // A protection callback can change blocks or the held item; never bind stale entities.
        if(ComputerBlock.find(level,pc.getBlockPos())!=pc||level.getBlockEntity(pos)!=e||p.getItemInHand(c.getHand())!=stack)return InteractionResult.FAIL;
        var old=e.connected();var occupied=pc.peripheral(e.keyboard());
        if(old!=null&&old!=pc){ComputerEntity.say(p,"先空手 Shift + 右键断开旧连接");return InteractionResult.CONSUME;}
        if(occupied!=null&&occupied!=e){ComputerEntity.say(p,"主机已有这类外设，请先断开");return InteractionResult.CONSUME;}
        if(e.combined()&&pc.peripheral(false)!=null&&pc.peripheral(false)!=e){ComputerEntity.say(p,"请先断开原来的鼠标");return InteractionResult.CONSUME;}
        if(pc.powered){ComputerEntity.say(p,"先关机再配对接收器");return InteractionResult.CONSUME;}
        e.connect(pc);ComputerEntity.say(p,"无线键鼠已配对，USB 接收器已插入主机");return InteractionResult.CONSUME;
    }
}
