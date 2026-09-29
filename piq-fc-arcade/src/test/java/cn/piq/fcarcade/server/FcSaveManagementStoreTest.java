package cn.piq.fcarcade.server;

import cn.piq.fcarcade.server.hosted.ServerCoreFiles;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FcSaveManagementStoreTest {
    private static final String ROM="a".repeat(64),OTHER="b".repeat(64);
    private static final UUID OWNER=UUID.fromString("b00132d0-3be9-4fb7-8efe-c50c150679b0"),OTHER_PLAYER=UUID.fromString("b00132d0-3be9-4fb7-8efe-c50c150679b1");
    @TempDir Path root;
    private String key(UUID owner,int slot){return "player|"+owner+"|global-slot|"+slot;}
    private ArcadeSaveStore old(){return new ArcadeSaveStore(root);}
    private FcSaveManagementStore managed(){return new FcSaveManagementStore(root);}
    private FcSaveManagementStore.Row first()throws Exception{return managed().list(ROM,OWNER,false).rows().getFirst();}
    @Test void filtersCurrentRomAndExactPlayerButOperatorMaySeeAllOwners()throws Exception{
        old().save(key(OWNER,1),ROM,new byte[]{1},"我的",2);old().save(key(OTHER_PLAYER,1),ROM,new byte[]{2},"其他玩家",1);old().save(key(OWNER,2),OTHER,new byte[]{3},"其他游戏",1);
        assertEquals(1,managed().list(ROM,OWNER,false).rows().size());assertEquals(2,managed().list(ROM,OWNER,true).rows().size());assertEquals("我的",first().name());
    }
    @Test void ownedPrefixesRecognizeExistingMediaNamespacesOnly()throws Exception{
        for(String prefix:List.of("","server-home-v1|","player-home-v1|","core|piq-fc-zapper-v1|")){
            String expected=prefix.startsWith("core|")?"":OWNER.toString();assertEquals(expected,FcSaveManagementStore.owner(prefix+key(OWNER,1)));
        }
        assertEquals("",FcSaveManagementStore.owner("forged|"+key(OWNER,1)));
        old().save("minecraft:overworld|1,2,3|LOCKSTEP",ROM,new byte[]{1},"机器进度",1);
        assertTrue(managed().list(ROM,OWNER,false).rows().isEmpty());assertEquals(1,managed().list(ROM,OWNER,true).rows().size());
    }
    @Test void renamePreservesStatePlayersKeyRomAndV3Format()throws Exception{
        byte[] state={1,5,8,0,99};old().save(key(OWNER,1),ROM,state,"旧名",2);var row=first();
        managed().rename(row.id(),row.version(),"新名字");var updated=first();
        assertEquals("新名字",updated.name());assertEquals(row.key(),updated.key());assertEquals(ROM,updated.rom());assertEquals(2,updated.players());assertEquals(3,updated.format());assertNotEquals(row.version(),updated.version());assertArrayEquals(state,old().loadReadOnly(row.key(),ROM));
    }
    @Test void staleRenameAndDeleteDoNotOverwriteNewProgress()throws Exception{
        old().save(key(OWNER,1),ROM,new byte[]{1},"before",1);var row=first();old().save(row.key(),ROM,new byte[]{9},"changed",2);
        assertThrows(IOException.class,()->managed().rename(row.id(),row.version(),"wrong"));assertThrows(IOException.class,()->managed().delete(row.id(),row.version()));
        assertArrayEquals(new byte[]{9},old().loadReadOnly(row.key(),ROM));assertEquals("changed",first().name());
    }
    @Test void deleteMovesExactlyOneFileToRecoveryAndRepeatedDeleteCannotAffectAnother()throws Exception{
        old().save(key(OWNER,1),ROM,new byte[]{1},"first",1);old().save(key(OWNER,2),ROM,new byte[]{2},"other",1);
        var row=managed().list(ROM,OWNER,false).rows().stream().filter(r->r.name().equals("first")).findFirst().orElseThrow();byte[] original=Files.readAllBytes(root.resolve(row.id()+".sav"));
        managed().delete(row.id(),row.version());assertFalse(Files.exists(root.resolve(row.id()+".sav")));
        try(var recovered=Files.list(root.resolve("deleted-by-cartridge-manager"))){var copies=recovered.toList();assertEquals(1,copies.size());assertTrue(copies.getFirst().getFileName().toString().startsWith(row.id()+"-"+row.version()+"-"));assertArrayEquals(original,Files.readAllBytes(copies.getFirst()));}
        assertThrows(IOException.class,()->managed().delete(row.id(),row.version()));assertEquals(1,managed().list(ROM,OWNER,false).rows().size());assertArrayEquals(new byte[]{2},old().loadReadOnly(key(OWNER,2),ROM));
    }
    @Test void existingRecoveryCopyIsNeverOverwritten()throws Exception{
        old().save(key(OWNER,1),ROM,new byte[]{1},"safe",1);var row=first();var recovery=root.resolve("deleted-by-cartridge-manager");Files.createDirectory(recovery);var target=recovery.resolve(row.id()+"-"+row.version()+".sav");Files.write(target,new byte[]{44});
        managed().delete(row.id(),row.version());assertFalse(Files.exists(root.resolve(row.id()+".sav")));assertArrayEquals(new byte[]{44},Files.readAllBytes(target));try(var recovered=Files.list(recovery)){assertEquals(2,recovered.count());}
    }
    @Test void corruptOriginalIsNeitherListedQuarantinedNorModified()throws Exception{
        old().save(key(OWNER,1),ROM,new byte[]{1,2,3},"safe",1);var row=first();Path file=root.resolve(row.id()+".sav");byte[] corrupt=Files.readAllBytes(file);corrupt[corrupt.length-1]^=3;Files.write(file,corrupt);
        assertTrue(managed().list(ROM,OWNER,false).rows().isEmpty());assertThrows(IOException.class,()->managed().rename(row.id(),row.version(),"bad"));assertThrows(IOException.class,()->managed().delete(row.id(),row.version()));assertArrayEquals(corrupt,Files.readAllBytes(file));try(var paths=Files.list(root)){assertEquals(1,paths.count());}
    }
    @Test void renamedStorageIdentityIsRejectedEvenWithValidEnvelope()throws Exception{
        old().save(key(OWNER,1),ROM,new byte[]{1},"safe",1);var row=first();Files.move(root.resolve(row.id()+".sav"),root.resolve("f".repeat(64)+".sav"));assertTrue(managed().list(ROM,OWNER,true).rows().isEmpty());
    }
    @Test void pathTraversalAndUnboundedOrControlNamesAreRejected()throws Exception{
        old().save(key(OWNER,1),ROM,new byte[]{1},"safe",1);var row=first();assertThrows(IllegalArgumentException.class,()->managed().current("../secret"));assertThrows(IllegalArgumentException.class,()->managed().list("bad",OWNER,true));
        for(String name:List.of("x".repeat(33),"line\nbreak"))assertThrows(IllegalArgumentException.class,()->managed().rename(row.id(),row.version(),name));assertThrows(IOException.class,()->managed().rename(row.id(),row.version(),"   "));assertEquals(row.version(),first().version());
    }
    @Test void unrelatedLargeRomsDoNotConsumeMatchingPayloadBudget()throws Exception{
        for(int i=0;i<6;i++)old().save("machine-"+i,OTHER,new byte[2*1024*1024],"other",1);
        old().save(key(OWNER,1),ROM,new byte[]{5},"mine",1);var list=managed().list(ROM,OWNER,false);assertFalse(list.truncated());assertEquals(1,list.rows().size());assertEquals("mine",list.rows().getFirst().name());
    }
    @Test void matchingPayloadBudgetExplicitlyMarksTruncation()throws Exception{
        for(int i=0;i<6;i++)old().save("machine-"+i,ROM,new byte[2*1024*1024],"large",1);
        var list=managed().list(ROM,OWNER,true);assertTrue(list.truncated());assertEquals(3,list.rows().size());
    }
    @Test void scanLimitIsExplicitEvenIfNoMatchFound()throws Exception{
        for(int i=0;i<513;i++)Files.write(root.resolve("ignored-"+i),new byte[0]);var list=managed().list(ROM,OWNER,false);assertTrue(list.rows().isEmpty());assertTrue(list.truncated());
    }
    @Test void versionTwoRemainsReadOnlyWithoutAutomaticMigration()throws Exception{
        String key=key(OWNER,1);Path file=old().path(key,ROM);byte[] state={1,2};try(var out=new DataOutputStream(Files.newOutputStream(file))){out.writeInt(0x50465153);out.writeInt(2);out.writeUTF(key);out.writeUTF(ROM);out.writeInt(state.length);out.write(HexFormat.of().parseHex(ServerCoreFiles.sha256(state)));out.write(state);}
        var row=first();assertEquals(2,row.format());assertThrows(IOException.class,()->managed().rename(row.id(),row.version(),"no"));assertThrows(IOException.class,()->managed().delete(row.id(),row.version()));assertEquals(row.version(),first().version());
    }
}
