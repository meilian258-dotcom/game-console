package cn.piq.fcarcade.audio;

public final class SpatialAudio {
    private SpatialAudio() {
    }

    public static float distanceGain(
            double distance,
            double fullVolumeDistance,
            double maximumDistance
    ) {
        if (!Double.isFinite(distance)
                || fullVolumeDistance < 0
                || maximumDistance <= fullVolumeDistance) {
            throw new IllegalArgumentException("非法的距离衰减参数");
        }
        if (distance <= fullVolumeDistance) return 1.0F;
        if (distance >= maximumDistance) return 0.0F;
        return (float) ((maximumDistance - distance)
                / (maximumDistance - fullVolumeDistance));
    }
}
