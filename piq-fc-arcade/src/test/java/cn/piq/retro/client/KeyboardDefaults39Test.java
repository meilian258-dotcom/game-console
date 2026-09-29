package cn.piq.retro.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static cn.piq.retro.client.KeyboardRouting.Route.*;

class KeyboardDefaults39Test {
    @TempDir Path dir;

    private static byte[] old(KeyboardConfig config,int version)throws Exception{
        return new String(KeyboardConfigStore.encode(config),StandardCharsets.UTF_8)
                .replace("version=3","version="+version).getBytes(StandardCharsets.UTF_8);
    }

    @Test void everyFixedProfileMigratesOnlyOldDefaultHotkeysAndPreservesBindings()throws Exception{
        for(int version:new int[]{1,2})for(var preset:List.of(KeyboardConfig.Preset.WASD,KeyboardConfig.Preset.CLASSIC,KeyboardConfig.Preset.NUMPAD)){
            var before=KeyboardConfig.defaults().withHotkeys(version==1?297:90,296);
            for(var profile:KeyboardConfig.Profile.values())before=before.withPreset(profile,preset);
            var after=KeyboardConfigStore.decode(old(before,version));
            assertEquals(78,after.toggleKey());assertEquals(before.settingsKey(),after.settingsKey());
            assertEquals(before.profiles(),after.profiles());
        }
    }

    @Test void oldVersionOneZDefaultAlsoMigrates()throws Exception{
        assertEquals(78,KeyboardConfigStore.decode(old(KeyboardConfig.defaults().withHotkeys(90,296),1)).toggleKey());
    }

    @Test void migrationReadsOnlyAndExplicitSaveWritesCurrentFormat()throws Exception{
        Files.createDirectory(dir.resolve("config"));var file=KeyboardConfigStore.path(dir);
        byte[] bytes=old(KeyboardConfig.defaults().withHotkeys(90,296),2);Files.write(file,bytes);
        var loaded=KeyboardConfigStore.load(dir);
        assertEquals(78,loaded.config().toggleKey());assertTrue(loaded.warning().contains("N"));
        assertArrayEquals(bytes,Files.readAllBytes(file));
        KeyboardConfigStore.save(dir,loaded.config(),loaded.revision());
        assertTrue(Files.readString(file).contains("version=3"));
        assertEquals(loaded.config(),KeyboardConfigStore.load(dir).config());
        assertEquals("",KeyboardConfigStore.load(dir).warning());assertFalse(Files.exists(dir.resolve("options.txt")));
    }

    @Test void oldExplicitHotkeysIncludingCustomSettingsAreNeverReplaced()throws Exception{
        for(int version:new int[]{1,2})for(var pair:List.of(List.of(298,299),List.of(90,78),List.of(297,299))){
            var before=KeyboardConfig.defaults().withHotkeys(pair.get(0),pair.get(1));
            assertEquals(before,KeyboardConfigStore.decode(old(before,version)));
        }
    }

    @Test void oldCustomNGameButtonIsNotReassignedToMakeRoomForNewDefault()throws Exception{
        var before=KeyboardConfig.defaults().withHotkeys(90,296)
                .withCustom(KeyboardConfig.Profile.NES,List.of(78,88,259,257,265,264,263,262));
        for(int version:new int[]{1,2})assertEquals(before,KeyboardConfigStore.decode(old(before,version)));
    }

    @Test void oldLiveMinecraftProfileIsPreservedBecauseItsCustomKeysAreUnknown()throws Exception{
        var before=KeyboardConfig.defaults().withHotkeys(90,296).withPreset(KeyboardConfig.Profile.NES,KeyboardConfig.Preset.LEGACY);
        for(int version:new int[]{1,2})assertEquals(before,KeyboardConfigStore.decode(old(before,version)));
    }

