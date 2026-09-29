package cn.piq.fcarcade.session;

public enum ArcadeRole {
    PLAYER_ONE(0, "role.piq_fc_arcade.player_one"),
    PLAYER_TWO(1, "role.piq_fc_arcade.player_two"),
    SPECTATOR(-1, "role.piq_fc_arcade.spectator");

    private final int controllerIndex;
    private final String translationKey;

    ArcadeRole(int controllerIndex, String translationKey) {
        this.controllerIndex = controllerIndex;
        this.translationKey = translationKey;
    }

    public int controllerIndex() {
        return controllerIndex;
    }

    public String translationKey() {
        return translationKey;
    }

    public static ArcadeRole fromNetwork(int value) {
        ArcadeRole[] roles = values();
        if (value < 0 || value >= roles.length) {
            throw new IllegalArgumentException("非法街机角色编号：" + value);
        }
        return roles[value];
    }
}
