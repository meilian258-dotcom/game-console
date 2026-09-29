package cn.piq.fcarcade.score;

public final class RoadRaceScoreRule {
    public static final String ROM_SHA256 =
            "BB6029AF4DFD6404772DC8C788113BA74D45B52FD8F120DDB7A607C9ECF45922";
    public static final int MAX_SCORE = 999_999;

    private static final int PRIMARY_ADDRESS = 0x0057;
    private static final int MIRROR_ADDRESS = 0x005D;
    private static final int BYTE_COUNT = 3;

    private RoadRaceScoreRule() {
    }

    public static int decode(byte[] cpuRam) {
        if (cpuRam == null || cpuRam.length < MIRROR_ADDRESS + BYTE_COUNT) return -1;
        int primary = decodePackedBcdLittleEndian(cpuRam, PRIMARY_ADDRESS);
        int mirror = decodePackedBcdLittleEndian(cpuRam, MIRROR_ADDRESS);
        return primary >= 0 && primary == mirror ? primary : -1;
    }

    private static int decodePackedBcdLittleEndian(byte[] ram, int address) {
        int value = 0;
        int multiplier = 1;
        for (int index = 0; index < BYTE_COUNT; index++) {
            int packed = Byte.toUnsignedInt(ram[address + index]);
            int low = packed & 0x0F;
            int high = packed >>> 4;
            if (low > 9 || high > 9) return -1;
            value += (high * 10 + low) * multiplier;
            multiplier *= 100;
        }
        return value <= MAX_SCORE ? value : -1;
    }
}
