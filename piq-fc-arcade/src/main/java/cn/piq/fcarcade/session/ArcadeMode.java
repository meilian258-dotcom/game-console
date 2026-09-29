package cn.piq.fcarcade.session;

public enum ArcadeMode {
    LOCKSTEP("mode.piq_fc_arcade.lockstep"),
    STREAM("mode.piq_fc_arcade.stream");

    private final String translationKey;

    ArcadeMode(String translationKey) {
        this.translationKey = translationKey;
    }

    public String translationKey() {
        return translationKey;
    }

    public static ArcadeMode fromNetwork(int value) {
        ArcadeMode[] modes = values();
        if (value < 0 || value >= modes.length) {
            throw new IllegalArgumentException("非法街机模式编号：" + value);
        }
        return modes[value];
    }
}
