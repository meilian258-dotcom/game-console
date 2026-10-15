// SPDX-License-Identifier: GPL-3.0-or-later
// PIQ-authored code: additional permission in piq-fc-arcade/SNES9X-LINKING-EXCEPTION.txt.
package cn.piq.sfchome.world;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** SFC51's legacy Netplay flag already selected JNI; changing its menu name must not change saves. */
public final class SfcNetplaySettings {
    private static final String JNI = "SfcNetplayJni", LEGACY = "SfcNetplayExperimental";
    private SfcNetplaySettings() {}
    public static boolean read(CompoundTag tag) {
        // A present malformed/new false choice never falls back to an older true flag.
        String key = tag.contains(JNI) ? JNI : LEGACY;
        return tag.contains(key, Tag.TAG_BYTE) && tag.getBoolean(key);
    }
    public static void write(CompoundTag tag, boolean jni) {
        tag.putBoolean(JNI, jni);
        // Retain the legacy reader's actual JNI meaning for reversible world upgrades.
        tag.putBoolean(LEGACY, jni);
    }
    public static int supportedModes(boolean playerMedia, boolean local, boolean hosted) {
        return (playerMedia ? 1 : 0) | (local ? 18 : 0) | (hosted ? 4 : 0);
    }
}
