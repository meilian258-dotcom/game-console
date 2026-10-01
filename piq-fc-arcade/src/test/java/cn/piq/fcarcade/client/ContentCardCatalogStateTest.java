package cn.piq.fcarcade.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContentCardCatalogStateTest {
    @Test void localScanCompletesWithoutAnyServerReply(){
        var state=new ContentCardCatalogState();assertTrue(state.beginServer(0));assertTrue(state.beginLocal(0));
        state.localSuccess("本地：1 项可用，1 项被拒绝","Zombie High.md：not MD");
        assertFalse(state.localPending());assertTrue(state.serverPending());assertTrue(state.details().contains("Zombie High.md"));
        state.serverFailure("无权限");assertFalse(state.serverPending());assertTrue(state.summary().contains("本地：1 项可用"));
        assertTrue(state.details().contains("Zombie High.md"));
    }
    @Test void serverFailureAndRefreshThrottleNeverErasePerFileDiagnostics(){
        var state=new ContentCardCatalogState();state.beginServer(0);state.beginLocal(0);
        state.localSuccess("本地：1 项可用，1 项被拒绝","bad.md：SEGA header rejected");
        state.serverFailure("目录不可读");assertFalse(state.beginServer(100));assertFalse(state.beginLocal(100));
        assertTrue(state.details().contains("目录不可读"));assertTrue(state.details().contains("SEGA header rejected"));
        assertTrue(state.summary().contains("刷新过快"));
        state.serverFailure("刷新过快，请稍后再试");assertTrue(state.details().contains("目录不可读"));assertTrue(state.hasFailure());
    }
    @Test void serverTimeoutKeepsLocalScanButRejectsLateRepliesAndSameSessionRetry(){
        var state=new ContentCardCatalogState();state.beginServer(0);state.beginLocal(0);
        assertFalse(state.expireServer(120_000_000_000L));assertTrue(state.expireServer(120_000_000_001L));
        assertTrue(state.serverExpired());assertFalse(state.allowsServerMutation());assertTrue(state.localPending());assertFalse(state.serverPending());assertTrue(state.details().contains("请求超时"));
        state.localSuccess("本地：2 项可用","");assertTrue(state.summary().contains("本地：2 项可用"));
        assertFalse(state.beginServer(121_000_000_000L));
        String before=state.details();state.serverSuccess("迟到的服务器结果","");state.serverFailure("迟到的STATUS");
        assertEquals(before,state.details());assertTrue(state.serverExpired());assertFalse(state.beginServer(Long.MAX_VALUE));assertFalse(state.allowsServerMutation());
        assertTrue(state.beginLocal(121_000_000_000L));state.localSuccess("本地：3 项可用","");assertTrue(state.serverExpired());
        var reopened=new ContentCardCatalogState();assertFalse(reopened.serverExpired());assertTrue(reopened.beginServer(122_000_000_000L));
        reopened.serverSuccess("新会话扫描完成","");assertTrue(reopened.allowsServerMutation());
    }
    @Test void failedDirectoryRefreshKeepsPriorStateUntilSuccessfulRecovery(){
        var state=new ContentCardCatalogState();state.beginLocal(0);state.localFailure("读取失败");
        assertTrue(state.beginLocal(1_000_000_000L));assertTrue(state.details().contains("读取失败"));
        state.localSuccess("本地：0 项可用","");assertFalse(state.hasFailure());assertTrue(state.summary().contains("0 项可用"));
    }
    @Test void separateTabsDoNotShareCooldownOrErrors(){
        var games=new ContentCardCatalogState();var covers=new ContentCardCatalogState();
        games.beginServer(0);games.serverFailure("bad.md：rejected");
        assertTrue(covers.beginServer(1));covers.serverSuccess("封面正常","");
        assertTrue(games.hasFailure());assertFalse(covers.hasFailure());
    }
}
