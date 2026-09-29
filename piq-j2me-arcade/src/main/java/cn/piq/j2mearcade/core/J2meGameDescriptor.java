package cn.piq.j2mearcade.core;

import java.nio.file.Path;

public record J2meGameDescriptor(
        Path jar,
        String name,
        String vendor,
        String version,
        String midletEntry
) {
}
