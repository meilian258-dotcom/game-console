package cn.piq.fcarcade.home;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Source guardrails are complementary to the pure policy tests and production compilation. */
class DeviceDebugServerSourceTest {
    private static String source(String path)throws Exception{return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/"+path+".java"));}
    @Test void toolConsumesFirstAndOnlyRoutesIntoSettings()throws Exception{
        String item=source("home/DeviceDebugItem"),service=source("home/DeviceDebugService");
        assertTrue(item.contains("onItemUseFirst("));assertTrue(item.contains("return InteractionResult.CONSUME;"));
        assertTrue(service.contains("HomeSyncSettings.openDebug(player,selection)"));assertTrue(service.contains("ServerCabinets.openDebugSettings(player,selection)"));
        for(String forbidden:new String[]{"ServerCabinets.interact(","startHomeConsole(","stopHomeConsole(","ServerCartridgeService","takeCartridge(","synchronizationMode("})
            assertFalse(service.contains(forbidden));
    }
    @Test void rangeNeverLoadsChunksAndUsesFirstBlockingShape()throws Exception{
        String code=source("home/DeviceDebugService");
        assertTrue(code.contains("Math.min(DeviceDebugPolicy.RANGE,player.blockInteractionRange())"));
        assertTrue(code.contains("ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE"));
        assertTrue(code.contains("level.hasChunkAt(pos) ? level.getBlockState(pos) : Blocks.BARRIER.defaultBlockState()"));
        assertTrue(code.contains("level.hasChunkAt(pos) ? level.getBlockEntity(pos) : null"));
        assertTrue(code.contains("hit.getBlockPos().equals(clicked)"));
        assertTrue(code.indexOf("addCooldown(item,")<code.indexOf("var hit = target(player)"));
        assertFalse(code.contains("getChunk("));
    }
    @Test void toolAuthorityRetainsConnectionHandStackOperatorAndClickedEntity()throws Exception{
        String code=source("home/DeviceDebugService");
        assertFalse(code.contains("isCreative()"));
        for(String required:new String[]{"!player.hasPermissions(2)","player.connection.getConnection() != selected.connection()",
                "player.getItemInHand(selected.hand()) != selected.held()","ItemStack.matches(selected.held(),selected.original())",
                "selected.level().getBlockEntity(clicked) == selected.clickedEntity()","!selected.connection().isConnected()"})assertTrue(code.contains(required),required);
        assertTrue(code.contains("selected.level().mayInteract(player,clicked) && selectionIdentity(player,selected)"));
    }
    @Test void homeRevisionRateLimitAndActualCapabilitiesAreCheckedBeforeMutation()throws Exception{
        String code=source("home/HomeSyncSettings");
        assertTrue(code.contains("request.revision()!=intent.revision"));
        assertTrue(code.contains("old.mode()!=displayMode(c)"));
        assertTrue(code.contains("old.occupancy()!=c.occupancyVisible()"));
        assertTrue(code.contains("old.approval()!=c.joinApprovalRequired()"));
        assertTrue(code.indexOf("now-intent.lastRequest<DeviceDebugPolicy.REQUEST_TICKS")<code.indexOf("requestChecked(player,intent,request)"));
        String cached=code.substring(code.indexOf("private static void cachedReply("),code.indexOf("private static boolean available("));
        assertFalse(cached.contains("authorized("));assertFalse(cached.contains("busy(player,"));assertFalse(cached.contains("supported(player,"));
        assertTrue(code.contains("intent.tool==null?InteractionHand.MAIN_HAND:intent.tool.hand()"));
        assertTrue(code.contains("HomePresentationSettings.occupancySupported(c.getLevel(),c.getBlockPos()),intent.tool!=null,modeReasons"));
        assertTrue(code.contains("!player.hasPermissions(2)||!identity(player,intent)"));
        assertTrue(code.contains("open(player,clicked,hit,null)"));
    }
    @Test void cabinetToolPacketsNeverFallBackToLegacyAuthority()throws Exception{
        String code=source("cabinet/CabinetSyncSettings"),server=source("cabinet/ServerCabinets");
        assertTrue(code.contains("if(packet.debugToken()!=null)"));
        assertTrue(code.contains("debug==null||!DeviceDebugPolicy.matchesToken(packet.debugToken(),debug.token)"));
        assertTrue(code.contains("!i.invalid&&old.editable()"));
        assertTrue(code.contains("debugValid(p,debug)"));
        assertTrue(code.contains("Objects.equals(i.secondary,CabinetLinks.peer(server,i.primary))"));
        assertTrue(code.contains("CabinetLinks.hasLink(server,i.primary)!=(i.secondary!=null)"));
        assertTrue(code.contains("level.getBlockEntity(i.primary.anchor())!=i.master"));
        assertTrue(code.contains("!i.backend.equals(i.master.cabinetBackend())"));
        assertTrue(server.contains("CabinetSyncSettings.clearDebug(player)"));
        String open=server.substring(server.indexOf("public static void openDebugSettings("),server.indexOf("public static boolean isCabinetBusy("));
        assertFalse(open.contains("launch("));assertFalse(open.contains("menus.put("));assertFalse(open.contains("choose("));
        assertTrue(open.contains("selection.hand()"));
    }
    @Test void packetsCarryExplicitDebugAndRevisionWhileLegacyMenuRemainsNonempty()throws Exception{
        String home=source("home/HomeSyncNetwork"),menu=source("cabinet/CabinetNetwork"),sync=source("cabinet/CabinetSyncNetwork");
        assertTrue(home.contains("TrafficPayloadRegistrar.create(event,\"home-sync-5\")"));assertTrue(home.contains("b.writeVarInt(p.revision)"));
        assertTrue(home.contains("DeviceDebugPolicy.validRequest(revision,mode,occupancy,approval)"));
        assertTrue(menu.contains("TrafficPayloadRegistrar.create(event,\"cabinet-4\")"));assertTrue(menu.contains("entries.isEmpty()"));
        assertTrue(menu.contains("this(target,token,entries,selected,false)"));assertTrue(menu.contains("b.writeBoolean(p.debugTool())"));
        assertTrue(sync.contains("TrafficPayloadRegistrar.create(event,\"cabinet-sync-10\")"));assertTrue(sync.contains("this(target,backend,mode,null)"));
        assertTrue(sync.contains("writeDebugToken(b,p.debugToken)"));assertTrue(sync.contains("readDebugToken(b)"));
    }
}