    private static EnumMap<KeyboardConfig.Profile,int[][]> rawLegacy(){
        var raw=new EnumMap<KeyboardConfig.Profile,int[][]>(KeyboardConfig.Profile.class);
        for(var profile:KeyboardConfig.Profile.values())raw.put(profile,KeyboardConfig.presetKeys(profile,KeyboardConfig.Preset.LEGACY).stream().map(k->new int[]{k}).toArray(int[][]::new));
        return raw;
    }
    private static EnumMap<KeyboardConfig.Profile,int[]> rawExtras(){
        var raw=new EnumMap<KeyboardConfig.Profile,int[]>(KeyboardConfig.Profile.class);for(var profile:KeyboardConfig.Profile.values())raw.put(profile,new int[0]);return raw;
    }
    private KeyboardConfigStore.Loaded live(KeyboardConfig before,int version,Map<KeyboardConfig.Profile,int[][]> raw,Map<KeyboardConfig.Profile,int[]> extras)throws Exception{
        Files.createDirectories(dir.resolve("config"));byte[] bytes=old(before,version);Files.write(KeyboardConfigStore.path(dir),bytes);
        var loaded=KeyboardConfigStore.load(dir,raw,extras);assertArrayEquals(bytes,Files.readAllBytes(KeyboardConfigStore.path(dir)));return loaded;
    }
    @Test void actualLegacyDefaultsMigrateAfterRawAliasAndExtrasCheck()throws Exception{
        var raw=rawLegacy();raw.get(KeyboardConfig.Profile.NES)[0]=new int[]{75,88};raw.get(KeyboardConfig.Profile.NES)[1]=new int[]{74,90};
        var extras=rawExtras();extras.put(KeyboardConfig.Profile.NES,new int[]{82,77});
        for(int version:new int[]{1,2})for(int toggle:new int[]{90,version==1?297:90}){
            var before=KeyboardConfig.defaults().withHotkeys(toggle,296);for(var p:KeyboardConfig.Profile.values())before=before.withPreset(p,KeyboardConfig.Preset.LEGACY);
            var loaded=live(before,version,raw,extras);assertEquals(78,loaded.config().toggleKey());assertEquals(before.profiles(),loaded.config().profiles());assertTrue(loaded.warning().contains("旧默认位置锁已改为 N"));
            assertArrayEquals(new int[]{74,90},raw.get(KeyboardConfig.Profile.NES)[1]);assertArrayEquals(new int[]{82,77},extras.get(KeyboardConfig.Profile.NES));
        }
    }
    @Test void legacyNPrimaryOrAliasKeepsDefaultLookingHotkeyAndShowsReason()throws Exception{
        var before=KeyboardConfig.defaults().withHotkeys(90,296).withPreset(KeyboardConfig.Profile.NES,KeyboardConfig.Preset.LEGACY);
        for(int[] binding:List.of(new int[]{78},new int[]{75,78})){
            var raw=rawLegacy();raw.get(KeyboardConfig.Profile.NES)[0]=binding;
            var loaded=live(before,2,raw,rawExtras());assertEquals(before,loaded.config());assertTrue(loaded.warning().contains("N 已被旧游戏功能使用"));assertArrayEquals(binding,raw.get(KeyboardConfig.Profile.NES)[0]);
        }
    }
    @Test void legacyNExtraFunctionIsNeverReassignedForMigration()throws Exception{
        var before=KeyboardConfig.defaults().withHotkeys(90,296).withPreset(KeyboardConfig.Profile.NES,KeyboardConfig.Preset.LEGACY);
        var extras=rawExtras();extras.put(KeyboardConfig.Profile.NES,new int[]{78,77});
        var loaded=live(before,2,rawLegacy(),extras);assertEquals(before,loaded.config());assertTrue(loaded.warning().contains("N 已被旧游戏功能使用"));assertArrayEquals(new int[]{78,77},extras.get(KeyboardConfig.Profile.NES));
    }
    @Test void missingLiveBindingsCannotAuthorizeMigration()throws Exception{
        var before=KeyboardConfig.defaults().withHotkeys(90,296).withPreset(KeyboardConfig.Profile.NES,KeyboardConfig.Preset.LEGACY);
        var raw=rawLegacy();raw.remove(KeyboardConfig.Profile.NES);assertEquals(before,live(before,2,raw,rawExtras()).config());
        var extras=rawExtras();extras.remove(KeyboardConfig.Profile.NES);assertEquals(before,live(before,2,rawLegacy(),extras).config());
    }
    @Test void liveResolutionNeverChangesSchemaThreeExplicitZOrCustomHotkeys()throws Exception{
        var before=KeyboardConfig.defaults().withHotkeys(90,296).withPreset(KeyboardConfig.Profile.NES,KeyboardConfig.Preset.LEGACY);
        assertEquals(before,live(before,3,rawLegacy(),rawExtras()).config());
        for(var pair:List.of(List.of(298,296),List.of(90,299),List.of(297,296))){var explicit=before.withHotkeys(pair.get(0),pair.get(1));assertEquals(explicit,live(explicit,2,rawLegacy(),rawExtras()).config());}
    }
    @Test void explicitCustomProfilesRemainUntouchedEvenWhenNIsFree()throws Exception{
        var before=KeyboardConfig.defaults().withHotkeys(90,296).withPreset(KeyboardConfig.Profile.NES,KeyboardConfig.Preset.LEGACY)
                .withCustom(KeyboardConfig.Profile.SFC,KeyboardConfig.presetKeys(KeyboardConfig.Profile.SFC,KeyboardConfig.Preset.WASD));
        assertEquals(before,live(before,2,rawLegacy(),rawExtras()).config());
    }
    @Test void liveMigrationOnlyPersistsOnExplicitSave()throws Exception{
        var before=KeyboardConfig.defaults().withHotkeys(90,296).withPreset(KeyboardConfig.Profile.NES,KeyboardConfig.Preset.LEGACY);
        var loaded=live(before,2,rawLegacy(),rawExtras());assertEquals(78,loaded.config().toggleKey());
        KeyboardConfigStore.save(dir,loaded.config(),loaded.revision());assertTrue(Files.readString(KeyboardConfigStore.path(dir)).contains("version=3"));
        assertEquals(loaded.config(),KeyboardConfigStore.load(dir,rawLegacy(),rawExtras()).config());assertFalse(Files.exists(dir.resolve("options.txt")));
    }

