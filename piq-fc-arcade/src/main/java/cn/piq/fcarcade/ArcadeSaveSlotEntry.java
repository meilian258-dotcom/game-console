package cn.piq.fcarcade;

public record ArcadeSaveSlotEntry(
        int slot,
        boolean occupied,
        String name,
        int players,
        long modifiedEpochMillis,
        String romSha256,
        String romName
) {
    public ArcadeSaveSlotEntry {
        if (slot < 1 || slot > 3) {
            throw new IllegalArgumentException("存档槽编号无效");
        }
        if (name == null || name.length() > 32
                || name.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("存档名称无效");
        }
        if (players < 1 || players > 2 || modifiedEpochMillis < 0) {
            throw new IllegalArgumentException("存档槽信息无效");
        }
        if (romSha256 == null
                || (!romSha256.isEmpty()
                && !romSha256.matches("[0-9a-f]{64}"))) {
            throw new IllegalArgumentException("存档 ROM SHA-256 无效");
        }
        if (romName == null || romName.length() > 160
                || romName.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("存档 ROM 名称无效");
        }
        if (occupied != (!romSha256.isEmpty() && !romName.isBlank())) {
            throw new IllegalArgumentException("存档占用状态与 ROM 信息不一致");
        }
    }
}
