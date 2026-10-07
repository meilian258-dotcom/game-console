# 通用 Libretro JNI 一期：使用与附属接入

适用候选：FC76.22、完整SFC43、街机1.5.4、PvZ11、电脑10；Minecraft 1.21.1 / NeoForge21.1 / Java21。制作日期2026-09-29，作者像素匠。**本地候选已构建与冻结，未在玩家实例安装、未完成Minecraft验收。**

## 这次做到哪里

公共 `LibretroRuntime` 将现有独立进程与可选JNI接在同一拥有线程接口上；公共原生桥只随主包装一份，各附属保留自己的固定核心和资源。不是给每款游戏复制PvZ桥，也不是新增一个必须安装的平台MOD。

| 使用者 | 本期接入 | 仍保持原样 |
| --- | --- | --- |
| FC | “仅自己玩”页明确确认后，本次私人启动用JNI；新独立试验档 | 默认进程、共享局、卡带/服务器正式存档、Netplay |
| SFC | SFC43私人适配器复用同一接口和菜单 | 服务端核心、公开主机、已有Netplay、原本机备份 |
| FBNeo街机 | 可信四端口profile及隔离核心测试；**尚无游戏内JNI选择入口** | 普通音画仍MAME，Netplay仍RetroArch，本地同步及投币权限不变 |
| PvZ | 电脑10程序页确认后，PvZ11改用公共桥的OpenGL分支 | 默认独立进程、串流码率/权限/32格规则、Flash |

因此不要为了“街机也有JNI菜单”只替换普通MAME工厂：普通音画可用性检查、确认页生命周期、维护菜单OP限制仍需专门接入。开发用FBNeo profile不把Netplay的管理员组合键开放到普通输入。

## 玩家怎么试

先保留旧JAR与世界/存档副本，正常退出游戏后由用户决定安装；不要同时放两个版本的同一MOD。主包两端更新；已经使用的SFC用完整SFC43，PvZ与电脑成对用PvZ11/电脑10。没有使用的附属无需额外安装。内部平台JAR不要放进mods。

- FC/SFC：公开主机关闭、手持借出的有效实体手柄，打开原“仅自己玩”入口（也可用已有 `/gameconsole-private`），在“本次私人启动”里选择JNI并确认风险。每次重新打开页默认恢复独立进程。服务端手柄权限与6格租约没有取消。
- PvZ：电脑“选择程序 → PvZ → 下次启动”，确认通用JNI v1风险后使用。旧PvZ10的`jni`选择不会自动转成新桥，需重新确认，默认仍进程。真实核心声明键盘能力后可能出现新用户名输入框，可用正常键盘输入；不是加载失败。
- 单客户端只有一个公共JNI活动槽。正在用、正在退出或原生卡住时不能开第二个JNI；可结束后重试，或者明确选择独立进程。**不会静默换后端或换存档。** 这不是限制全服务器只开一台机器；原进程路径各自原有并发约束仍存在。

JNI原生崩溃可能直接带崩Minecraft。启动45秒、一步/保存10秒、关闭12秒超时仅报告失败，不强杀线程、不卸载正在用的DLL、不删除仍在用的工作区。卡住须先保存MC世界，再由用户正常重启客户端。不是安全沙箱。

## 存档边界

- FC/SFC私人试验根为`game-console/piq-private-home-jni-v1/<玩家>/<服务器标识>/`，内部再用trial身份与游戏分隔；原`piq-private-home`不导入、不覆盖。仍由原私人保存流程执行，结束并保存后再续玩。
- PvZ11公共JNI用`game-console/piq-pvz/jni-common-v1-saves/<玩家及内容身份>/PvZ-Portable/`。原进程`saves`、PvZ10专用桥`jni-saves`全部保留，不自动迁移。进度由PvZ自己的文件保存机制负责，不声称支持通用即时状态。
- 通用state、SAVE_RAM/RTC与游戏私有文件是三种不同能力。`LibretroMemoryStore`仅管理调用方提供的归属目录；公共桥不替玩家/卡带/机柜选择保存归属。
- Netplay不使用这个JNI桥。本版另外修复已经实证的回滚嵌套采样/保存时机：只在外层正常帧完成后取检查点，不改回滚或CRC算法、服务器保存权限和格式。历史那次新局偶发CRC报错仍未定因，不能称已修复未知异常。

