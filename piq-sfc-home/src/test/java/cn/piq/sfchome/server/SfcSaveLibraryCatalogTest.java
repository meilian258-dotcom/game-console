// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.server;

import cn.piq.fcarcade.netplay.NetplaySaveState;
import cn.piq.fcarcade.netplay.NetplaySaveStore;
import cn.piq.sfchome.core.SfcNetplayProfile;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SfcSaveLibraryCatalogTest {
    @TempDir Path temporary;
    private final UUID player=UUID.randomUUID(),card=UUID.randomUUID();
    private static final String ROM="a".repeat(64);
    private Path directory(String owner,NetplaySaveState.Identity identity){return temporary.resolve(owner.replace('|','-')).resolve(identity.profile()).resolve(identity.content());}
    private SfcSaveLibraryCatalog catalog(){return new SfcSaveLibraryCatalog(player,card,ROM,this::directory);}
    private NetplaySaveState.Identity identity(boolean legacy,String rom){return NetplaySaveState.identity((legacy?SfcNetplayProfile.legacy():SfcNetplayProfile.current()).template(),rom,Map.of());}
    private Path save(UUID person,UUID cartridge,int mode,boolean legacy,String rom)throws Exception{
        var identity=identity(legacy,rom);Path target=directory(SfcHomeStartPolicy.saveOwner(mode,person,cartridge),identity);
        byte[] bytes=NetplaySaveState.encode(new NetplaySaveState.Parts(identity,10,new byte[]{1,2,3},new byte[]{4},new byte[0]));
        try(var store=new NetplaySaveStore(target,identity)){store.write(bytes);}
        return target.resolve("checkpoint.bin");
    }
    private Map<String,String> snapshot()throws Exception{
        var result=new TreeMap<String,String>();try(var files=Files.walk(temporary)){for(Path path:files.filter(Files::isRegularFile).toList())result.put(temporary.relativize(path).toString(),NetplaySaveState.hash(Files.readAllBytes(path)));}return result;
    }
    @Test void absentEntriesDoNotCreateDirectoriesOrPretendSlotsExist()throws Exception{
        var listing=catalog().inspect();assertTrue(listing.rows().isEmpty());assertEquals(0,listing.unreadable());
        assertTrue(listing.message().contains("只读"));assertTrue(listing.message().contains("不支持重命名或删除"));
        try(var files=Files.list(temporary)){assertEquals(0,files.count());}
    }
    @Test void exactCurrentAndLegacyOwnerFilesAreListedWithoutMutatingAnyFile()throws Exception{
        for(int mode:new int[]{1,2})for(boolean legacy:new boolean[]{false,true})save(player,card,mode,legacy,ROM);
        var before=snapshot();var rows=catalog().inspect().rows();assertEquals(4,rows.size());
        assertEquals(Set.of("personal-snes9x","personal-mesen-legacy","card-snes9x","card-mesen-legacy"),rows.stream().map(SfcSaveLibraryCatalog.Row::id).collect(java.util.stream.Collectors.toSet()));
        assertEquals(4,rows.stream().map(SfcSaveLibraryCatalog.Row::slotKey).distinct().count());
        assertEquals(2,rows.stream().filter(row->row.owner().equals("本人")).count());
        assertEquals(2,rows.stream().filter(row->row.name().contains("回滚风险")).count());
        for(var row:rows){assertTrue(row.bytes()>0);assertTrue(row.modified()>0);assertTrue(row.version().matches("[0-9a-f]{64}"));}
        assertEquals(before,snapshot());
    }
    @Test void otherPeopleCardsAndGamesNeverEnterTheListing()throws Exception{
        save(UUID.randomUUID(),card,1,false,ROM);save(player,UUID.randomUUID(),2,false,ROM);
        save(player,card,1,false,"b".repeat(64));save(player,card,2,true,"c".repeat(64));
        assertTrue(catalog().inspect().rows().isEmpty());assertEquals(0,catalog().inspect().unreadable());
    }
    @Test void corruptFileIsReportedAndKeptWhileValidOtherOwnerRemainsVisible()throws Exception{
        Path corrupt=save(player,card,1,false,ROM);Files.write(corrupt,new byte[]{1,2,3});save(player,card,2,false,ROM);
        var before=snapshot();var listing=catalog().inspect();assertEquals(1,listing.unreadable());assertEquals(1,listing.rows().size());
        assertEquals("card-snes9x",listing.rows().getFirst().id());assertTrue(listing.message().contains("无法校验"));assertEquals(before,snapshot());
    }
    @Test void wrongIdentityIsNeverPresentedAsValidProgress()throws Exception{
        Path legacy=save(player,card,1,true,ROM),current=save(player,card,1,false,ROM);
        Files.copy(legacy,current,StandardCopyOption.REPLACE_EXISTING);var before=snapshot();
        var listing=catalog().inspect();assertEquals(1,listing.unreadable());assertEquals(1,listing.rows().size());assertEquals("personal-mesen-legacy",listing.rows().getFirst().id());assertEquals(before,snapshot());
    }
    @Test void backupFilesAreNotInventedAsExtraSlots()throws Exception{
        Path checkpoint=save(player,card,1,false,ROM);Files.copy(checkpoint,checkpoint.resolveSibling("checkpoint.bak"));
        assertEquals(1,catalog().inspect().rows().size());
    }
    @Test void redirectedCheckpointIsRejectedWithoutFollowingIt()throws Exception{
        Path real=save(player,card,1,false,ROM);Path target=directory(SfcHomeStartPolicy.saveOwner(2,player,card),identity(false,ROM));Files.createDirectories(target);
        try{Files.createSymbolicLink(target.resolve("checkpoint.bin"),real);}catch(FileSystemException|UnsupportedOperationException unavailable){org.junit.jupiter.api.Assumptions.abort("Symbolic links unavailable: "+unavailable.getClass().getSimpleName());}
        var listing=catalog().inspect();assertEquals(1,listing.unreadable());assertEquals(1,listing.rows().size());
    }
    @Test void allFourDirectoriesMustRemainIndependent(){assertThrows(IllegalArgumentException.class,()->new SfcSaveLibraryCatalog(player,card,ROM,(owner,id)->temporary));}
}
