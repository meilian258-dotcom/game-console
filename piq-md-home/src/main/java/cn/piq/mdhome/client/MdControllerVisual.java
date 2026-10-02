// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome.client;

import cn.piq.mdhome.*;
import cn.piq.fcarcade.client.ControllerCapture;
import cn.piq.fcarcade.client.PrivateHomeClient;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.common.NeoForge;

/** Presentation only: no keyboard polling, core calls, input ownership changes or private network writes. */
public final class MdControllerVisual {
    private static final MdClient.Provider PROVIDER=new MdClient.Provider();
    private static final MdButtonAnimation LOCAL=new MdButtonAnimation(),IDLE=new MdButtonAnimation();
    private static PrivateBinding privateBinding;
    private static Player renderingPlayer;
    private static Key localKey;
    private static Object connection;
    private static final Map<UUID,Remote> REMOTE=new LinkedHashMap<>();
    private static final long TTL=1_000_000_000L;
    private record PrivateBinding(MdEngine engine,UUID hardware,UUID power,Object connection){}
    private record Key(Object connection,Object session,UUID hardware,UUID loan,int port,int hand){}
    private static final class Remote {
        MdPublicNetwork.Visual packet;long received;
        final MdControllerFrames frames=new MdControllerFrames();
        final MdButtonAnimation animation=new MdButtonAnimation();
        Remote(MdPublicNetwork.Visual packet,long now){this.packet=packet;received=now;}
    }
    private MdControllerVisual(){}
    static void install(){
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Pre e)->frame());
        NeoForge.EVENT_BUS.addListener((RenderPlayerEvent.Pre e)->renderingPlayer=e.getEntity());
        NeoForge.EVENT_BUS.addListener((RenderPlayerEvent.Post e)->renderingPlayer=null);
        NeoForge.EVENT_BUS.addListener((RenderLivingEvent.Pre<?,?> e)->renderingPlayer=e.getEntity() instanceof Player p?p:null);
        NeoForge.EVENT_BUS.addListener((RenderLivingEvent.Post<?,?> e)->renderingPlayer=null);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e)->clear());
    }
    static void privateEngine(MdEngine engine,MdConsole console){
        privateBinding=new PrivateBinding(engine,console.hardwareId(),console.powerSession(),currentConnection());
        LOCAL.clear();localKey=null;
    }
    private static Object currentConnection(){var c=Minecraft.getInstance().getConnection();return c==null?null:c.getConnection();}
    private static boolean poseAllowed(Player p){return p!=null&&p.isAlive()&&!p.isSpectator()&&!p.isUsingItem()&&!p.isVisuallySwimming()&&!p.isFallFlying();}
    private static boolean allowed(){var mc=Minecraft.getInstance();return mc.level!=null&&currentConnection()!=null&&mc.screen==null&&!mc.isPaused()&&mc.isWindowActive()&&poseAllowed(mc.player);}
    private static void clearLocal(){LOCAL.clear();localKey=null;MdPublicClient.clearVisual();if(privateBinding!=null)privateBinding.engine.clearVisual();}
    private static void clear(){clearLocal();privateBinding=null;REMOTE.clear();connection=null;renderingPlayer=null;}
    private static Key key(Player player,ItemStack stack,int hand,Object session){
        var loan=PROVIDER.lease(stack);
        if(loan==null||!(PROVIDER.locate(player,stack) instanceof MdConsole c)||!ControllerCapture.unique(player,stack,loan,PROVIDER::identity))return null;
        return new Key(currentConnection(),session,c.hardwareId(),loan,MdController.port(stack),hand);
    }
    private static void frame(){
        renderingPlayer=null;
        if(connection!=currentConnection()){clear();connection=currentConnection();}
        long now=System.nanoTime();REMOTE.values().removeIf(r->now-r.received>TTL);
        if(!allowed()){clearLocal();return;}
        if(privateBinding!=null&&(!privateBinding.engine.visualAlive()||privateBinding.connection!=connection))privateBinding=null;
        var player=Minecraft.getInstance().player;
        for(int hand=0;hand<2;hand++){
            var stack=hand==0?player.getMainHandItem():player.getOffhandItem();
            var session=MdPublicClient.visualSession(stack);int mask=-1;
            if(session!=null){
                var k=key(player,stack,hand,session);if(k==null)continue;
                if(!k.equals(localKey)){clearLocal();localKey=k;}
                mask=MdPublicClient.visualInput(stack);if(mask>=0){LOCAL.update(k,mask,now);return;}
            }else if(privateBinding!=null&&PROVIDER.locate(player,stack) instanceof MdConsole c
                    &&c.hardwareId().equals(privateBinding.hardware)&&Objects.equals(c.powerSession(),privateBinding.power)
                    &&PROVIDER.cartridgeSession(player,c)!=null&&PrivateHomeClient.cartridgeState(MdMod.SYSTEM,c.getBlockPos())==1){
                var k=key(player,stack,hand,privateBinding.engine);if(k==null||k.port!=0)continue;
                if(!k.equals(localKey)){clearLocal();localKey=k;}
                mask=privateBinding.engine.visualInput(0);if(mask>=0){LOCAL.update(k,mask,now);return;}
            }
        }
        clearLocal();
    }
    static void accept(MdPublicNetwork.Visual packet){
        var mc=Minecraft.getInstance();if(mc.level==null||mc.player==null||!packet.dimension().equals(mc.level.dimension().location())||packet.player().equals(mc.player.getUUID()))return;
        var actor=mc.level.getPlayerByUUID(packet.player());if(!poseAllowed(actor)||actor.distanceToSqr(mc.player)>1024)return;
        long now=System.nanoTime();var prior=REMOTE.get(packet.player());
        if(prior!=null&&same(prior.packet,packet)&&packet.sequence()<=prior.packet.sequence())return;
        if(prior==null||!same(prior.packet,packet)){
            if(prior==null&&REMOTE.size()>=64)REMOTE.remove(REMOTE.keySet().iterator().next());
            prior=new Remote(packet,now);REMOTE.put(packet.player(),prior);
        }
        prior.packet=packet;prior.received=now;
        // Preserve short authorized edges coalesced by the server without extending real input.
        prior.frames.offer(packet.port(),packet.mask()|packet.pressedMask());prior.frames.offer(packet.port(),packet.mask());
        if(packet.reset()){prior.frames.clear(0);prior.animation.clear();}
    }
    private static boolean same(MdPublicNetwork.Visual a,MdPublicNetwork.Visual b){return a.wire()==b.wire()&&a.port()==b.port()&&a.loan().equals(b.loan())&&a.hardware().equals(b.hardware())&&a.console().equals(b.console())&&a.dimension().equals(b.dimension());}
    private static Key remoteKey(Player actor,ItemStack held,int hand,MdPublicNetwork.Visual packet){
        var data=held.get(DataComponents.CUSTOM_DATA);if(data==null||held.getCount()!=1)return null;
        var tag=data.copyTag();
        if(!packet.loan().equals(PROVIDER.lease(held))||packet.port()!=MdController.port(held)
                ||!tag.hasUUID("MdConsole")||!packet.hardware().equals(tag.getUUID("MdConsole"))
                ||packet.console().asLong()!=tag.getLong("MdPos")||!packet.dimension().toString().equals(tag.getString("MdDimension"))
                ||!ControllerCapture.unique(actor,held,packet.loan(),PROVIDER::identity))return null;
        // The operator may be tracked while the adjacent console chunk is not. The short-lived
        // server-authorized receipt is sufficient there; never load a chunk to draw a controller.
        var level=Minecraft.getInstance().level;
        if(level.hasChunkAt(packet.console())&&(!(level.getBlockEntity(packet.console()) instanceof MdConsole c)
                ||!c.running()||!c.publicPlay()||!packet.hardware().equals(c.hardwareId())
                ||!packet.loan().equals(c.loan(packet.port()))||!actor.getUUID().equals(c.borrower(packet.port()))))return null;
        return new Key(currentConnection(),packet.wire(),packet.hardware(),packet.loan(),packet.port(),hand);
    }
    public static MdButtonAnimation state(ItemStack stack,ItemDisplayContext context){
        var mc=Minecraft.getInstance();boolean first=context==ItemDisplayContext.FIRST_PERSON_LEFT_HAND||context==ItemDisplayContext.FIRST_PERSON_RIGHT_HAND;
        boolean third=context==ItemDisplayContext.THIRD_PERSON_LEFT_HAND||context==ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
        if(!first&&!third||mc.level==null)return IDLE;
        var actor=first?mc.player:renderingPlayer;if(!poseAllowed(actor))return IDLE;
        if(actor==mc.player){
            if(!allowed()||localKey==null)return IDLE;
            var held=localKey.hand==0?actor.getMainHandItem():actor.getOffhandItem();
            return ItemStack.isSameItemSameComponents(stack,held)&&localKey.equals(key(actor,held,localKey.hand,localKey.session))&&LOCAL.matches(localKey)?LOCAL:IDLE;
        }
        var remote=REMOTE.get(actor.getUUID());if(remote==null||System.nanoTime()-remote.received>TTL||mc.player==null||actor.distanceToSqr(mc.player)>1024)return IDLE;
        var packet=remote.packet;
        for(int hand=0;hand<2;hand++){
            var held=hand==0?actor.getMainHandItem():actor.getOffhandItem();if(!ItemStack.isSameItemSameComponents(stack,held)||!packet.loan().equals(MdController.loan(held))||packet.port()!=MdController.port(held))continue;
            var k=remoteKey(actor,held,hand,packet);if(k==null)continue;
            remote.animation.update(k,remote.frames.present(packet.port()),System.nanoTime());return remote.animation;
        }
        remote.animation.clear();remote.frames.clear(0);return IDLE;
    }
}
