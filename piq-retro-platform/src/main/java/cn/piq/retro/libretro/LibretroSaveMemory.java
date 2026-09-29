// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.retro.libretro;

/** Detached native SAVE_RAM/RTC only. File-backed saves need a separate adapter. */
public record LibretroSaveMemory(byte[] ram, byte[] rtc) {
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    public LibretroSaveMemory {
        if (ram == null || rtc == null || (long) ram.length + rtc.length > MAX_BYTES)
            throw new IllegalArgumentException("Persistent memory size");
        ram = ram.clone(); rtc = rtc.clone();
    }
    @Override public byte[] ram() { return ram.clone(); }
    @Override public byte[] rtc() { return rtc.clone(); }
    public boolean isEmpty() { return ram.length == 0 && rtc.length == 0; }
}
