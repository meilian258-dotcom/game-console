package cn.piq.fcarcade.rom;

public record RomCatalogEntry(
        String fileName,
        String sha256,
        int size,
        int mapper,
        int maxPlayers,
        RomSaveMode saveMode
) {
    public RomCatalogEntry {
        if (fileName == null || fileName.isBlank()
                || fileName.length() > RomTransferLimits.MAX_FILE_NAME_CHARS) {
            throw new IllegalArgumentException("ROM 显示名称无效");
        }
        if (sha256 == null || !sha256.matches(RomRepository.SHA256_PATTERN)) {
            throw new IllegalArgumentException("ROM SHA-256 无效");
        }
        if (size <= 0 || size > RomRepository.MAX_ROM_BYTES) {
            throw new IllegalArgumentException("ROM 大小无效");
        }
        if (mapper < 0 || mapper > 4095) {
            throw new IllegalArgumentException("ROM Mapper 无效");
        }
        if (maxPlayers < 1 || maxPlayers > 2) {
            throw new IllegalArgumentException("ROM 玩家数量无效");
        }
        if (saveMode == null) {
            throw new IllegalArgumentException("ROM 存档模式无效");
        }
    }

    public static RomCatalogEntry from(
            RomDescriptor descriptor,
            String displayName,
            int maxPlayers,
            RomSaveMode saveMode
    ) {
        String fileName = displayName;
        if (fileName.length() > RomTransferLimits.MAX_FILE_NAME_CHARS) {
            fileName = fileName.substring(
                    0,
                    RomTransferLimits.MAX_FILE_NAME_CHARS);
        }
        return new RomCatalogEntry(
                fileName,
                descriptor.sha256(),
                descriptor.size(),
                descriptor.header().mapper(),
                maxPlayers,
                saveMode);
    }
}
