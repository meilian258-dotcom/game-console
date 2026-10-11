package cn.piq.replaycompat.capture;
import java.util.Objects;
final class CaptureAssertions {
    private CaptureAssertions() {}
    static void assertTrue(boolean value) { assertTrue(value,"expected true"); }
    static void assertTrue(boolean value,String message) { if(!value) throw new AssertionError(message); }
    static void assertFalse(boolean value) { assertTrue(!value,"expected false"); }
    static void assertFalse(boolean value,String message) { assertTrue(!value,message); }
    static void assertEquals(Object expected,Object actual) {
        if(!Objects.equals(expected,actual)) throw new AssertionError("expected "+expected+", got "+actual);
    }
    static void assertEquals(double expected,double actual) {
        if(Double.compare(expected,actual)!=0) throw new AssertionError("expected "+expected+", got "+actual);
    }
    static void assertNotEquals(Object expected,Object actual) { assertTrue(!Objects.equals(expected,actual),"expected distinct values"); }
    static void assertThrows(Class<? extends Throwable> type,Runnable action) {
        try { action.run(); } catch(Throwable failure) {
            if(type.isInstance(failure)) return;
            throw new AssertionError("wrong exception",failure);
        }
        throw new AssertionError("expected "+type.getName());
    }
}
