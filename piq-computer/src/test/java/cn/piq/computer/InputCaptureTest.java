package cn.piq.computer;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class InputCaptureTest {
    @Test void entryClickMustRelease(){var s=new InputCapture();s.begin(Set.of(),Set.of(1));assertTrue(s.active());assertFalse(s.armed());s.sample(Set.of(),Set.of(1));assertFalse(s.armed());assertTrue(s.button(1,0));s.sample(Set.of(),Set.of());assertTrue(s.armed());}
    @Test void heldKeysMustRelease(){var s=new InputCapture();s.begin(Set.of(87),Set.of());assertTrue(s.key(87,2));s.sample(Set.of(87),Set.of());assertFalse(s.armed());s.key(87,0);s.sample(Set.of(),Set.of());assertTrue(s.armed());}
    @Test void exitDrainsOldPressOnly(){var s=new InputCapture();s.begin(Set.of(),Set.of());s.sample(Set.of(),Set.of());s.key(87,1);s.button(0,1);s.end();assertFalse(s.key(65,1));assertTrue(s.key(87,2));assertTrue(s.key(87,0));assertFalse(s.key(87,1));assertTrue(s.button(0,0));assertFalse(s.button(0,1));}
    @Test void focusReturnPollingClearsReleasedKeys(){var s=new InputCapture();s.begin(Set.of(87),Set.of(1));s.end();s.sample(Set.of(),Set.of());assertFalse(s.key(87,1));assertFalse(s.button(1,1));}
    @Test void aNewSessionResetsEntryState(){var s=new InputCapture();s.begin(Set.of(),Set.of());s.sample(Set.of(),Set.of());s.end();s.begin(Set.of(65),Set.of());assertFalse(s.armed());}
}
