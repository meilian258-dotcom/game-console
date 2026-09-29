# SFC AV 修复与 Native 统一机柜接入交接

修改者：Codex `/root/fix_sfc_av`；时间：2026-09-10 13:00 +08:00。
组件：像素匠 FC AV 交互、SFC Home 单个方块交互文件、Native/MAME 附属客户端提供器。
此文供根代理合并 `Codex维护手册.md`；本子任务未并行修改该手册，未运行 Gradle、安装或发布。

## SFC AV 实际根因与修复

`ExternalHomeConsoleBlockEntity.kind()` 原本已是 CONSOLE，问题不在端点类型。
SFC 的 `useItemOn` 允许执行默认方块交互后，原 `useWithoutItem` 不检查手中物品就返回成功，吞掉后续 AV 物品的 `useOn`。因此用户点电视选中端点、再点 SFC 并未真正操作 AV，下一次点电视就得到同类端点提示。

- `piq-sfc-home/src/main/java/cn/piq/sfchome/world/SfcHomeConsoleBlock.java`：只有双手真正为空才执行空手行为；持有任意物品时返回 PASS，让 AV 继续到物品交互。
- `piq-fc-arcade/src/main/java/cn/piq/fcarcade/home/AvCableItem.java`：客户端预测识别已经加载、有效的外部主机端点。服务端接线逻辑保持不变。
- 未修改 UUID、收费退款、权限、结构/多格验证、账本实现。

真实生产类探针：`piq-fc-arcade/tools/check_sfc_cable_interaction.py` 与 `tools/qa/ActualSfcCableDispatchProbe.java`。
报告：`piq-fc-arcade/design/sfc-av-real-class-regression-20260910.json`。
结果：382 个断言通过，覆盖 FC / 四格小霸王 / SFC × 六种电视布局 × 两端操作顺序，共 36 正向组合；以及 18 个权限、加载、身份、结构不完整、错误手持物负例，并验证客户端不变更状态。测试实际调用当前生产 AvCableItem/SfcHomeConsoleBlock 与冻结 FC/SFC 的真实 HomeHardware/账本/端点实现；仅用 Unsafe 构造受控世界/玩家夹具，不替换生产方法。不是 Minecraft 世界内实测，也不是第三方领地插件验收。

## Native 统一机柜接入

- `NativeArcadeMod.java`：common 注册 `piq_native_arcade:mame`，`localOnly=true`；移除独立创造标签页，保留旧方块/物品/方块实体注册和历史类。
- `client/NativeArcadeClient.java`：客户端 setup 注册 `NativeCabinetBackend`；原旧机柜渲染入口保留。
- `client/NativeCabinetBackend.java`：包装 `NativeProcessSession` 为主模组 `CabinetEmulator`，转发输入、帧、清空与关闭；主模组拥有机柜/界面/租约，Native 只拥有隔离子进程。
- 主模组未引入 JNA/DLL；Native common 未链接 MC 或 FC 客户端 API。
- `bridge/NativeRomStaging.java` + `NativeProcessSession.java`：只暂存用户指定游戏 ZIP，以及同目录精确文件名 `neogeo.zip` / `qsound_hle.zip`。单包 22 字节至 64 MiB，合计不超过 128 MiB；不扫描目录、不解压 ZIP、不执行包内程序；拒绝链接/非普通文件，NOFOLLOW/CREATE_NEW，检查复制前后大小、时间与 fileKey。未知 BIOS/CHD 依赖不宣称支持。

## 验证

1. `tools/check_native_staging.py` 调用真实生产 staging 和六个 JUnit 方法：6/6 PASS。包括精确两个 BIOS、缺少可选 BIOS、异常类型/尺寸、目标不覆盖、driver 校验和单包/总额边界。
2. `design/unified-process-staging-regression-20260910/audit.json`：真实 MAME 冻结运行库 + 新源码，原创测试固件 24 项 PASS；父 JVM 正常退出会回收精确子进程，15 秒停滞 watchdog 回收 PASS。未改变 helper 协议/核心。
3. Python unittest 全部 35/35 PASS。旧 alpha.1 测试从冻结 source-v2 ZIP（SHA `1647EF02747741F5E11F2E1E808E9D653C68711C9AA12E58004BF2CB92C7DE5A`）读取源码/TOML；故意破坏 UV、源码 pin 和当前 alpha.2 被旧验包器拒绝均有断言。`verify_native_delivery.py` 的 alpha.1 规则未改动。
4. `tools/verify_native_unified_delivery.py` / `design/unified-alpha2-final-independent-audit-20260910.json`：最终 alpha.2 JAR SHA `B3DC23F4DFAE53CD87730FEBB7668D470D111ADDCEE577803B09351CE34EFA56`，八个源码 pin，41 个 Java21 类、28 个 common 类隔离、SPI/local-only/客户端适配/允许名单、旧类保留、runtime/helper hash、完整协议语义均 PASS；最终 JAR 纯 Java 协议与未裁剪 UV 探针 118 PASS。

## 用户 KOF97 的实际兼容结果

在用户授权下，只复制指定的 `kof97.zip` 和 `neogeo.zip` 至 `private-qa` 后测试，没有执行下载的 WinKawaks EXE，没有修改 E 盘原件。最终 alpha.2 JAR + 冻结 MAME 0.289/helper 持续运行 42 秒，得到 320×224 视频、2,207 次有效帧读取、707 次变化、1,258,088 个非零 PCM 样本；精确原生子进程已回收。

人工查看实际输出截图确认：24 秒 CREDIT 00；26 秒投币后 28 秒标题显示 CREDIT 01 / PRESS 1P BUTTON；30 秒开始后进入操作说明和 ADVANCED / EXTRA 模式选择，CREDIT 回到 00。已证明加载、视频、音频、投币和 P1 开始，不宣称通关、实际 MC 世界内游玩或恐龙快打已测。

- KOF97 SHA：`804F892924D4650545D3EA2D19FB85670094DC46DB882FECAF3E03009E2C4B9F`
- NeoGeo BIOS SHA：`E1FFD4AB180E2F6AA4A3AA4D2C6F991E19D8EF762BEC8B5A6283704A2EDC3BBC`
- 原始用户文件测试前后 hash 相同。
- 本地证据：`private-qa/kof97-user-20260910-final-jar/audit.json` 与 `screens/frame-{24000,28000,34000,39000}.png`。
- **整个 `private-qa/**` 必须排除在源代码 ZIP、交付归档和上传之外。**

边界：原生子进程隔离不是操作系统安全沙箱；父进程被操作系统强杀/崩溃的完整清理不在正常 shutdown hook 测试承诺内。当前 Native 后端仅 Windows x64、本地未开放局域网的单人会话；不宣称全街机游戏通用兼容。

当前状态：上述生产改动与根代理最终 JAR 已冻结，工具测试和独立审计完成；由根代理统一维护记录及安装交付。
