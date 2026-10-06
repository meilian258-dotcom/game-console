# 方块电玩 Game Console

方块电玩是一个 Minecraft 模组：把游戏机、电视、街机和掌机放进方块世界，插上卡带、拿起手柄游玩，也可以和附近的朋友一起玩或观看。

主模组已经包含 **FC/NES、电视、机柜和卡带制作等公共设备**。想玩 SFC、MD、GBA 或原生街机，再安装对应附属，不需要把所有组件都装上。

项目仍在测试阶段。源码公开不等于所有功能都已稳定，也不代表每款游戏、光影或联机方式都兼容。

## 我想开始使用

先看[玩家指南](piq-fc-arcade/docs/玩家指南.md)，里面说明怎么获取安装包、选择附属、准备游戏，以及第一次开机的操作。

- **游戏环境**：Minecraft 1.21.1、NeoForge 21.1.229 起、Java 21。当前原生游戏运行端以 Windows x64 为支持范围；其他平台不能照搬安装。
- **多人服务器**：客户端和服务端安装配套版本的主模组及所用附属。
- **游戏文件**：自行准备有权使用的 ROM；部分街机游戏还需要匹配的 BIOS。仓库不提供这些游戏内容。
- **下载**：[测试版下载](https://github.com/meilian258-dotcom/game-console/releases)目前仅提供给已有配套 FC76.43 用户的 MD15 补丁，不含主包，**不是新玩家首次安装套装**。完整公开安装包仍在补齐发行材料；GitHub 的 Code → Download ZIP 是源码，不能放进 `mods`。

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

- [构建源码](source-control/BUILDING.md)：环境、固定运行库和构建顺序。当前还不是空环境一键编译全部附属。
- [开发协作](GIT_WORKFLOW.md)：克隆、分支、提交与审查。
- [制作新设备](piq-fc-arcade/design/机器制作与交互标准.md)：玩家应该得到怎样的操作体验。
- [公共接口与现状](piq-fc-arcade/design/全组件运行流程与复用接口总览.md)：哪些可以复用，哪些仍需适配。

更多资料在[文档导航](piq-fc-arcade/docs/README.md)。各组件首页介绍当前用途，旧版本日志放在各自的 `README-history.md`，不必按日期逐篇阅读才能开始。

## 遇到问题

请在 [Issues](https://github.com/meilian258-dotcom/game-console/issues) 描述问题，附上游戏和模组版本、单人或服务器、操作步骤，以及必要的日志片段。光影问题请同时写 Iris 和光影包名称；不要上传游戏 ROM、BIOS、令牌或个人存档。完整反馈清单见[玩家指南](piq-fc-arcade/docs/玩家指南.md#遇到问题时提供什么)。

## 源码与许可

仓库现已公开，任何人都可以阅读和克隆。主模组代码许可见 [LICENSE](piq-fc-arcade/LICENSE.md)，附属、模型和第三方核心分别遵循各自声明，不把全部素材统一视为 GPL。

MD 模型与贴图由维护者原创，并已确认随本仓公开；这不改变第三方核心许可，也不自动授予所有素材任意再分发或商用权。第三方依赖见[主模组声明](piq-fc-arcade/THIRD_PARTY_NOTICES.md)及各组件说明。
