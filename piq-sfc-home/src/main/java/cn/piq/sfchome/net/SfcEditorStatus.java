// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.net;

/** Keeps directory diagnostics separate from transient editor operation replies. Wire shape is unchanged. */
public final class SfcEditorStatus {
    public static final String PREFIX = "服务器目录：";
    public static final String LOADING = PREFIX + "正在扫描；本地列表独立读取";
    private SfcEditorStatus() {}
    public static boolean directory(String message) { return message != null && message.startsWith(PREFIX); }
    public static String remember(String previous, String message) {
        return directory(message) ? message : previous;
    }
    public static String compact(String message, int maximum) {
        String clean = message == null ? "未知错误" : message.replaceAll("[\\p{Cntrl}]", " ").strip();
        if (clean.length() <= maximum) return clean;
        int end = maximum - 1;
        if (end > 0 && Character.isHighSurrogate(clean.charAt(end - 1))) end--;
        return clean.substring(0, end) + "…";
    }
}
