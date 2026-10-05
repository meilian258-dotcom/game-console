// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.server;

import cn.piq.fcarcade.home.content.ContentScanReport;
import cn.piq.sfchome.net.SfcEditorStatus;
import cn.piq.sfchome.net.SfcHomeNetwork;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Independent, bounded directory results. Failed scans retain display data, never grant write permission. */
final class SfcEditorCatalog {
    @FunctionalInterface interface Scan<T> { T read() throws Exception; }
    record Result(List<SfcHomeNetwork.RomEntry> roms, List<String> covers, String romStatus, String coverStatus) {
        Result { roms = List.copyOf(roms); covers = List.copyOf(covers); }
        String status() { return status(true); }
        String status(boolean canUseCover) {
            // Permission can change while the scan is running. Do not advertise a now-inaccessible cover list.
            return SfcEditorStatus.PREFIX + SfcEditorStatus.compact(romStatus, 120) + "；"
                    + SfcEditorStatus.compact(canUseCover ? coverStatus : "封面：无服务器封面使用权限", 120);
        }
    }
    private SfcEditorCatalog() {}
    static Result scan(Scan<ContentScanReport<SfcHomeNetwork.RomEntry>> romScan,
                       Scan<List<String>> coverScan, List<SfcHomeNetwork.RomEntry> previousRoms,
                       List<String> previousCovers, boolean canUseCover) {
        List<SfcHomeNetwork.RomEntry> roms = previousRoms;
        List<String> covers = canUseCover ? previousCovers : List.of();
        String romStatus, coverStatus;
        try {
            var report = romScan.read();
            roms = report.entries();
            String details = new String(report.diagnostics(), StandardCharsets.UTF_8).replace('\n', ' ');
            romStatus = report.summary("ROM") + (details.isBlank() ? "" : "；" + details);
        } catch (Exception error) {
            romStatus = failed("ROM", previousRoms.size(), error);
        }
        if (!canUseCover) coverStatus = "封面：无服务器封面使用权限";
        else try {
            covers = List.copyOf(coverScan.read());
            coverStatus = "封面：" + covers.size() + " 项可用";
        } catch (Exception error) {
            coverStatus = failed("封面", previousCovers.size(), error);
        }
        // Both categories always fit the existing Editor.message 256-character budget.
        return new Result(roms, covers, romStatus, coverStatus);
    }
    private static String failed(String type, int previous, Exception error) {
        return type + "读取失败（保留上次 " + previous + " 项，未重新验证）："
                + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
    }
}
