// SPDX-License-Identifier: GPL-3.0-or-later
// PIQ-authored code: additional permission in piq-fc-arcade/MD-ARCADE-LINKING-EXCEPTION.txt.
package cn.piq.nativearcade.bridge;

import java.util.*;
import java.util.regex.Pattern;

/** Interprets MAME's explicit fatal content audit, never infers missing files from an exit code. */
final class NativeMameLoadFailure {
    private static final Pattern MISSING = Pattern.compile(
            "^([A-Za-z0-9_][A-Za-z0-9_.-]{0,95}) NOT FOUND(?: \\(tried in ([A-Za-z0-9_ .-]{1,192})\\))?$",
            Pattern.CASE_INSENSITIVE);
    private static final int MAX_FILES = 32, MAX_LOG = 32768;
    record Report(String summary, String detail) {}
    private NativeMameLoadFailure() {}

    static Report interpret(String contentName, String diagnostic) {
        if (diagnostic == null) return null;
        String tail = diagnostic.substring(Math.max(0, diagnostic.length() - MAX_LOG));
        if (!tail.toLowerCase(Locale.ROOT).contains("fatal error: required files are missing")) return null;
        var files = new LinkedHashSet<String>();
        var deviceFiles = new LinkedHashSet<String>();
        var companions = new LinkedHashSet<String>();
        boolean truncated = false;
        for (String line : tail.split("\\R")) {
            var match = MISSING.matcher(line.strip());
            if (!match.matches()) continue;
            String name = match.group(1);
            if (name.equals(".") || name.equals("..")) continue;
            if (files.size() == MAX_FILES && !files.contains(name)) { truncated = true; continue; }
            files.add(name);
            if (match.group(2) == null) continue;
            for (String tried : match.group(2).split(" +")) {
                String zip = tried.toLowerCase(Locale.ROOT) + ".zip";
                if (NativeRomStaging.AUXILIARY_NAMES.contains(zip)) {
                    companions.add(zip); deviceFiles.add(name);
                }
            }
        }
        // Put independently packaged device/BIOS data before board audit filenames in the short notice.
        var ordered = new LinkedHashSet<>(deviceFiles); ordered.addAll(files);
        String shortNames = String.join("、", ordered.stream().limit(2).toList());
        String summary = "MAME 游戏数据缺失" + (shortNames.isEmpty() ? "" : "：" + shortNames)
                + (ordered.size() > 2 || truncated ? "等" : "") + "；请查看诊断";
        String safeName = contentName != null && contentName.matches("[a-z0-9_]{1,32}\\.zip") ? contentName : "所选游戏";
        String detail = "游戏：" + safeName + "\nMAME 因缺少所需数据拒绝加载，不是缺少模拟器 DLL。"
                + "\n核心报告的缺失文件：" + (ordered.isEmpty() ? "请查看下方原生记录" : String.join("、", ordered))
                + (truncated ? "（列表已截断）" : "")
                + (companions.isEmpty() ? "" : "\n核心搜索过的配套包：" + String.join("、", companions)
                    + "（搜索候选，不表示这些包都必须提供）")
                + "\n请使用与当前 MAME 匹配且有权使用的完整 ROM 和配套 BIOS／设备数据，保留原文件名。"
                + "\n本地上传：将配套 ZIP 放在游戏同目录，重新选择并上传完整内容。"
                + "\n服务器游戏：由有上传权限的人补齐内容清单，重新选到补齐后的游戏，再关机重开。"
                + "\n不要修改共享缓存或按摘要命名的库存文件；运行环境“就绪”不代表游戏数据完整。";
        return new Report(summary, detail);
    }
}
