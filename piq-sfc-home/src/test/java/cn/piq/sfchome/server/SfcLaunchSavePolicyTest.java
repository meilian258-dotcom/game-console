package cn.piq.sfchome.server;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class SfcLaunchSavePolicyTest {
    @Test void sharedPresentationDoesNotSwapLegacyOwnership(){
        var player=UUID.randomUUID();var other=UUID.randomUUID();var card=UUID.randomUUID();
        assertEquals(0,SfcHomeStartPolicy.commonSaveMode(0));assertEquals(2,SfcHomeStartPolicy.commonSaveMode(1));assertEquals(1,SfcHomeStartPolicy.commonSaveMode(2));
        assertEquals("sfc-personal|"+player,SfcHomeStartPolicy.saveOwner(1,player,card));
        assertNotEquals(SfcHomeStartPolicy.saveOwner(1,player,card),SfcHomeStartPolicy.saveOwner(1,other,card));
        assertEquals(SfcHomeStartPolicy.saveOwner(2,player,card),SfcHomeStartPolicy.saveOwner(2,other,card));
        assertEquals("sfc-card|"+card,SfcHomeStartPolicy.saveOwner(2,player,card));
        assertThrows(IllegalArgumentException.class,()->SfcHomeStartPolicy.commonSaveMode(3));
        assertThrows(NullPointerException.class,()->SfcHomeStartPolicy.saveOwner(2,player,null));
    }
}
