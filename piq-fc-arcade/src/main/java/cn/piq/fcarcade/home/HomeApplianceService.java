package cn.piq.fcarcade.home;

import cn.piq.fcarcade.FcArcadeMod;
import cn.piq.fcarcade.layout.ScreenSurfaceGeometry;
import cn.piq.fcarcade.world.FcArcadeBlock;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Physical actions only. Providers own emulator/host/controller state. */
public final class HomeApplianceService {
    private static final Map<ResourceLocation,BiFunction<Vec3,Vec3,HomeApplianceControl>> CONTROLS = new ConcurrentHashMap<>();
    private static final Map<ServerPlayer,Long> LAST_BUTTON = new WeakHashMap<>();
    private static final Map<HomeEndpointBlockEntity,EmptyConsolePower> EMPTY_POWER = new WeakHashMap<>();
    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> false);
    private HomeApplianceService() {}
    /** Preserve cartridge/cable/controller item transactions; other held items may press buttons. */
    public static net.minecraft.world.ItemInteractionResult tryItemButton(net.minecraft.world.item.ItemStack stack,
            Level level, BlockPos pos, net.minecraft.world.entity.player.Player player, InteractionHand hand, BlockHitResult hit) {
        var item = stack.getItem();
        if (item instanceof AvCableItem || item instanceof ZapperStandCableItem || item instanceof FcCartridgeItem
                || item instanceof FcControllerItem || item instanceof HomeZapperItem
                || item instanceof cn.piq.fcarcade.cabinet.CabinetLinkCableItem)
            return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (player instanceof ServerPlayer server) {
            var result = tryButton(server,pos,hand,hit);
            if (result == InteractionResult.FAIL) return net.minecraft.world.ItemInteractionResult.FAIL;
            if (result != InteractionResult.PASS) return net.minecraft.world.ItemInteractionResult.CONSUME;
        } else {
            Vec3 eye = player.getEyePosition();
            if (controlAt(level,pos,eye,eye.add(player.getLookAngle().scale(Math.min(6,player.blockInteractionRange())))) != HomeApplianceControl.NONE)
                return net.minecraft.world.ItemInteractionResult.SUCCESS;
        }
        return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }
    public static void registerControls(ResourceLocation system, BiFunction<Vec3,Vec3,HomeApplianceControl> picker) {
        if (CONTROLS.putIfAbsent(java.util.Objects.requireNonNull(system),java.util.Objects.requireNonNull(picker)) != null)
            throw new IllegalArgumentException("Duplicate appliance controls: " + system);
    }
    public static float audioGain(Level level, BlockPos screen) {
        if (level == null || screen == null || !level.hasChunkAt(screen)) return 0;
        return level.getBlockEntity(screen) instanceof HomeTvBlockEntity tv ? tv.audioGain() : 1;
    }
    public static boolean videoAllowed(Level level, BlockPos screen) {
        return level != null && screen != null && level.hasChunkAt(screen)
                && (!(level.getBlockEntity(screen) instanceof HomeTvBlockEntity tv) || tv.powered());
    }
    public static void refresh(ServerLevel level, BlockPos screen) {
        if (level == null || screen == null || !level.getServer().isSameThread() || !level.hasChunkAt(screen)
                || !(level.getBlockEntity(screen) instanceof HomeTvBlockEntity tv)) return;
        var console = HomeHardware.connectedEndpoint(level,screen);
        boolean active = running(level,console);
        tv.signal(active);
        boolean empty = emptyPowered(level,console);
        tv.emptyConsole(empty);
        tv.syncLight();
        if (console != null) console.visualPowered(active || empty);
    }
    static void refreshConsole(ServerLevel level, HomeEndpointBlockEntity console) {
        console.visualPowered(running(level,console) || emptyPowered(level,console));
    }
    private static boolean hasCartridge(HomeEndpointBlockEntity console) {
        return console instanceof HomeConsoleBlockEntity fc ? fc.hasCartridge()
                : !(console instanceof ExternalHomeConsoleBlockEntity external) || external.hasInsertedCartridge();
    }
    private static boolean emptyPowered(ServerLevel level, HomeEndpointBlockEntity console) {
        if (console == null) return false;
        var idle = EMPTY_POWER.get(console);
        if (idle == null) return false;
        var endpoint = HomeHardware.loadedEndpoint(level,console.peerPos());
        var tv = endpoint instanceof HomeTvBlockEntity t ? t : null;
        boolean linked = tv != null && console.getLevel() == level && !console.isRemoved()
                && HomeHardware.loadedEndpoint(level,console.getBlockPos()) == console
                && HomeHardware.connectedEndpoint(level,tv.getBlockPos()) == console;
        if (!idle.reconcile(hasCartridge(console),linked,tv != null && tv.powered())) {
            EMPTY_POWER.remove(console);
            return false;
        }
        return true;
    }
    /** Addons call after committing media changes; never starts a game or alters the cartridge. */
    public static void cartridgeChanged(ServerLevel level, BlockPos consolePos) {
        if (level == null || consolePos == null || !level.getServer().isSameThread()) return;
        var console = HomeHardware.loadedEndpoint(level,consolePos);
        if (console != null && hasCartridge(console) && EMPTY_POWER.remove(console) != null) {
            refreshConsole(level,console);
            if (console.peerPos() != null) refresh(level,console.peerPos());
        }
    }
    /** Removal, AV disconnect and chunk unload cancel only matching transient empty hardware. */
    static void clearEmptyPower(HomeEndpointBlockEntity endpoint) {
        if (endpoint == null || !(endpoint.getLevel() instanceof ServerLevel level)) return;
        if (endpoint instanceof HomeTvBlockEntity tv) {
            var console = HomeHardware.loadedEndpoint(level,tv.consolePos());
            // During chunk unload the structure may already be incomplete. Captured identities,
            // not a new complete-structure query, authorize clearing (never creating) this state.
            if (paired(console,tv) && EMPTY_POWER.remove(console) != null) console.visualPowered(false);
            tv.emptyConsole(false);
        } else if (EMPTY_POWER.remove(endpoint) != null) {
            endpoint.visualPowered(false);
            if (HomeHardware.loadedEndpoint(level,endpoint.peerPos()) instanceof HomeTvBlockEntity tv
                    && paired(endpoint,tv)) tv.emptyConsole(false);
        }
    }
    private static boolean paired(HomeEndpointBlockEntity console, HomeTvBlockEntity tv) {
        return console != null && console.linkId() != null && console.linkId().equals(tv.linkId())
                && console.hardwareId().equals(tv.peerId()) && tv.hardwareId().equals(console.peerId())
                && console.getBlockPos().equals(tv.consolePos()) && tv.getBlockPos().equals(console.peerPos());
    }
    static void clearEmptyPowerFor(net.minecraft.server.MinecraftServer server) {
        EMPTY_POWER.keySet().removeIf(console -> console.getLevel() instanceof ServerLevel level && level.getServer() == server);
    }
    private static boolean running(ServerLevel level, HomeEndpointBlockEntity console) {
        if (console instanceof HomeConsoleBlockEntity fc) return HomeConsoleRuntime.running(level,fc);
        if (console instanceof ExternalHomeConsoleBlockEntity external) {
            var hooks = HomeSystems.applianceHooks(external.systemId());
            if (hooks != null) try { return hooks.isRunning(level,external); }
            catch (RuntimeException error) { FcArcadeMod.LOGGER.warn("Home running state rejected",error); }
        }
        return false;
    }
    public static InteractionResult interactConsole(ServerPlayer player, BlockPos pos, BlockHitResult hit) {
        var result = tryButton(player,pos,InteractionHand.MAIN_HAND,hit);
        if (result != InteractionResult.PASS) return result;
        if (ACTIVE.get()) return InteractionResult.CONSUME;
        // Blank case surfaces are not a debugger shortcut. Physical hotspots above,
        // cartridge handling and the explicit screwdriver/legacy settings entry remain.
        HomeFeedback.show(player,"appliance_console_hint");
        return InteractionResult.CONSUME;
    }
    public static InteractionResult interactTv(ServerPlayer player, BlockPos pos, BlockHitResult hit) {
        var result = tryButton(player,pos,InteractionHand.MAIN_HAND,hit);
        if (result != InteractionResult.PASS) return result;
        if (!ACTIVE.get()) HomeFeedback.show(player,"appliance_tv_hint");
        return InteractionResult.CONSUME;
    }
    /** An item must call this before interpreting a right-click as returning its controller. */
    public static InteractionResult tryButton(ServerPlayer player, BlockPos pos, InteractionHand hand, BlockHitResult hit) {
        if (ACTIVE.get()) return InteractionResult.CONSUME;
        if (player == null || pos == null || hand == null || hit == null || !pos.equals(hit.getBlockPos())
                || player.getServer() == null || !player.getServer().isSameThread()) return InteractionResult.FAIL;
        var level = player.serverLevel(); var endpoint = HomeHardware.loadedEndpoint(level,pos);
        if (endpoint == null) return InteractionResult.PASS;
        double reach = Math.min(6,player.blockInteractionRange());
        Vec3 eye = player.getEyePosition(), end = eye.add(player.getLookAngle().scale(reach));
        HomeApplianceControl control = controlAt(level,pos,eye,end);
        if (control == HomeApplianceControl.NONE) return InteractionResult.PASS;
        boolean unplug=control==HomeApplianceControl.VIDEO_DISCONNECT;
        if(unplug && (!player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty()))return InteractionResult.PASS;
        if (!facts(player,pos,endpoint,eye,end)) return InteractionResult.FAIL;
        var held = player.getItemInHand(hand);
        var tv = endpoint instanceof HomeTvBlockEntity t ? t : HomeHardware.loadedEndpoint(level,endpoint.peerPos()) instanceof HomeTvBlockEntity t ? t : null;
        var source = endpoint instanceof HomeTvBlockEntity && tv != null ? HomeHardware.connectedEndpoint(level,tv.getBlockPos()) : endpoint;
        var sourceId = source == null ? null : source.hardwareId();
        var link = endpoint.linkId();
        var peer = endpoint.peerPos();
        if(unplug && (endpoint instanceof HomeTvBlockEntity || tv==null || link==null || !paired(endpoint,tv)
                || player.distanceToSqr(Vec3.atCenterOf(tv.getBlockPos()))>256.0))return InteractionResult.PASS;
        ACTIVE.set(true);
        try {
            var event = NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickBlock(player,hand,pos,hit));
            if (event.isCanceled() || event.getUseBlock() == TriState.FALSE || event.getUseItem() == TriState.FALSE
                    || !HomeHardware.allowAnchorInteraction(player,pos,endpoint,hand,hit)) return InteractionResult.FAIL;
            if (source != null && source != endpoint) {
                BlockPos p = source.getBlockPos();
                var sourceEvent = NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickBlock(player,hand,p,
                    new BlockHitResult(hit.getLocation(),hit.getDirection(),p,hit.isInside())));
                if (sourceEvent.isCanceled() || sourceEvent.getUseBlock() == TriState.FALSE || sourceEvent.getUseItem() == TriState.FALSE)
                    return InteractionResult.FAIL;
            }
            // Recompute authority, inventory and ray after arbitrary protection callbacks.
            if(unplug){
                var p=tv.getBlockPos();
                var peerEvent=NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickBlock(player,hand,p,
                    new BlockHitResult(hit.getLocation(),hit.getDirection(),p,hit.isInside())));
                if(peerEvent.isCanceled()||peerEvent.getUseBlock()==TriState.FALSE||peerEvent.getUseItem()==TriState.FALSE
                    ||!player.getMainHandItem().isEmpty()||!player.getOffhandItem().isEmpty()||!paired(endpoint,tv)
                    ||player.distanceToSqr(Vec3.atCenterOf(p))>256.0)return InteractionResult.FAIL;
            }
            if (player.getItemInHand(hand) != held || player.serverLevel() != level
                    || !facts(player,pos,endpoint,player.getEyePosition(),player.getEyePosition().add(player.getLookAngle().scale(reach))) || !java.util.Objects.equals(link,endpoint.linkId())
                    || !java.util.Objects.equals(peer,endpoint.peerPos())
                    || controlAt(level,pos,player.getEyePosition(),player.getEyePosition().add(player.getLookAngle().scale(reach))) != control
                    || (source != null && (!level.mayInteract(player,source.getBlockPos())
                        || HomeHardware.loadedEndpoint(level,source.getBlockPos()) != source || !sourceId.equals(source.hardwareId())))
                    || (tv != null && (!level.mayInteract(player,tv.getBlockPos()) || HomeHardware.loadedEndpoint(level,tv.getBlockPos()) != tv
                        || source != null && HomeHardware.connectedEndpoint(level,tv.getBlockPos()) != source)))
                return InteractionResult.FAIL;
            long now = level.getGameTime();
            Long last = LAST_BUTTON.get(player);
            if (last != null && now >= last && now-last < 6) return InteractionResult.CONSUME;
            LAST_BUTTON.put(player,now);
            if(unplug){
                HomeHardware.disconnect(endpoint,player);
                HomeFeedback.show(player,"home_wire_disconnected");
                return InteractionResult.CONSUME;
            }
            if (endpoint instanceof HomeTvBlockEntity television) {
                if (control == HomeApplianceControl.POWER) {
                    boolean on = !television.powered();
                    if (!on) stop(level,source);
                    if (television.setPower(on)) HomeInteractionSounds.play(level,television.getBlockPos(),
                            on ? HomeInteractionSounds.Action.POWER_ON : HomeInteractionSounds.Action.POWER_OFF);
                    refresh(level,television.getBlockPos());
                    HomeFeedback.show(player,on ? "appliance_tv_on" : "appliance_tv_off");
                } else {
                    if (television.setVolume(television.volume() + (control == HomeApplianceControl.VOLUME_UP ? 10 : -10)))
                        HomeInteractionSounds.play(level,television.getBlockPos(),HomeInteractionSounds.Action.VOLUME);
                    HomeFeedback.show(player,"appliance_volume",television.volume());
                }
            } else {
                if (control.port() >= 0) {
                    if (source instanceof HomeConsoleBlockEntity fc) {
                        if (!HomeControllerService.returnHeldAtDock(player,fc,control.port(),hand))
                            HomeConsoleRuntime.takeController(player,fc,control.port());
                    }
                    else if (source instanceof ExternalHomeConsoleBlockEntity external) {
                        var hooks = HomeSystems.applianceHooks(external.systemId());
                        if (hooks != null) hooks.onControllerDock(player,external,control.port());
                    }
                } else if (control == HomeApplianceControl.POWER && player.isShiftKeyDown()
                        && source instanceof HomeConsoleBlockEntity subor && subor.getBlockState().getBlock() instanceof SuborConsoleBlock) {
                    HomeConsoleRuntime.reset(player,subor);
                } else if (control == HomeApplianceControl.POWER && (running(level,source) || emptyPowered(level,source))) {
                    stop(level,source);
                    if (!running(level,source)) HomeInteractionSounds.play(level,source.getBlockPos(),HomeInteractionSounds.Action.POWER_OFF);
                    HomeFeedback.show(player,"appliance_console_off");
                } else {
                    if (tv == null || !tv.powered()) { HomeFeedback.show(player,"appliance_need_tv"); return InteractionResult.CONSUME; }
                    if (control == HomeApplianceControl.POWER && source != null && !hasCartridge(source)) {
                        var idle = new EmptyConsolePower();
                        if (idle.turnOn(false,HomeHardware.connectedEndpoint(level,tv.getBlockPos()) == source,tv.powered())) {
                            EMPTY_POWER.put(source,idle);
                            refresh(level,tv.getBlockPos());
                            HomeInteractionSounds.play(level,source.getBlockPos(),HomeInteractionSounds.Action.POWER_ON);
                        }
                        return InteractionResult.CONSUME;
                    }
                    if (source instanceof HomeConsoleBlockEntity fc) {
                        if (control == HomeApplianceControl.POWER) {
                            boolean started = HomeConsoleRuntime.powerOn(player,fc);
                            if (started) HomeInteractionSounds.play(level,fc.getBlockPos(),HomeInteractionSounds.Action.POWER_ON);
                            HomeFeedback.show(player,started ? "appliance_console_on" : HomeConsoleRuntime.pendingSave(player,fc)
                                    ? "appliance_choose_save" : "appliance_power_failed");
                        }
                        else if (control == HomeApplianceControl.RESET) HomeConsoleRuntime.reset(player,fc);
                    } else if (source instanceof ExternalHomeConsoleBlockEntity external) {
                        var connection = HomeSystems.connection(level,external.getBlockPos());
                        var hooks = HomeSystems.applianceHooks(external.systemId());
                        if (connection.isEmpty() || hooks == null) return InteractionResult.FAIL;
                        if (control == HomeApplianceControl.POWER) {
                            boolean started = hooks.onPowerOn(player,connection.get());
                            if (started) HomeInteractionSounds.play(level,external.getBlockPos(),HomeInteractionSounds.Action.POWER_ON);
                            HomeFeedback.show(player,started ? "appliance_console_on" : "appliance_power_failed");
                        }
                        else if (control == HomeApplianceControl.RESET) hooks.onReset(player,connection.get());
                    }
                }
                if (tv != null) refresh(level,tv.getBlockPos());
            }
            return InteractionResult.CONSUME;
        } catch (RuntimeException error) {
            FcArcadeMod.LOGGER.warn("Home appliance action rejected at {}",pos,error);
            return InteractionResult.FAIL;
        } finally { ACTIVE.remove(); }
    }
    private static void stop(ServerLevel level, HomeEndpointBlockEntity source) {
        clearEmptyPower(source);
        if (source instanceof HomeConsoleBlockEntity fc) HomeConsoleRuntime.powerOff(level,fc);
        else if (source instanceof ExternalHomeConsoleBlockEntity external) {
            var hooks = HomeSystems.applianceHooks(external.systemId());
            if (hooks != null) hooks.onPowerOff(level,external);
        }
    }
    private static boolean facts(ServerPlayer player, BlockPos clicked, HomeEndpointBlockEntity expected, Vec3 eye, Vec3 end) {
        var level = player.serverLevel();
        if (!player.isAlive() || player.isSpectator() || player.hasDisconnected() || !player.connection.getConnection().isConnected()
                || level.getServer().getPlayerList().getPlayer(player.getUUID()) != player || expected.getLevel() != level
                || expected.isRemoved() || !HomeHardware.mayUse(player,clicked) || !level.mayInteract(player,expected.getBlockPos())
                || HomeHardware.loadedEndpoint(level,clicked) != expected) return false;
        if (expected instanceof HomeTvBlockEntity && !HomeTvStructure.complete(level,expected.getBlockPos())) return false;
        if (expected instanceof HomeConsoleBlockEntity && !SuborStructure.complete(level,expected.getBlockPos())) return false;
        var frame = cn.piq.fcarcade.compat.HomeShipSpace.at(level,expected.getBlockPos());
        if (frame == null) return false;
        if (frame.ship()) {
            if (!shipControlsSupported(expected)) return false;
            var ray = HomeShipRay.trace(level,player,eye,end,frame,true,(ship,p) -> false);
            return ray.valid() && ray.hit() != null && HomeHardware.loadedEndpoint(level,ray.hit().getBlockPos()) == expected;
        }
        BlockHitResult actual = new LoadedView(level).clip(new ClipContext(eye,end,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,player));
        return actual.getType() == HitResult.Type.BLOCK && HomeHardware.loadedEndpoint(level,actual.getBlockPos()) == expected;
    }
    public static HomeApplianceControl controlAt(Level level, BlockPos clicked, Vec3 eye, Vec3 end) {
        return controlAt(level,clicked,eye,end,Float.NaN);
    }
    /** Render-time hints use the same interpolated ship pose as the displayed model. Authority uses logical pose. */
    public static HomeApplianceControl controlAt(Level level, BlockPos clicked, Vec3 eye, Vec3 end, float partialTick) {
        var endpoint = HomeHardware.loadedEndpoint(level,clicked);
        if (endpoint == null) return HomeApplianceControl.NONE;
        var frame = cn.piq.fcarcade.compat.HomeShipSpace.at(level,endpoint.getBlockPos(),partialTick);
        if (frame == null || !cn.piq.fcarcade.compat.HomeShipSpace.finite(eye) || !cn.piq.fcarcade.compat.HomeShipSpace.finite(end)
                || frame.ship() && !shipControlsSupported(endpoint)) return HomeApplianceControl.NONE;
        if (frame.ship()) { eye = frame.toStorage(eye); end = frame.toStorage(end); }
        var state = endpoint.getBlockState();
        int turns = switch (state.getValue(BlockStateProperties.HORIZONTAL_FACING)) { case EAST -> 1; case SOUTH -> 2; case WEST -> 3; default -> 0; };
        Vec3 offset = Vec3.atLowerCornerOf(endpoint.getBlockPos());
        if (endpoint instanceof HomeTvBlockEntity && state.getBlock() instanceof FcArcadeBlock block) {
            var t = ScreenSurfaceGeometry.translation(block.displayStyle(),turns,HomeTvStructure.centered(state));
            offset = offset.add(t.x(),t.y(),t.z());
        }
        var a = local(eye.subtract(offset),turns); var b = local(end.subtract(offset),turns);
        if (endpoint instanceof HomeTvBlockEntity && state.getBlock() instanceof FcArcadeBlock block)
            return ApplianceControls.television(block.displayStyle(),point(a),point(b));
        if (endpoint instanceof ExternalHomeConsoleBlockEntity external) {
            var picker = CONTROLS.get(external.systemId());
            return picker == null ? HomeApplianceControl.NONE : picker.apply(a,b);
        }
        if (endpoint instanceof HomeConsoleBlockEntity) return state.getBlock() instanceof SuborConsoleBlock
                ? ApplianceControls.subor(SuborConsoleBlock.wide(state),SuborConsoleBlock.compact(state),point(a),point(b)) : ApplianceControls.famicom(point(a),point(b));
        return HomeApplianceControl.NONE;
    }
    private static boolean shipControlsSupported(HomeEndpointBlockEntity endpoint) {
        return endpoint instanceof HomeTvBlockEntity || endpoint instanceof HomeConsoleBlockEntity
                && !(endpoint.getBlockState().getBlock() instanceof SuborConsoleBlock);
    }
    private static Vec3 local(Vec3 p,int turns) { var v = ApplianceRay.unrotate(point(p),turns); return new Vec3(v.x(),v.y(),v.z()); }
    private static ApplianceRay.Point point(Vec3 p) { return new ApplianceRay.Point(p.x,p.y,p.z); }
    private record LoadedView(ServerLevel level) implements BlockGetter {
        public BlockState getBlockState(BlockPos p) { return level.hasChunkAt(p) ? level.getBlockState(p) : Blocks.BARRIER.defaultBlockState(); }
        public BlockEntity getBlockEntity(BlockPos p) { return level.hasChunkAt(p) ? level.getBlockEntity(p) : null; }
        public FluidState getFluidState(BlockPos p) { return level.hasChunkAt(p) ? level.getFluidState(p) : Fluids.EMPTY.defaultFluidState(); }
        public int getHeight() { return level.getHeight(); }
        public int getMinBuildHeight() { return level.getMinBuildHeight(); }
    }
}
