# 方块电玩 Game Console

方块电玩把可以游玩的游戏机、电视、街机、掌机和可组装电脑带进 Minecraft。摆放设备、接上视频线、制作卡带，再拿起手柄，在自己的房间或朋友搭建的游戏厅里游玩。

**[各模组下载](piq-fc-arcade/docs/下载中心.md) · [历史版本](piq-fc-arcade/docs/下载历史.md) · [安装与游玩](piq-fc-arcade/docs/玩家指南.md) · [文档中心](piq-fc-arcade/docs/README.md)**

主模组内置 FC/NES 和公共设备，其他机型按需添加。项目目前提供测试版，不包含 ROM、BIOS 或商业游戏数据；游戏、联机方式和光影兼容性以所用版本的说明为准。

## 可以玩什么

- **布置游戏空间**：摆放主机、不同电视与机柜，用视频线连接显示设备。
- **使用实体设备**：制作和插入卡带，开关机器，领取手柄或操作摇杆；不同机型保留各自的操作方式。
- **和朋友一起玩或观看**：支持的设备可开放操作席和附近旁观。串流、JNI Netplay 等模式的能力与要求不同，见[联机与旁观说明](piq-fc-arcade/docs/玩家指南.md#联机和观看有什么区别)。
- **按需扩展机型**：不必安装全部附属，先用 FC 熟悉流程，再添加需要的设备。

## 主模组与附属

| 组件 | 提供什么 | 安装关系与说明 |
| --- | --- | --- |
| 主模组 / FC | FC/NES、电视、公共机柜、卡带工作台、线材及公共设置 | 必装；[主模组说明](piq-fc-arcade/README.md) |
| SFC | SFC 家用机、卡带和手柄 | 主模组 + 完整附属包；[SFC 说明](piq-sfc-home/README.md) |
| MD | MD 家用机、卡带和手柄 | 主模组 + 附属；[MD 说明](piq-md-home/README.md) |
| GBA | GBA 掌机及对应机柜 | 主模组 + 附属；[GBA 说明](piq-gba/README.md) |
| 街机 | 原生街机游戏运行支持 | 主模组 + 附属；[街机说明](piq-native-arcade/README.md) |
| 电脑 | 可组装主机、键鼠与程序入口 | 主模组 + 附属，再准备对应程序；[电脑说明](piq-computer/README.md) |

这是组件用途表，不是混装清单。**实际可下载版本、配套主包及客户端／服务端要求统一看[下载中心](piq-fc-arcade/docs/下载中心.md)。** 内部 `piq-retro-platform`、SFC 家用薄包与独立内部核心不是额外的玩家前置；SFC 玩家选择完整包。

[Flash Box](piq-flash-box/README.md) 和 [PvZ](piq-pvz-addon/README.md) 属于开发验证机型，[Java ME](piq-j2me-arcade/README.md) 是独立原型，不是入门必装项。

## 下载和安装

| 环境 | 要求 |
| --- | --- |
| Minecraft | 1.21.1 |
| 加载器与 Java | NeoForge 21.1.229 起、Java 21；可选补丁可能要求更高的 NeoForge 版本 |
| 原生游戏运行端 | 当前以 Windows x64 为支持范围；其他平台不能照搬安装 |
| 多人游戏 | 客户端与服务端安装配套主模组及所用设备附属；仅客户端工具不装到服务端 |

1. 从[下载中心](piq-fc-arcade/docs/下载中心.md)选择主模组和配套附属；查旧版则使用[按模组分类的历史列表](piq-fc-arcade/docs/下载历史.md)。加入服务器时，以服主提供的配套为准。
2. 正常退出游戏，升级服务器先停服；备份世界、配置和 `game-console`，将玩家 JAR 放入 `mods`，移出并备份同一模组的旧版本。
3. 自备有权使用的游戏文件和适用 BIOS，按[玩家指南](piq-fc-arcade/docs/玩家指南.md#第一次玩家用机)完成拷卡、接线与第一次开机。

GitHub 的 **Code → Download ZIP** 和自动生成的 **Source code** 是源码，不是安装包。不要将各附属不同批次的最高版本拼装在一起。NeoForge 最低要求不代表全部新版本和整合包均已验证，详见[兼容范围](piq-fc-arcade/design/NeoForge兼容范围-20261004.md)。

## 可选工具与联动

- **Replay 屏幕与声音兼容**：用于录像中的设备音画，下载及配套条件见[下载中心](piq-fc-arcade/docs/下载中心.md)。不是运行游戏设备的前置。
- **快速回放兼容**：处理 ReForgedPlay 握手和 Sable 玩家位置，和屏幕／声音补丁用途不同；见[组件说明](piq-replay-quickmode-compat/README.md)及[公开下载状态](piq-fc-arcade/docs/下载历史.md#快速模式兼容补丁)。
- **WaterFrames**：可选显示联动，不是内置电视和机柜的必装前置。

## 遇到问题

请在 [Issues](https://github.com/meilian258-dotcom/game-console/issues) 描述问题，附上游戏和模组版本、单人或服务器、操作步骤，以及必要的日志片段。光影问题请同时写 Iris 和光影包名称；不要上传游戏 ROM、BIOS、令牌或个人存档。完整反馈清单见[玩家指南](piq-fc-arcade/docs/玩家指南.md#遇到问题时提供什么)。

## 参与开发

- [开发协作](GIT_WORKFLOW.md)：获取源码、分支、提交、检查与 PR。
- [构建指南](source-control/BUILDING.md)：固定依赖、附属构建和自动快照要求；`main` 源码不等于已发布成品。
- [制作新设备](piq-fc-arcade/design/机器制作与交互标准.md)：统一的玩家体验与验收要求。
- [公共接口与现状](piq-fc-arcade/design/全组件运行流程与复用接口总览.md)：可复用入口、当前能力及尚未完成的部分。

完整导航见[文档中心](piq-fc-arcade/docs/README.md)。历史实现记录保留在各组件的 `README-history.md` 和专项版本说明中。

## 源码与许可

请先看[技术组件与第三方许可总表](THIRD_PARTY_NOTICES.md)：区分本项目代码、模拟核心、RetroArch 前端、运行依赖和美术素材，列出对应源码、许可原文以及仍待核验的分发条件。公开源码不等于全部组件都可商业使用，也不代表全部素材采用同一许可证。

仓库现已公开，任何人都可以阅读和克隆。主模组代码许可见 [LICENSE](piq-fc-arcade/LICENSE.md)，附属、模型和第三方核心分别遵循各自声明，不把全部素材统一视为 GPL。

MD 模型与贴图由维护者原创，并已确认随本仓公开；这不改变第三方核心许可，也不自动授予所有素材任意再分发或商用权。第三方依赖见[主模组声明](piq-fc-arcade/THIRD_PARTY_NOTICES.md)及各组件说明。
