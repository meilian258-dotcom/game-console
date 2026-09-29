package cn.piq.sfchome.client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SfcEditorWorkTest {
    @Test void retryRejectsPreviousCompletion(){var work=new SfcEditorWork();int a=work.begin(),b=work.begin();assertFalse(work.accepts(a));assertTrue(work.accepts(b));}
    @Test void closeRejectsPendingAndAnyFutureTicket(){var work=new SfcEditorWork();int ticket=work.begin();work.close();assertFalse(work.accepts(ticket));assertFalse(work.accepts(work.begin()));}
    @Test void independentScreenInstanceCannotReactivateOldWork(){var old=new SfcEditorWork();int oldTicket=old.begin();old.close();var next=new SfcEditorWork();assertTrue(next.accepts(next.begin()));assertFalse(old.accepts(oldTicket));}
}
