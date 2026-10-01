# FBNeo JNI state-v3 修复源

2026-09-30，Game Console / 方块电玩 GC-110。这里只维护模拟器适配，不包含游戏 ROM、BIOS 或玩家存档。

## 来源与许可

上游提交：`a251c76229f1637e433b93e29845039752771b6d`，来自 [libretro/FBNeo](https://github.com/libretro/FBNeo/tree/a251c76229f1637e433b93e29845039752771b6d)。原始源码 ZIP 的 SHA-256 固定为 `55cc0f5bf305d8953fa0a20c3598164d39efc03ef3740c7b01e7ebf143cd4d7a`。

FBNeo 使用其独立的非商业许可证（`../vendor/license.txt`），**不因放入本项目而改为 GPL**。对外分发修改后的核心时，必须提供许可证和完整修改源码；仅提供补丁或 DLL 不足以完成本项目的交付要求。源码包 `FBNeo-a251c76-jni-state-v3-source.zip` 保留上游所有文件和许可，并附修改补丁。不得把 ROM／BIOS 一同提交或分发。

## 修改

`state-v3.patch` 修改 17 个上游源码文件：

- 没有维护输入的驱动也声明诊断选项，实际维护按键仅在驱动提供输入时绑定；不放宽可信选项检查或 OP 权限。
- AV 信息查询不再无条件重新分配同尺寸画面缓冲，防止恢复后首帧丢失。
- 补齐 PGM 周期余量、CPS 延迟精灵历史、诊断键计数及多种音频芯片的重采样／滤波／包络状态。
- YM2610 恢复只重连派生指针，不重复写寄存器而重置动态状态；记录 Delta-T 和 ADPCM 声道索引，不保存裸指针。
- 修正 FM 包络释放分支中将芯片类型比较误写成赋值的问题，避免运行中改变芯片类型，导致新实例恢复音频不一致。

## 离线重建

从项目根目录执行 `tools/build_fbneo_state_v3.py`（Python 3.11+）：

```text
python tools/build_fbneo_state_v3.py --source-archive <固定上游ZIP> --output <全新目录> --toolchain <LLVM-MinGW的bin> --shell <MSYS的sh.exe> --git <git.exe>
```

使用 LLVM-MinGW 20250910 UCRT x86_64，Java 桥仍为现有 ABI2。脚本先验证源码 SHA、压缩包路径和补丁，再从无对象文件的源码树构建；输出完整修改源码、DLL、构建日志及 `build.json`。不会下载软件、修改原始源码树、安装或启动 MC。上游 macOS framework 的 ZIP 符号链接在 Windows 构建树中是普通文本文件，在分发源码包中保留原始 ZIP 元数据。

编译成功不代表逐字节可复现；`binaryReproducibilityVerified` 只有独立复现后才能改为 true。本轮没有该声明。

## 身份与验证边界

新 DLL 使用新资源路径 `win-x64-fbneo-state-v3` 和新 SHA，服务器和客户端使用同一可信 profile。旧核心及旧 Netplay 存档保留，不自动转换；核心身份变化会隔离保存槽。不会通过忽略 CRC 或状态字节差异获得“通过”。

完整测试结果见 `../../JNI-街机修复-20260929.md`。受控 JNI 子 JVM 验证不等于 Minecraft 真人多人、弱网长时或所有街机游戏验收。MAME 音画模式和旧特殊 NeoGeo 本地同步核心没有被这个补丁替换。
