// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.nativearcade;

import cn.piq.fcarcade.runtime.RuntimeCatalog.RuntimeId;
import cn.piq.fcarcade.runtime.RuntimeInstaller;
import cn.piq.fcarcade.runtime.RuntimeStartupState;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/** Called synchronously by the parallel common-setup lifecycle, never by core launch or a tick. */
public final class NativeBundledRuntime {
    private static final System.Logger LOGGER = System.getLogger(NativeBundledRuntime.class.getName());

    private NativeBundledRuntime() {}

    public static void prepare() {
        Path root;
        try {
            root = FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
        } catch (RuntimeException | LinkageError failure) {
            // A bad loading environment must not crash a Linux/media-only client with this add-on.
            LOGGER.log(System.Logger.Level.ERROR, "无法确定街机运行库启动目录；未继续安装，请检查加载日志", failure);
            return;
        }
        try {
            // The trusted loaded mod file exposes SecureJar.getPrimaryPath(), not its union:/ root.
            // Pass the outer archive to the fixed ZipFile source: class-resource streams can make
            // SecureJar/zipfs buffer the whole 372 MB DLL. Never fall back to that resource route.
            var modFile = Objects.requireNonNull(ModList.get().getModFileById(NativeArcadeMod.MOD_ID),
                    "加载器未提供街机附属文件").getFile();
            Path archive = Objects.requireNonNull(modFile.getFilePath(), "加载器未提供街机附属 JAR 路径");
            if (archive.getFileSystem() != FileSystems.getDefault())
                throw new IllegalStateException("街机内置库需要独立放置于 mods 的外层 JAR；不支持嵌套或虚拟文件系统");
            // Declaration remains before the platform guard so receive-only clients can identify
            // bundled components. Registration must not open or extract their large resources.
            RuntimeInstaller.registerBundledArcade(archive);
        } catch (RuntimeException | LinkageError failure) {
            remember(root, diagnostic(RuntimeInstaller.Outcome.FAILED, "街机内置运行库来源注册失败；未安装",
                    failure.getClass().getSimpleName() + ": " + Objects.toString(failure.getMessage(), "无详细信息")));
            LOGGER.log(System.Logger.Level.ERROR, "街机内置运行库来源注册失败：" + root, failure);
            return;
        }
        prepare(root, RuntimeInstaller.supportedPlatform(), System.getProperty("os.arch", ""), () ->
                new RuntimeInstaller(root).install(EnumSet.of(RuntimeId.MAME, RuntimeId.NEOGEO_SNAPSHOT),
                        () -> Thread.currentThread().isInterrupted(), progress -> {}));
    }

    @FunctionalInterface
    interface Preparation {
        RuntimeInstaller.Report run() throws Exception;
    }

    /** Inert test seam. The production caller supplies only the pinned installer above. */
    static RuntimeInstaller.Report prepare(Path gameRoot, boolean platformSupported, String architecture, Preparation work) {
        Path root = Objects.requireNonNull(gameRoot, "gameRoot").toAbsolutePath().normalize();
        Objects.requireNonNull(work, "work");
        RuntimeInstaller.Report report;
        if (Thread.currentThread().isInterrupted()) {
            report = diagnostic(RuntimeInstaller.Outcome.CANCELLED, "街机内置运行库准备已取消；未开始安装", "启动线程已被中断");
        } else if (!platformSupported || !"amd64".equals(architecture)) {
            report = diagnostic(RuntimeInstaller.Outcome.BLOCKED, "街机内置运行库仅支持 Windows x64（JVM amd64）；未解压",
                    "普通 MAME 桥要求 os.arch=amd64；当前为 " + architecture + "。仅接收音画或旁观可返回继续。");
        } else {
            try {
                report = Objects.requireNonNull(work.run(), "启动安装器未返回报告");
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
                report = diagnostic(RuntimeInstaller.Outcome.CANCELLED, "街机内置运行库准备已取消", "等待被中断；请查看安装清理日志后重启重试");
            } catch (CancellationException cancelled) {
                report = diagnostic(RuntimeInstaller.Outcome.CANCELLED, "街机内置运行库准备已取消", "原文件不应覆盖；请查看安装清理日志后重试");
            } catch (Exception | LinkageError failure) {
                report = diagnostic(RuntimeInstaller.Outcome.FAILED, "街机内置运行库准备失败；请打开运行环境诊断",
                        failure.getClass().getSimpleName() + ": " + Objects.toString(failure.getMessage(), "无详细信息"));
                LOGGER.log(System.Logger.Level.ERROR, "街机内置运行库准备失败：" + root, failure);
            }
        }
        return remember(root, report);
    }

    private static RuntimeInstaller.Report remember(Path root, RuntimeInstaller.Report report) {
        RuntimeStartupState.recordReport(root, report);
        LOGGER.log(report.ready() ? System.Logger.Level.INFO : System.Logger.Level.WARNING,
                "街机运行库启动检查：{0}；目录：{1}", report.summary(), root);
        if (!report.ready() && !report.details().isEmpty())
            LOGGER.log(System.Logger.Level.WARNING, "街机运行库诊断：{0}", String.join("\n", report.details()));
        return report;
    }

    private static RuntimeInstaller.Report diagnostic(RuntimeInstaller.Outcome outcome, String summary, String detail) {
        // Empty runtime statuses mean no file inspection result is being invented here.
        return new RuntimeInstaller.Report(outcome, summary, List.of(), RuntimeInstaller.PackState.MISSING,
                List.of(detail, "内置资源尚未完成检查；此结果不表示缺少外置 ZIP。"), 0, 0);
    }
}
