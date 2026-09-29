# 方块电玩：MD / Game Console: MD

当前 MD2 alpha.1 私人单人试验。按[命名规范](../source-control/BRANDING.md)交付为 `game-console-md-版本.jar`；原 ID、路径、保存和能力不变，旧成品保留。

适用：Minecraft 1.21.1、NeoForge 21.1.236+、Java21；需两端同装主包 **FC76.24** 与 `game_console_md-0.1.0-alpha.1.jar`。这是新增附属，不另装内部平台 JAR，不修改 SFC 附属。当前执行端仅 Windows x64。

2026-09-29 配套 **FC76.26** 时，私人入口默认 JNI，MD1 原 JAR 沿用、不新增 MD 联机；原进程存档不迁移。完整范围见[当前 JNI 默认指南](../piq-fc-arcade/design/FC76.26-JNI默认与自动旁观.md)，下方旧验收不等于本轮 MC 真人验收。

## 使用

1. 在创造模式“方块电玩”末尾找到 MD2 主机和 MD 卡带，或用配方合成。手柄不额外合成，是机器借用物。
2. 放主机/电视，用主包 AV 线连接；先连接再交互主机。持 MD 卡带右键插入，空手右键借出1P手柄；背包需要空格。
3. 手持借用手柄，在主机6格内输入 `/gameconsole-private`，填本机普通 MD ROM 完整路径（`.md/.bin/.gen`）。原文件不上传、不修改。
4. FC76.26 起默认 JNI；可点私人页“本次私人启动”切回进程，重新打开页面采用 JNI 默认。同一 JVM 只允许一个 JNI 核心；JNI 原生故障可能使整个 MC 崩溃，使用独立存档、不导入旧进程档。FC76.24 的旧页面默认进程。
5. 关闭菜单继续玩；失焦/菜单暂停并释放按键。手柄超6格、断线/换世界/取走硬件等使私人会话停止并请求保存。
6. 手持手柄右键原主机归还；空手潜行右键取出卡带。先归还才能拔卡，不吞其他机器的手柄。重载世界取消旧借用票据，旧票据不能控制机器。

初版只接**私人单人**。电视仅本人看到游戏，不向他人发布音画。公共电源不会假报“公开游戏已运行”。两只手柄模型保留，1P借出隐藏，2P目前仅模型，不能加入；无服务器游戏目录/公开双人/Netplay/服务端托管、CD、32X、SMD解交错或 BIOS 功能。物理卡带是插槽物，本地文件通过私人菜单选；尚不是可携带跨玩家进度的内容卡。

键位复用公共 SFC 12位可配置布局（F7）。对应 MD：SFC B→A、A→B、R→C、Y→X、X→Y、L→Z、Select→Mode、Start→Start。默认 WASD方向、J/K/P为ABC、L/I/O为XYZ、退格Mode、回车Start。N切换移动锁；不是新增一套独立MD键位页。

## 存档与运行器

私人档沿用主包个人+服务器隔离目录，再按 MD、核心SHA、ROM SHA及进程/JNI隔离。保存完整核心状态和实际SRAM；30秒检查点与正常停止保存，原子替换、上一代备份，坏档拒绝开局覆盖。意外崩溃只保证已落盘检查点，不保证最后瞬间数据。

BlastEm固定提交 `1e0de94dc7e669c0925a22c0fccf6cdc837af0a0`，实际报告 `1.0.1-pre`；DLL SHA256 `3e275e9656e389be11a2623a47a6b454b214a91966af984a2105fa7c8249d016`。软件帧，PAL/NTSC有界时序与立体声48k转换。视觉外壳是MD2，核心固定无TMSS的md1va3配置，避免暗带BIOS；MegaWiFi明确关闭。JNI仍有原生崩溃风险，默认进程保留。

## 开发/来源

新增附属先看[能力声明](design/能力声明.md)，公共API使用主包76.24的 `HomeSystems`、`ExternalHomeConsoleBlockEntity`、`PrivateHomeClient.Provider`、`LibretroRuntimes`。本轮只对公共私人菜单增加默认方法 `acceptsFile/fileHint`，旧FC/SFC行为不变，不在主包加入MD文件名特判。接口仍非稳定公开SDK。

源码构建：Java21，在本目录用工作区主包 Gradle wrapper 执行 `--offline check jar`（依赖已缓存时）。也可用 Gradle9.2.1 与 `-PgameConsoleJar=/完整路径/主包76.24.jar`；当前主包仍有私有历史打包债务，不宣称可从空仓库构建整套模组。

核心：`tools/build_core.py SOURCE_DIR LLVM-MINGW/bin OUTPUT_DIR`，官方源ZIP、GPL及嵌入的第三方许可随交付源码材料保留。源SHA `31ff70a654dfc44b4e9f642855060450475d1b7327b206082ef1f23f507bf5e9`；生成CPU源/配置C后编译，只有Windows zlib补全系统io.h包含参数，无删安全校验。未附ROM/BIOS；探针用原创68000程序。

模型来自用户 `MD2_挡板修正_双状态模型包.zip`（SHA `b23e8f7907c371c080a030a2674105608a4637be48a188ba27732d83967fb7ff`），保留原可编辑源、UV与纹理；双状态已烘焙挡板，不二次转90度。代码GPL-3.0-or-later；模型公开分发许可待服主确认。当前未额外渲染MD外接AV线实体，只复用真实AV连接/断开功能，不把未实现的线材展示说成完成。

## 验证状态

最终JAR独立JVM原版进程及共用JNI各18项真实核心断言通过（中文路径、音画、A键变化、SRAM、状态、退出重开后的磁盘值、共享JNI槽释放），5项纯行为测试通过。16模型状态/四朝向包内几何检查、原纹理/元素一致和七包专服启动正常退出通过。MC内模型、借还/保护插件、6格边界与真人手感待验，不标稳定或保证全部游戏兼容。没有代装、服务器发布/重启。详见[总指南](../piq-fc-arcade/design/FC76.24-GBA11-MD1-JNI试用说明.md)。
