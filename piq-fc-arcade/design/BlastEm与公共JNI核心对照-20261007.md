# Libretro 核心的 JNA 与 JNI 对照测试

测试日期：2026-10-07。测试对象为 FC `0.31.0-alpha.76.43` 公共 JNI 与最小 JNA 前端，使用同一原创诊断程序，比较固定核心的输入、音画和保存行为。

三种核心在相同初始化顺序下，两种前端各 180 帧的视频与 PCM 长度、摘要逐项一致。这个结果仅覆盖下列固定用例，不代表所有游戏兼容、完整 Netplay 或 Minecraft 多人验收。BlastEm 仅用于对照，已不属于当前生产运行入口。

## 测试对象与环境

| 核心 | 自报版本 | DLL SHA-256 |
| --- | --- | --- |
| 官方 buildbot BlastEm | 1.0.1-pre | `b4f156b98a3c64f7f4d1034ddcdc6581da4e012b3d8110e890adb7af369ff94d` |
| 对照构建 BlastEm | 1.0.1-pre | `3e275e9656e389be11a2623a47a6b454b214a91966af984a2105fa7c8249d016` |
| 官方 Genesis Plus GX | v1.7.4 c2838c7 | `9ffa10a115b20e1b49e9caf0b53f287c640ed4e5bb93f7ed9a23b416a4ccfdf7` |

官方 BlastEm 来自 [Libretro Windows x64 buildbot](https://buildbot.libretro.com/nightly/windows/x86_64/latest/blastem_libretro.dll.zip)，压缩包 SHA-256 为 `7cebac87b4fe79279ec14932f65c390690e37db52d714f5694885fd503a8bc14`。`latest` 地址内容可变，复现时需要核对摘要，不能只凭版本号判断是否同一构建。

环境为 Windows x64、Java 21.0.11、JNA 5.14.0。公共桥 SHA-256 为 `aec72d00384d89de94c214e12fe32a19c1c54c93ea80c9bc82727895be509a36`；原创 68000 诊断程序 SHA-256 为 `f3ae7ea45d2b1220dedb050aaa3acaf30e512466d2dc59a7ed07a0351c4ab9d6`，不含商业游戏或 BIOS。

每个用例使用独立 JVM、空 system/save 目录，并串行运行。BlastEm 使用默认选项；GX 使用普通 MD profile 的七项固定选项和两个六键端口。A 键按各核心的 RetroPad 映射输入。MD 专用 Netplay 核心不在此对照中。

## 测试结果

| 检查 | 官方 BlastEm | 对照 BlastEm | 官方 GX |
| --- | --- | --- | --- |
| 两前端启动、音画和 A 键输入 | 通过 | 通过 | 通过 |
| 同 DLL 两前端 180 帧逐项比较 | 相同 | 相同 | 相同 |
| 按 A 写入 SRAM | 通过，字节索引 0 | 通过，字节索引 0 | 通过，字节索引 1 |
| 连续序列化字节一致 | 否 | 否 | 是 |
| 恢复后重复 20 帧输入，共三轮 | 画面相同，PCM 不同 | 画面相同，PCM 不同 | 画面与 PCM 相同 |
| 只恢复即时状态可回退 SRAM | 否 | 否 | 否 |
| 状态与 SRAM 落盘后重新建立会话 | 两前端通过 | 两前端通过 | 两前端通过 |
| 正常关闭与 JNI 槽位释放 | 通过 | 通过 | 通过 |

每个用例包含 90 帧预热、20 帧按键、三次各 20 帧恢复重演，以及重新建立会话后的 10 帧。比较实际有效视频和 PCM 长度与数据，不补零、不裁剪；每帧均有非空 PCM。比较范围不包含纹理上传、光影、扬声器听感、网络延迟或 Minecraft 会话权限。

重新建立会话发生在同一个 JVM 内：JNI 关闭并卸载库后创建新实例；JNA 执行 unload/deinit 后重新加载游戏，未强制卸载动态库。恢复检查覆盖 API 接受状态、SRAM 标记保持和后续 10 帧音画输出，未与不中断运行的基线比较完整进度，也不属于跨进程或断电耐久性验证。

## 保存与调用顺序

即时状态与 SRAM 需要分别处理。此诊断中，只恢复核心状态不会恢复电池进度；SRAM 独立保存，并在重新建立会话时显式恢复。GX 的有效 SRAM 长度可能变化，恢复时只能写入已保存且不超过新实例声明容量的有效部分。

`retro_serialize_size()` 的首次查询时序会影响 BlastEm 的状态长度：首帧前查询为 141704 字节，运行 90 帧后首次查询为 141716 字节。当前 `LibretroJniRuntime.load` 在首帧前查询能力；保存与恢复必须遵守一致的调用顺序和长度合同。

BlastEm 的序列化实现可能继续执行 68000 到可保存位置，状态长度还会缓存。重复序列化字节和恢复后 PCM 的差异均出现在两种前端；具体 PCM 差异来源仍未定位，不能将其直接归因于 JNI，也不能忽略未知字段来判定回滚正确。

## 核心选型与兼容边界

可信官方制品可以通过公共 JNI 接入；只有明确的适配缺口才需要补丁或自行编译。核心能够加载和输出音画，不等于 Netplay 已具备确定性。

版本字符串用于展示，文件摘要用于识别具体构建。两份 BlastEm 的版本字符串相同而摘要不同。联机兼容还取决于 ROM、BIOS、关键选项、协议和实际回放，文件一致只是其中一项。

接口背景参见 [BlastEm](https://docs.libretro.com/library/blastem/)、[Genesis Plus GX](https://docs.libretro.com/library/genesis_plus_gx/)及 [Libretro 核心生命周期](https://docs.libretro.com/development/cores/developing-cores/)。官方能力列表不能替代模组内的运行与联机验证。
