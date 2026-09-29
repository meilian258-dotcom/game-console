package cn.piq.fcarcade;

public record ArcadeSaveCatalogEntry(
        String storageId,
        String owner,
        String romName,
        String romSha256,
        String slotName,
        int players,
        long modifiedEpochMillis,
        long fileBytes,
        boolean legacy
) {
    public ArcadeSaveCatalogEntry {
        if (storageId == null || !storageId.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("存档文件标识无效");
        }
        if (owner == null || owner.isBlank() || owner.length() > 128) {
            throw new IllegalArgumentException("存档归属显示名无效");
        }
        if (romName == null || romName.isBlank() || romName.length() > 160) {
            throw new IllegalArgumentException("存档 ROM 显示名无效");
        }
        if (romSha256 == null || !romSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("存档 ROM SHA-256 无效");
        }
        if (slotName == null || slotName.length() > 32
                || players < 1 || players > 2) {
            throw new IllegalArgumentException("存档槽信息无效");
        }
        if (modifiedEpochMillis < 0 || fileBytes <= 0) {
            throw new IllegalArgumentException("存档文件信息无效");
        }
    }
}
