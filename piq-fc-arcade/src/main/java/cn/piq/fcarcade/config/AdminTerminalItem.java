package cn.piq.fcarcade.config;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

public final class AdminTerminalItem extends Item {
    public interface ClientOpen { void open(InteractionHand hand); }
    private static volatile ClientOpen client;
    public static void clientOpen(ClientOpen callback){client=java.util.Objects.requireNonNull(callback);}
    public AdminTerminalItem(Properties properties){super(properties);}
    private InteractionResult activate(Player player,InteractionHand hand){
        if(player!=null&&player.level().isClientSide&&player.isAlive()&&!player.isSpectator()&&player.getItemInHand(hand).is(this)){
            var current=client;if(current!=null)current.open(hand);
        }
        return InteractionResult.CONSUME;
    }
    @Override public InteractionResult onItemUseFirst(ItemStack stack,UseOnContext context){return activate(context.getPlayer(),context.getHand());}
    @Override public InteractionResult useOn(UseOnContext context){return activate(context.getPlayer(),context.getHand());}
    @Override public InteractionResultHolder<ItemStack> use(Level level,Player player,InteractionHand hand){return new InteractionResultHolder<>(activate(player,hand),player.getItemInHand(hand));}
    @Override public void appendHoverText(ItemStack stack,TooltipContext context,List<Component> lines,TooltipFlag flag){lines.add(Component.translatable("tooltip.piq_fc_arcade.admin_terminal"));}
}
