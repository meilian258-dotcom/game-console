package cn.piq.fcarcade.home;

public final class CartridgeLimits {
    public static final int CHUNK_BYTES = 16 * 1024;
    public static final int CHUNKS_PER_TICK = 2;
    public static final int MAX_COVER_BYTES = 2 * 1024 * 1024;
    public static final int MAX_SOURCE_COVER_BYTES = 8 * 1024 * 1024;
    public static final int MAX_TRANSFERS = 4;
    public static final long MAX_RESERVED_BYTES = 64L * 1024 * 1024;
    public static final long TRANSFER_TIMEOUT_NANOS = 120_000_000_000L;
    public static final int MAX_TITLE = 80;
    public static final int MAX_CATALOG = 256;
    public static final String SHA_PATTERN = "[0-9a-f]{64}";
    private CartridgeLimits() {}
    public static boolean validHash(String hash) { return hash != null && hash.matches(SHA_PATTERN); }
    public static String hashOrEmpty(String hash) {
        if (hash == null || hash.isEmpty()) return "";
        if (!validHash(hash)) throw new IllegalArgumentException("SHA-256 格式无效");
        return hash;
    }
    public static String cleanTitle(String title) {
        String value = title == null ? "" : title.strip();
        if (value.length() > MAX_TITLE || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("游戏名最多 80 字符，不能包含控制字符");
        return value;
    }
}
