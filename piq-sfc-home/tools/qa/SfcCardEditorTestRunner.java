import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
public final class SfcCardEditorTestRunner {
    public static void main(String[]args){
        var request=LauncherDiscoveryRequestBuilder.request().selectors(
            selectClass("cn.piq.sfchome.client.SfcCardLibraryTest"),selectClass("cn.piq.sfchome.client.SfcEditorWorkTest"),
            selectClass("cn.piq.sfchome.client.SfcUploadTitleTest"),selectClass("cn.piq.sfchome.client.SfcCardEditorLayoutTest"),
            selectClass("cn.piq.sfchome.client.SfcCardEditorSourceTest")).build();
        var listener=new SummaryGeneratingListener();var launcher=LauncherFactory.create();launcher.registerTestExecutionListeners(listener);launcher.execute(request);
        listener.getSummary().printTo(new java.io.PrintWriter(System.out));listener.getSummary().printFailuresTo(new java.io.PrintWriter(System.out));
        if(listener.getSummary().getTestsFoundCount()!=25||listener.getSummary().getTestsFailedCount()!=0||listener.getSummary().getTestsSkippedCount()!=0)throw new AssertionError("SFC editor regression failure");
    }
}
