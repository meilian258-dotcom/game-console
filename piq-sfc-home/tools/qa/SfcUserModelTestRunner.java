import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
public final class SfcUserModelTestRunner {
    public static void main(String[] args){
        var request=LauncherDiscoveryRequestBuilder.request().selectors(
                selectClass("cn.piq.sfchome.client.SfcHardwareMeshDataTest"),selectClass("cn.piq.sfchome.client.SfcButtonAnimationTest"),
                selectClass("cn.piq.sfchome.client.SfcCoverGeometryTest"),selectClass("cn.piq.sfchome.client.SfcAvCableGeometryTest")).build();
        var listener=new SummaryGeneratingListener();var launcher=LauncherFactory.create();launcher.registerTestExecutionListeners(listener);launcher.execute(request);
        listener.getSummary().printTo(new java.io.PrintWriter(System.out));listener.getSummary().printFailuresTo(new java.io.PrintWriter(System.out));
        if(listener.getSummary().getTestsFoundCount()!=30||listener.getSummary().getTestsFailedCount()!=0||listener.getSummary().getTestsSkippedCount()!=0)throw new AssertionError("SFC user model tests");
    }
}
