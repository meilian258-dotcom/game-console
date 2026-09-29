package cn.piq.fcarcade.client;

import cn.piq.fcarcade.home.HomeControllerData;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.common.NeoForge;

/** No remote-player inference and no new payload. Only the matching local leased hand animates. */
final class ClientControllerAnimation {
    private static final ControllerButtonAnimation ACTIVE=new ControllerButtonAnimation(),IDLE=new ControllerButtonAnimation();
    private static boolean localThird;
    private ClientControllerAnimation() {}
    static void register() {
        NeoForge.EVENT_BUS.addListener(ClientControllerAnimation::beforeLiving);
        NeoForge.EVENT_BUS.addListener(ClientControllerAnimation::afterLiving);
        NeoForge.EVENT_BUS.addListener(ClientControllerAnimation::beforePlayer);
        NeoForge.EVENT_BUS.addListener(ClientControllerAnimation::afterPlayer);
    }
    private static void beforeLiving(RenderLivingEvent.Pre<?,?> event) { localThird=event.getEntity()==Minecraft.getInstance().player; }
    private static void afterLiving(RenderLivingEvent.Post<?,?> event) { localThird=false; }
    private static void beforePlayer(RenderPlayerEvent.Pre event) { localThird=event.getEntity()==Minecraft.getInstance().player; }
    private static void afterPlayer(RenderPlayerEvent.Post event) { localThird=false; }
    static void clear() { ACTIVE.clear();localThird=false; }
    private static long session(ItemStack stack) {
        var tag=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().getCompound("PiqHomeController");
        return tag.contains("Session")?tag.getLong("Session"):-1;
    }
    private static boolean allowed() {
        var mc=Minecraft.getInstance();var player=mc.player;
        return player!=null&&mc.level!=null&&mc.getConnection()!=null&&mc.screen==null&&!mc.isPaused()&&mc.isWindowActive()
                &&player.isAlive()&&!player.isUsingItem()&&!player.isVisuallySwimming()&&!player.isFallFlying();
    }
    static void frame() {
        localThird=false;
        if(!allowed()){clear();return;}
        var player=Minecraft.getInstance().player;
        for(int hand=0;hand<2;hand++) {
            ItemStack stack=hand==0?player.getMainHandItem():player.getOffhandItem();
            if(!HomeControllerData.isBorrowed(stack))continue;
            long id=session(stack);int port=HomeControllerData.port(stack);
            int mask=ClientArcadeEvents.controllerAnimationMask(id,port);
            if(mask<0)continue;
            ACTIVE.update(HomeControllerData.leaseId(stack),id,port,hand,mask,true,System.nanoTime());return;
        }
        clear();
    }
    static ControllerButtonAnimation state(ItemStack stack,ItemDisplayContext context) {
        if(!allowed())return IDLE;
        boolean first=context==ItemDisplayContext.FIRST_PERSON_LEFT_HAND||context==ItemDisplayContext.FIRST_PERSON_RIGHT_HAND;
        boolean third=context==ItemDisplayContext.THIRD_PERSON_LEFT_HAND||context==ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
        if(!first&&!(third&&localThird))return IDLE;
        var player=Minecraft.getInstance().player;
        for(int hand=0;hand<2;hand++) {
            var held=hand==0?player.getMainHandItem():player.getOffhandItem();
            if(ItemStack.isSameItemSameComponents(stack,held)&&ACTIVE.matches(HomeControllerData.leaseId(held),session(held),HomeControllerData.port(held),hand))return ACTIVE;
        }
        return IDLE;
    }
}
