package cn.piq.flashbox.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlaybackPolicyTest {
    @Test void closingReadyMenuKeepsTelevisionPlaying() { assertTrue(PlaybackPolicy.keepAfterMenuClose(false,true)); }
    @Test void closingPendingOrFailedMenuDoesNotLaunchLater() {
        assertFalse(PlaybackPolicy.keepAfterMenuClose(true,false));
        assertFalse(PlaybackPolicy.keepAfterMenuClose(true,true));
        assertFalse(PlaybackPolicy.keepAfterMenuClose(false,false));
    }
    @Test void worldViewAndControlMenuDoNotPause() { assertFalse(PlaybackPolicy.pause(true,false,false)); }
    @Test void focusAndOtherMenuStillPause() {
        assertTrue(PlaybackPolicy.pause(false,false,false));
        assertTrue(PlaybackPolicy.pause(true,true,false));
        assertTrue(PlaybackPolicy.pause(true,false,true));
    }
}
