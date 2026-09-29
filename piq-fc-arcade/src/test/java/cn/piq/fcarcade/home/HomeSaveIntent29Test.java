package cn.piq.fcarcade.home;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class HomeSaveIntent29Test {
    @Test void exactConnectionIdentityNotEquals(){var c=new String("c");var id=UUID.randomUUID();var g=new HomeSaveIntent<>(id,c,100);assertTrue(g.valid(id,c,1));assertFalse(g.valid(id,new String("c"),1));assertFalse(g.valid(UUID.randomUUID(),c,1));}
    @Test void deadlineInclusiveAndNegativeTimeReject(){var c=new Object();var id=UUID.randomUUID();var g=new HomeSaveIntent<>(id,c,100);assertTrue(g.valid(id,c,99));assertFalse(g.valid(id,c,100));assertFalse(g.valid(id,c,-1));}
    @Test void consumedSelectionCannotPlayOrDeleteAgain(){var c=new Object();var id=UUID.randomUUID();var g=new HomeSaveIntent<>(id,c,100);assertTrue(g.consume(id,c,1));assertFalse(g.consume(id,c,2));assertFalse(g.valid(id,c,2));assertTrue(g.expired(2));}
    @Test void freshIntentForSameDeviceCannotAcceptOldSelection(){var c=new Object();var old=UUID.randomUUID();var fresh=UUID.randomUUID();var g=new HomeSaveIntent<>(old,c,100);assertTrue(g.consume(old,c,1));var next=new HomeSaveIntent<>(fresh,c,100);assertFalse(next.consume(old,c,2));assertTrue(next.consume(fresh,c,2));}
    @Test void staleCandidateDoesNotConsumeCurrentToken(){var c=new Object();var id=UUID.randomUUID();var g=new HomeSaveIntent<>(id,c,100);assertFalse(g.consume(UUID.randomUUID(),c,1));assertFalse(g.consume(id,new Object(),1));assertTrue(g.consume(id,c,2));}
}
