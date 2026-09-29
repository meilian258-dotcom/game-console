package cn.piq.pvz;

import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.VersionRange;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class MetadataCompatibilityTest {
    private VersionRange fcRange() throws Exception {
        try (var stream = getClass().getResourceAsStream("/META-INF/neoforge.mods.toml")) {
            assertNotNull(stream, "Test the generated, expanded metadata shipped to NeoForge");
            String metadata = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            var matcher = Pattern.compile("(?s)modId=\"piq_fc_arcade\"\\s+type=\"required\"\\s+versionRange=\"([^\"]+)\"")
                    .matcher(metadata);
            assertTrue(matcher.find(), "Required FC dependency must be present");
            return VersionRange.createFromVersionSpec(matcher.group(1));
        }
    }

    @Test void requiresSharedJniFc7622() throws Exception {
        assertTrue(fcRange().containsVersion(new DefaultArtifactVersion("0.31.0-alpha.76.22")));
        assertFalse(fcRange().containsVersion(new DefaultArtifactVersion("0.31.0-alpha.76.21")));
        assertFalse(fcRange().containsVersion(new DefaultArtifactVersion("0.31.0-alpha.76")));
    }

    @Test void rejectsOldStorageLayouts() throws Exception {
        for(int v=70;v<=75;v++)assertFalse(fcRange().containsVersion(new DefaultArtifactVersion("0.31.0-alpha."+v)));
    }

    @Test void rejectsUnreviewedOlderAndFutureVersions() throws Exception {
        var range = fcRange();
        for (String version : new String[]{"0.31.0-alpha.69", "0.31.0-alpha.77", "0.31.0", "1.0.0"}) {
            assertFalse(range.containsVersion(new DefaultArtifactVersion(version)), version);
        }
    }
}
