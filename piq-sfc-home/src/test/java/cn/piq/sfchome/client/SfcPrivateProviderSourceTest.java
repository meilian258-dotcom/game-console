package cn.piq.sfchome.client;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SfcPrivateProviderSourceTest {
    String source()throws Exception{return Files.readString(Path.of("src/main/java/cn/piq/sfchome/client/SfcPrivateProvider.java"));}
    @Test void controllerReceiptRequiresCorrectItemSingleCountTypedPortAndNonzeroUuid()throws Exception{
        String source=source();
        for(String check:new String[]{"!SfcControllerData.isController(stack)||stack.getCount()!=1","if(data==null)return null",
                "!tag.hasUUID(\"SfcLease\")","!tag.contains(\"SfcPort\",Tag.TAG_INT)","if(port<0||port>1)return null",
                "lease.getMostSignificantBits()==0&&lease.getLeastSignificantBits()==0?null:lease"})assertTrue(source.contains(check),check);
        assertFalse(source.contains("SfcControllerData.port(stack)"));
    }
    @Test void matchesRequiresIdleSameWorldHardwareAndExactPhysicalReceipt()throws Exception{
        String source=source();
        for(String check:new String[]{"SfcHomeConsoleBlockEntity console","console.isRemoved()","console.getLevel()!=player.level()",
                "console.visualPowered()","SfcHomeClient.currentSession()!=null","ControllerCapturePolicy.receipt(player.getUUID(),lease,port",
                "console.controllerVisualPlayer(port)","console.controllerVisualLease(port)","console.controllerDocked(port)",
                "player.distanceToSqr(console.getBlockPos().getCenter())"})assertTrue(source.contains(check),check);
        for(String forbidden:new String[]{"PacketDistributor","sendToServer","RomRequest","grant(","powerOn(","setCount(","setControllerVisual"})assertFalse(source.contains(forbidden),forbidden);
    }
    @Test void rawIdentityCountsMalformedAndCountTwoDuplicatesWithoutAuthorizingThem()throws Exception{
        String source=source();int from=source.indexOf("UUID identity(ItemStack stack)");int to=source.indexOf("@Override public UUID lease",from);
        assertTrue(from>=0&&to>from);String identity=source.substring(from,to);
        assertTrue(identity.contains("SfcControllerData.isController(stack)"));assertTrue(identity.contains("tag.hasUUID(\"SfcLease\")"));
        assertFalse(identity.contains("getCount()"));assertFalse(identity.contains("SfcPort"));
    }
    @Test void registeredUnderSfcAndUsesPrivateEngineNotPublicPlayback()throws Exception{
        String source=source();assertTrue(source.contains("implements PrivateHomeClient.Provider"));
        assertTrue(source.contains("KeyboardConfig.Profile.SFC"));assertTrue(source.contains("KeyboardInput.keys(SfcHomeKeys.KEYS)"));
        assertTrue(source.contains("String storageKey(){return \"sfc\";}"));assertTrue(source.contains("new SfcPrivateEngine(rom,saveRoot)"));
        String setup=Files.readString(Path.of("src/main/java/cn/piq/sfchome/client/SfcHomeClient.java"));
        assertTrue(setup.contains("PrivateHomeClient.register(SYSTEM,new SfcPrivateProvider())"));
        assertTrue(setup.contains("ResourceLocation.fromNamespaceAndPath(\"piq_sfc_home\",\"sfc\")"));
        assertTrue(source.contains("boolean publicBusy(){return SfcHomeClient.currentSession()!=null;}"));
        int receive=setup.indexOf("@Override public void session(");
        int stop=setup.indexOf("PrivateHomeClient.stop(\"收到公开 SFC 游戏会话\")",receive);
        assertTrue(stop>setup.indexOf("if(!SESSION_ORDER.accept(",receive));
        assertTrue(stop<setup.indexOf("closeLocal();",receive));
    }
}
