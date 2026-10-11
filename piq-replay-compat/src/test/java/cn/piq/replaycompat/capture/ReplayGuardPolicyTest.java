package cn.piq.replaycompat.capture;

import static cn.piq.replaycompat.capture.CaptureAssertions.*;

class ReplayGuardPolicyTest {
    public static void main(String[] args) {
        var tests=new ReplayGuardPolicyTest();
        tests.neverBlocksNormalPlayOrRecording();
        tests.onlyDeviceProtocolsAreBlockedInActualReplay();
        System.out.println("ReplayGuardPolicyTest: 2 behavioral cases passed");
    }
    void neverBlocksNormalPlayOrRecording() {
        assertFalse(ReplayGuardPolicy.blocks(false,"piq_fc_arcade"));
        assertFalse(ReplayGuardPolicy.blocks(false,"piq_computer"));
    }
    void onlyDeviceProtocolsAreBlockedInActualReplay() {
        for(var namespace:java.util.List.of("piq_fc_arcade","piq_sfc_home","piq_sfc_arcade","piq_native_arcade",
                "piq_md_home","piq_gba","piq_computer","piq_j2me_arcade","piq_flash_box","piq_pvz"))
            assertTrue(ReplayGuardPolicy.blocks(true,namespace),namespace);
        for(var namespace:java.util.List.of("minecraft","neoforge","replaymod","piq_replay_compat","othermod","piq_unrelated"))
            assertFalse(ReplayGuardPolicy.blocks(true,namespace),namespace);
        assertFalse(ReplayGuardPolicy.blocks(true,null));
    }
}
