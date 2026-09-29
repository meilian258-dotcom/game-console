# 原生街机技术预览：固定运行时来源

记录：2026-09-10，审计代理 alpha7_audit。仅新增独立组件；没有修改 FC/SFC 模拟器核心。

## MAME

- 上游：https://github.com/libretro/mame
- 固定源码：https://github.com/libretro/mame/tree/4fc9a9312baaf34963847f884961ad9793fbbc1d
- 官方下载：https://buildbot.libretro.com/nightly/windows/x86_64/latest/mame_libretro.dll.zip
- 本次下载对应 Last-Modified：2026-09-09 14:19:14 GMT。
- ZIP：107980161 字节，SHA256 CBE11E3969E025CF1A119CCC1DE3CC633397951DF8C15A98BE8A9980F28E1D12。
- DLL：372431360 字节，SHA256 6172A988AB67FE68F4177A6FC8FBB82619EB2044C330930F0F572F7B1EDC2301。
- 实际 DLL API 返回 MAME 0.289 (4fc9a931)、libretro ABI 1。
- 核心未经本项目修改或重新编译，二进制版本提交前缀与固定源码匹配；未声称完成可重现构建。
- 保留上游完整 COPYING 和 docs/legal/GPL-2.0 于 docs/licenses/。整个 MAME 按该固定版本声明采用 GPL-2.0，内部第三方许可随 COPYING 保留。不是非商业许可的旧 MAME2003 核心。
- 对外分发核心时须同时履行 GPL 对对应源码的要求；本页 URL 仅记录来源，不冒充已经附带完整对应源码。

## JNA

- 缓存 Maven 坐标 net.java.dev.jna:jna:5.14.0。
- JAR SHA256：34ED1E1F27FA896BCA50DBC4E99CF3732967CEC387A7A0D5E3486C09673FE8C6。
- META-INF/LICENSE 原样提取到 docs/licenses/JNA-LICENSE.txt，声明 Apache-2.0 OR LGPL-2.1。本组件选择 Apache-2.0，完整条款另附 JNA-Apache-2.0.txt。
- JNA 仅供独立 helper 的 Java classpath 使用。Minecraft JVM 中 NativeProcessSession 不链接 JNA、不加载 MAME DLL。

## 原创测试固件

tools/make_diagnostic_rom.py 生成 26 字节原创 Intel 8080 指令，其余填零，四块各 2048 字节。程序读取输入口写入显存，并启用模拟 SN76477 振荡器。无商业游戏代码、图像、音频或 BIOS。

使用现代 invaders 硬件驱动所需四个芯片文件名；出现四个预期 WRONG CHECKSUMS 警告。该 ZIP 是硬件诊断，不是《太空侵略者》游戏。

- 原始 8192 字节 SHA256：554C673E65D5957DA0BC1DF0CEB986CBAC5500D407AE2D780FEAB4F8D2EED810
- 固定时间戳 ZIP SHA256：10E62BBACBF6E79EFB6DB6F7A65EA1EDFB65653EAB60B41E80DBCB4673A5276C

## 已验证与边界

- 真实现代 MAME DLL：260×224 软件帧、59.5419846 Hz、48 kHz 非零立体声 PCM、六条该驱动有效输入线路及所有松键、399512 字节状态和画面重放。
- 正式桥接：24 项真实测试，包含中文/空格路径、快速按松边沿、低宿主 FPS 的 PCM 聚合、单进程锁、停止退出。
- 单独验证实际父 JVM 正常退出时 shutdown hook 回收子进程，以及无帧 helper 在约15秒后被精确终止。
- 核心仅在专属 Java 子进程运行。它不是操作系统沙箱；可信本地 ROM 技术预览，不承诺任意 ROM 安全或兼容，不支持网络玩家。
- 默认无每条指令 fuel 或 native 内存硬配额。极端本地 ROM 仍可能消耗系统资源。强制杀死/崩溃父 JVM 不属于正常 shutdown hook 已验证范围。
- 帧协议统一输出未旋转 DAR。固定 MAME 源码 window.cpp 已对竖屏反转 aspect，helper 先规范回原始 DAR；客户端仅旋转并倒数一次。
- 没有运行商业 ROM，没有在 Minecraft 游戏画面中完成兼容性或音画延迟验收。
