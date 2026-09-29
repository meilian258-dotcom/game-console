import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
public final class CartridgeComputerTestRunner {
    public static void main(String[] args) {
        var request = LauncherDiscoveryRequestBuilder.request().selectors(
                selectClass("cn.piq.fcarcade.home.CartridgeComputerBindingTest"),
                selectClass("cn.piq.fcarcade.home.CartridgeEditBindingTest"),
                selectClass("cn.piq.fcarcade.server.ServerRomPlayersPolicyTest")).build();
        var listener = new SummaryGeneratingListener(); var launcher = LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener); launcher.execute(request);
        listener.getSummary().printTo(new java.io.PrintWriter(System.out));
        listener.getSummary().printFailuresTo(new java.io.PrintWriter(System.out));
        if (listener.getSummary().getTestsFoundCount() != 16 || listener.getSummary().getTestsFailedCount() != 0)
            throw new AssertionError("Cartridge computer tests did not pass");
    }
}
