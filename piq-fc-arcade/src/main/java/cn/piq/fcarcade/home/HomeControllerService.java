package cn.piq.fcarcade.home;

import cn.piq.fcarcade.registry.ModItems;
import cn.piq.fcarcade.server.ServerArcadeSessions;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Physical tokens are receipts, never authority on their own. Server-thread only. */
public final class HomeControllerService {
    private static final Map<MinecraftServer, State> STATES = new WeakHashMap<>();
    private static final int TRANSFER_TICKS = 1_200;
    private static final int MAX_LOCAL_MENU_SLOTS = 4_096;
    private static final class State {
        final HomeControllerLedger ledger = new HomeControllerLedger();
        final Map<UUID, ItemStack> originals = new HashMap<>();
        final java.util.Set<UUID> ambiguousNotices = new HashSet<>();
    }
    private HomeControllerService() {}
    public static void register() {
        NeoForge.EVENT_BUS.addListener(HomeControllerService::tossed);
        NeoForge.EVENT_BUS.addListener(HomeControllerService::pickedUp);
        NeoForge.EVENT_BUS.addListener(HomeControllerService::containerOpened);
        NeoForge.EVENT_BUS.addListener(HomeControllerService::containerClosed);
    }
    private static State state(MinecraftServer server) { return STATES.computeIfAbsent(server, ignored -> new State()); }
    private static HomeControllerLedger.Console identity(HomeConsoleBlockEntity console) {
        var pos = console.getBlockPos();
        return new HomeControllerLedger.Console(console.hardwareId(), console.getLevel().dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ());
    }
    private static HomeConsoleBlockEntity console(MinecraftServer server, HomeControllerLedger.Console owner) {
        for (ServerLevel level : server.getAllLevels()) {
            if (!level.dimension().location().toString().equals(owner.dimension())) continue;
            var pos = new BlockPos(owner.x(), owner.y(), owner.z());
            if (level.hasChunkAt(pos) && level.getBlockEntity(pos) instanceof HomeConsoleBlockEntity found
                    && !found.isRemoved() && owner.id().equals(found.hardwareId())) return found;
        }
        return null;
    }
    private static InteractionHand freeHand(ServerPlayer player) {
        if (player.getMainHandItem().isEmpty()) return InteractionHand.MAIN_HAND;
        return player.getOffhandItem().isEmpty() ? InteractionHand.OFF_HAND : null;
    }
    private static boolean holds(ServerPlayer player, ItemStack original) {
        return original != null && !original.isEmpty() && original.getCount() == 1
                && (player.getMainHandItem() == original || player.getOffhandItem() == original);
    }
    private static HomeControllerInventory.Selection<ItemStack> locate(ServerPlayer player, UUID id) {
        return HomeControllerInventory.locate(id, personalItems(player), HomeControllerData::leaseId, ItemStack::getCount);
    }
    private static ArrayList<ItemStack> personalItems(ServerPlayer player) {
        var slots = new ArrayList<ItemStack>(player.getInventory().getContainerSize() + 8);
        for (int i = 0; i < player.getInventory().getContainerSize(); i++)
            slots.add(player.getInventory().getItem(i));
        // The player's own 2x2 crafting grid is also personal temporary storage.
        for (var slot : player.inventoryMenu.slots) slots.add(slot.getItem());
        // Picking an item up with the inventory cursor is not giving it away.
        slots.add(player.containerMenu.getCarried());
        return slots;
    }
    private static ArrayList<ItemStack> localItems(ServerPlayer player, AbstractContainerMenu menu) {
        var slots = personalItems(player);
        for (int i = 0; i < Math.min(menu.slots.size(), MAX_LOCAL_MENU_SLOTS); i++) slots.add(menu.slots.get(i).getItem());
        slots.add(menu.getCarried());
        return slots;
    }
    private static void changed(ServerPlayer player, AbstractContainerMenu menu) {
        player.getInventory().setChanged();
        for (int i = 0; i < Math.min(menu.slots.size(), MAX_LOCAL_MENU_SLOTS); i++) menu.slots.get(i).setChanged();
        menu.broadcastChanges();
    }
    private static void recycleLocalLease(ServerPlayer player, AbstractContainerMenu menu, UUID id) {
        int removed = HomeControllerInventory.recycle(localItems(player, menu),
                stack -> HomeControllerData.isBorrowed(stack) && id.equals(HomeControllerData.leaseId(stack)),
                ItemStack::getCount, HomeControllerData::recycle);
        if (removed > 0) changed(player, menu);
    }
    private static boolean hasExternalLoan(ServerPlayer player, AbstractContainerMenu menu, UUID id) {
        if (menu == player.inventoryMenu) return false;
        for (int i = 0; i < Math.min(menu.slots.size(), MAX_LOCAL_MENU_SLOTS); i++) {
            var slot = menu.slots.get(i);
            if (slot.container != player.getInventory() && id.equals(HomeControllerData.leaseId(slot.getItem()))) return true;
        }
        return false;
    }
    private static void containerOpened(PlayerContainerEvent.Open event) {
        if (event.getEntity() instanceof ServerPlayer player) cleanMenu(player, event.getContainer());
    }
    private static void containerClosed(PlayerContainerEvent.Close event) {
        // NeoForge posts Close before swapping player.containerMenu, after
        // vanilla has put the cursor back; the original container is still available.
        if (event.getEntity() instanceof ServerPlayer player) cleanMenu(player, event.getContainer());
    }
    private static void cleanMenu(ServerPlayer player, AbstractContainerMenu menu) {
        var state = STATES.get(player.getServer());
        var owned = state == null ? null : state.ledger.player(player.getUUID());
        if (owned != null && hasExternalLoan(player, menu, owned.id())) {
            release(player.getServer(), state, owned);
            recycleLocalLease(player, menu, owned.id());
        }
        int removed = 0;
        for (int i = 0; i < Math.min(menu.slots.size(), MAX_LOCAL_MENU_SLOTS); i++) {
            var slot = menu.slots.get(i); var stack = slot.getItem();
            if (!HomeControllerData.isBorrowed(stack)) continue;
            var lease = state == null ? null : state.ledger.get(HomeControllerData.leaseId(stack));
            boolean personal = menu == player.inventoryMenu || slot.container == player.getInventory();
            if (!personal || !HomeControllerInventory.keepForPlayer(lease, player.getUUID(), player.getServer().getTickCount())) {
                removed += stack.getCount(); HomeControllerData.recycle(stack); slot.setChanged();
            }
        }
        if (removed > 0) changed(player, menu);
    }
    /** Runs only on this item in an online player's inventory, including old alpha loans. */
    public static void inventoryTick(ServerPlayer player, ItemStack stack) {
        if (!HomeControllerData.isBorrowed(stack)) return;
        var state = STATES.get(player.getServer());
        var lease = state == null ? null : state.ledger.get(HomeControllerData.leaseId(stack));
        if (!HomeControllerInventory.keepForPlayer(lease, player.getUUID(), player.getServer().getTickCount())) {
            HomeControllerData.recycle(stack); player.getInventory().setChanged();
        }
    }
    /** ItemEntity calls this before its vanilla tick; no entity/world enumeration. */
    public static boolean updateDropped(ItemEntity entity) {
        if (!(entity.level() instanceof ServerLevel level) || !HomeControllerData.isBorrowed(entity.getItem())) return false;
        var state = STATES.get(level.getServer()); var stack = entity.getItem();
        var lease = state == null ? null : state.ledger.get(HomeControllerData.leaseId(stack));
        if (lease != null && lease.console().dimension().equals(level.dimension().location().toString())
                && HomeControllerInventory.keepDropped(lease, entity.getUUID(), stack.getCount(), level.getServer().getTickCount())) {
            state.originals.put(lease.id(), stack);
            return false;
        }
        if (lease != null && entity.getUUID().equals(lease.carrier())) revoke(level.getServer(), state, lease);
        HomeControllerData.recycle(stack); entity.discard();
        return true;
    }
    private static ItemStack ownedItem(State state, HomeControllerLedger.Lease lease, ServerPlayer player) {
        if (!player.getUUID().equals(lease.player())) return null;
        var found = locate(player, lease.id());
        if (found.status() != HomeControllerInventory.Status.UNIQUE) return null;
        state.originals.put(lease.id(), found.value());
        return found.value();
    }
    private static void message(ServerPlayer player, String key, Object... arguments) {
        HomeFeedback.show(player, "controller_" + key, arguments);
    }
    private static boolean usable(ServerPlayer player, HomeConsoleBlockEntity console) {
        if (!physicalUsable(player,console)
                || console.tvPos() == null
                || !player.serverLevel().mayInteract(player, console.tvPos())) return false;
        var tv = console.tvPos(); var pos = console.getBlockPos();
        return HomeRuntimeAuthority.controllerInRange(player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5))
                && HomeHardware.connectedConsole(player.serverLevel(), tv) == console
                && HomeHardware.validPlayback(player.serverLevel(), tv);
    }
    private static boolean physicalUsable(ServerPlayer player,HomeConsoleBlockEntity console){
        return player.getServer()!=null&&player.getServer().isSameThread()&&player.isAlive()&&!player.isSpectator()&&!player.hasDisconnected()
                &&player.connection.getConnection().isConnected()&&console!=null&&!console.isRemoved()&&console.getLevel()==player.serverLevel()
                &&player.serverLevel().hasChunkAt(console.getBlockPos())&&player.serverLevel().getBlockEntity(console.getBlockPos())==console
                &&player.serverLevel().mayInteract(player,console.getBlockPos())
                &&HomeRuntimeAuthority.controllerInRange(player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(console.getBlockPos())));
    }
    public static void borrowIdle(ServerPlayer player,HomeConsoleBlockEntity console,int port){
        if(!physicalUsable(player,console)||port<0||port>1)return;
        var state=state(player.getServer());var owned=state.ledger.player(player.getUUID());
        if(owned!=null){if(owned.console().equals(identity(console))&&owned.port()==port)reclaim(player,owned.session());else message(player,"already_owned");return;}
        var hand=freeHand(player);if(hand==null){message(player,"hand_full");return;}
        var lease=state.ledger.borrow(identity(console),port,player.getUUID());if(lease==null){message(player,"reserved");return;}
        ItemStack item=new ItemStack(ModItems.FC_CONTROLLER.get());var style=console.getBlockState().getBlock() instanceof SuborConsoleBlock?HomeControllerData.Style.SUBOR:HomeControllerData.Style.FAMICOM;
        HomeControllerData.bind(item,lease,style);state.originals.put(lease.id(),item);player.setItemInHand(hand,item);
        console.controllerTaken(port,true);console.controllerVisual(port,player.getUUID(),lease.id());player.getInventory().setChanged();
        HomeInteractionSounds.play(player.serverLevel(),console.getBlockPos(),HomeInteractionSounds.Action.CONTROLLER_TAKE);
        player.displayClientMessage(net.minecraft.network.chat.Component.literal("已取下 P"+(port+1)+" 手柄；按电源才会开机。"),true);
    }
    public static void attachHeld(ServerPlayer player,HomeConsoleBlockEntity console){
        var s=STATES.get(player.getServer());var loan=s==null?null:s.ledger.player(player.getUUID());
        if(loan!=null&&loan.phase()==HomeControllerLedger.Phase.IDLE&&loan.console().equals(identity(console))&&holds(player,ownedItem(s,loan,player)))
            ServerArcadeSessions.takeHomeController(player,console,loan.port());
    }
    /** Read-only admission check, repeated after P1 approval and before mutating the roster. */
    public static boolean canJoin(ServerPlayer player, BlockPos tv, long session, int port) {
        var console = HomeHardware.connectedConsole(player.serverLevel(), tv);
        if (!usable(player, console)) { message(player, "unavailable"); return false; }
        var state = state(player.getServer()); var owned = state.ledger.player(player.getUUID());
        if (owned != null) {
            boolean pending = (owned.phase() == HomeControllerLedger.Phase.IDLE || owned.phase() == HomeControllerLedger.Phase.AWAITING_APPROVAL && owned.session() == session)
                    && owned.port() == port && owned.console().equals(identity(console))
                    && holds(player, ownedItem(state, owned, player));
            if (!pending) message(player, "already_owned");
            return pending;
        }
        if (state.ledger.port(session, port) != null || state.ledger.socket(identity(console),port)!=null) { message(player, "reserved"); return false; }
        if (freeHand(player) == null) { message(player, "hand_full"); return false; }
        return true;
    }
    /** No await/event between this physical transaction and the caller's successful roster commit. */
    public static boolean grant(ServerPlayer player, BlockPos tv, long session, int port) {
        if (!canJoin(player, tv, session, port)) return false;
        var state = state(player.getServer()); var console = HomeHardware.connectedConsole(player.serverLevel(), tv);
        var owned = state.ledger.player(player.getUUID());
        boolean newlyBorrowed = owned == null;
        if (owned != null) {
            if (!state.ledger.activate(owned.id(), player.getUUID(), identity(console), session, port)) return false;
            owned=state.ledger.get(owned.id());HomeControllerData.bind(ownedItem(state,owned,player),owned);
        } else {
            InteractionHand hand = freeHand(player);
            if (hand == null) return false;
            owned = state.ledger.issue(identity(console), session, port, player.getUUID());
            if (owned == null) return false;
            ItemStack stack = new ItemStack(ModItems.FC_CONTROLLER.get());
            var style = console.getBlockState().getBlock() instanceof SuborConsoleBlock
                    ? HomeControllerData.Style.SUBOR : HomeControllerData.Style.FAMICOM;
            HomeControllerData.bind(stack, owned, style); state.originals.put(owned.id(), stack); player.setItemInHand(hand, stack);
        }
        console.controllerTaken(port, true);
        console.controllerVisual(port,player.getUUID(),owned.id());
        player.getInventory().setChanged();
        if (newlyBorrowed) HomeInteractionSounds.play(player.serverLevel(),console.getBlockPos(),HomeInteractionSounds.Action.CONTROLLER_TAKE);
        message(player, "taken", port + 1);
        return true;
    }
    /** Repeat empty click only retrieves the exact existing item, never issues P2 or a duplicate. */
    public static void reclaim(ServerPlayer player, long session) {
        var state = state(player.getServer()); var lease = state.ledger.player(player.getUUID());
        if (lease == null || lease.session() != session) { message(player, "invalid"); return; }
        ItemStack original = ownedItem(state, lease, player);
        if (holds(player, original)) { message(player, "already_owned"); return; }
        InteractionHand hand = freeHand(player);
        if (hand == null || original == null || player.containerMenu.getCarried() == original) { message(player, "hand_full"); return; }
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (player.getInventory().getItem(slot) != original) continue;
            player.getInventory().setItem(slot, ItemStack.EMPTY); player.setItemInHand(hand, original);
            player.getInventory().setChanged(); return;
        }
    }
    public static boolean authorized(ServerPlayer player, BlockPos tv, long session, int port) {
        var state = STATES.get(player.getServer());
        if (state == null) return false;
        var lease = state.ledger.player(player.getUUID());
        var console = HomeHardware.connectedConsole(player.serverLevel(), tv);
        return lease != null && usable(player, console) && holds(player, ownedItem(state, lease, player))
                && state.ledger.authorized(lease.id(), player.getUUID(), identity(console), session, port);
    }
    /** Keeping one's unique controller in personal storage pauses input, not reception.
     * Caller separately proves the runtime's exact Connection and physical lease. */
    public static boolean mediaAuthorized(ServerPlayer player,BlockPos tv,long session,int port,UUID expectedLease){
        if(player==null||player.getServer()==null||!player.getServer().isSameThread()
                ||player.getServer().getPlayerList().getPlayer(player.getUUID())!=player||expectedLease==null)return false;
        var state=STATES.get(player.getServer());if(state==null)return false;
        var lease=state.ledger.player(player.getUUID());
        var console=HomeHardware.connectedConsole(player.serverLevel(),tv);
        return lease!=null&&expectedLease.equals(lease.id())&&usable(player,console)
                &&state.ledger.authorized(lease.id(),player.getUUID(),identity(console),session,port)
                &&!hasExternalLoan(player,player.containerMenu,lease.id())
                &&locate(player,lease.id()).status()==HomeControllerInventory.Status.UNIQUE;
    }
    public static UUID leaseId(ServerPlayer player,long session,int port){
        var s=STATES.get(player.getServer());var lease=s==null?null:s.ledger.player(player.getUUID());
        return lease!=null&&lease.session()==session&&lease.port()==port&&lease.phase()==HomeControllerLedger.Phase.ACTIVE?lease.id():null;
    }
    public static void activate(ServerPlayer player, InteractionHand hand) {
        var state = state(player.getServer()); var stack = player.getItemInHand(hand);
        var lease = state.ledger.get(HomeControllerData.leaseId(stack));
        if (lease == null || ownedItem(state, lease, player) != stack) {
            HomeControllerData.recycle(stack); message(player, "invalid"); return;
        }
        var console = console(player.getServer(), lease.console());
        if (!physicalUsable(player, console)) { message(player, "unavailable"); return; }
        if(lease.phase()==HomeControllerLedger.Phase.IDLE){player.displayClientMessage(net.minecraft.network.chat.Component.literal("手柄已取下；按主机电源开机，开机后点击主机手柄申请控制。"),true);return;}
        if (lease.phase() == HomeControllerLedger.Phase.AWAITING_APPROVAL)
            message(player, "p2_approval"); // Click the bound hardware, so standard block protection is consulted.
        else message(player, "already_owned");
    }
    /** Called only after the appliance's ray, protection and exact endpoint checks.
     * Holding a receipt in either hand returns that same socket; other clicks retain
     * borrowing/reclaiming/admission behavior and automatic activation is untouched.
     */
    public static boolean returnHeldAtDock(ServerPlayer player, HomeConsoleBlockEntity target, int port, InteractionHand hand) {
        var state = STATES.get(player.getServer());
        if (state == null || target == null || !physicalUsable(player,target)) return false;
        for (var candidate : new InteractionHand[]{hand,
                hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND}) {
            var stack = player.getItemInHand(candidate);
            var lease = state.ledger.get(HomeControllerData.leaseId(stack));
            if (lease == null || lease.port() != port || !lease.console().equals(identity(target))
                    || ownedItem(state,lease,player) != stack || !holds(player,stack)) continue;
            release(player.getServer(),state,lease,true);
            return true;
        }
        return false;
    }
    public static InteractionResult useOn(ServerPlayer player, UseOnContext context) {
        var level = player.serverLevel(); var clicked = context.getClickedPos();
        var button=HomeApplianceService.tryButton(player,clicked,context.getHand(),new net.minecraft.world.phys.BlockHitResult(context.getClickLocation(),context.getClickedFace(),clicked,context.isInside()));
        if(button!=InteractionResult.PASS)return button;
        if (!HomeHardware.mayUse(player, clicked)) return InteractionResult.FAIL;
        var endpoint = HomeHardware.loadedEndpoint(level, clicked);
        if (endpoint == null) return InteractionResult.PASS;
        var endpointPos = endpoint.getBlockPos();
        if(endpoint instanceof HomeTvBlockEntity){player.displayClientMessage(net.minecraft.network.chat.Component.literal("请到主机归还手柄。"),true);return InteractionResult.CONSUME;}
        var hit = new net.minecraft.world.phys.BlockHitResult(context.getClickLocation(), context.getClickedFace(), clicked, context.isInside());
        if (!HomeHardware.allowAnchorInteraction(player, clicked, endpoint, context.getHand(), hit)) return InteractionResult.FAIL;
        HomeConsoleBlockEntity target = endpoint instanceof HomeConsoleBlockEntity c ? c : HomeHardware.connectedConsole(level, endpointPos);
        var state = state(player.getServer()); var stack = player.getItemInHand(context.getHand());
        var lease = state.ledger.get(HomeControllerData.leaseId(stack));
        if (lease == null || ownedItem(state, lease, player) != stack) {
            // Explicitly returning a stale souvenir recycles it, never grants a seat.
            if (target != null && level.mayInteract(player, target.getBlockPos())) HomeControllerData.recycle(stack);
            message(player, "invalid"); return InteractionResult.CONSUME;
        }
        if (target == null || !lease.console().equals(identity(target)) || !physicalUsable(player, target)) {
            message(player, "wrong_console"); return InteractionResult.CONSUME;
        }
        {
            HomeControllerData.recycle(stack);
            release(player.getServer(), state, lease, true);
        }
        return InteractionResult.CONSUME;
    }
    private static void tossed(ItemTossEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player) || event.isCanceled()) return;
        ItemStack dropped = event.getEntity().getItem();
        if (!HomeControllerData.isBorrowed(dropped)) return;
        var state = STATES.get(player.getServer());
        var lease = state == null ? null : state.ledger.get(HomeControllerData.leaseId(dropped));
        // The server-observed toss must remove the owner's sole token, including
        // their cursor. Throwing a clone while keeping a copy grants no receipt.
        boolean removedSoleToken = lease != null && dropped.getCount() == 1 && HomeControllerInventory.removedForToss(
                locate(player, lease.id()), player.containerMenu.getCarried(), dropped);
        var action = HomeControllerInventory.tossAction(lease, player.getUUID(), player.isAlive() && !player.hasDisconnected(),
                removedSoleToken, player.getServer().getTickCount());
        if (action == HomeControllerInventory.TossAction.RECYCLE_COPY) { recycleToss(event); return; }
        if (action == HomeControllerInventory.TossAction.RETURN_LOAN) {
            try { release(player.getServer(), state, lease, true); }
            finally { recycleToss(event); }
        }
    }
    private static void recycleToss(ItemTossEvent event) {
        // CommonHooks does not restore a canceled toss. GUI outside-click clears
        // its cursor after this callback; Q/split already removed the source.
        HomeControllerData.recycle(event.getEntity().getItem());
        event.getEntity().discard(); event.setCanceled(true);
    }
    private static void pickedUp(ItemEntityPickupEvent.Post event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        var state = STATES.get(player.getServer()); if (state == null) return;
        UUID oldId = HomeControllerData.leaseId(event.getOriginalStack()); var old = state.ledger.get(oldId);
        if (old == null || old.phase() != HomeControllerLedger.Phase.IN_TRANSIT
                || !event.getItemEntity().getUUID().equals(old.carrier()) || !event.getCurrentStack().isEmpty()) return;
        if (!old.console().dimension().equals(player.serverLevel().dimension().location().toString())
                || !HomeControllerInventory.keepDropped(old, event.getItemEntity().getUUID(),
                event.getOriginalStack().getCount(), player.getServer().getTickCount())) {
            revoke(player.getServer(), state, old); recycleLocalLease(player, player.containerMenu, oldId); return;
        }
        var found = locate(player, oldId);
        if (found.status() != HomeControllerInventory.Status.UNIQUE) {
            // A pre-existing copied token makes the actual inserted stack
            // ambiguous. Do not rotate authority onto an arbitrary first slot.
            revoke(player.getServer(), state, old); recycleLocalLease(player, player.containerMenu, oldId);
            message(player, "duplicate"); return;
        }
        ItemStack received = found.value();
        var origin=console(player.getServer(),old.console());if(!physicalUsable(player,origin)){revoke(player.getServer(),state,old);recycleLocalLease(player,player.containerMenu,oldId);return;}
        var next = state.ledger.pickup(oldId, old.carrier(), player.getUUID(), player.getServer().getTickCount() + TRANSFER_TICKS);
        if (next == null) { HomeControllerData.recycle(received); revoke(player.getServer(), state, old); return; }
        state.originals.remove(oldId); state.originals.put(next.id(), received); HomeControllerData.bind(received, next);
        origin.controllerVisual(next.port(),player.getUUID(),next.id());
        player.getInventory().setChanged(); message(player, "p2_approval");
    }
    /** Called before lockstep advances. No world/entity or offline inventory scan. */
    public static void tick(MinecraftServer server) {
        var state = STATES.get(server); if (state == null) return;
        for (var lease : state.ledger.snapshots()) {
            if (state.ledger.get(lease.id()) == null) continue;
            var console = console(server, lease.console());
            if (console == null) {
                release(server, state, lease); continue;
            }
            if (lease.phase() == HomeControllerLedger.Phase.IN_TRANSIT) {
                var entity = ((ServerLevel) console.getLevel()).getEntity(lease.carrier());
                if (server.getTickCount() >= lease.deadline()) revoke(server, state, lease);
                else if (entity instanceof ItemEntity item) {
                    if (!HomeRuntimeAuthority.controllerInRange(item.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(console.getBlockPos())))
                            ||!lease.id().equals(HomeControllerData.leaseId(item.getItem())) || item.getItem().getCount() != 1)
                        revoke(server, state, lease);
                    else state.originals.put(lease.id(), item.getItem());
                }
                // A temporarily unloaded carrier keeps its one dock reservation
                // until expiry; no chunk is forced and a reload is checked by UUID.
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(lease.player());
            if (player != null && hasExternalLoan(player, player.containerMenu, lease.id())) {
                release(server, state, lease); recycleLocalLease(player, player.containerMenu, lease.id()); continue;
            }
            var located = player == null ? null : locate(player, lease.id());
            if (player == null || !physicalUsable(player, console) || located.status() == HomeControllerInventory.Status.MISSING
                    || (lease.phase() == HomeControllerLedger.Phase.AWAITING_APPROVAL && server.getTickCount() >= lease.deadline()))
                release(server, state, lease);
            else {
                if (located.status() == HomeControllerInventory.Status.UNIQUE) {
                    state.originals.put(lease.id(), located.value()); state.ambiguousNotices.remove(lease.id());
                } else if (state.ambiguousNotices.add(lease.id())) message(player, "duplicate");
                if (lease.phase() == HomeControllerLedger.Phase.ACTIVE && !holds(player, located.value()))
                    ServerArcadeSessions.pauseHomeController(player, lease.session(), lease.port());
            }
            // Putting one's original controller in another personal slot pauses
            // input, not the whole session; only actual ownership loss releases it.
        }
    }
    private static void release(MinecraftServer server, State state, HomeControllerLedger.Lease lease) {
        release(server, state, lease, false);
    }
    private static void release(MinecraftServer server, State state, HomeControllerLedger.Lease lease, boolean explicitReturn) {
        if (state.ledger.get(lease.id()) != lease) return;
        var returnedConsole = explicitReturn ? console(server,lease.console()) : null;
        releaseLoan(server,state,lease,explicitReturn);
        if (returnedConsole != null && state.ledger.get(lease.id()) == null)
            HomeInteractionSounds.play((ServerLevel)returnedConsole.getLevel(),returnedConsole.getBlockPos(),HomeInteractionSounds.Action.CONTROLLER_RETURN);
    }
    private static void releaseLoan(MinecraftServer server, State state, HomeControllerLedger.Lease lease, boolean explicitReturn) {
        ServerPlayer player = lease.player() == null ? null : server.getPlayerList().getPlayer(lease.player());
        if(lease.session()==0){revoke(server,state,lease);if(player!=null)player.displayClientMessage(net.minecraft.network.chat.Component.literal("P"+(lease.port()+1)+" 手柄已归还。"),true);return;}
        if(ServerArcadeSessions.isPoweredHomeSession(server,lease.session())){
            if(player!=null)ServerArcadeSessions.releaseHomeController(player,lease.session());
            revoke(server,state,lease);
            if(player!=null)player.displayClientMessage(net.minecraft.network.chat.Component.literal("P"+(lease.port()+1)+" 手柄已归还；主机继续运行。"),true);
            return;
        }
        if (lease.port() == 0) {
            // A repaired/relinked console must never stop a newer session at its
            // current TV coordinates. The lease's exact generation owns cleanup.
            ServerArcadeSessions.stopHomeControllerSession(server, lease.session());
            closeSession(server, lease.session());
            if (player != null) message(player, explicitReturn ? "p1_returned" : "p1_stopped");
        } else {
            if (player != null) ServerArcadeSessions.releaseHomeController(player, lease.session());
            revoke(server, state, lease);
            if (player != null) message(player, explicitReturn ? "p2_returned" : "p2_stopped");
        }
    }
    private static void revoke(MinecraftServer server, State state, HomeControllerLedger.Lease lease) {
        if (state.ledger.revoke(lease.id()) == null) return;
        invalidateRemoved(server, state, lease);
    }
    private static void invalidateRemoved(MinecraftServer server, State state, HomeControllerLedger.Lease lease) {
        state.ambiguousNotices.remove(lease.id());
        ServerPlayer player = lease.player() == null ? null : server.getPlayerList().getPlayer(lease.player());
        if (player != null) recycleLocalLease(player, player.containerMenu, lease.id());
        ItemStack stack = state.originals.remove(lease.id());
        if (lease.carrier() != null) for (ServerLevel level : server.getAllLevels()) {
            if (!level.dimension().location().toString().equals(lease.console().dimension())) continue;
            var entity = level.getEntity(lease.carrier());
            if (entity instanceof ItemEntity item && lease.id().equals(HomeControllerData.leaseId(item.getItem()))) {
                HomeControllerData.recycle(item.getItem()); item.discard();
            }
        }
        if (stack != null) HomeControllerData.recycle(stack);
        var console = console(server, lease.console()); if (console != null) console.controllerTaken(lease.port(), false);
    }
    public static void memberLeft(ServerPlayer player, long session) {
        memberLeft(player.getServer(),player.getUUID(),session);
    }
    public static void memberLeft(MinecraftServer server,UUID player,long session) {
        var state = STATES.get(server); if (state == null) return;
        var lease = state.ledger.player(player);
        if (lease != null && lease.session() == session && lease.phase()!=HomeControllerLedger.Phase.IN_TRANSIT) revoke(server,state,lease);
    }
    public static void closeSession(MinecraftServer server, long session) {
        var state = STATES.get(server); if (state == null) return;
        for (var lease : state.ledger.revokeSession(session)) invalidateRemoved(server, state, lease);
    }
    /** Power loss removes authorization, not the physical object in the player's hands. */
    public static void deactivateSession(MinecraftServer server,long session){
        var state=STATES.get(server);if(state==null)return;
        for(var old:state.ledger.snapshots())if(old.session()==session){
            var c=console(server,old.console());var p=old.player()==null?null:server.getPlayerList().getPlayer(old.player());
            if(p==null||!physicalUsable(p,c)){revoke(server,state,old);continue;}
            var item=ownedItem(state,old,p);if(item==null){revoke(server,state,old);continue;}
            var next=state.ledger.idle(old.id());if(next!=null){HomeControllerData.bind(item,next);p.getInventory().setChanged();}
        }
    }
    public static void stopped(MinecraftServer server) {
        var state = STATES.get(server);
        if (state != null) for (var lease : state.ledger.snapshots()) revoke(server, state, lease);
        STATES.remove(server);
    }
}
