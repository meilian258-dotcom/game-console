# GBA 独立试玩附属：只读可行性核验

日期：2026-09-11。基线为已交 alpha29。本文是接入设计与验收范围，不是成品、运行报告或兼容性保证。未改生产源码、原素材、ROM、BIOS、存档；未下载/执行核心或附件脚本，未构建/安装/启动游戏。

## 结论

单人 GBA 可以试作，难度中等且可隔离，不需改 FC/SFC 模拟算法。建议新增 `piq-gba` 附属（拟 `piq_gba`），仅依赖当前 FC 主包内公共 API，不依赖 MAME 附属。第一版 Windows x64、单人、本地合法 `.gba` 文件；先通过真实核心诊断，再接手持模型。不要把 GBA 联机线/交换宝可梦等功能当成现有街机多人席位。

## 已有模型

- 本地同字节原件：`piq-fc-arcade/design/refined-dual-zapper-20260911/original.zip`。两者 SHA256 均 `8E4B502BA203892D7580D9B3A0DB4308737C70AA7543905AD42AFB8DA62C3FC7`。
- ZIP 成员 `03_GBA_经典横版/` 有 BBmodel、Java JSON、唯一 PNG、屏幕模板、按键映射、说明、预览。当前 local source 仅提取了 01/02，GBA 仍在 original.zip，不能说已接入。
- 实际 JSON 735 元素、451 元素含 rotation；紫色横版 AGB-001。bounds 约 `[3.75,0,5.56]..[12.25,1.443,10.482]` 模型单位，16 单位/格；原宽 0.53125 格。无需重做外形或 PNG。
- 实时屏元素 `可替换游戏画面`：from `[6.2,1.294,6.61]`、to `[9.8,1.301,9.01]`，唯一 up 面；实际屏幕 3.6×2.4，3:2。应在原面稍高处绘 240×160 动态纹理，不能把每帧写回 2048 PNG。
- 原 PNG SHA256 `376FB935DEB9D6F5F4682A24FC4DF94D5EF9A5793D14B4255F573FE6FF921BCC`；屏幕 atlas `[880,32,720,480]`。透明皮肤遮罩必须保留。
- 独立 dpad/A/B/Start/Select/L/R 共 7 动效组；power_switch 另组。十字键一整块，用旋转倾斜；动作位移是模型单位，不是方块单位。

## 真实接缝与不能直接复用的部分

公共 `piq-retro-platform/src/main/java/cn/piq/retro/api/RetroEmulator.java`、`RetroFrame.java`、`RetroFactoryRegistry.java` 已实际内置 FC。边界可直接消费 GBA 单核帧：RGBA/ABGR、48kHz 双声道、异步 ready/error、清键和关闭。键位保持公共 12bit 顺序，仅使用 A、B、方向、Start、Select、L、R；P2=0。

`piq-fc-arcade/src/main/java/cn/piq/fcarcade/client/cabinet/CabinetBackend.java` 可注册显式 `.gba` 后缀和目录，沿用 picker/保存所选路径。`CabinetBackends` 普通声明可做 localOnly 单人测试入口，不调用 `registerNetwork`；后者仅 2..4 人。手持 GBA 不能伪造 CabinetTarget，需独立持有物品 UUID/连接身份和客户端宿主，复用输入所有权而不借用旧机柜租约。

旧 `piq-native-arcade/.../NativeProcessSession.java` 的构造/启动（42、106 行附近）固定 `mame_libretro.dll`、核心/helper SHA、ZIP staging 和全局进程所有权。`helper/.../NativeCoreWorker.java` 固定 ZIP、`NativeArcadeButtons.toMame`、XRGB8888 和 48kHz，接口也没有 SaveRAM/serialize。它是 MAME 专用桥，不能改名 DLL 或放宽一行校验来接 GBA；本版应完整保护旧 Native/helper 字节。

## 官方核验与新增依赖

[mGBA 下载页](https://mgba.io/downloads.html) 当前正式版 0.10.5，提供 Windows x64 等平台；[官方 FAQ](https://mgba.io/faq.html) 提醒 libretro 与 standalone 可能不同步。因此要固定真实核心构建来源/提交/SHA，不能把滚动 nightly 标签当版本保证。

[libretro mGBA 文档](https://docs.libretro.com/library/mgba/) 明确 MPL2.0、GBA BIOS 可选、SaveRAM/状态可用，但 Netplay/Subsystem 标不支持。首版使用内置 BIOS 路径，不要求/分发 Nintendo BIOS；不得将 standalone 同电脑 link 功能推断为本附属跨机器 link。

[官方 0.10.5 libretro 源码](https://raw.githubusercontent.com/mgba-emu/mgba/0.10.5/src/platform/libretro/libretro.c) 第30行音频 32768Hz、103附近是 port0 按键映射、1063附近 SaveRAM 指针/长度；[同版 CMake](https://raw.githubusercontent.com/mgba-emu/mgba/0.10.5/CMakeLists.txt) 902附近 libretro target 默认 RGB565。这是新增 GBA helper 必须处理的格式，不应修改主包 48kHz 帧合同。系统所需新增二进制为审核固定的 mGBA libretro core；JNA/独立 Java 子进程设计可沿现做法复用，不在 MC JVM 直接载 DLL。

[许可证原文](https://raw.githubusercontent.com/mgba-emu/mgba/0.10.5/LICENSE) 3.2要求提供对应 covered source 获取方式，3.4保留 notices。交付要附版本、来源、SHA、许可证和对应源码/构建说明，并审第三方依赖 notices。没有下载/验证候选 DLL，所以目前不声明二进制已经可信或依赖已经齐全。命令行 PATH 未找到 CMake/GCC/Clang，不代表系统其它路径完全没有工具链。

## 最小试玩与验收门槛

1. 新隔离 GBA helper：只接受已审核核心和 bounded `.gba` staging；RGB565/XRGB8888 按真实 pitch 解码；32768→48000 连续双声道重采样；核心回调只复制有界数据，压缩/渲染不进核心线程。按原生窗口格式实际读取，不套 MAME 色序/旋转补偿。
2. 一个核心、一个输入 owner、一个进程；连接切换/GUI失焦/切手/死亡清键，关闭仅精确本子进程，不能按进程名杀应用。第一版不提供跨服输入、P2-4、ROM 网络上传或 GBA link。
3. 基础 SaveRAM 必须作为最小可玩能力：新独立目录按 ROM SHA+核心版本隔离，实际长度有界、原子保存、旧备份可回退；不写原 ROM 旁的 .sav，不读取/覆盖用户原存档。不把 quick-state 与电池存档混用；首版无快速状态 UI 可明确限制。
4. 真实自制 GBA 诊断 ROM，验证 240×160、3:2、十个逻辑键、短按边沿、GUI neutral、pitch/RGB565、PCM 时钟连续；双重开关/坏 DLL/不支持架构/崩溃超时隔离；SaveRAM 保存退出重启后逐字节一致。只测试临时专属数据，不运行用户 ROM。
5. 再接原模型与共享输入配置/文件夹 UI；手持渲染第一/三人称/四向及七组动画实几何测试、所有源 PNG 字节不变。候选 JAR 实际 FML 发现、客户端/common 隔离、最终核心与进程探针绑定 SHA；离线结果不冒称 Minecraft/实体手柄实测。

验证门槛：先确认可信核心构建/格式探针能通过，再继续单人手持试玩。若仅模型能显示或 core 还没有音频/持久保存，不把它交成“可玩版”。
