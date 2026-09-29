package cn.piq.sfchome.client.cabinet;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SfcCabinetSourceContractTest {
    String source(String name)throws Exception{return Files.readString(Path.of("src/main/java/cn/piq/sfchome/"+name+".java"));}
    @Test void commonOnlyDeclaresProviderAndUsesSharedCreativePage()throws Exception{String s=source("SfcHomeMod");assertTrue(s.contains("CabinetBackends.register(CABINET_BACKEND"));assertTrue(s.contains("CabinetBackends.registerNetwork(CABINET_BACKEND,2)"));assertTrue(s.contains("Super Famicom\",false"));assertTrue(s.contains("ModCreativeTabs.FC.getKey()"));assertFalse(s.contains("TABS.register"));assertFalse(s.contains(".client."));assertFalse(s.contains("ModItems.AV_CABLE"));}
    @Test void clientSetupIsDistGatedAndUsesExistingPublicCore()throws Exception{String s=source("client/cabinet/SfcCabinetProvider");assertTrue(s.contains("value=Dist.CLIENT"));assertTrue(s.contains("FMLClientSetupEvent"));assertTrue(s.contains("event.enqueueWork"));assertTrue(s.contains("CabinetClientBackends.register"));s=source("client/cabinet/SfcCabinetSession");assertTrue(s.contains("LibretroSfcCore::new"));assertTrue(s.contains("Math.multiplyExact(samples,2)"));assertTrue(s.contains("lease.close()"));}
    @Test void homePlaybackSharesTheSameOwnerLease()throws Exception{String s=source("client/SfcPlayback");assertTrue(s.contains("lease=SfcCoreLease.acquire()"));assertTrue(s.contains("running=false;lease.close()"));assertFalse(s.contains("NATIVE_WORKER"));}
}
