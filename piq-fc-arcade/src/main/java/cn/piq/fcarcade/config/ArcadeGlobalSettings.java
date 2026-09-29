package cn.piq.fcarcade.config;

public record ArcadeGlobalSettings(
        int viewDistance,
        int audioDistance,
        int audioVolumePercent,
        int saveRetentionDays
) {
    public static final int DEFAULT_VIEW_DISTANCE = 16;
    public static final int DEFAULT_AUDIO_DISTANCE = 16;
    public static final int DEFAULT_AUDIO_VOLUME_PERCENT = 100;
    public static final int DEFAULT_SAVE_RETENTION_DAYS = 0;
    public static final int MIN_DISTANCE = 4;
    public static final int MAX_DISTANCE = 64;
    public static final int MIN_AUDIO_VOLUME_PERCENT = 0;
    public static final int MAX_AUDIO_VOLUME_PERCENT = 100;
    public static final int MIN_RETENTION_DAYS = 0;
    public static final int MAX_RETENTION_DAYS = 3650;

    public static final ArcadeGlobalSettings DEFAULT = new ArcadeGlobalSettings(
            DEFAULT_VIEW_DISTANCE,
            DEFAULT_AUDIO_DISTANCE,
            DEFAULT_AUDIO_VOLUME_PERCENT,
            DEFAULT_SAVE_RETENTION_DAYS);

    public ArcadeGlobalSettings {
        if (viewDistance < MIN_DISTANCE || viewDistance > MAX_DISTANCE) {
            throw new IllegalArgumentException("街机画面距离必须在 4 至 64 格之间");
        }
        if (audioDistance < MIN_DISTANCE || audioDistance > MAX_DISTANCE) {
            throw new IllegalArgumentException("街机声音距离必须在 4 至 64 格之间");
        }
        if (audioVolumePercent < MIN_AUDIO_VOLUME_PERCENT
                || audioVolumePercent > MAX_AUDIO_VOLUME_PERCENT) {
            throw new IllegalArgumentException("街机音量必须在 0% 至 100% 之间");
        }
        if (saveRetentionDays < MIN_RETENTION_DAYS
                || saveRetentionDays > MAX_RETENTION_DAYS) {
            throw new IllegalArgumentException("存档保留天数必须在 0 至 3650 天之间");
        }
    }

    public double trackingDistanceSquared() {
        int distance = Math.max(viewDistance, audioDistance);
        return (double) distance * distance;
    }
}
