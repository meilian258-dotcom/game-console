# Native 实验本地同步接缝（FC33 / Native11）

日期：2026-09-12。范围：源码实现与隔离 QA；未启动 Minecraft 世界或进行远程服务器验收。

## 固定实验边界

- 原 `piq_native_arcade:mame` backend 保留传画面与网络 4 席声明；新 `registerSnapshotSync(id, 2, compatibilityId)` 仅给本地同步 2 席。
- 新策略需要服务端 `HOST_SNAPSHOT`。两台冷启动的初态仍不相等，不能移除全局初态校验；只有这个固定 profile 使用主持人快照引导。恢复后完整状态 SHA、逐帧输入与摘要校验不豁免。
- 只接受准确文件名与内容：`kof97.zip`、`mslug2.zip` 及相同目录下的 `neogeo.zip`。具体 SHA/大小见 `NativeSnapshotProfile`。未知游戏明确拒绝，传画面保持可用。
- Windows x64，固定 RTC `20000101000000` / `TZ=UTC0` / 32 次零输入 bootstrap，之后 native frame 32 对应联机 logical frame 0。
- 实际 fps `59.18560791015625`，48 kHz 双声道，320×224、4:3、rotation 0；物理核心协议仍四端口，但新适配仅传 P1/P2，后两端始终零。
- 临时独立目录只复制已验证 ROM/BIOS，不读写用户存档。正常退出不提供该实验游戏持久化存档承诺。

## 独立运行库（不覆盖旧 runtime）

位于游戏目录 `piq-native-arcade/runtime-snapshot-v1/`，仅以下三件。服务器不加载或执行它们。

| 文件 | 字节 | SHA-256 |
| --- | ---: | --- |
| piqneogeo_libretro.dll | 56494080 | E8F435903332AC80468769604779295A6965046DC25D4706A583D14D91C35201 |
| piq-snapshot-helper.jar | 43610 | F175B7CB60B37A95E1F5B2ED5FB48CE066FC1B6AE1ECD8089B8E6F957EF76C97 |
| jna-5.14.0.jar | 1878533 | 34ED1E1F27FA896BCA50DBC4E99CF3732967CEC387A7A0D5E3486C09673FE8C6 |

冻结来源分别是独立 Lab 的 `candidates/rtc-audio-lua-v2/` 与 `build/standalone/`；本轮不重新编译 C++/helper，不替换旧媒体运行库。

`compatibilityId = piq:neogeo-snapshot-v1:D569E1876BD29141843CBA6A666441227ECC707BC7D10C90E36E166B70AEFC28`。

## 调用与生命周期

`NativeCabinetBackend.prepareSyncFactory(Key, UUID, Connection)` 在客户端主线程捕获 runtime 绝对路径；返回专属一次性 `SnapshotFactory`。后台 `open(Path)` 不访问 Minecraft/世界/连接。直接 `openSync(Path)` 明确拒绝缺失的捕获上下文。

`SyncFactory.requestClose()` 与 `NativeSnapshotCore.requestClose()` 只发取消/精确进程结束信号，不执行 `retro_*`。实际 Step/Save/Load 都在单 worker，命令限时 15 秒；取消不等待 spawn monitor。constructor 发布新 child 后立即重验取消，finally 在真实 child 退出之后才回收临时目录和共享 NativeProcessSession slot。与旧传画面互斥，不会关闭别人的 JVM。

完整 state envelope 148 字节头 + 原生全部不透明状态，绑定 schema、固定 profile、ROM/BIOS、内部帧和 payload SHA。网络二参 `loadState(bytes, logicalFrame)` 必须 `internalFrame == logicalFrame + 32` 且不溢出，失败在 native LOAD 前拒绝。保留一参 API 仅作兼容/独立验证。快照上传间隔 1800 帧；FC 的完整状态摘要仍每 300 帧。

## 精确生产范围

修改既有：

- `cn/piq/nativearcade/NativeArcadeMod.class`：提取 public `registerBackend()` 并新增固定实验声明；common 无 client/bridge/JNA 探测。
- `cn/piq/nativearcade/client/NativeCabinetBackend.class`：新增模式可用性、捕获式同步 factory；原 media `open()` 内容不变。
- 既有 `NativeCabinetBackend$1.class` 原 media 匿名类可能仅 debug 行号变化；新 factory 使用命名嵌套类，避免旧匿名编号重排。

新增：

- `cn/piq/nativearcade/NativeSnapshotProfile.class`（纯 JDK common）。
- `cn/piq/nativearcade/bridge/NativeSnapshotWorkspace.class`、`$1.class`。
- `cn/piq/nativearcade/bridge/NativeSnapshotSession.class`。
- `cn/piq/nativearcade/bridge/NativeSnapshotState.class`、`$Decoded.class`。
- `cn/piq/nativearcade/client/NativeSnapshotCore.class`。
- `cn/piq/nativearcade/client/NativeCabinetBackend$SnapshotFactory.class`。

此外根本轮将原独立 step 原型接入正式 JAR 的 `NativeStepProtocol`/相关已批准 bridge 类；它们不由此新适配重写。没有资源、模型、PNG、运行库字节或新网络 payload 修改。

## 窄 QA

- `tools/check_native_snapshot_unit.py --native FINAL_NATIVE --report NEW_JSON`：仅编 18 项实际 JUnit tests；无核心运行，无 skip。
- `tools/check_native_snapshot_boundary.py --fc FINAL_FC --native FINAL_NATIVE --kof97 READ_ONLY_ROM --mslug2 READ_ONLY_ROM --bios READ_ONLY_BIOS --report NEW_JSON`：仅编实际 adapter 两父/子进程探针，真实冻结核心。逻辑 0 冷恢复 + 600 帧完整 RGB/PCM/state 每帧比较；不忽略任何字节；错 logical frame 拒绝后原生状态不变；原 ROM/BIOS/runtime SHA、大小、mtime 和源围栏重验。
- 不给两个 `--fc/--native` 时是明确标记的 production-source-subset；不能冒称 final-jar-only。最终冻结后由同工具绑定双最终 JAR 复验。

本记录不代替两台 Minecraft、远程服务器或实际网络抖动验收。
