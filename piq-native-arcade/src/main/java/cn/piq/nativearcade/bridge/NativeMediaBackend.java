// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.nativearcade.bridge;

/** Local, explicit media selection only. Never interpreted from a server packet or save. */
public enum NativeMediaBackend {
    JNI, RETROARCH;

    public static final String PROPERTY = "piq.arcade.mediaBackend";

    public static NativeMediaBackend selected() { return parse(System.getProperty(PROPERTY, "jni")); }

    public static NativeMediaBackend parse(String value) {
        return switch (value) {
            case "jni" -> JNI;
            case "retroarch" -> RETROARCH;
            default -> throw new IllegalArgumentException("街机音画后端必须为 jni 或 retroarch；不会自动切换后端");
        };
    }
}
