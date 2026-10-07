# GBA 退出存档修复独立复核 · 2026-09-13

新增 JVM shutdown hook / 应用退出显式 shutdown 的方案可解决旧 daemon owner 在正常 System.exit 时来不及保存的问题。独立复核进一步修复以下时序风险，仅修改获授权的两个源文件，不改 helper/DLL、版本、资源、存档格式或隔离命名。

## 精确生产范围

- `src/main/java/cn/piq/gba/bridge/GbaProcessSession.java`：ProcessBuilder.start 移出 LAUNCH_LOCK；session 仍在启动前登记，晚到子进程发布后立即检查关闭并仅回收自身。首次 shutdown 确定共同 5 秒保存 + 总 6 秒收尾期限，之后显式调用/JVM hook 不重新计时。真正仍存活的子进程保留 ACTIVE/临时 runtime，由独立 exact-child reaper 等到死亡再完成清理。新增 package-private `ChildLauncher` 和构造重载仅提供可控竞态测试缝；公开构造仍调用真实 ProcessBuilder.start，原 runtime SHA/ROM 上限/存档校验保持。
- `src/main/java/cn/piq/gba/client/GbaHandheldClient.java`：stop 先请求 core/audio 异步关闭，再释放输入/纹理，避免可选清理异常跳过存档。保留原 shutdown 的 finally 调用 bridge.shutdown。

新增 class 仅 `GbaProcessSession$ChildLauncher`；两个 outer class 修改。现有 Frame、Binding/Play 等嵌套可能仅重编行号差异，未改其字段/行为。没有新增 native 或资源。

## 验证结果

`source-v1/report.json`，SHA256 `A266FB59EC241B2EED4342E3BF27EBCB2ED39E4D4EDACF371412E81A1C501DD5`。

- 模式明确为 isolated-source：仅隔离编译上述两个生产源；真实 MC API 编译通过。
- 9 个独立 JVM 场景，367 条断言：正常 await、两次 close 后立即 System.exit、一次不调用 close 的 hook-only 全部实际保存 SRAM[0]=3，文件各 32768 bytes 且完整 SHA 一致。
- 四次实际游戏探针全部使用 `tools/diagnostic_rom.py` 原创 ARM 指令诊断；没有商业 ROM/用户存档。真实固定 mGBA/JNA/helper 保持原 SHA，所有实际产生的 exact child PID 在探针退出后均不存活。
- 延迟 spawn 普通 close / shutdown、构造异常、inactive、受控阻塞 IPC/拒绝终止均验证。普通 close 实测约 17–34 微秒；受控 timeout 首次约 6.003 秒，重复 shutdown 约 0.78 毫秒，不重新等待；ACTIVE 保持到受控子进程死亡。
- 真实 JVM hook 与 owner 存档链被执行；handheld 的异常 finally/清理顺序另以最终可复用 ASM 接线校验，不冒称运行过 Minecraft GUI。

工具 `tools/check_gba_shutdown.py` 与 `tools/qa/GbaShutdownRegression.java` 已冻结。最终成品复验（默认 final-jar-only，不编译生产；输出目录不得已存在）：

```text
python piq-gba/tools/check_gba_shutdown.py --gba <最终GBA.jar> --fc <最终FC.jar> --runtime <固定三文件runtime目录> --output <新的QA目录>
```

## 边界

这是正常 JVM 退出，不覆盖断电、任务管理器强杀或 Runtime.halt。6 秒限制 Java 等待，不应描述为任意异常 OS 创建/终止进程、文件系统驱动都能硬实时完成；一旦晚到子进程返回，仍按精确身份清理。timeout 场景使用明确标注的受控 Process，不是假称真实核心挂死。保留历史审查 outputs，不修改或覆盖。生成的诊断 ROM/SRAM 只用于隔离 QA，交付源包应包含生成代码和报告，不复制这些生成的二进制到用户运行目录。
