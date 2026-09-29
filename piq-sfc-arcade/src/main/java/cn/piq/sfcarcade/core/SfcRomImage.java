// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfcarcade.core;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/** Validated ROM bytes with an optional 512-byte legacy copier header removed. */
public final class SfcRomImage {
    public static final int MIN_ROM_BYTES = 32 * 1024;
    public static final int MAX_ROM_BYTES = 32 * 1024 * 1024;
    public static final int COPIER_HEADER_BYTES = 512;

    private final byte[] payload;
    private final int sourceLength;
    private final int removedHeaderBytes;
    private final String sha256;

    private SfcRomImage(byte[] payload, int sourceLength, int removedHeaderBytes) {
        this.payload = payload;
        this.sourceLength = sourceLength;
        this.removedHeaderBytes = removedHeaderBytes;
        this.sha256 = sha256(payload);
    }

    public static SfcRomImage fromBytes(byte[] source) {
        if (source == null) {
            throw new NullPointerException("source");
        }
        if (source.length < MIN_ROM_BYTES) {
            throw new IllegalArgumentException("SFC ROM is too small: " + source.length + " bytes");
        }
        if (source.length > MAX_ROM_BYTES + COPIER_HEADER_BYTES) {
            throw new IllegalArgumentException("SFC ROM exceeds safety limit: " + source.length + " bytes");
        }

        boolean hasCopierHeader = source.length % 1024 == COPIER_HEADER_BYTES
                && source.length - COPIER_HEADER_BYTES >= MIN_ROM_BYTES;
        int offset = hasCopierHeader ? COPIER_HEADER_BYTES : 0;
        byte[] payload = Arrays.copyOfRange(source, offset, source.length);
        return new SfcRomImage(payload, source.length, offset);
    }

    public byte[] copyPayload() {
        return payload.clone();
    }

    public int payloadLength() {
        return payload.length;
    }

    public int sourceLength() {
        return sourceLength;
    }

    public int removedHeaderBytes() {
        return removedHeaderBytes;
    }

    public boolean hadCopierHeader() {
        return removedHeaderBytes != 0;
    }

    public String sha256() {
        return sha256;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java runtime has no SHA-256 provider", impossible);
        }
    }
}

