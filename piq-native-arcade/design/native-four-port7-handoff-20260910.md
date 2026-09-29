# Native alpha7 四端口链路交接

修改者：Codex `/root/fix_sfc_av`。记录时间：2026-09-10 23:48 Asia/Shanghai。
状态：本子任务生产源码冻结，等待根代理统一 FC21/Native7 构建及最终 JAR 复验；未安装、未发布、未修改用户运行库。

## 实现边界

- `NativeProcessSession` 支持 `maxPlayers()=4`、`offerInputs(p1,p2,p3,p4)`、零基 `releasePort(0..3)`。旧 `offerInput(p1,p2)` 保留，额外两口为零。
- 私有 Java 子进程管道为 v2；新增 `INPUT4`（四个 32-bit big-endian mask，合法值 0..65535）及 `RELEASE_PORT`（一个 32-bit port）。旧 `INPUT` 命令读取仍支持双口；版本握手不允许旧 v1 helper 假兼容。
- 新 `NativeInputPorts` 为每口各 128 条边沿 FIFO，每个模拟帧各口最多消费一条，溢出整批拒绝；单口释放清该口 held/latest/全部排队边沿，不丢其他端口边沿。
- 父进程待发送队列为 128 条正常输入加 4 个控制预留槽。释放单口保留其他口的已有排队状态，同时把被释放口从旧快照中清零，随后发送专用释放命令。
- helper 在 `retro_run` 前取四口一致快照；真实输入回调允许 port 0..3，max-users 返回 4。原有 ABGR、DAR/旋转、48 kHz PCM、帧上限、MAME ABI 和 DLL 均不改。
- `NativeCabinetBackend` 转发三项新 API；generic backend 声明可注册 4 口网络能力。服务器房间、租约、视频音频转发由 FC21 根代理实现，不由 Native 附属绕过验证。
- **旧独立 Native block/GUI 仍只允许 Windows x64、未发布局域网的单人世界**；`NativeArcadeClient.supported/current`、身份验证及实际使用入口没有改变。
- 保持仅一个活跃本地原生子进程、精确 PID 关闭、15 秒无帧 watchdog；进程隔离不等于 OS 安全沙箱。

## 精确生产差异

Native JAR：

- 修改 `cn/piq/nativearcade/bridge/BridgeProtocol.class`
- 修改 `cn/piq/nativearcade/bridge/NativeProcessSession.class` 与 `$Input.class`；`$Frame` 源码未变
- 新增 `cn/piq/nativearcade/bridge/NativeInputPorts.class`
- 修改 `cn/piq/nativearcade/client/NativeCabinetBackend$1.class`（外围类仅属性变化如出现需核对）
- 修改 `cn/piq/nativearcade/NativeArcadeMod.class`
- `gradle.properties` alpha7；build 本地依赖 FC21；metadata 最小 FC `[0.31.0-alpha.21,0.32.0)`，描述更新。
- 无模型、材质、语言或其他游戏资产变化；ROM 暂存与依赖包白名单 `NativeRomStaging` 未改。

独立 helper JAR：`NativeCoreWorker$Engine` 和 `BridgeProtocol` 修改，新增 `NativeInputPorts`，删除旧 `$Buttons` record；外围 NestMembers 属性随之改变。只在该独立进程包含 JNA 依赖，未将 JNA/MAME DLL 加入主模组。

## 本轮新 runtime

路径：`build/native-four-port7/runtime/`（QA/待根代理冻结交付，不是实例目录）。

| 文件 | SHA-256 |
|---|---|
| piq-native-helper.jar (19022 B) | 229268989AD4E263277FDF0BD5D49E59F69EEB3E980B948DD1D3628182437120 |
| mame_libretro.dll | 6172A988AB67FE68F4177A6FC8FBB82619EB2044C330930F0F572F7B1EDC2301 |
| jna-5.14.0.jar | 34ED1E1F27FA896BCA50DBC4E99CF3732967CEC387A7A0D5E3486C09673FE8C6 |

后两文件只是旧冻结原件的哈希相同复制；旧交付 helper 及用户 runtime 未写入。交付 alpha7 时须带此 v2 helper，不能误配旧 v1。

## 已完成验证

1. 纯生产 FIFO JUnit **10/10**：各口全部 16 bits、同步四口边沿、单口释放、别口快按、重复去重、队列溢出原子性、重新领取不回放旧输入、非法值无副作用、防外部修改返回帧、显式整局 clear。
2. 实际 helper 命令解析/输入回调 **4183 断言**：直接执行生产 `NativeCoreWorker.Engine`，四口逐 bit 回调、max-users=4、四种单口释放后的剩余边沿、旧双口命令和错误端口拒绝。此项不加载 MAME DLL。
3. 实际 `NativeProcessSession` + 新 helper + 冻结 MAME DLL，原创 8080 诊断固件 **14 断言**：P1/P2 实际帧改变，释放 P3/P4 不影响 P1，释放 P1 不影响 P2，其他口释放不吞 P1 快按，旧双口调用、clear-all、精确子进程回收。
4. 旧 `NativeBridgeProbe` **24 检查**通过；中文带空格 runtime 路径可用；真实父 JVM orderly-shutdown 后精确子进程退出；无帧私有 helper 在 **15463 ms** 被回收。

证据：

- `design/native-four-port-helper-20260911.json`
- `design/native-four-port-real-20260911.json`
- `design/native-four-port-legacy-regression-20260911/audit.json`
- 纯 JUnit runner：`tools/qa/NativeInputPortsTestRunner.java`（本次 10 found/10 succeeded/0 failed/0 aborted）。

报告 suffix 保留工具创建时命名，实际执行日期以上述记录为准。

## 最终包复验入口

`tools/check_native_four_ports.py --jar <最终Native7.jar> --runtime <新runtime目录> --report <不存在的新JSON>`

该模式只编译 QA probes，复制并核对两份 JAR SHA 到 ASCII 临时目录，核验实际 Engine 和 NativeProcessSession 的 CodeSource，输出 `production_origin: final-jar-only`，不会从生产源重编或借用 build classes。

## 不能据此宣称的能力

未启动 Minecraft、未测试真实跨机器服务器、未访问商业 ROM、未验证某款 3/4 人游戏驱动实际使用全部四口。原创 invaders 硬件仅有其固有输入线；四口完整 mask 的证据来自实际 helper parser/callback，不冒充四人商业游戏测试。主机断网/租约/媒体中转验证仍由 FC21 集成阶段负责。
