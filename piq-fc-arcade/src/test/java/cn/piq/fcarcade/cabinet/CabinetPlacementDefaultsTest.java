package cn.piq.fcarcade.cabinet;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CabinetPlacementDefaultsTest {
    @Test void newPlacementMarkerIsExplicitAndMissingOldNbtCannotOptIn() throws Exception {
        String code=Files.readString(Path.of("src/main/java/cn/piq/fcarcade/world/LegacyFcArcadeBlockEntity.java"));
        assertTrue(code.contains("private boolean placedJniDefault = true;"));
        String load=code.substring(code.indexOf("protected void loadAdditional"),code.indexOf("protected void saveAdditional"));
        assertTrue(load.contains("placedJniDefault=tag.getBoolean(\"PlacedJniDefault\")"));
        assertTrue(code.contains("tag.putBoolean(\"PlacedJniDefault\",placedJniDefault)"));
        assertFalse(new CompoundTag().getBoolean("PlacedJniDefault"));
        for(boolean fresh:new boolean[]{false,true}) {
            var stored=new CompoundTag();stored.putBoolean("PlacedJniDefault",fresh);
            assertEquals(fresh,stored.copy().getBoolean("PlacedJniDefault"));
        }
        // NES has its separate established route; visiting it must not freeze a later native backend.
        String sync=Files.readString(Path.of("src/main/java/cn/piq/fcarcade/cabinet/CabinetSyncSettings.java"));
        String get=sync.substring(sync.indexOf("static CabinetSyncMode get("),sync.indexOf("/** One-time resolution"));
        assertTrue(get.indexOf("b.equals(CabinetBackends.NES)")<get.indexOf("rememberDefault("));
        assertTrue(get.indexOf("if(saved!=null)return saved")<get.indexOf("rememberDefault("));
        assertTrue(get.indexOf("modes.size()>=4096")<get.indexOf("rememberDefault("));
    }
    @Test void onlyNewAutomaticCapableJniPlacementsOptIn() {
        for(boolean fresh:new boolean[]{false,true})for(int preferred=-1;preferred<=2;preferred++)
            for(boolean local:new boolean[]{false,true})for(boolean supported:new boolean[]{false,true})
                for(boolean jni:new boolean[]{false,true})
                    assertEquals(fresh&&preferred==-1&&local&&supported&&jni,
                            CabinetPlacementDefaults.jni(fresh,preferred,local,supported,jni));
        assertThrows(IllegalArgumentException.class,()->CabinetPlacementDefaults.jni(true,3,true,true,true));
    }
    @Test void newChoiceIsFrozenAcrossReloadAndNeverChangesToAnExperimentalVariant() {
        var store=new CabinetSyncSettings();
        assertEquals(CabinetSyncMode.LOCAL_SYNC,store.rememberDefault("new|native",CabinetSyncMode.LOCAL_SYNC,true,"legacy"));
        var first=store.save(new CompoundTag(),null);
        var restored=CabinetSyncSettings.load(first,null);
        restored.rememberDefault("new|native",CabinetSyncMode.MEDIA,false,null);
        restored.rememberDefault("new|native",CabinetSyncMode.LOCAL_SYNC,true,"retroarch-stock-v1");
        assertEquals(first,restored.save(new CompoundTag(),null));
        assertTrue(first.getList("Modes",10).getCompound(0).getBoolean("Netplay"));
        assertEquals("legacy",first.getList("Modes",10).getCompound(0).getString("NetplayVariant"));
    }
    @Test void oldExplicitMediaLocalHostedJniAndRetroarchStayByteEquivalent() {
        for(int mode=0;mode<=2;mode++)for(boolean netplay:new boolean[]{false,true})for(String variant:new String[]{"","legacy","retroarch-stock-v1"}) {
            var row=new CompoundTag();row.putString("Key","old|native");row.putInt("Mode",mode);row.putInt("SaveMode",2);
            row.putBoolean("Netplay",mode==1&&netplay);if(!variant.isEmpty())row.putString("NetplayVariant",variant);
            var rows=new ListTag();rows.add(row);var before=new CompoundTag();before.put("Modes",rows);
            var store=CabinetSyncSettings.load(before,null);
            store.rememberDefault("old|native",CabinetSyncMode.LOCAL_SYNC,true,"legacy");
            assertEquals(before,store.save(new CompoundTag(),null));
        }
    }
    @Test void unsupportedDefaultDoesNotBecomeEnabledWhenCapabilityArrivesLater() {
        var store=new CabinetSyncSettings();store.rememberDefault("new|unknown",CabinetSyncMode.MEDIA,false,null);
        var first=store.save(new CompoundTag(),null);var reload=CabinetSyncSettings.load(first,null);
        reload.rememberDefault("new|unknown",CabinetSyncMode.LOCAL_SYNC,true,"legacy");
        assertEquals(first,reload.save(new CompoundTag(),null));
        // Backend-specific keys: visiting the original NES cabinet cannot preselect a later native backend.
        reload.rememberDefault("new|native",CabinetSyncMode.LOCAL_SYNC,true,"legacy");
        assertEquals(2,reload.save(new CompoundTag(),null).getList("Modes",10).size());
    }
    @Test void capacityFailureCannotPretendThatTheDefaultWasSaved() {
        var store=new CabinetSyncSettings();for(int i=0;i<4096;i++)store.rememberDefault("cabinet"+i,CabinetSyncMode.MEDIA,false,null);
        assertThrows(IllegalStateException.class,()->store.rememberDefault("overflow",CabinetSyncMode.LOCAL_SYNC,true,"legacy"));
        assertEquals(CabinetSyncMode.MEDIA,store.rememberDefault("cabinet0",CabinetSyncMode.LOCAL_SYNC,true,"legacy"));
    }
}
