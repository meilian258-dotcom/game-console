package cn.piq.flashbox.runtime;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class StartTicketTest {
    @Test void cancellationBeforeLaunchClosesLateResource() {
        var ticket=new StartTicket();var closed=new AtomicInteger();ticket.cancel();
        assertFalse(ticket.register(closed::incrementAndGet));assertEquals(1,closed.get());assertTrue(ticket.cancelled());
    }
    @Test void cancellationAfterLaunchIsIdempotent() {
        var ticket=new StartTicket();var closed=new AtomicInteger();assertTrue(ticket.register(closed::incrementAndGet));
        ticket.cancel();ticket.cancel();assertEquals(1,closed.get());
    }
}
