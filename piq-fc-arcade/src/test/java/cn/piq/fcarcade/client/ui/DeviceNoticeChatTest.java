package cn.piq.fcarcade.client.ui;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DeviceNoticeChatTest {
    private static DeviceNotices.Entry entry(long id, String summary) {
        return new DeviceNotices.Entry(id, Instant.EPOCH, "街机", summary, "private path / stack trace");
    }
    @Test void distinctFailuresAreShownInOrderAndRateLimited() {
        var gate = new DeviceNoticeChat();
        var first = entry(1,"首次失败"); var second = entry(2,"后续失败");
        var entries = List.of(second,first);
        assertSame(first,gate.poll(entries,0));
        assertNull(gate.poll(entries,999_999_999L));
        assertSame(second,gate.poll(entries,1_000_000_000L));
        assertNull(gate.poll(entries,2_000_000_000L));
    }
    @Test void repeatedSummaryIsThrottledWithoutHidingDifferentFailure() {
        var gate = new DeviceNoticeChat();
        assertNotNull(gate.poll(List.of(entry(1,"同步失败")),0));
        assertEquals(3,gate.poll(List.of(entry(3,"保存失败"),entry(2,"同步失败")),1_000_000_000L).id());
        assertNull(gate.poll(List.of(entry(4,"同步失败")),9_999_999_999L));
        assertEquals(5,gate.poll(List.of(entry(5,"同步失败")),10_000_000_000L).id());
    }
    @Test void offlineFailuresCanWaitButPreviousConnectionIsDiscarded() {
        var gate = new DeviceNoticeChat();
        var old = entry(7,"旧连接失败");
        gate.discardThrough(old);
        assertNull(gate.poll(List.of(old),0));
        assertEquals(8,gate.poll(List.of(entry(8,"新失败"),old),0).id());
    }
    @Test void boundedHistoryCanSkipLostEntriesWithoutReplayingSeenOnes() {
        var gate = new DeviceNoticeChat();
        assertEquals(90,gate.poll(List.of(entry(91,"B"),entry(90,"A")),0).id());
        assertEquals(91,gate.poll(List.of(entry(91,"B"),entry(90,"A")),1_000_000_000L).id());
        assertNull(gate.poll(List.of(entry(91,"B"),entry(90,"A")),2_000_000_000L));
    }
    @Test void localChatUsesSummaryNotPrivateDiagnostic() {
        var line = DeviceNoticeChat.line(entry(1,"运行环境不可用"));
        assertTrue(line.contains("运行环境不可用"));
        assertTrue(line.contains("复制诊断"));
        assertFalse(line.contains("private"));
        assertFalse(line.contains("stack trace"));
    }
}