    @Test void newlyExplicitZRemainsZAcrossSaveReload()throws Exception{
        var explicit=KeyboardConfig.defaults().withHotkeys(90,296);
        assertEquals(explicit,KeyboardConfigStore.decode(KeyboardConfigStore.encode(explicit)));
        var saved=KeyboardConfigStore.save(dir,explicit,"missing");
        assertEquals(explicit,KeyboardConfigStore.load(dir).config());assertEquals("",saved.warning());
    }

    @Test void defaultNLeavesLegacyZXAliasesUntouchedAcrossEveryProfile(){
        var config=KeyboardConfig.defaults();
        for(var profile:KeyboardConfig.Profile.values()){
            int[][] raw=new int[profile.bits][];for(int i=0;i<raw.length;i++)raw[i]=new int[]{-1};
            raw[0]=new int[]{75,88};raw[1]=new int[]{74,90};
            var effective=KeyboardConfig.effectiveLegacyKeys(profile,raw,new int[0],config.toggleKey(),config.settingsKey());
            assertArrayEquals(raw[0],effective[0]);assertArrayEquals(raw[1],effective[1]);
            var state=new KeyboardControlState(KeyboardConfig.Preset.LEGACY,effective);
            for(var mode:KeyboardControlState.Mode.values())for(int key:new int[]{88,90}){
                assertEquals(GAME,KeyboardRouting.route(key,config.settingsKey(),config.toggleKey(),true,true,true,mode,state.gameKey(key),state.directionOnly(key),false));
                assertEquals(PASS,KeyboardRouting.route(key,config.settingsKey(),config.toggleKey(),true,false,false,mode,true,false,false));
            }
            state.activate(true,key->false);state.key(88,1);state.key(90,1);assertEquals(3,state.mask(0));
            assertEquals(TOGGLE,KeyboardRouting.route(78,296,78,true,true,true,state.mode(),false,false,false));
        }
    }

    @Test void defaultDoesNotConsumeUnassignedZXAsControlHotkeys(){
        var config=KeyboardConfig.defaults();
        for(var mode:KeyboardControlState.Mode.values())for(int key:new int[]{88,90})
            assertEquals(PASS,KeyboardRouting.route(key,config.settingsKey(),config.toggleKey(),true,true,true,mode,false,false,false));
    }

    @Test void statusUsesTheActiveToggleInsteadOfHardcodedOldDefault(){
        var state=new KeyboardControlState(KeyboardConfig.Preset.WASD,new int[][]{{74}});
        assertTrue(state.status("N").contains("按 N 锁定"));state.toggle();
        assertTrue(state.status("N").contains("按 N 解锁"));assertFalse(state.status("N").contains("按 Z"));
    }
}
