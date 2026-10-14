package cn.piq.fcarcade.client.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NetplayFailureCodeTest {
    @AfterEach void clean(){DeviceNotices.clear();}
    @Test void actualJniFailureReasonsAreDistinctFromMissingDependencies(){
        assertEquals(NetplayFailureCode.STATE_MISMATCH,NetplayFailureCode.classifyReason("JNI Netplay 确认状态 CRC 不一致"));
        assertEquals(NetplayFailureCode.STATE_MISMATCH,NetplayFailureCode.classifyReason("CRC 不在确认窗口或重复"));
        assertEquals(NetplayFailureCode.SEED_RESTORE,NetplayFailureCode.classifyReason("JNI 种子/存档恢复不精确"));
        assertEquals(NetplayFailureCode.INPUT_SEQUENCE,NetplayFailureCode.classifyReason("JNI Netplay 包序列或授权席位异常"));
        assertEquals(NetplayFailureCode.DISCONNECTED,NetplayFailureCode.classifyReason("Netplay 连接已结束，请重新加入"));
        assertEquals(NetplayFailureCode.TIMEOUT,NetplayFailureCode.classifyReason("JNI 主持超时，请重新加入"));
        assertEquals(NetplayFailureCode.PROTOCOL,NetplayFailureCode.classifyReason("Netplay 帧协议不匹配"));
        assertEquals(NetplayFailureCode.DEPENDENCY,NetplayFailureCode.classify(new UnsatisfiedLinkError("native symbol")));
        assertEquals(NetplayFailureCode.DEPENDENCY,NetplayFailureCode.classify(new IllegalStateException("init",new UnsatisfiedLinkError("native symbol"))));
        assertEquals(NetplayFailureCode.DEPENDENCY,NetplayFailureCode.classifyReason("Missing core.dll"));
        assertEquals(NetplayFailureCode.RUNTIME,NetplayFailureCode.classifyReason("运行已停止，请查看运行环境诊断\nLoaded core.dll"));
    }
    @Test void explicitCodePreservesOriginalDetailAndCannotBeOverriddenByStartupLogs(){
        var failure=new NetplayFailureCode.Failure("JNI Netplay 确认状态 CRC 不一致");
        var entry=DeviceNotices.recordFailure("SFC",failure.code(),"Loaded core.dll and rom.zip\n运行环境已就绪",failure);
        assertEquals(NetplayFailureCode.STATE_MISMATCH.summary(),entry.summary());
        assertTrue(entry.detail().contains("[STATE_MISMATCH]"));assertTrue(entry.detail().contains("core.dll"));
        assertTrue(entry.detail().contains("NetplayFailureCodeTest"));assertTrue(entry.detail().contains(failure.getMessage()));
        assertSame(entry,DeviceNotices.recordFailure("SFC",failure.code(),"Loaded core.dll and rom.zip\n运行环境已就绪",failure));
        assertEquals(1,DeviceNotices.snapshot().size());assertSame(entry,DeviceNotices.last());
    }
}
