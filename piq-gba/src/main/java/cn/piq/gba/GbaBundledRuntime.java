// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.gba;

import cn.piq.fcarcade.runtime.RuntimeInstaller;
import cn.piq.fcarcade.runtime.RuntimeCatalog.RuntimeId;
import cn.piq.fcarcade.runtime.RuntimeStartupState;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import java.nio.file.Path;
import java.util.List;
import java.util.EnumSet;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/** Pinned offline preparation, at load-complete on the loading worker. Never loads a DLL. */
public final class GbaBundledRuntime {
    private static final System.Logger LOGGER = System.getLogger(GbaBundledRuntime.class.getName());
    private GbaBundledRuntime() {}

    public static void prepare() {
        Path root;
        try { root = FMLPaths.GAMEDIR.get().toAbsolutePath().normalize(); }
        catch (RuntimeException | LinkageError failure) {
            LOGGER.log(System.Logger.Level.ERROR, "无法确定 GBA 运行库目录；未安装", failure);
            return;
        }
        try {
            var file = Objects.requireNonNull(ModList.get().getModFileById(GbaMod.ID), "加载器未提供 GBA 附属").getFile();
            RuntimeInstaller.registerBundledGba(file.getFilePath());
        } catch (RuntimeException | LinkageError failure) {
            remember(root, diagnostic(RuntimeInstaller.Outcome.FAILED, "GBA 内置来源注册失败；未安装", failure.toString()));
            return;
        }
        prepare(root, RuntimeInstaller.supportedPlatform(), () ->
                new RuntimeInstaller(root).install(EnumSet.of(RuntimeId.GBA),
                        () -> Thread.currentThread().isInterrupted(), progress -> {}));
    }

    @FunctionalInterface interface Preparation { RuntimeInstaller.Report run() throws Exception; }
    static RuntimeInstaller.Report prepare(Path root, boolean supported, Preparation work) {
        Objects.requireNonNull(root); Objects.requireNonNull(work);
        RuntimeInstaller.Report report;
        if (Thread.currentThread().isInterrupted())
            report = diagnostic(RuntimeInstaller.Outcome.CANCELLED, "GBA 运行库准备已取消", "加载线程已中断，未开始安装");
        else if (!supported)
            report = diagnostic(RuntimeInstaller.Outcome.BLOCKED, "GBA 内置运行库仅支持 Windows x64；未解压", "其它平台可以加载物品/旁观；不能在本机执行此核心");
        else try { report = Objects.requireNonNull(work.run(), "安装器没有返回结果"); }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            report = diagnostic(RuntimeInstaller.Outcome.CANCELLED, "GBA 运行库准备已取消", "加载线程被中断");
        } catch (CancellationException failure) {
            report = diagnostic(RuntimeInstaller.Outcome.CANCELLED, "GBA 运行库准备已取消", "请查看安装日志后重试");
        } catch (Exception | LinkageError failure) {
            report = diagnostic(RuntimeInstaller.Outcome.FAILED, "GBA 运行库准备失败；请查看运行环境诊断", failure.toString());
        }
        return remember(root, report);
    }
    private static RuntimeInstaller.Report diagnostic(RuntimeInstaller.Outcome result, String summary, String detail) {
        return new RuntimeInstaller.Report(result, summary, List.of(), RuntimeInstaller.PackState.MISSING, List.of(detail), 0, 0);
    }
    private static RuntimeInstaller.Report remember(Path root, RuntimeInstaller.Report report) {
        RuntimeStartupState.recordReport(root, report);
        LOGGER.log(report.ready() ? System.Logger.Level.INFO : System.Logger.Level.WARNING,
                "GBA 内置运行库：{0}；目录：{1}；详情：{2}", report.summary(), root, String.join("\n", report.details()));
        return report;
    }
}
