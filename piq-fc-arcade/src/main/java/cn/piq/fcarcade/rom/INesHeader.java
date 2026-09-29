package cn.piq.fcarcade.rom;

import java.util.Arrays;

public record INesHeader(
        Format format,
        int mapper,
        int subMapper,
        int prgRomBytes,
        int chrRomBytes,
        boolean trainerPresent,
        boolean batteryPresent
) {
    public static final int HEADER_BYTES = 16;
    public static final int TRAINER_BYTES = 512;
    private static final byte[] MAGIC = {'N', 'E', 'S', 0x1A};

    public static INesHeader parse(byte[] rom) {
        if (rom.length < HEADER_BYTES) {
            throw new IllegalArgumentException("ROM 小于 16 字节，缺少 iNES Header");
        }
        if (!Arrays.equals(Arrays.copyOf(rom, MAGIC.length), MAGIC)) {
            throw new IllegalArgumentException("不是有效的 .nes 文件：魔数应为 NES 1A");
        }

        int flags6 = Byte.toUnsignedInt(rom[6]);
        int flags7 = Byte.toUnsignedInt(rom[7]);
        boolean nes20 = (flags7 & 0x0C) == 0x08;
        int mapper = (flags6 >>> 4) | (flags7 & 0xF0);
        int subMapper = 0;
        long prgBytes;
        long chrBytes;

        if (nes20) {
            int byte8 = Byte.toUnsignedInt(rom[8]);
            mapper |= (byte8 & 0x0F) << 8;
            subMapper = byte8 >>> 4;
            int byte9 = Byte.toUnsignedInt(rom[9]);
            prgBytes = decodeRomSize(Byte.toUnsignedInt(rom[4]), byte9 & 0x0F, 16 * 1024);
            chrBytes = decodeRomSize(Byte.toUnsignedInt(rom[5]), byte9 >>> 4, 8 * 1024);
        } else {
            prgBytes = (long) Byte.toUnsignedInt(rom[4]) * 16 * 1024;
            chrBytes = (long) Byte.toUnsignedInt(rom[5]) * 8 * 1024;
        }

        if (prgBytes <= 0 || prgBytes > Integer.MAX_VALUE || chrBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("ROM Header 声明了不支持的 PRG/CHR 大小");
        }

        boolean trainer = (flags6 & 0x04) != 0;
        long required = HEADER_BYTES + (trainer ? TRAINER_BYTES : 0) + prgBytes + chrBytes;
        if (required > rom.length) {
            throw new IllegalArgumentException(
                    "ROM 数据被截断：Header 至少需要 " + required + " 字节，实际 " + rom.length + " 字节");
        }

        return new INesHeader(
                nes20 ? Format.NES_2_0 : Format.INES,
                mapper,
                subMapper,
                (int) prgBytes,
                (int) chrBytes,
                trainer,
                (flags6 & 0x02) != 0
        );
    }

    private static long decodeRomSize(int lsb, int msbNibble, int unit) {
        if (msbNibble != 0x0F) {
            return (long) ((msbNibble << 8) | lsb) * unit;
        }
        int exponent = lsb >>> 2;
        int multiplier = (lsb & 0x03) * 2 + 1;
        if (exponent >= 63) {
            throw new IllegalArgumentException("NES 2.0 ROM 大小指数过大");
        }
        return Math.multiplyExact(1L << exponent, multiplier);
    }

    public enum Format {
        INES,
        NES_2_0
    }
}
