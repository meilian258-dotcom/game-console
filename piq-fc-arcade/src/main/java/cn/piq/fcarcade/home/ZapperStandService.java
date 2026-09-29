package cn.piq.fcarcade.home;

import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Physical stand inventory + one-to-one persistent cable. No ROM/core/input algorithms. */
public final class ZapperStandService {
    private static final ThreadLocal<Boolean> BUSY=ThreadLocal.withInitial(()->false);
    private static final Map<MinecraftServer,Map<UUID,Pending>> PENDING=new WeakHashMap<>();
    private record Pending(ServerPlayer player,Connection connection,ItemStack cable,ZapperStandBlockEntity stand,
                           ZapperStandLinks.End end,BlockHitResult hit,int expires){}
    private ZapperStandService(){}
    public static void register(){NeoForge.EVENT_BUS.addListener(ZapperStandService::tick);NeoForge.EVENT_BUS.addListener(ZapperStandService::stopped);}
    private static Data data(MinecraftServer server){return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(Data::new,Data::load),"piq_zapper_stands");}
    private static Map<UUID,Pending> pending(MinecraftServer s){return PENDING.computeIfAbsent(s,k->new HashMap<>());}
    private static BlockPos pos(ZapperStandLinks.End end){return new BlockPos(end.x(),end.y(),end.z());}
    private static ServerLevel level(MinecraftServer s,ZapperStandLinks.End e){return s.getLevel(ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,ResourceLocation.parse(e.dimension())));}
    private static ZapperStandLinks.End end(HomeConsoleBlockEntity c){var p=c.getBlockPos();return new ZapperStandLinks.End(c.getLevel().dimension().location().toString(),p.getX(),p.getY(),p.getZ(),c.hardwareId());}
    private static boolean current(ServerPlayer p){return p!=null&&p.getServer()!=null&&p.getServer().isSameThread()&&p.isAlive()&&!p.isSpectator()&&!p.hasDisconnected()
            &&p.connection.getConnection().isConnected()&&p.getServer().getPlayerList().getPlayer(p.getUUID())==p;}
    private static boolean current(ServerPlayer p,BlockEntity b){return current(p)&&b!=null&&!b.isRemoved()&&b.getLevel()==p.serverLevel()
            &&p.serverLevel().hasChunkAt(b.getBlockPos())&&p.serverLevel().getBlockEntity(b.getBlockPos())==b
            &&p.serverLevel().getWorldBorder().isWithinBounds(b.getBlockPos())&&p.serverLevel().mayInteract(p,b.getBlockPos())
            &&p.distanceToSqr(Vec3.atCenterOf(b.getBlockPos()))<=64;}
    private static boolean permission(ServerPlayer p,BlockEntity b,BlockHitResult original){if(!current(p,b))return false;
        var hit=new BlockHitResult(original.getLocation(),original.getDirection(),b.getBlockPos(),original.isInside());
        var e=NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickBlock(p,InteractionHand.MAIN_HAND,b.getBlockPos(),hit));
        return !e.isCanceled()&&e.getUseBlock()!=TriState.FALSE&&e.getUseItem()!=TriState.FALSE&&current(p,b);}
    private static InteractionResult notice(ServerPlayer p,String key){p.displayClientMessage(Component.translatable("message.piq_fc_arcade.zapper_stand_"+key),true);return InteractionResult.CONSUME;}
    private static ZapperStandBlockEntity stand(MinecraftServer server,ZapperStandLinks.End e){var l=level(server,e);return l!=null&&l.hasChunkAt(pos(e))
            &&l.getBlockEntity(pos(e)) instanceof ZapperStandBlockEntity b&&!b.isRemoved()&&b.identity().equals(e.id())?b:null;}
    private static HomeConsoleBlockEntity console(MinecraftServer server,ZapperStandLinks.Link link){var l=level(server,link.console());return l!=null&&l.hasChunkAt(pos(link.console()))
            &&l.getBlockEntity(pos(link.console())) instanceof HomeConsoleBlockEntity c&&!c.isRemoved()&&c.hardwareId().equals(link.console().id())?c:null;}
    /** The link is authoritative only if both exact live endpoints and saved identity agree. */
    public static boolean connected(HomeConsoleBlockEntity c){if(c==null||!(c.getLevel() instanceof ServerLevel l)||!l.getServer().isSameThread())return false;
        var link=data(l.getServer()).links.at(end(c));if(link==null||console(l.getServer(),link)!=c)return false;
        var b=stand(l.getServer(),link.stand());return b!=null&&link.equals(b.link());}
    /** Read-only physical borrower, not an input grant; unknown/unloaded/duplicate/inventory-only guns return null. */
    public static ServerPlayer loanPlayer(HomeConsoleBlockEntity c){
        if(!connected(c))return null;var server=((ServerLevel)c.getLevel()).getServer();
        var link=data(server).links.at(end(c));var b=link==null?null:stand(server,link.stand());
        if(b==null||b.loan()==null)return null;var player=server.getPlayerList().getPlayer(b.loan().player());
        if(!current(player)||player.serverLevel()!=c.getLevel()||player.distanceToSqr(Vec3.atCenterOf(c.getBlockPos()))>36)return null;
        var held=player.getMainHandItem();return held.getCount()==1&&held.getItem() instanceof HomeZapperItem
                &&originMatches(held,b)&&unique(player,held)&&validOrigin(player,held,c)?player:null;
    }
    /** Switching data-cable family cancels only an unfinished selection, never an installed link. */
    public static void cancelCableSelection(ServerPlayer p){if(p!=null&&p.getServer()!=null&&p.getServer().isSameThread()){
        var choices=PENDING.get(p.getServer());if(choices!=null)choices.remove(p.getUUID());}}
    public static boolean validOrigin(ServerPlayer p,ItemStack item){if(!ZapperStandOrigin.present(item))return true;
        var receipt=ZapperStandOrigin.read(item);if(!current(p)||receipt==null)return false;var b=stand(p.getServer(),receipt.stand());
        if(b==null||b.loan()==null||!receipt.loan().equals(b.loan().id())||!p.getUUID().equals(b.loan().player()))return false;
        var link=b.link();var c=link==null?null:console(p.getServer(),link);return c!=null&&connected(c);}
    /** A borrowed physical gun may control only the console reached by its own stand cable. */
    public static boolean validOrigin(ServerPlayer p,ItemStack item,HomeConsoleBlockEntity target){
        if(!ZapperStandOrigin.present(item))return true;if(!validOrigin(p,item)||target==null)return false;
        var receipt=ZapperStandOrigin.read(item);var b=stand(p.getServer(),receipt.stand());
        return b!=null&&b.link()!=null&&console(p.getServer(),b.link())==target;}
    private static boolean orphaned(MinecraftServer server,ZapperStandOrigin.Receipt receipt){
        var l=level(server,receipt.stand());return l!=null&&l.hasChunkAt(pos(receipt.stand()))&&stand(server,receipt.stand())==null;}
    public static boolean originMatches(ItemStack stack,ZapperStandBlockEntity b){var r=ZapperStandOrigin.read(stack);return r!=null&&b.loan()!=null&&r.stand().equals(b.endpoint())&&r.loan().equals(b.loan().id());}
    private static List<ItemStack> items(ServerPlayer p){Set<ItemStack> seen=Collections.newSetFromMap(new IdentityHashMap<>());var all=new ArrayList<ItemStack>();
        for(int i=0;i<p.getInventory().getContainerSize();i++){var item=p.getInventory().getItem(i);if(seen.add(item))all.add(item);}
        for(var slot:p.inventoryMenu.slots)if(seen.add(slot.getItem()))all.add(slot.getItem());var carried=p.containerMenu.getCarried();if(seen.add(carried))all.add(carried);return all;}
    private static boolean unique(ServerPlayer p,ItemStack held){var r=ZapperStandOrigin.read(held);if(r==null)return true;int count=0;for(var item:items(p))if(r.equals(ZapperStandOrigin.read(item)))count+=item.getCount();return count==1;}
    /** Sneaking empty-hand unlink. Both ends use the same server-only, paid-once transaction. */
    static InteractionResult tryDisconnect(ServerPlayer p,BlockPos clicked,BlockHitResult hit){
        if(BUSY.get())return InteractionResult.CONSUME;
        if(!current(p)||!p.isShiftKeyDown()||clicked==null||hit==null||!clicked.equals(hit.getBlockPos())||!emptyHands(p))return InteractionResult.PASS;
        var l=p.serverLevel();if(!l.hasChunkAt(clicked))return InteractionResult.PASS;
        var clickedEntity=l.getBlockEntity(clicked);
        BlockEntity endpoint=clickedEntity instanceof ZapperStandBlockEntity?clickedEntity:HomeHardware.loadedEndpoint(l,clicked);
        if(!(endpoint instanceof ZapperStandBlockEntity)&&!(endpoint instanceof HomeConsoleBlockEntity))return InteractionResult.PASS;
        if(!unplugTarget(p,clicked,endpoint))return InteractionResult.PASS;
        var d=data(p.getServer());
        var link=d.links.at(endpoint instanceof ZapperStandBlockEntity b?b.endpoint():end((HomeConsoleBlockEntity)endpoint));
        // Consume this physical socket even when no cable remains: repeated/shift clicks must not eject a cartridge.
        if(link==null)return notice(p,"not_connected");
        var b=stand(p.getServer(),link.stand());var c=console(p.getServer(),link);
        if(b==null||c==null||!link.equals(b.link())||!current(p,b)||!current(p,c))return notice(p,"changed");
        var connection=p.connection.getConnection();var standId=b.identity();var consoleId=c.hardwareId();
        var original=b.link();var loan=b.loan();var stored=b.dock.stored();var clickedState=l.getBlockState(clicked);
        var standState=b.getBlockState();var consoleState=c.getBlockState();
        BUSY.set(true);try{
            if(!permission(p,b,hit)||!permission(p,c,hit)
                    ||(endpoint instanceof HomeConsoleBlockEntity target
                    &&!HomeHardware.allowAnchorInteraction(p,clicked,target,InteractionHand.MAIN_HAND,hit)))return notice(p,"denied");
            // Protection hooks may move the player, replace hardware, swap hands, or edit the link.
            if(!current(p,b)||!current(p,c)||p.serverLevel()!=l||p.connection.getConnection()!=connection||!p.isShiftKeyDown()||!emptyHands(p)
                    ||l.getBlockEntity(clicked)!=clickedEntity||l.getBlockState(clicked)!=clickedState
                    ||b.getBlockState()!=standState||c.getBlockState()!=consoleState
                    ||!b.identity().equals(standId)||!c.hardwareId().equals(consoleId)||b.loan()!=loan||b.dock.stored()!=stored
                    ||b.link()!=original||d.links.at(b.endpoint())!=link||d.links.at(end(c))!=link
                    ||stand(p.getServer(),link.stand())!=b||console(p.getServer(),link)!=c||!unplugTarget(p,clicked,endpoint))return notice(p,"changed");
            disconnect(b);cancelCableSelection(p);
            HomeInteractionSounds.play(l,endpoint.getBlockPos(),HomeInteractionSounds.Action.CABLE_DISCONNECT);
            return notice(p,"disconnected");
        }catch(RuntimeException failure){cn.piq.fcarcade.FcArcadeMod.LOGGER.warn("Zapper empty-hand unplug failed closed",failure);return notice(p,"changed");}
        finally{BUSY.remove();}
    }
    private static boolean emptyHands(ServerPlayer p){return p.getMainHandItem().isEmpty()&&p.getOffhandItem().isEmpty();}
    private static boolean unplugTarget(ServerPlayer p,BlockPos clicked,BlockEntity endpoint){
        if(!current(p,endpoint)||!p.serverLevel().hasChunkAt(clicked)||!p.serverLevel().mayInteract(p,clicked))return false;
        if(endpoint instanceof HomeConsoleBlockEntity c
                &&(HomeHardware.loadedEndpoint(p.serverLevel(),clicked)!=c||!SuborStructure.complete(p.serverLevel(),c.getBlockPos())))return false;
        var state=endpoint.getBlockState();
        if(!state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING))return false;
        double reach=Math.min(6.0,p.blockInteractionRange());if(!Double.isFinite(reach)||reach<=0)return false;
        Vec3 eye=p.getEyePosition(),end=eye.add(p.getLookAngle().scale(reach)),offset=Vec3.atLowerCornerOf(endpoint.getBlockPos());
        int turns=switch(state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)){
            case EAST->1;case SOUTH->2;case WEST->3;default->0;};
        var a=localRay(eye.subtract(offset),turns);var z=localRay(end.subtract(offset),turns);
        boolean target;
        if(endpoint instanceof ZapperStandBlockEntity)target=ZapperCableControls.stand(a,z);
        else{
            var style=state.getBlock() instanceof SuborConsoleBlock
                    ?SuborConsoleBlock.compact(state)?cn.piq.fcarcade.layout.ControllerCableGeometry.Style.SUBOR_COMPACT
                    :SuborConsoleBlock.wide(state)?cn.piq.fcarcade.layout.ControllerCableGeometry.Style.SUBOR_WIDE
                    :cn.piq.fcarcade.layout.ControllerCableGeometry.Style.SUBOR
                    :cn.piq.fcarcade.layout.ControllerCableGeometry.Style.FAMICOM;
            target=ZapperCableControls.console(style,a,z);
        }
        if(!target)return false;
        var actual=new UnplugView(p.serverLevel()).clip(new net.minecraft.world.level.ClipContext(eye,end,
                net.minecraft.world.level.ClipContext.Block.OUTLINE,net.minecraft.world.level.ClipContext.Fluid.NONE,p));
        if(actual.getType()!=HitResult.Type.BLOCK)return false;
        return endpoint instanceof ZapperStandBlockEntity?actual.getBlockPos().equals(clicked)
                :HomeHardware.loadedEndpoint(p.serverLevel(),actual.getBlockPos())==endpoint;
    }
    private static ApplianceRay.Point localRay(Vec3 p,int turns){return ApplianceRay.unrotate(new ApplianceRay.Point(p.x,p.y,p.z),turns);}
    private record UnplugView(ServerLevel level) implements net.minecraft.world.level.BlockGetter{
        public net.minecraft.world.level.block.state.BlockState getBlockState(BlockPos p){return level.hasChunkAt(p)?level.getBlockState(p):net.minecraft.world.level.block.Blocks.BARRIER.defaultBlockState();}
        public BlockEntity getBlockEntity(BlockPos p){return level.hasChunkAt(p)?level.getBlockEntity(p):null;}
        public net.minecraft.world.level.material.FluidState getFluidState(BlockPos p){return level.hasChunkAt(p)?level.getFluidState(p):net.minecraft.world.level.material.Fluids.EMPTY.defaultFluidState();}
        public int getHeight(){return level.getHeight();}public int getMinBuildHeight(){return level.getMinBuildHeight();}
    }
    public static InteractionResult interact(ServerPlayer p,BlockPos clicked,BlockHitResult hit){if(BUSY.get()||!current(p))return InteractionResult.CONSUME;
        if(!p.serverLevel().hasChunkAt(clicked))return InteractionResult.PASS;
        if(!(p.serverLevel().getBlockEntity(clicked) instanceof ZapperStandBlockEntity b))return InteractionResult.PASS;
        var unplug=tryDisconnect(p,clicked,hit);if(unplug!=InteractionResult.PASS)return unplug;
        BUSY.set(true);try{
            var hand=p.getMainHandItem();var loan=b.loan();var stored=b.dock.stored();var identity=b.identity();var link=b.link();
            if(!permission(p,b,hit)||!current(p,b)||!b.identity().equals(identity)||p.getMainHandItem()!=hand||b.loan()!=loan||b.dock.stored()!=stored)return notice(p,"changed");
            if(hand.isEmpty()){
                if(stored==null)return notice(p,loan==null?"empty":"borrowed");
                var c=link==null?null:console(p.getServer(),link);
                if(c==null||!connected(c))return notice(p,"power_first");
                if(!permission(p,c,hit)||!current(p,b)||!current(p,c)||b.dock.stored()!=stored||b.loan()!=loan||b.link()!=link||p.getMainHandItem()!=hand
                        ||!connected(c))return notice(p,"changed");
                ItemStack gun=b.dock.take(p.getUUID());if(gun==null)return notice(p,"changed");
                ZapperStandOrigin.bind(gun,b.endpoint(),b.loan().id());p.setItemInHand(InteractionHand.MAIN_HAND,gun);b.changed();p.getInventory().setChanged();p.inventoryMenu.broadcastChanges();
                HomeInteractionSounds.play(p.serverLevel(),b.getBlockPos(),HomeInteractionSounds.Action.CONTROLLER_TAKE);
                // The service can only join an already running gun variant, never start/stop a core.
                boolean took=HomeZapperService.take(p,c,gun);
                return notice(p,took?"taken":"take_no_input");
            }
            if(!(hand.getItem() instanceof HomeZapperItem)||hand.getCount()!=1)return notice(p,"use_gun");
            if(stored!=null)return notice(p,"full");
            var receipt=ZapperStandOrigin.read(hand);UUID lease=receipt==null?null:receipt.loan();
            if(ZapperStandOrigin.present(hand)){
                if(receipt==null||!unique(p,hand))return notice(p,"wrong_gun");
                // A real dropped/borrowed gun survives destruction of its old stand. Only a
                // loaded, provably replaced/missing origin may be deposited as an ordinary
                // existing item; an unloaded origin is never mistaken for a destroyed one.
                if(!receipt.stand().equals(b.endpoint())){
                    if(loan!=null||!orphaned(p.getServer(),receipt))return notice(p,"wrong_gun");lease=null;
                }
            }
            if(!b.dock.canDeposit(lease))return notice(p,"wrong_gun");
            HomeZapperService.returnGun(p,hand);
            if(!current(p,b)||p.getMainHandItem()!=hand||b.dock.stored()!=stored||b.loan()!=loan||!b.dock.canDeposit(lease))return notice(p,"changed");
            ZapperStandOrigin.clear(hand);ZapperData.clear(hand);
            p.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
            if(!b.dock.deposit(hand,lease)){p.setItemInHand(InteractionHand.MAIN_HAND,hand);return notice(p,"changed");}
            b.changed();p.getInventory().setChanged();p.inventoryMenu.broadcastChanges();
            HomeInteractionSounds.play(p.serverLevel(),b.getBlockPos(),HomeInteractionSounds.Action.CONTROLLER_RETURN);
            return notice(p,"returned");
        }catch(RuntimeException failure){cn.piq.fcarcade.FcArcadeMod.LOGGER.warn("Zapper stand interaction failed closed",failure);return notice(p,"changed");}finally{BUSY.remove();}}
    public static InteractionResult cable(ServerPlayer p,UseOnContext ctx){if(BUSY.get()||!current(p))return InteractionResult.CONSUME;
        var held=p.getMainHandItem();if(held!=ctx.getItemInHand()||held.getCount()!=1||!(held.getItem() instanceof ZapperStandCableItem))return InteractionResult.PASS;
        var hit=new BlockHitResult(ctx.getClickLocation(),ctx.getClickedFace(),ctx.getClickedPos(),ctx.isInside());var l=p.serverLevel();
        if(!l.hasChunkAt(ctx.getClickedPos()))return InteractionResult.PASS;
        BUSY.set(true);try{
            if(l.getBlockEntity(ctx.getClickedPos()) instanceof ZapperStandBlockEntity b){
                if(!permission(p,b,hit)||p.getMainHandItem()!=held)return notice(p,"denied");
                if(p.isShiftKeyDown()){
                    var originalLink=b.link();var c=originalLink==null?null:console(p.getServer(),originalLink);
                    if(c!=null&&(!permission(p,c,hit)||!current(p,c)))return notice(p,"denied");
                    if(!current(p,b)||p.getMainHandItem()!=held||b.link()!=originalLink
                            ||(c!=null&&console(p.getServer(),originalLink)!=c))return notice(p,"changed");
                    disconnect(b);pending(p.getServer()).remove(p.getUUID());
                    if(originalLink!=null)HomeInteractionSounds.play(l,b.getBlockPos(),HomeInteractionSounds.Action.CABLE_DISCONNECT);
                    return notice(p,"disconnected");}
                if(pending(p.getServer()).size()>=128&&!pending(p.getServer()).containsKey(p.getUUID()))return notice(p,"busy");
                pending(p.getServer()).put(p.getUUID(),new Pending(p,p.connection.getConnection(),held,b,b.endpoint(),hit,p.getServer().getTickCount()+1200));return notice(p,"selected");}
            if(p.isShiftKeyDown()&&HomeHardware.loadedEndpoint(l,ctx.getClickedPos()) instanceof HomeConsoleBlockEntity target){
                cancelCableSelection(p);
                var d=data(p.getServer());var saved=d.links.at(end(target));
                if(saved==null)return notice(p,"not_connected");
                var b=stand(p.getServer(),saved.stand());if(b==null)return notice(p,"changed");
                var original=b.link();var consoleId=target.hardwareId();var clicked=l.getBlockEntity(ctx.getClickedPos());
                if(!saved.equals(original)||console(p.getServer(),saved)!=target||!permission(p,target,hit)
                        ||!HomeHardware.allowAnchorInteraction(p,ctx.getClickedPos(),target,InteractionHand.MAIN_HAND,hit)
                        ||!permission(p,b,hit))return notice(p,"denied");
                // Both real endpoints must still be the captured objects after the last protection callback.
                if(!current(p,target)||!current(p,b)||p.getMainHandItem()!=held||!target.hardwareId().equals(consoleId)
                        ||l.getBlockEntity(ctx.getClickedPos())!=clicked||b.link()!=original
                        ||d.links.at(end(target))!=saved||console(p.getServer(),saved)!=target)return notice(p,"changed");
                disconnect(b);HomeInteractionSounds.play(l,target.getBlockPos(),HomeInteractionSounds.Action.CABLE_DISCONNECT);
                return notice(p,"disconnected");
            }
            var first=pending(p.getServer()).remove(p.getUUID());if(first==null||!pendingCurrent(first))return notice(p,"select_first");
            if(!(HomeHardware.loadedEndpoint(l,ctx.getClickedPos()) instanceof HomeConsoleBlockEntity c))return notice(p,"fc_only");
            var b=first.stand();var oldLink=b.link();var originalClicked=l.getBlockEntity(ctx.getClickedPos());var consoleId=c.hardwareId();
            if(!ZapperStandLinks.compatible(first.end(),end(c))||!permission(p,b,first.hit())||!permission(p,c,hit)
                    ||!HomeHardware.allowAnchorInteraction(p,ctx.getClickedPos(),c,InteractionHand.MAIN_HAND,hit))return notice(p,"denied");
            if(!pendingCurrent(first)||!current(p,b)||!current(p,c)||!c.hardwareId().equals(consoleId)||l.getBlockEntity(ctx.getClickedPos())!=originalClicked||b.link()!=oldLink)return notice(p,"changed");
            if(HomeConsoleRuntime.running(l,c))return notice(p,"stop_first");
            var d=data(p.getServer());prune(p.getServer(),d);
            if(!pendingCurrent(first)||!current(p,c)||!c.hardwareId().equals(consoleId)||b.link()!=oldLink)return notice(p,"changed");
            var link=d.links.connect(b.endpoint(),end(c));if(link==null)return notice(p,"already_linked");
            try { b.link(link); }
            catch(RuntimeException failure) {d.links.remove(link);d.setDirty();b.link(null);throw failure;}
            // All permission callbacks and identity checks are complete. No await between debit and receipt.
            if(!pendingCurrent(first)||!current(p,c)||d.links.at(end(c))!=link||b.link()!=link||!d.paid.record(link.id()))
                {d.links.remove(link);d.setDirty();b.link(null);return notice(p,"changed");}
            held.shrink(1);d.setDirty();p.getInventory().setChanged();p.inventoryMenu.broadcastChanges();
            HomeInteractionSounds.play(l,c.getBlockPos(),HomeInteractionSounds.Action.CABLE_CONNECT);
            return notice(p,"connected");
        }catch(RuntimeException failure){cn.piq.fcarcade.FcArcadeMod.LOGGER.warn("Zapper cable interaction failed closed",failure);return notice(p,"changed");}finally{BUSY.remove();}}
    private static boolean pendingCurrent(Pending pending){var p=pending.player();return current(p)&&p.connection.getConnection()==pending.connection()&&p.getMainHandItem()==pending.cable()
            &&pending.cable().getCount()==1&&pending.expires()>p.getServer().getTickCount()&&current(p,pending.stand())&&pending.end().equals(pending.stand().endpoint());}
    private static void releaseBorrower(ZapperStandBlockEntity b,boolean erase){if(!(b.getLevel() instanceof ServerLevel l)||b.loan()==null)return;
        var player=l.getServer().getPlayerList().getPlayer(b.loan().player());if(player==null)return;
        for(var item:items(player))if(originMatches(item,b)){HomeZapperService.returnGun(player,item);if(erase)ZapperStandOrigin.clear(item);}player.getInventory().setChanged();player.inventoryMenu.broadcastChanges();}
    private static void disconnect(ZapperStandBlockEntity b){if(!(b.getLevel() instanceof ServerLevel l))return;releaseBorrower(b,false);
        var d=data(l.getServer());var link=d.links.at(b.endpoint());
        if(d.links.remove(link)){d.setDirty();refund(d,link,l,b.getBlockPos());}b.link(null);}
    private static void refund(Data d,ZapperStandLinks.Link link,ServerLevel level,BlockPos pos) {
        if(link!=null&&d.paid.claim(link.id())) {d.setDirty();net.minecraft.world.level.block.Block.popResource(level,pos,
            new ItemStack(cn.piq.fcarcade.registry.ModItems.ZAPPER_STAND_CABLE.get()));}
    }
    public static void removed(ZapperStandBlockEntity b){if(!(b.getLevel() instanceof ServerLevel l)||!b.beginRemoval())return;releaseBorrower(b,true);disconnect(b);
        ItemStack stored=b.dock.remove();if(stored!=null&&!stored.isEmpty())net.minecraft.world.level.block.Block.popResource(l,b.getBlockPos(),stored);b.changed();}
    public static void loaded(ZapperStandBlockEntity b){if(b.getLevel() instanceof ServerLevel l){var link=data(l.getServer()).links.at(b.endpoint());if(!Objects.equals(link,b.link()))b.link(link);}}
    public static void unloaded(ZapperStandBlockEntity b){releaseBorrower(b,false);/* Preserve SavedData link on ordinary unload. */}
    private static void prune(MinecraftServer server,Data d){for(var link:d.links.snapshot()){
        var a=level(server,link.stand());var c=level(server,link.console());boolean invalid=a!=null&&a.hasChunkAt(pos(link.stand()))&&stand(server,link.stand())==null
                ||c!=null&&c.hasChunkAt(pos(link.console()))&&console(server,link)==null;
        if(invalid){var b=stand(server,link.stand());if(b!=null){releaseBorrower(b,false);b.link(null);}
            if(d.links.remove(link)){d.setDirty();
                if(a!=null&&a.hasChunkAt(pos(link.stand())))refund(d,link,a,pos(link.stand()));
                else if(c!=null&&c.hasChunkAt(pos(link.console())))refund(d,link,c,pos(link.console()));
            }}}}
    private static void tick(ServerTickEvent.Post e){var map=PENDING.get(e.getServer());if(map!=null)map.entrySet().removeIf(v->!pendingCurrent(v.getValue()));if(e.getServer().getTickCount()%20==0)prune(e.getServer(),data(e.getServer()));}
    private static void stopped(ServerStoppedEvent e){PENDING.remove(e.getServer());}
    static final class Data extends SavedData {
        final ZapperStandLinks links=new ZapperStandLinks();
        final DataCablePayments paid=new DataCablePayments();
        static Data load(CompoundTag t,HolderLookup.Provider p){var d=new Data();var list=t.getList("Links",Tag.TAG_COMPOUND);for(int i=0;i<Math.min(512,list.size());i++)try{
            var row=list.getCompound(i);var link=readLink(row);if(d.links.restore(link)&&row.getBoolean("PaidCable"))d.paid.record(link.id());
        }catch(IllegalArgumentException ignored){}return d;}
        @Override public CompoundTag save(CompoundTag t,HolderLookup.Provider p){var list=new ListTag();for(var link:links.snapshot()){
            var row=writeLink(link);row.putBoolean("PaidCable",paid.paid(link.id()));list.add(row);
        }t.put("Links",list);return t;}
        static CompoundTag writeLink(ZapperStandLinks.Link link){var t=new CompoundTag();t.putUUID("Id",link.id());t.put("Stand",writeEnd(link.stand()));t.put("Console",writeEnd(link.console()));return t;}
        static ZapperStandLinks.Link readLink(CompoundTag t){if(!t.hasUUID("Id")||!t.contains("Stand",Tag.TAG_COMPOUND)||!t.contains("Console",Tag.TAG_COMPOUND))throw new IllegalArgumentException("Link fields");return new ZapperStandLinks.Link(t.getUUID("Id"),readEnd(t.getCompound("Stand")),readEnd(t.getCompound("Console")));}
        private static CompoundTag writeEnd(ZapperStandLinks.End e){var t=new CompoundTag();t.putString("Dim",e.dimension());t.putLong("Pos",pos(e).asLong());t.putUUID("Id",e.id());return t;}
        private static ZapperStandLinks.End readEnd(CompoundTag t){if(!t.hasUUID("Id")||!t.contains("Pos",Tag.TAG_LONG))throw new IllegalArgumentException("Endpoint fields");var p=BlockPos.of(t.getLong("Pos"));return new ZapperStandLinks.End(t.getString("Dim"),p.getX(),p.getY(),p.getZ(),t.getUUID("Id"));}
    }
}
