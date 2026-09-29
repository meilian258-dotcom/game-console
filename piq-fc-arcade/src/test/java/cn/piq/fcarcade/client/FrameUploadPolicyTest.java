package cn.piq.fcarcade.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameUploadPolicyTest {
    @Test
    void controllersKeepSixtyFpsWhileSpectatorsUploadEveryOtherFrame() {
        assertTrue(FrameUploadPolicy.shouldUpload(true, true, 1));
        assertTrue(FrameUploadPolicy.shouldUpload(true, true, 2));
        assertFalse(FrameUploadPolicy.shouldUpload(false, true, 1));
        assertTrue(FrameUploadPolicy.shouldUpload(false, true, 2));
    }

    @Test
    void invisibleMachinesDoNotUploadTextures() {
        assertFalse(FrameUploadPolicy.shouldUpload(true, false, 2));
        assertFalse(FrameUploadPolicy.shouldUpload(false, false, 2));
    }
}
