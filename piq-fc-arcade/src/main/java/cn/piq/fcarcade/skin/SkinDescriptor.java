package cn.piq.fcarcade.skin;

public record SkinDescriptor(String name, String sha256, int size, SkinLayout layout) {
    public SkinDescriptor {
        name = normalizeName(name);
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("皮肤 SHA-256 无效");
        }
        if (size <= 0 || size > SkinTransferLimits.MAX_PNG_BYTES) {
            throw new IllegalArgumentException("皮肤文件大小无效");
        }
        if (layout == null) throw new IllegalArgumentException("皮肤 UV 布局缺失");
    }

    public boolean compatible() { return layout.compatible(); }

    public static String normalizeName(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.toLowerCase(java.util.Locale.ROOT).endsWith(".png")) {
            normalized = normalized.substring(0, normalized.length() - 4).strip();
        }
        if (normalized.isBlank()
                || normalized.length() > SkinTransferLimits.MAX_NAME_CHARS
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("皮肤名称无效");
        }
        return normalized;
    }
}
