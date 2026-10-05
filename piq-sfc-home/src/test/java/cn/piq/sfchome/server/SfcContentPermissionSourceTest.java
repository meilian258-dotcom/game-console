package cn.piq.sfchome.server;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SfcContentPermissionSourceTest {
    private String source(String name)throws Exception{return Files.readString(Path.of("src/main/java/cn/piq/sfchome/"+name+".java"));}
    @Test void editorEntryAndLifetimeUseSharedBrowsePermission()throws Exception{
        String s=source("server/SfcCartridgeEditorService");
        assertTrue(s.contains("if(!PlayerContentAccess.canBrowse(p))"));
        assertTrue(s.contains("||!PlayerContentAccess.canBrowse(p)||p.connection.getConnection()!=e.connection"));
        assertTrue(s.contains("p.getServer().getPlayerList().getPlayer(p.getUUID())!=p"));
        assertTrue(s.contains("e.level.getBlockEntity(e.pos)!=e.computer"));
        assertTrue(s.contains("ItemStack.isSameItemSameComponents(e.stack,e.snapshot)"));
    }
    @Test void uploadedTypesHaveIndependentLiveAuthority()throws Exception{
        String s=source("server/SfcCartridgeEditorService");
        assertTrue(s.contains("cover?PlayerContentAccess.canUploadCover(p):PlayerContentAccess.canUploadRom(p)"));
        assertTrue(s.contains("boolean cover=a.operation()==SfcHomeNetwork.COVER_START;if(!uploadAllowed(p,cover))"));
        int handle=s.indexOf("public static void handle(");int dispatch=s.indexOf("switch(a.operation())",handle);
        assertTrue(s.substring(handle,dispatch).contains("e.upload!=null&&!uploadAllowed(p,e.uploadCover)"),"CHUNK and FINISH must be checked before dispatch too");
        assertTrue(s.substring(s.indexOf("public static void tick(")).contains("e.upload!=null&&!uploadAllowed(p,e.uploadCover)"));
    }
    @Test void revokedBufferedUploadReleasesOnlyItsBudgetAndIgnoresLateFragments()throws Exception{
        String s=source("server/SfcCartridgeEditorService");
        String abort=s.substring(s.indexOf("private static void abortUpload("),s.indexOf("private static boolean commitUploadAllowed("));
        assertTrue(abort.contains("e.abortedUploadHash=e.uploadHash;st.uploadBudget.release(e.upload.length);e.upload=null;"));
        assertTrue(s.contains("e.upload==null&&a.hash().equals(e.abortedUploadHash)"));
        assertFalse(abort.contains("Files.delete"));
    }
    @Test void submittedStorageCannotApplyToCardAfterObservedRevocation()throws Exception{
        String s=source("server/SfcCartridgeEditorService");
        assertTrue(s.contains("if(e.committingCover!=null&&!uploadAllowed(p,e.committingCover))e.commitRevoked=true;"));
        assertTrue(s.contains("if(e.commitRevoked||!uploadAllowed(p,cover))"));
        assertTrue(s.contains("stored->{if(!commitUploadAllowed(p,e,cover))return;SfcCartridgeData.setCover"));
        assertTrue(s.contains("if(!commitUploadAllowed(p,e,cover))return;if(catalog.stream().noneMatch"));
        assertTrue(s.contains("()->{e.committingCover=null;e.commitRevoked=false;st.uploadBudget.release(data.length);}"));
        assertTrue(s.contains("finally{completed.run();}"));
    }
    @Test void browsingNeverAddsRawRomDownloadOrAdminSettingsAuthority()throws Exception{
        String s=source("server/SfcCartridgeEditorService");
        String download=s.substring(s.indexOf("private static boolean downloadAuthorized("),s.indexOf("public static void download("));
        assertTrue(download.contains("if(SfcHomeServer.authorizedRom(p,hash)"));
        assertTrue(download.contains("WatchNetplay.authorizedRom(p,cn.piq.sfchome.SfcHomeMod.CABINET_BACKEND,hash)"));
        assertFalse(download.contains("canBrowse"));
        assertTrue(download.contains("return p.hasPermissions(2)&&e!=null&&valid(p,e)&&"));
        assertTrue(s.contains("if(players&&!p.hasPermissions(2))"));
        assertTrue(s.contains("PlayerContentAccess.canBrowse(p)?catalog:List.of()"));
    }
    @Test void editorWireCarriesValidatedCapabilitiesAndOldConstructorsDenyByDefault()throws Exception{
        String s=source("net/SfcHomeNetwork");
        assertTrue(s.contains("TrafficPayloadRegistrar.create(event,\"13\")"));
        assertTrue(s.contains("boolean explicitPlayers,int capabilities,List<RomEntry> catalog"));
        assertTrue(s.contains("b.writeByte(v.capabilities)"));
        assertTrue(s.contains("SfcEditorPermissions.checked(b.readUnsignedByte())"));
        assertTrue(s.contains("coverSha,maxPlayers,explicitPlayers,0,catalog"));
        assertTrue(s.contains("title,\"\",2,false,0,catalog"));
    }
    @Test void existingServerResourcesUseTheirOwnCapabilitiesAtAsyncCommit()throws Exception{
        String s=source("server/SfcCartridgeEditorService");
        assertTrue(s.contains("case SfcHomeNetwork.USE_COVER"));assertTrue(s.contains("!e.covers.contains(a.hash())"));
        assertTrue(s.contains("if(!PlayerContentAccess.canUseServerCover(p)){reply"));
        assertEquals(2,s.split(java.util.regex.Pattern.quote("needsPermission&&!PlayerContentAccess.canUseServerRom(p)"),-1).length-1);
        assertTrue(s.contains("if(!valid(p,e)){cancel(p,st,e,\"授权失效，旧卡带未改动\");return;}"));
        assertTrue(s.contains("PlayerContentAccess.canUseServerCover(p)?e.covers:List.of()"));
        assertFalse(s.contains("isCreative()"));
    }
    @Test void clientButtonsAndAsyncImportUseLatestServerCapabilities()throws Exception{
        String s=source("client/SfcCardEditorScreen");
        assertTrue(s.contains("SfcEditorPermissions.upload(data.capabilities(),cover)"));
        assertTrue(s.contains("writeButton.active=!busy()&&canWrite(selected)"));
        assertTrue(s.contains("playersButton.active=!busy()&&canAdmin()"));
        assertTrue(s.contains("if(!canUpload(scanCovers))"));
        assertTrue(s.contains("if(!canUpload(importingCover))"));
        assertTrue(s.contains("phase==Phase.WAIT_UPLOAD&&upload!=null&&canUpload(uploadCover)"));
    }
    @Test void permissionOnlyPushDoesNotAcknowledgeAnotherUpload()throws Exception{
        String s=source("client/SfcCardEditorScreen");
        int start=s.indexOf("if(message.message().equals(\"PERMISSIONS_UPDATED\"))");
        int end=s.indexOf("if(message.message().equals(\"UPLOAD_READY\"))",start);
        String push=s.substring(start,end);
        assertTrue(push.contains("phase==Phase.FINISHING&&!canUpload(uploadCover)"));
        assertTrue(push.contains("rebuildRows();return;"));
        assertFalse(push.contains("send(SfcHomeNetwork.WRITE"));
        assertTrue(s.contains("entry.local()?\"本地待上传\":\"服务器库\""));
    }
}
