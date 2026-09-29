import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

/** Final-JAR test launcher, not a production implementation or Minecraft launcher. */
public final class RuntimeInstaller37Probe {
    public static void main(String[] args) throws Exception {
        Path expected = Path.of(args[0]).toRealPath();
        int origins = 0;
        for (String name : List.of("cn.piq.fcarcade.runtime.RuntimeInstaller", "cn.piq.fcarcade.runtime.RuntimeCatalog",
                "cn.piq.fcarcade.runtime.RuntimePaths", "cn.piq.fcarcade.runtime.RuntimePack",
                "cn.piq.fcarcade.client.runtime.RuntimePanelLayout")) {
            Class<?> type = Class.forName(name);
            Path actual = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            if (!actual.equals(expected)) throw new AssertionError("Production origin mismatch: " + name + " " + actual);
            origins++;
        }
        var request = LauncherDiscoveryRequestBuilder.request().selectors(
                DiscoverySelectors.selectClass("cn.piq.fcarcade.runtime.RuntimeInstallerTest"),
                DiscoverySelectors.selectClass("cn.piq.fcarcade.client.runtime.RuntimePanelLayoutTest")).build();
        var listener = new SummaryGeneratingListener();
        var launcher = LauncherFactory.create(); launcher.registerTestExecutionListeners(listener); launcher.execute(request);
        var result = listener.getSummary();
        result.printTo(new PrintWriter(System.out, true)); result.printFailuresTo(new PrintWriter(System.out, true));
        if (result.getTestsFoundCount() != 30 || result.getTestsFailedCount() != 0
                || result.getContainersFailedCount() != 0 || result.getTestsSkippedCount() != 0
                || result.getTestsSucceededCount() < 29 || result.getTestsAbortedCount() > 1)
            throw new AssertionError("Required 28 installer + 2 layout tests, no failures; only OS symlink privilege abort permitted");
        System.out.println("{\"ok\":true,\"production_origins_verified\":" + origins + ",\"tests\":" + result.getTestsFoundCount()
                + ",\"passed\":" + result.getTestsSucceededCount() + ",\"aborted\":" + result.getTestsAbortedCount()
                + ",\"failed\":" + result.getTestsFailedCount() + ",\"minecraft_started\":false,\"native_library_loaded\":false}");
    }
}
