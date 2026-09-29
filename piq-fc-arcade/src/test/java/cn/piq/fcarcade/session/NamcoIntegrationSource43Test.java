package cn.piq.fcarcade.session;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Complements executable identity/codec/store tests with actual MC integration wiring. */
class NamcoIntegrationSource43Test {
    private static String source(String path)throws Exception{return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/"+path+".java"));}
    private static String section(String text,String start,String end){int a=text.indexOf(start),b=text.indexOf(end,a);assertTrue(a>=0&&b>a);return text.substring(a,b).replaceAll("\\s+","");}
    @Test void bothSessionEntryPathsSelectFromServerIndexedRom()throws Exception{
        String s=source("server/ServerArcadeSessions");
        assertTrue(s.contains("return cn.piq.fcarcade.session.NesCoreVariant.forRom(descriptor.header(),gun)"));
        assertTrue(s.contains("s.homeConsole=true;s.playerMedia=c.synchronizationMode()==CabinetSyncMode.MEDIA;s.variant=coreVariant(p.getServer(),rom,gun)"));
        assertTrue(s.contains("session.variant=coreVariant(player.getServer(),romSha256,false)"));
        String power=section(s,"public static boolean powerHomeConsole(","public static void homeSaveAction(");
        assertTrue(power.indexOf("m.coreVariant(player.getServer(),rom,gun)")<power.indexOf("m.openHomeSaveSlots"));
        assertTrue(power.contains("catch(IllegalArgumentExceptionincompatible)"));
    }
    @Test void homeAndCabinetSlotsUseTheSameQualifiedNamespaceWithoutOldMigration()throws Exception{
        String s=source("server/ServerArcadeSessions");
        assertTrue(s.contains("coreVariant(server,rom,gun).saveKey(key)"));
        assertTrue(s.contains("homeSaveKey(p.getServer(),c,rom,gun,playerSlotKey(p.getUUID(),slot))"));
        assertTrue(s.contains("homeSaveKey(p.getServer(),c,r.rom(),r.gun(),playerSlotKey(p.getUUID(),a.slot()))"));
        assertTrue(s.contains("selectedVariant.saveKey(playerSlotKey(player.getUUID(), slot))"));
        assertTrue(s.contains("coreVariant(server,romSha256,false).saveKey(saveKey(key, saveMode, playerId))"));
        assertTrue(s.contains("if(selectedVariant==cn.piq.fcarcade.session.NesCoreVariant.LEGACY)migratePlayerSavesToGlobalSlots"));
        assertTrue(s.contains("catalogPlayerKey(save.saveKey()).startsWith(\"player|\")"));
    }
    @Test void hostedAndClientWorkersUseTheSameFactoryAndValidateIdentity()throws Exception{
        String client=source("client/ClientArcadeSession"),server=source("server/hosted/NesServerCoreFactory");
        assertTrue(client.contains("NesCores.create(expectedVariant)"));
        assertTrue(client.contains("forRom(rom.header(),expectedVariant.isZapper())!=expectedVariant"));
        assertTrue(client.contains("created.stateNamespace().equals(expectedVariant.stateNamespace())"));
        assertTrue(server.contains("NesCores.create(selected)"));assertTrue(server.contains("NesCores.moduleResource(selected)"));
        assertTrue(server.contains("context.nesVariant()!=selected&&(managed!=null||context.nesVariant()!=NesCoreVariant.LEGACY)"));
        assertTrue(server.contains("core.stateNamespace().equals(selected.stateNamespace())"));
    }
    @Test void snapshotUploadHostedCaptureAndRestoreKeepExplicitCoreHeaderGates()throws Exception{
        String s=source("server/ServerArcadeSessions");
        assertTrue(section(s,"private void collectHostedSnapshot(","private boolean tickHosted(").contains("session.variant.acceptsPersistentStateHeader(state,session.romSha256)"));
        assertTrue(s.contains("session.variant.acceptsPersistentStateHeader(payload.state(),session.romSha256)"));
        assertTrue(section(s,"private byte[] loadState(","private boolean saveState(").contains("session.variant.acceptsPersistentStateHeader(state,session.romSha256)"));
        assertTrue(s.contains("NesPersistentState.snapshot(session.snapshot)"), "Spectators only receive the transient snapshot");
    }
}
