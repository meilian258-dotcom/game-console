package cn.piq.fcarcade.cabinet;

/** Cabinet-only persistent settings; neither visibility nor key inactivity is a shutdown signal. */
public final class CabinetPowerSettings {
    public static final int DEFAULT_SECONDS=60, DEFAULT_RENDER_DISTANCE=16;
    private CabinetPowerSettings(){}
    public static boolean validSeconds(int value){return value>=1&&value<=3600;}
    public static boolean validRenderDistance(int value){return value>=1&&value<=128;}
    public static int seconds(int value){return validSeconds(value)?value:DEFAULT_SECONDS;}
    public static int renderDistance(int value){return validRenderDistance(value)?value:DEFAULT_RENDER_DISTANCE;}
    public static boolean visible(double distanceSquared,int range){
        return Double.isFinite(distanceSquared)&&distanceSquared>=0&&distanceSquared<=(double)renderDistance(range)*renderDistance(range);
    }
}