## 给后续附属作者

先读[行为规范](方块电玩功能行为与配置规范-v1.md)和[能力声明模板](附属能力声明模板.md)，本桥不是绕过它们的捷径。主包仍提供唯一共用层；不重复装公共class、worker、JNI DLL。相关源码入口：

- [LibretroRuntime](../../piq-retro-platform/src/main/java/cn/piq/retro/libretro/LibretroRuntime.java)：版本1同步拥有线程合同，所有阻塞调用在后台；`diagnosticError()`是非阻塞跨线程诊断。
- [LibretroRuntimes](../../piq-retro-platform/src/main/java/cn/piq/retro/libretro/LibretroRuntimes.java)：显式`PROCESS`或`JNI_TRIAL`，无服务器下发的默认切换。
- [LibretroProfile](../../piq-retro-platform/src/main/java/cn/piq/retro/libretro/LibretroProfile.java)：真实核心名称/扩展名/fullPath/端口/选项，以及附属资源所有者和固定SHA。
- [LibretroJniRuntime](../../piq-retro-platform/src/main/java/cn/piq/retro/libretro/LibretroJniRuntime.java)：可信Java适配器的扩展输入/命名文件清单，不接受游戏或服务器指定任意DLL。
- [LibretroFrameConverter](../../piq-retro-platform/src/main/java/cn/piq/retro/libretro/LibretroFrameConverter.java)：原始RGBA/源采样率转公共ABGR/48kHz，不预旋转；恢复/重置后清空重采样历史。
- [原生ABI、构建与限制](../native/libretro-jni/README.md)：软件三种像素格式及OpenGL兼容分支，共用生命周期。

最小软件适配示意（必须在拥有线程创建、运行和关闭）：

```java
LibretroRuntime core = LibretroRuntimes.create(profile, MyAddon.class, selectedBackend);
try {
    core.load(romBytes); // <=64MiB；主线程不能调用
    var output = core.run(List.of(new LibretroProcess.Controls(new int[]{p1, p2}, 0)), 3);
    // 低层output音频仍为info.sampleRate()；公共RetroFrame用转换器输出48kHz。
    RetroFrame frame = converter.convert(output, core.rotation());
} finally {
    core.close(); // 原生退出确认前不能释放仍使用的保存锁/工作区
}
```

需要原名ZIP/BIOS或系统文件时，只有可信本机适配器使用`loadFiles`：最多16文件、单文件64MiB、合计128MiB，保留原名，拒绝逃逸/链接/设备名/复制时变化。**这不扩大服务器游戏清单原有5文件/权限/网络预算。** 对仅靠系统文件运行的core，NO_GAME必须由profile显式声明且核心确认支持。

v1暴露最多四个数字端口与显式lightgun/pointer/mouse/keyboard；不支持Vulkan/GLES/Core-profile GL、通用VFS、模拟轴、震动、换盘、异步core回调或自动Netplay。核心v0选项必须使用其真实声明值，未知选项失败而不是默默忽略。PvZ当前DLL的非标准inline选项通过显式兼容flag处理，不按游戏名字在公共层特判。

`capabilities()`不是承诺所有ROM、场景、版本的保存都成功；JNI在加载后查询真实state/SAVE_RAM/RTC暴露情况，文件存档由适配器另行声明。每个核心仍须测试启动/输入/音画/旋转、保存往返、退出重进和不支持能力。共享权限、投币、联机、管理终端、内容传输不能因注册成功就宣称已接齐。

现有能力范围内的新附属可只提供自己的profile/适配器，无需再复制主包桥；遇到新GPU后端、模拟轴、光盘等公共层尚未支持的能力，仍需协商升级API。v1是这批候选的版本化边界，不承诺永久二进制SDK，也不声称已解决整个项目历史构建/许可溯源缺口。

## 验证记录

验证分别覆盖自动契约、原生隔离 JVM、实际 Java 适配器、完整 JAR 和依赖检查。Minecraft真实菜单/音画、玩家存档、专服多人、光影与长时资源占用待用户验收；没有测试数据就不宣传JNI提升多少FPS/降低多少毫秒，也不能把本机原生检查说成已安装新包。
