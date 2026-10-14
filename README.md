# 方块电玩 Game Console

方块电玩是一个 Minecraft 模组：把游戏机、电视、街机和掌机放进方块世界，插上卡带、拿起手柄游玩，也可以和附近的朋友一起玩或观看。

主模组已经包含 **FC/NES、电视、机柜和卡带制作等公共设备**。想玩 SFC、MD、GBA 或原生街机，再安装对应附属，不需要把所有组件都装上。

项目仍在测试阶段。源码公开不等于所有功能都已稳定，也不代表每款游戏、光影或联机方式都兼容。

## 我想开始使用

先看[玩家指南](piq-fc-arcade/docs/玩家指南.md)，里面说明怎么获取安装包、选择附属、准备游戏，以及第一次开机的操作。

- **游戏环境**：Minecraft 1.21.1、NeoForge 21.1.229 起、Java 21。当前原生游戏运行端以 Windows x64 为支持范围；其他平台不能照搬安装。
- **多人服务器**：客户端和服务端安装配套版本的主模组及所用附属。
- **游戏文件**：自行准备有权使用的 ROM；部分街机游戏还需要匹配的 BIOS。仓库不提供这些游戏内容。
- **下载**：[FC76.54 设备体验候选](https://github.com/meilian258-dotcom/game-console/releases/tag/device-polish-fc7654-20261012-r1)已公开主包、街机、电脑和可选 Replay 补丁四件，**不是完整套装**；按发行页的精确版本表选配。较早的[整套测试版](https://github.com/meilian258-dotcom/game-console/releases/tag/full-test-20261007-r1)及其[安装说明](piq-fc-arcade/docs/整套测试版-20261007.md)仅对应那一批旧版本，不拿其中的旧 SFC 混入 FC76.54。完整 SFC54 套装仍待分发材料补齐。GitHub 的 Code → Download ZIP 是源码，不能放进 `mods`。

NeoForge 最低版本不是对所有更新版本或整个整合包的兼容保证。具体范围见[兼容说明](piq-fc-arcade/design/NeoForge兼容范围-20261004.md)。

## 有哪些设备

| 想使用的设备 | 需要的组件 |
| --- | --- |
| FC/NES、电视和公共机柜 | [主模组](piq-fc-arcade/README.md) |
| SFC 家用机 | 主模组 + [SFC 完整附属包](piq-sfc-home/README.md) |
| MD 家用机 | 主模组 + [MD 附属](piq-md-home/README.md) |
| GBA 掌机及对应机柜 | 主模组 + [GBA 附属](piq-gba/README.md) |
| 原生街机游戏 | 主模组 + [街机附属](piq-native-arcade/README.md) |
| 可组装电脑与程序 | 主模组 + [电脑附属](piq-computer/README.md)，再按程序说明准备内容 |

[Flash Box](piq-flash-box/README.md) 和 [PvZ](piq-pvz-addon/README.md) 暂作开发验证机型；[Java ME](piq-j2me-arcade/README.md) 是独立原型。它们不是新玩家必须安装的前置。

源码中的 `piq-retro-platform` 和 `piq-sfc-arcade` 是内部组件，**不要看到一个源码目录就另装一个 JAR**。SFC 玩家使用包含所需核心的完整包。

## 我想参与开发

可选客户端工具：[快速回放兼容补丁](piq-replay-quickmode-compat/README.md)提供 ReForgedPlay 的握手和 Sable 玩家位置适配源码。它不是方块电玩设备运行的前置，也不是屏幕/声音录制补丁；具体依赖、构建与待验范围见组件说明。

- [构建源码](source-control/BUILDING.md)：固定核心开发构建，以及 `main` 自动编译七个成品并发布 nightly 核心测试快照的流程。
- [开发协作](GIT_WORKFLOW.md)：克隆、分支、提交与审查。
- [制作新设备](piq-fc-arcade/design/机器制作与交互标准.md)：玩家应该得到怎样的操作体验。
- [公共接口与现状](piq-fc-arcade/design/全组件运行流程与复用接口总览.md)：哪些可以复用，哪些仍需适配。

更多资料在[文档导航](piq-fc-arcade/docs/README.md)。各组件首页介绍当前用途，旧版本日志放在各自的 `README-history.md`，不必按日期逐篇阅读才能开始。

## 遇到问题

请在 [Issues](https://github.com/meilian258-dotcom/game-console/issues) 描述问题，附上游戏和模组版本、单人或服务器、操作步骤，以及必要的日志片段。光影问题请同时写 Iris 和光影包名称；不要上传游戏 ROM、BIOS、令牌或个人存档。完整反馈清单见[玩家指南](piq-fc-arcade/docs/玩家指南.md#遇到问题时提供什么)。

## 源码与许可

请先看[技术组件与第三方许可总表](THIRD_PARTY_NOTICES.md)：区分本项目代码、模拟核心、RetroArch 前端、运行依赖和美术素材，列出对应源码、许可原文以及仍待核验的分发条件。公开源码不等于全部组件都可商业使用，也不代表全部素材采用同一许可证。

仓库现已公开，任何人都可以阅读和克隆。主模组代码许可见 [LICENSE](piq-fc-arcade/LICENSE.md)，附属、模型和第三方核心分别遵循各自声明，不把全部素材统一视为 GPL。

MD 模型与贴图由维护者原创，并已确认随本仓公开；这不改变第三方核心许可，也不自动授予所有素材任意再分发或商用权。第三方依赖见[主模组声明](piq-fc-arcade/THIRD_PARTY_NOTICES.md)及各组件说明。
