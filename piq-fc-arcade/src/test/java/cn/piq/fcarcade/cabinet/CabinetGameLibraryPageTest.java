package cn.piq.fcarcade.cabinet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CabinetGameLibraryPageTest {
    @Test void shrinkingAndEmptyCatalogsAlwaysReturnAnExistingPageBoundary(){
        assertEquals(14,CabinetGameLibraryService.pageOffset(63,20));assertEquals(0,CabinetGameLibraryService.pageOffset(511,0));assertEquals(63,CabinetGameLibraryService.pageOffset(63,64));assertEquals(63,CabinetGameLibraryService.pageOffset(511,65));assertEquals(511,CabinetGameLibraryService.pageOffset(511,512));
        for(int total=0;total<=512;total++)for(int requested=0;requested<=512;requested+=CabinetLibraryPage.SIZE){int offset=CabinetGameLibraryService.pageOffset(requested,total);assertEquals(0,offset%CabinetLibraryPage.SIZE);assertTrue(offset>=0&&offset<=requested);assertTrue(total==0?offset==0:offset<total);}
    }
    @Test void malformedRequestOrCatalogCountsFailClosed(){for(int bad:new int[]{-1,1,64,512,513,576})assertThrows(IllegalArgumentException.class,()->CabinetGameLibraryService.pageOffset(bad,512));for(int total:new int[]{-1,513})assertThrows(IllegalArgumentException.class,()->CabinetGameLibraryService.pageOffset(0,total));}
    @Test void everyPageFitsSenderBudgetIncludingThePreviouslyBrokenEightGameLibrary(){
        assertTrue(1024+8*4096>32768);
        for(int total:new int[]{0,1,7,8,64,65,511,512}){
            int seen=0;
            do{int count=Math.min(CabinetLibraryPage.SIZE,total-seen);assertTrue(CabinetLibraryPage.conservativeBytes(count)<=32768);seen+=count;}while(seen<total);
            assertEquals(total,seen);
        }
        assertThrows(IllegalArgumentException.class,()->CabinetLibraryPage.conservativeBytes(8));
        assertThrows(IllegalArgumentException.class,()->CabinetLibraryPage.conservativeBytes(-1));
    }
}
