package cn.piq.fcarcade.config;

/** Terminal transport bounds, independent of Minecraft state or command parsing. */
public final class AdminTerminalPolicy {
    public static final int READ=0, ACCESS=1, MODE=2, RANGE=3, TARGET=4, TRAFFIC=5, RETENTION=6;
    public static final int OPTION_MASK=31;
    private AdminTerminalPolicy() {}
    public static boolean valid(int action,int value) {
        return switch(action) {
            case READ,TARGET -> value==0;
            case ACCESS -> value>=0 && (value&~OPTION_MASK)==0;
            case MODE -> GameConsoleAdminPolicy.validMode(value);
            case RANGE -> GameConsoleAdminPolicy.validRange(value);
            case TRAFFIC -> value>=0&&value<=2;
            case RETENTION -> value>=0&&value<=3650;
            default -> false;
        };
    }
    public static boolean same(int expectedOptions,int expectedMode,int expectedRange,int options,int mode,int range) {
        return expectedOptions==options&&expectedMode==mode&&expectedRange==range;
    }
}
