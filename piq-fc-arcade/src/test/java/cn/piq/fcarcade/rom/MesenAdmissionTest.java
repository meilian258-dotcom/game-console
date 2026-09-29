package cn.piq.fcarcade.rom;

import cn.piq.fcarcade.session.NesCoreVariant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class MesenAdmissionTest {
    @TempDir Path directory;

    public static byte[] rom(int mapper, int submapper, boolean nes2, int prgBanks, int chrBanks) {
        byte[] bytes = new byte[16 + prgBanks * 16384 + chrBanks * 8192];
        bytes[0]='N'; bytes[1]='E'; bytes[2]='S'; bytes[3]=26;
        bytes[4]=(byte)prgBanks; bytes[5]=(byte)chrBanks;
        bytes[6]=(byte)((mapper & 15) << 4);
        bytes[7]=(byte)((mapper & 240) | (nes2 ? 8 : 0));
        if(nes2)bytes[8]=(byte)((submapper << 4) | (mapper >>> 8));
        return bytes;
    }

    @Test void reportedBoardsParseSelectAndStoreWithoutChangingOriginalBytes() throws Exception {
        var repo=new RomRepository(directory);
        int[][] boards={{25,1,1,8,16},{69,0,0,16,16},{66,0,0,8,4},{85,0,0,32,0}};
        for(var board:boards){
            byte[] bytes=rom(board[0],board[1],board[2]==1,board[3],board[4]);
            var header=INesHeader.parse(bytes);
            assertEquals(board[0],header.mapper()); assertEquals(board[1],header.subMapper());
            assertTrue(NesCompatibility.isSupported(header));
            assertEquals("",NesCompatibility.unsupportedReason(header.mapper()));
            assertEquals(NesCoreVariant.LIBRETRO_V1,NesCoreVariant.forRom(header,false));
            assertEquals(NesCoreVariant.LIBRETRO_ZAPPER_V1,NesCoreVariant.forRom(header,true));
            assertThrows(IllegalArgumentException.class,()->NesCompatibility.requireLegacySupported(header));
            var stored=repo.storeVerified("board-"+board[0]+".nes",RomRepository.sha256(bytes),bytes);
            assertArrayEquals(bytes,Files.readAllBytes(stored.path()));
            assertEquals(header,repo.load(stored.fileName()).header());
        }
    }

    @Test void tableCoversModernBoardsButRejectsMissingOrUnimplementedIds(){
        for(int mapper:new int[]{0,1,2,3,4,5,19,23,25,66,69,85,140,210,258,559})
            assertTrue(NesCompatibility.isMapperSupported(mapper),"Mapper "+mapper);
        for(int mapper:new int[]{-1,20,84,186,256,257,342,4095,4096,Integer.MAX_VALUE}){
            assertFalse(NesCompatibility.isMapperSupported(mapper));
            assertTrue(NesCompatibility.unsupportedReason(mapper).contains("Mapper "+mapper));
        }
        assertFalse(NesCompatibility.isSupported(null));
        assertThrows(IllegalArgumentException.class,()->NesCompatibility.requireSupported(null));
    }

    @Test void unsupportedBoardIsStillReadableForDiagnosisButCannotBeStored() throws Exception {
        byte[] bytes=rom(4095,0,true,2,1);
        Files.write(directory.resolve("unknown.nes"),bytes);
        var repo=new RomRepository(directory);
        assertEquals(4095,repo.load("unknown.nes").header().mapper());
        var failure=assertThrows(IllegalArgumentException.class,()->repo.storeVerified("upload.nes",RomRepository.sha256(bytes),bytes));
        assertTrue(failure.getMessage().contains("Mapper 4095"));
        assertFalse(Files.exists(directory.resolve("upload.nes")));
    }

    @Test void modernAdmissionDoesNotBypassHeaderHashOrSizeValidation(){
        var repo=new RomRepository(directory);
        byte[] valid=rom(25,1,true,8,16);
        byte[] truncated=Arrays.copyOf(valid,valid.length-1);
        byte[] badMagic=valid.clone();badMagic[0]=0;
        byte[] noPrg=valid.clone();noPrg[4]=0;
        for(byte[] bytes:new byte[][]{truncated,badMagic,noPrg,new byte[0],new byte[RomRepository.MAX_ROM_BYTES+1]})
            assertThrows(IllegalArgumentException.class,()->repo.storeVerified("bad.nes",RomRepository.sha256(bytes),bytes));
        assertThrows(IllegalArgumentException.class,()->repo.storeVerified("wrong.nes","00".repeat(32),valid));
        assertThrows(IllegalArgumentException.class,()->repo.load("../outside.nes"));
    }
}
