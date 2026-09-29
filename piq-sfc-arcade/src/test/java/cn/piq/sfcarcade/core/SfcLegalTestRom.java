// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfcarcade.core;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Builds a tiny, original LoROM cartridge used only by automated tests.
 * It contains no third-party game code or assets and paints a solid green backdrop.
 */
final class SfcLegalTestRom {
    private static final int ROM_BYTES = 32 * 1024;
    private static final int HEADER_OFFSET = 0x7FC0;

    private SfcLegalTestRom() {
    }

    static byte[] create() {
        byte[] rom = new byte[ROM_BYTES];
        Arrays.fill(rom, (byte) 0xFF);

        byte[] program = {
                0x78,                         // SEI
                0x18,                         // CLC
                (byte) 0xFB,                  // XCE: enter native mode
                (byte) 0xC2, 0x30,            // REP #$30: 16-bit A/X
                (byte) 0xA2, (byte) 0xFF, 0x1F, // LDX #$1FFF
                (byte) 0x9A,                  // TXS
                (byte) 0xE2, 0x20,            // SEP #$20: 8-bit A
                (byte) 0xA9, (byte) 0x80,     // LDA #$80: forced blank
                (byte) 0x8D, 0x00, 0x21,      // STA $2100
                (byte) 0x9C, 0x21, 0x21,      // STZ $2121: CGRAM address 0
                (byte) 0xA9, (byte) 0xE0,     // LDA #$E0: green low byte
                (byte) 0x8D, 0x22, 0x21,      // STA $2122
                (byte) 0xA9, 0x03,            // LDA #$03: green high byte
                (byte) 0x8D, 0x22, 0x21,      // STA $2122
                (byte) 0xA9, 0x0F,            // LDA #$0F: display on, full brightness
                (byte) 0x8D, 0x00, 0x21,      // STA $2100
                (byte) 0x80, (byte) 0xFE      // BRA forever
        };
        System.arraycopy(program, 0, rom, 0, program.length);

        putAscii(rom, HEADER_OFFSET, 21, "PIQ SFC LEGAL TEST");
        rom[HEADER_OFFSET + 0x15] = 0x20; // LoROM, slow ROM
        rom[HEADER_OFFSET + 0x16] = 0x00; // ROM only
        rom[HEADER_OFFSET + 0x17] = 0x05; // 32 KiB
        rom[HEADER_OFFSET + 0x18] = 0x00; // no SRAM
        rom[HEADER_OFFSET + 0x19] = 0x01; // NTSC
        rom[HEADER_OFFSET + 0x1A] = 0x00;
        rom[HEADER_OFFSET + 0x1B] = 0x00;

        // Native and emulation vectors all point to the safe reset loop at $8000.
        for (int offset : new int[]{0x7FE4, 0x7FE6, 0x7FE8, 0x7FEA, 0x7FEE,
                0x7FF4, 0x7FF8, 0x7FFA, 0x7FFC, 0x7FFE}) {
            putU16Le(rom, offset, 0x8000);
        }

        int sumWithoutChecksum = 0;
        for (int i = 0; i < rom.length; i++) {
            if (i < HEADER_OFFSET + 0x1C || i > HEADER_OFFSET + 0x1F) {
                sumWithoutChecksum = (sumWithoutChecksum + Byte.toUnsignedInt(rom[i])) & 0xFFFF;
            }
        }
        int checksum = (sumWithoutChecksum + 0x1FE) & 0xFFFF;
        putU16Le(rom, HEADER_OFFSET + 0x1C, checksum ^ 0xFFFF);
        putU16Le(rom, HEADER_OFFSET + 0x1E, checksum);
        return rom;
    }

    private static void putAscii(byte[] destination, int offset, int length, String value) {
        Arrays.fill(destination, offset, offset + length, (byte) ' ');
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, destination, offset, Math.min(length, bytes.length));
    }

    private static void putU16Le(byte[] destination, int offset, int value) {
        destination[offset] = (byte) value;
        destination[offset + 1] = (byte) (value >>> 8);
    }
}
