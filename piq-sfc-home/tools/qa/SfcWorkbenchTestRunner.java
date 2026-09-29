import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
public final class SfcWorkbenchTestRunner {
    public static void main(String[]args){
        var request=LauncherDiscoveryRequestBuilder.request().selectors(
            selectClass("cn.piq.sfchome.client.SfcCardLibraryTest"),selectClass("cn.piq.sfchome.client.SfcEditorWorkTest"),
            selectClass("cn.piq.sfchome.client.SfcUploadTitleTest"),selectClass("cn.piq.sfchome.client.SfcCardEditorLayoutTest"),
            selectClass("cn.piq.sfchome.client.SfcCardEditorSourceTest"),selectClass("cn.piq.sfchome.client.SfcWorkbenchDisplayTest")).build();
        var listener=new SummaryGeneratingListener();var launcher=LauncherFactory.create();launcher.registerTestExecutionListeners(listener);launcher.execute(request);
        var result=listener.getSummary();result.printFailuresTo(new java.io.PrintWriter(System.out));
        if(result.getTestsFoundCount()!=36||result.getTestsFailedCount()!=0||result.getTestsSkippedCount()!=0)throw new AssertionError("Workbench regression failure: "+result.getTestsFoundCount()+" found / "+result.getTestsFailedCount()+" failed");
        System.out.println("{\"passed\":true,\"tests\":"+result.getTestsFoundCount()+",\"failed\":"+result.getTestsFailedCount()+"}");
    }
}
