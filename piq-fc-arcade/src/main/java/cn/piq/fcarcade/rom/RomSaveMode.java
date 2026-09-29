package cn.piq.fcarcade.rom;

public enum RomSaveMode {
    NONE(0, "screen.piq_fc_arcade.save_none"),
    PLAYER(1, "screen.piq_fc_arcade.save_player"),
    MACHINE(2, "screen.piq_fc_arcade.save_machine");

    private final int id;
    private final String translationKey;

    RomSaveMode(int id, String translationKey) {
        this.id = id;
        this.translationKey = translationKey;
    }

    public int id() {
        return id;
    }

    public String translationKey() {
        return translationKey;
    }

    public RomSaveMode next() {
        return switch (this) {
            case NONE -> PLAYER;
            case PLAYER -> MACHINE;
            case MACHINE -> NONE;
        };
    }

    public static RomSaveMode fromId(int id) {
        return switch (id) {
            case 0 -> NONE;
            case 1 -> PLAYER;
            case 2 -> MACHINE;
            default -> throw new IllegalArgumentException("ROM 存档模式无效");
        };
    }
}
