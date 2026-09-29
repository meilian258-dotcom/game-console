package cn.piq.fcarcade.client;

import cn.piq.fcarcade.home.*;
import cn.piq.retro.client.KeyboardConfig;
import cn.piq.retro.client.KeyboardInput;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import java.util.*;
import java.util.function.*;

/** One local capture-only owner for physically borrowed devices, including powered-off consoles.
 * This class never grants a runtime seat, acquires InputOwnership or sends an input packet. */
public final class ControllerCapture {
    public interface Provider {
        KeyboardConfig.Profile profile();
        int[][] keys();
        UUID lease(ItemStack stack);
        boolean matches(Player player, ItemStack stack, BlockEntity endpoint);
        default BlockEntity locate(Player player, ItemStack stack) {
            // Bounded, loaded-only discovery. Valid leases are cached and do not repeat this scan.
            BlockPos center = player.blockPosition();
            for (BlockPos pos : BlockPos.betweenClosed(center.offset(-6,-6,-6), center.offset(6,6,6))) {
                if (player.distanceToSqr(pos.getCenter()) > 36 || !player.level().hasChunkAt(pos)) continue;
                var endpoint = player.level().getBlockEntity(pos);
                if (endpoint != null && matches(player,stack,endpoint)) return endpoint;
            }
            return null;
        }
    }
    private static final Map<ResourceLocation,Provider> PROVIDERS = new LinkedHashMap<>();
    private static final List<Runnable> RUNTIMES = new ArrayList<>();
    private static Candidate current;
    private static Object connection;
    private static long nextSearch;
    private static UUID searchedLease;
    private static Provider searchedProvider;
    private static boolean installed, refreshing;
    private record Candidate(Object token,Object connection,Provider provider,UUID lease,BlockEntity endpoint,int hand) {}
    private ControllerCapture() {}
    public static void register(ResourceLocation id, Provider provider) {
        if (PROVIDERS.size() >= 8 || PROVIDERS.putIfAbsent(Objects.requireNonNull(id),Objects.requireNonNull(provider)) != null)
            throw new IllegalArgumentException("Duplicate/too many controller capture providers");
    }
    public static void registerRuntime(Runnable refresh) { RUNTIMES.add(Objects.requireNonNull(refresh)); }
    public static void install() {
        if (installed) return;
        installed=true;
        register(ResourceLocation.fromNamespaceAndPath("piq_fc_arcade","controller"),new Provider() {
            public KeyboardConfig.Profile profile(){return KeyboardConfig.Profile.NES;}
            public int[][] keys(){return ArcadeKeyMappings.legacyKeys();}
            public UUID lease(ItemStack stack){return HomeControllerData.leaseId(stack);}
            public boolean matches(Player player,ItemStack stack,BlockEntity endpoint){
                int port=HomeControllerData.port(stack);UUID lease=lease(stack);
                return endpoint instanceof HomeConsoleBlockEntity c && ControllerCapturePolicy.receipt(player.getUUID(),lease,port,
                        c.controllerVisualPlayer(port),c.controllerVisualLease(port),c.controllerDocked(port),player.distanceToSqr(c.getBlockPos().getCenter()));
            }
        });
        register(ResourceLocation.fromNamespaceAndPath("piq_fc_arcade","zapper"),new Provider() {
            public KeyboardConfig.Profile profile(){return KeyboardConfig.Profile.NES;}
            public int[][] keys(){return ArcadeKeyMappings.legacyKeys();}
            public UUID lease(ItemStack stack){var receipt=ZapperStandOrigin.read(stack);return receipt==null?null:receipt.loan();}
            public BlockEntity locate(Player player,ItemStack stack){var r=ZapperStandOrigin.read(stack);
                if(r==null||!r.stand().dimension().equals(player.level().dimension().location().toString()))return null;
                var pos=new BlockPos(r.stand().x(),r.stand().y(),r.stand().z());
                if(!player.level().hasChunkAt(pos))return null;var endpoint=player.level().getBlockEntity(pos);
                return endpoint!=null&&matches(player,stack,endpoint)?endpoint:null;}
            public boolean matches(Player player,ItemStack stack,BlockEntity endpoint){
                var r=ZapperStandOrigin.read(stack);
                if(r==null||!(endpoint instanceof ZapperStandBlockEntity stand)||stand.loan()==null||stand.link()==null
                        ||!r.stand().equals(stand.endpoint())||!r.loan().equals(stand.loan().id())||!player.getUUID().equals(stand.loan().player()))return false;
                var link=stand.link();var target=link.console();var pos=new BlockPos(target.x(),target.y(),target.z());
                return link.stand().equals(r.stand())&&target.dimension().equals(player.level().dimension().location().toString())
                        &&player.level().hasChunkAt(pos)&&player.level().getBlockEntity(pos) instanceof HomeConsoleBlockEntity c
                        &&c.hardwareId().equals(target.id())&&HomeRuntimeAuthority.controllerInRange(player.distanceToSqr(pos.getCenter()));
            }
        });
        KeyboardInput.registerPresenceRefresh(ControllerCapture::refresh);
    }
    public static void refresh() {
        if(refreshing)return;refreshing=true;
        try {
            var mc=Minecraft.getInstance();
            if(!connected(mc)){clear();return;}
            if(connection!=mc.getConnection()){clear();connection=mc.getConnection();}
            // Attach an admitted runtime before HEAD routes this same key edge.
            for(var runtime:List.copyOf(RUNTIMES))try{runtime.run();}catch(RuntimeException|LinkageError failure){
                cn.piq.fcarcade.FcArcadeMod.LOGGER.debug("Controller capture runtime refresh failed closed",failure);
            }
            if(current!=null&&!valid(current)){KeyboardInput.release(current.token());current=null;searchedLease=null;nextSearch=0;}
            if(current==null){
                var hands=List.of(mc.player.getMainHandItem(),mc.player.getOffhandItem());
                outer: for(int hand=0;hand<hands.size();hand++)for(var provider:PROVIDERS.values()){
                    var held=hands.get(hand);
                    UUID lease=provider.lease(held);if(lease==null||!unique(mc.player,held,lease,provider::lease))continue;
                    long now=System.nanoTime();
                    if(provider==searchedProvider&&lease.equals(searchedLease)&&now<nextSearch)continue;
                    searchedProvider=provider;searchedLease=lease;nextSearch=now+100_000_000L;
                    var endpoint=provider.locate(mc.player,held);
                    if(endpoint!=null){current=new Candidate(new Object(),mc.getConnection(),provider,lease,endpoint,hand);break outer;}
                }
            }
            if(current!=null){var candidate=current;
                KeyboardInput.attach(candidate.token(),candidate.provider().profile(),candidate.provider()::keys,
                        ()->valid(candidate),()->false,()->{},()->{});
            }
        } finally {refreshing=false;}
    }
    public static void clear(){if(current!=null)KeyboardInput.release(current.token());current=null;connection=null;searchedLease=null;searchedProvider=null;nextSearch=0;}
    private static boolean connected(Minecraft mc){return mc.player!=null&&mc.level!=null&&mc.player.isAlive()&&!mc.player.isSpectator()
            &&mc.getConnection()!=null&&mc.getConnection().getConnection().isConnected();}
    private static boolean valid(Candidate candidate){
        var mc=Minecraft.getInstance();var endpoint=candidate.endpoint();
        if(!connected(mc)||mc.getConnection()!=candidate.connection()||endpoint.isRemoved()||endpoint.getLevel()!=mc.level
                ||!mc.level.hasChunkAt(endpoint.getBlockPos())||mc.level.getBlockEntity(endpoint.getBlockPos())!=endpoint)return false;
        var held=candidate.hand()==0?mc.player.getMainHandItem():mc.player.getOffhandItem();
        return candidate.lease().equals(candidate.provider().lease(held))&&unique(mc.player,held,candidate.lease(),candidate.provider()::lease)
                &&candidate.provider().matches(mc.player,held,endpoint);
    }
    public static boolean unique(Player player,ItemStack held,UUID lease,Function<ItemStack,UUID> identity){
        var items=new ArrayList<ItemStack>();
        for(int i=0;i<player.getInventory().getContainerSize();i++)items.add(player.getInventory().getItem(i));
        for(var slot:player.inventoryMenu.slots)items.add(slot.getItem());
        items.add(player.inventoryMenu.getCarried());items.add(player.containerMenu.getCarried());
        return ControllerCapturePolicy.uniqueHeld(held,lease,items,identity,ItemStack::getCount);
    }
}
