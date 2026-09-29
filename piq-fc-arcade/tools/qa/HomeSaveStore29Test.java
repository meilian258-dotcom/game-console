package cn.piq.fcarcade.server;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
/** Actual-Minecraft API fixture, run only against the submitted JAR with the MC classpath. */
class HomeSaveStore29Test {
    @TempDir Path temporary;
    static final String ROM="ab".repeat(32);
    static Class<?> manager()throws Exception{return Class.forName("cn.piq.fcarcade.server.ServerArcadeSessions$Manager");}
    static String namespace(boolean gun,String raw)throws Exception{var m=manager().getDeclaredMethod("homeSaveKey",boolean.class,String.class);m.setAccessible(true);return(String)m.invoke(null,gun,raw);}
    @Test void ordinaryGlobalSlotKeyIsUnchangedAndGunCannotAliasIt()throws Exception{UUID p=UUID.randomUUID();for(int slot=1;slot<=3;slot++){String plain=PlayerSaveSlots.key(p,slot);assertEquals(plain,namespace(false,plain));assertEquals("core|"+cn.piq.fcarcade.session.NesCoreVariant.ZAPPER_V1.stateNamespace()+"|"+plain,namespace(true,plain));assertNotEquals(namespace(false,plain),namespace(true,plain));}}
    @Test void actualOrdinaryAndGunSlotsCanSaveDeleteIndependently()throws Exception{var store=new ArcadeSaveStore(temporary);String plain=PlayerSaveSlots.key(UUID.randomUUID(),1),gun=namespace(true,plain);store.save(plain,ROM,new byte[]{1,2,3},"普通",1);store.save(gun,ROM,new byte[]{7,8,9},"光枪",2);assertArrayEquals(new byte[]{1,2,3},store.loadReadOnly(plain,ROM));assertArrayEquals(new byte[]{7,8,9},store.loadReadOnly(gun,ROM));store.delete(gun,ROM);assertArrayEquals(new byte[]{1,2,3},store.loadReadOnly(plain,ROM));assertNull(store.loadReadOnly(gun,ROM));}
    @Test void readOnlyCompatibilityDoesNotTouchValidBytesOrMtime()throws Exception{var store=new ArcadeSaveStore(temporary);String key="old-gun";store.save(key,ROM,new byte[]{2,4,6});Path file=store.path(key,ROM);var time=FileTime.fromMillis(123456789000L);Files.setLastModifiedTime(file,time);byte[] before=Files.readAllBytes(file);assertArrayEquals(new byte[]{2,4,6},store.loadReadOnly(key,ROM));assertArrayEquals(before,Files.readAllBytes(file));assertEquals(time,Files.getLastModifiedTime(file));}
    @Test void readOnlyCompatibilityCannotQuarantineOrRenameDamagedOriginal()throws Exception{var store=new ArcadeSaveStore(temporary);var file=store.path("old-gun",ROM);Files.createDirectories(file.getParent());Files.write(file,new byte[]{0,1,2,3});var time=FileTime.fromMillis(123456789000L);Files.setLastModifiedTime(file,time);assertNull(store.loadReadOnly("old-gun",ROM));assertArrayEquals(new byte[]{0,1,2,3},Files.readAllBytes(file));assertEquals(time,Files.getLastModifiedTime(file));try(var files=Files.list(temporary)){assertEquals(List.of(file),files.toList());}}
    @Test void oldLoadStillQuarantinesCorruption()throws Exception{var store=new ArcadeSaveStore(temporary);var file=store.path("ordinary",ROM);Files.write(file,new byte[]{0,1,2,3});assertNull(store.load("ordinary",ROM));assertFalse(Files.exists(file));try(var files=Files.list(temporary)){assertEquals(1,files.filter(f->f.getFileName().toString().contains(".corrupt-")).count());}}
    @Test void writingCanonicalMachineSaveDoesNotAlterOldHostQualifiedSave()throws Exception{var store=new ArcadeSaveStore(temporary);String machine="minecraft:overworld|1,64,2|LOCKSTEP",old=namespace(true,"player|"+UUID.randomUUID()+"|"+machine),next=namespace(true,machine);store.save(old,ROM,new byte[]{3,2,1});Path file=store.path(old,ROM);byte[] before=Files.readAllBytes(file);byte[] state=store.loadReadOnly(old,ROM);store.save(next,ROM,state);assertArrayEquals(before,Files.readAllBytes(file));assertArrayEquals(state,store.loadReadOnly(next,ROM));}
    @Test void actualManagerLocksEmptyPersonalSlotBeforeFirstDiskSnapshot()throws Exception{
        var type=manager();var ctor=type.getDeclaredConstructor();ctor.setAccessible(true);Object m=ctor.newInstance();var field=type.getDeclaredField("sessions");field.setAccessible(true);@SuppressWarnings("unchecked")Map<Object,Object> sessions=(Map<Object,Object>)field.get(m);
        Class<?> keyType=Class.forName("cn.piq.fcarcade.server.ServerArcadeSessions$SessionKey");var keyCtor=keyType.getDeclaredConstructors()[0];keyCtor.setAccessible(true);Object key=keyCtor.newInstance(net.minecraft.world.level.Level.OVERWORLD,net.minecraft.core.BlockPos.ZERO,cn.piq.fcarcade.session.ArcadeMode.LOCKSTEP);
        var sessionType=Class.forName("cn.piq.fcarcade.server.ServerArcadeSessions$Session");var sessionCtor=sessionType.getDeclaredConstructors()[0];sessionCtor.setAccessible(true);UUID player=UUID.randomUUID();String slot=PlayerSaveSlots.key(player,1);
        Object session=sessionCtor.newInstance(1L,key,ROM,2,cn.piq.fcarcade.rom.RomSaveMode.PLAYER,player,slot,"未落盘",1);sessions.put(key,session);
        var active=type.getDeclaredMethod("personalSaveKeyActive",String.class);active.setAccessible(true);assertEquals(true,active.invoke(m,slot));assertEquals(false,active.invoke(m,PlayerSaveSlots.key(player,2)));assertEquals(false,active.invoke(m,namespace(true,slot)));sessions.clear();assertEquals(false,active.invoke(m,slot));
    }
}
