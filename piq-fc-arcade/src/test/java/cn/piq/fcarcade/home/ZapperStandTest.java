package cn.piq.fcarcade.home;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ZapperStandTest {
    private static ZapperStandLinks.End end(int x,int z){return new ZapperStandLinks.End("minecraft:overworld",x,64,z,UUID.randomUUID());}
    @Test void emptyStandCannotProduceAGun(){var dock=new ZapperDock<Object>();assertNull(dock.take(UUID.randomUUID()));assertNull(dock.remove());}
    @Test void originalObjectIsMovedNotCopied(){var dock=new ZapperDock<Object>();var gun=new Object();var player=UUID.randomUUID();assertTrue(dock.deposit(gun,null));assertFalse(dock.deposit(new Object(),null));assertSame(gun,dock.take(player));assertNull(dock.stored());assertEquals(player,dock.loan().player());assertNull(dock.take(player));}
    @Test void onlyExactIssuedReceiptCanReturn(){var dock=new ZapperDock<Object>();var gun=new Object();dock.deposit(gun,null);dock.take(UUID.randomUUID());var loan=dock.loan();assertFalse(dock.deposit(gun,null));assertFalse(dock.deposit(gun,UUID.randomUUID()));assertTrue(dock.deposit(gun,loan.id()));assertSame(gun,dock.stored());assertNull(dock.loan());assertFalse(dock.deposit(gun,loan.id()));}
    @Test void oldReturnCannotFillANewLoan(){var dock=new ZapperDock<Object>();var gun=new Object();dock.deposit(gun,null);dock.take(UUID.randomUUID());var old=dock.loan();dock.deposit(gun,old.id());dock.take(UUID.randomUUID());assertFalse(dock.deposit(gun,old.id()));}
    @Test void destructionNeverDropsBorrowedGun(){var dock=new ZapperDock<Object>();var gun=new Object();dock.deposit(gun,null);dock.take(UUID.randomUUID());assertNull(dock.remove());assertNull(dock.loan());assertNull(dock.remove());}
    @Test void destructionDropsStoredOriginalExactlyOnce(){var dock=new ZapperDock<Object>();var gun=new Object();dock.deposit(gun,null);assertSame(gun,dock.remove());assertNull(dock.remove());}
    @Test void persistedLoanCannotCoexistWithStoredObject(){var dock=new ZapperDock<Object>();var loan=new ZapperDock.Loan(UUID.randomUUID(),UUID.randomUUID());assertThrows(IllegalArgumentException.class,()->dock.restore(new Object(),loan));dock.restore(null,loan);assertEquals(loan,dock.loan());assertNull(dock.stored());}
    @Test void linkRangeIncludesSixteenNotSeventeen(){assertTrue(ZapperStandLinks.compatible(end(0,0),end(16,0)));assertFalse(ZapperStandLinks.compatible(end(0,0),end(16,1)));assertFalse(ZapperStandLinks.compatible(end(0,0),end(17,0)));}
    @Test void crossDimensionIdentityAndSamePositionRejected(){var a=end(0,0);assertFalse(ZapperStandLinks.compatible(a,new ZapperStandLinks.End("minecraft:the_nether",1,64,0,UUID.randomUUID())));assertFalse(ZapperStandLinks.compatible(a,new ZapperStandLinks.End(a.dimension(),1,64,0,a.id())));assertFalse(ZapperStandLinks.compatible(a,end(0,0)));}
    @Test void eachDeviceHasOneLinkAndStaleIdentityCannotUnlink(){var links=new ZapperStandLinks();var a=end(0,0);var b=end(1,0);var one=links.connect(a,b);assertNotNull(one);assertNull(links.connect(end(2,0),b));assertNull(links.connect(a,end(3,0)));assertNull(links.at(end(0,0)));assertFalse(links.remove(new ZapperStandLinks.Link(one.id(),a,end(1,0))));assertTrue(links.remove(one));}
    @Test void persistedLinkRestoresExactIdentityNoMutableAlias(){var a=new ZapperStandLinks();var link=a.connect(end(0,0),end(1,0));var copy=new ZapperStandLinks();assertTrue(copy.restore(link));assertEquals(link,copy.at(link.console()));assertFalse(copy.restore(link));assertThrows(UnsupportedOperationException.class,()->copy.snapshot().clear());}
    @Test void globalCapacityIsBounded(){var links=new ZapperStandLinks();for(int i=0;i<128;i++)assertNotNull(links.connect(end(i*20,0),end(i*20+1,0)));assertNull(links.connect(end(3000,0),end(3001,0)));assertEquals(128,links.snapshot().size());}
    @Test void invalidPersistedCoordinatesFailClosed(){assertThrows(IllegalArgumentException.class,()->new ZapperStandLinks.End("broken",0,0,0,UUID.randomUUID()));assertThrows(IllegalArgumentException.class,()->new ZapperStandLinks.End("minecraft:overworld",Integer.MAX_VALUE,0,0,UUID.randomUUID()));}
    @Test void yUsesSignedTwelveBitBlockPosRange(){for(int y:new int[]{-2048,2047})assertEquals(y,new ZapperStandLinks.End("minecraft:overworld",0,y,0,UUID.randomUUID()).y());
        for(int y:new int[]{-2049,2048})assertThrows(IllegalArgumentException.class,()->new ZapperStandLinks.End("minecraft:overworld",0,y,0,UUID.randomUUID()));}
}
