# GBA 个人掌机客户端技术记录（2026-09-12）

范围：GBA 客户端、纯辅助逻辑与 QA。物品注册、模型渲染与最终构建须分别核对。

## 生产范围

- `client/GbaHandheldClient.java`（内部类 `Setup`、`Binding`、`Play`）：client-only 空中/方块/实体两事件，主手右键开/关、副手吞掉重复交互；Shift 右键配置；精确上下文、输入、帧贴图和清理。
- `client/GbaHandheldScreen.java`：300×214 居中原版按钮设置页，320×240 可用；复用既有本机 ROM picker、键盘设置和实体手柄设置。不是全屏模拟器窗口。
- `client/GbaHandheldAudio.java`（内部 record `Chunk`）：48 kHz / signed 16-bit / stereo，3 个 50 ms 块队列；音频设备操作只在 daemon 上，失败只静音。
- `client/GbaHandheldGate.java`（内部类 `UseGate`）：纯实际连接/世界 identity、玩家/主手槽/组件/count/生存状态门禁；0xDFD 掩码；同按住右键不重复开关。
- `client/GbaHandheldSelectionStore.java`：本机、世界/服务器+玩家隔离的 ROM 路径选择；8 KiB 上限、逐父目录拒重定向、单文件原子替换。

旧 `GbaCabinetBackend` 仅有说明文本变化，bridge/helper/核心、服务器协议或资源不变；没有新增或删除旧生产 class。

## 公共只读显示 API

`GbaHandheldClient.screenTexture()`：真实运行时返回 240×160 ABGR DynamicTexture 的 ResourceLocation，首帧前或未运行 null；nearest filtering。`running()`：核心 ready 且当前连接/手持有效。`visualInputMask()`：相同门禁下的本机十键 mask，否则 0。`visualMatches(ItemStack)`：额外要求传入 stack 正是本机主手 stack；渲染器须另限第一人称。无他人画面共享。

## 生命周期与保存

1. 捕获实际 client listener、Connection 对象、ClientLevel 对象、玩家 UUID、主手热栏槽和 item/components 副本。count 必须 1。切槽/物品组件变化/死亡/旁观模式/断线/换世界即关闭；同维度行走不关闭。
2. 只有实际 `world:<完整 LevelResource.ROOT 路径>` 或 `server:<当前 server.ip>` 可以产生 `GbaSaveScope`。不伪造 CabinetRomBindings.Key、不申请街机席位、不发 ROM/输入网络包。
3. 先 acquire 共享 CabinetClientOwner，拒绝旧 FC 控制席及别的输入 owner，再构造原 GbaProcessSession（内部自身异步启动及固定运行库 SHA 校验）。原全局 ACTIVE 仍禁止本机第二个 GBA。
4. 与原 GBA 街机共用 **同 context / player / ROM 电池存档**；不是随物品 NBT 复制的存档。目录不改变、不迁移旧存档。关闭走原非阻塞 SRAM 收尾，不谎称收尾已成功；强杀游戏进程/设备断电仍可能丢失最近未自动保存的进度。
5. `ControllerCapture.registerRuntime` 加入唯一聚合器，未替换 presenceRefresh。共享 SFC 键盘/实体手柄方案，0xDFD 去掉不存在的 X/Y。Z（或用户改后的键）控制世界移动锁，保留鼠标与右键关机。LEGACY 在开机、attach 前捕获公开有效 SFC 主键，包含鼠标 fallback；Minecraft 原绑定运行中改变须重新开掌机，UI tooltip 已说明；其它共享 preset/custom 即时生效。
6. GUI/失焦清 core FIFO、键盘和手柄 neutral rearm；只丢输入与 PCM，不停游戏。持续 drain 音频，恢复第一帧 PCM 也丢弃，避免最小化不 tick 后累计音频爆发。音频 generation 使旧队列失效。
7. 设置异步 IO callback 必须原 screen / revision / held context 仍有效；Esc、切页、关闭或失去物品不能迟到启动。只在成功接受用户 Start 后异步记住路径；不写物品、不写服务器。首个无选择的普通右键打开设置，有已记住选择时经校验直接开始。

## 验证

工具 `tools/check_gba_handheld_client.py --fc <最终 FC33.jar> --report <新文件>`：只在独立临时目录编译新客户端及必要 GBA common/bridge，使用真实 MC/NeoForge API，不 Gradle、不包装。

追加 `--jar <最终 GBA3.jar>` 切换 **final-jar-only**：仅编译探针（空 sourcepath），4 个纯生产 class 的 CodeSource 必须来自输入 GBA JAR，4359 纯断言之外另计 4 个 origin 断言。报告绑定 FC/GBA 路径与 SHA、probe 源码 SHA，`production_compiled=false`。新增测试工具支持 `--gba` 同义参数。

`design/handheld-client-source-v6.json`：编译通过，纯门禁/真实临时目录选择存储 **4359 断言**（其中 4096 是全部 12-bit mask 枚举，不是 4359 种交互功能）。验证实际对象 identity、槽、玩家、组件/count事实、死亡/断线、50轮持按去重、本机存储隔离/Unicode/非法长度和路径。

测试未启动 Minecraft 世界、窗口、音频设备、物理手柄或原生核心；旧 bridge 的真实核心与存档回归仍需针对最终成品复跑。这不等于游戏内掌机模型、小屏可读性或键鼠互动实测。
