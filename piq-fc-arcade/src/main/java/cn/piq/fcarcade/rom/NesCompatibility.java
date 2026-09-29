package cn.piq.fcarcade.rom;

import java.util.Set;

public final class NesCompatibility {
    private static final Set<Integer> LEGACY_MAPPERS = Set.of(0, 1, 2, 3, 4, 140);
    public static final String SUPPORTED_MAPPER_TEXT = "内置 Mesen 实现的 Mapper（具体 ROM 仍需核心加载验证）";

    private NesCompatibility() {
    }

    public static boolean isSupported(INesHeader header) {
        return header != null && isMapperSupported(header.mapper());
    }

    /** Catalogs carry the mapper ID, not the full header. Keep local/server admission identical. */
    public static boolean isMapperSupported(int mapper) {
        return MesenMapperSupport.contains(mapper);
    }

    public static String unsupportedReason(int mapper) {
        return isMapperSupported(mapper) ? "" : "内置 Mesen 未实现 Mapper " + mapper + "，不能写入或运行";
    }

    public static boolean isMapper19Supported(INesHeader header) {
        // The Rust cartridge header uses iNES sizes. Reject NES 2.0 extended
        // sizes/submappers until their board/audio variants are verified.
        return header != null && header.format() == INesHeader.Format.INES && header.mapper() == 19 && header.subMapper() == 0
                && header.prgRomBytes() >= 32 * 1024 && header.prgRomBytes() <= 512 * 1024
                && header.prgRomBytes() % (16 * 1024) == 0
                && header.chrRomBytes() > 0 && header.chrRomBytes() <= 256 * 1024
                && header.chrRomBytes() % (8 * 1024) == 0;
    }

    public static void requireMapper19Supported(INesHeader header) {
        if (!isMapper19Supported(header)) throw new IllegalArgumentException("Mapper 19 核心不支持此 ROM 布局或子类型");
    }

    public static void requireLegacySupported(INesHeader header) {
        if (header == null || !LEGACY_MAPPERS.contains(header.mapper()))
            throw new IllegalArgumentException("当前 ROM 使用 Mapper " + (header == null ? "未知" : header.mapper())
                    + "，此类型不能载入旧 FC/光枪核心，需使用对应的受支持核心");
    }

    public static void requireSupported(INesHeader header) {
        if (header == null) throw new IllegalArgumentException("ROM 文件头缺失");
        if (!isSupported(header)) throw new IllegalArgumentException(unsupportedReason(header.mapper()));
    }
}
