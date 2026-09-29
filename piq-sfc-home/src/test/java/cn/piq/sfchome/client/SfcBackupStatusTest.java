package cn.piq.sfchome.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SfcBackupStatusTest {
    @Test void failedFirstBackupDoesNotClaimAnyCurrentSave(){
        var status=new SfcBackupStatus();
        assertTrue(status.failure().contains("本次会话尚无成功备份"));
        assertNull(status.failure());
    }
    @Test void successfulRoutineBackupIsQuiet(){
        var status=new SfcBackupStatus();
        assertNull(status.success(1800));assertNull(status.success(3600));
        assertTrue(status.failure().contains("第 3600 帧"));
    }
    @Test void recoveryAndLaterFailureAreReportedOnceEach(){
        var status=new SfcBackupStatus();
        assertNotNull(status.failure());
        assertTrue(status.success(5400).contains("不是服务器卡带存档"));
        assertNull(status.success(7200));
        assertTrue(status.failure().contains("第 7200 帧"));
        assertNull(status.failure());
    }
    @Test void invalidSuccessDoesNotEraseFailure(){
        var status=new SfcBackupStatus();status.failure();
        assertThrows(IllegalArgumentException.class,()->status.success(-1));
        assertNotNull(status.success(1));
    }
}
