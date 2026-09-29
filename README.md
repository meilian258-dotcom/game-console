# 方块电玩 / Game Console

在 Minecraft 中放置和操作游戏设备的模组项目。主模组内置 FC/NES、电视与公共设备能力，SFC、街机、GBA、MD、电脑程序等通过各自附属接入。

面向 Minecraft 1.21.1、NeoForge 21.1 和 Java 21。当前是持续开发中的候选版本，**不是全部机型、联机方式和平台均已验证的稳定发行版，也不是稳定附属 SDK**。

## 从哪里开始

当前 JNI 默认候选的范围、配套版本和保存边界见 [FC76.26 / GBA12 / 电脑11](piq-fc-arcade/design/FC76.26-JNI默认与自动旁观.md)；不是所有机型的所有模式都已接入 JNI。

- 玩家：[主模组与当前版本说明](piq-fc-arcade/README.md)。按配套指南安装，不把各目录的最大版本号任意混搭。
- 开发者：[Git 工作流](GIT_WORKFLOW.md)、[固定输入与构建](source-control/BUILDING.md)、[协作规范](piq-fc-arcade/AGENTS.md)。
- 附属作者：[以 SFC 为蓝本的制作说明](piq-sfc-home/design/以SFC为蓝本-附属制作说明.md)、[公共层边界](piq-retro-platform/README.md)。
- 名称与交付：[Game Console 命名规范](source-control/BRANDING.md)。中文品牌“方块电玩”，正式英文品牌“Game Console”。

## 组件

| 对外名称 | 源码目录 | 说明 |
| --- | --- | --- |
| Game Console | [piq-fc-arcade](piq-fc-arcade) | 主包，含 FC 和公共设备/服务 |
| Game Console: SFC | [piq-sfc-home](piq-sfc-home) | 使用包含 SFC 核心的完整交付包；薄包不是完整安装包 |
| Game Console: Arcade | [piq-native-arcade](piq-native-arcade) | 原生街机附属，各运行模式能力不同 |
| Game Console: GBA | [piq-gba](piq-gba) | 掌机及单席机柜；不代表支持 GBA 通讯联机 |
| Game Console: MD | [piq-md-home](piq-md-home) | 当前为私人单人试验 |
| Game Console: Computer | [piq-computer](piq-computer) | 可组装电脑与程序接入 |
| Game Console: PvZ | [piq-pvz-addon](piq-pvz-addon) | 自备游戏资源的运行适配 |
| Game Console: Flash Box | [piq-flash-box](piq-flash-box) | 历史播放盒，有独立版本限制，不与当前主包随意混装 |
| Game Console: Java ME | [piq-j2me-arcade](piq-j2me-arcade) | 独立原型，能力以组件说明为准 |

`piq-retro-platform` 是随主包编译的内部源码库；`piq-sfc-arcade` 是 SFC 历史核心组件。它们不是供玩家额外安装的通用平台包。

## 兼容与内容边界

`piq_*` 模组/资源 ID、`cn.piq.*` Java 包名、配置和存档路径为兼容保留，不因对外更名而迁移。文件名变了也不能同时安装新旧两份同 ID 的 JAR。旧候选、版本记录和作者/第三方署名保留。

仓库不提供商业 ROM、BIOS、PvZ `main.pak` 或玩家存档。各组件代码、模型、核心与第三方依赖有不同许可，见各自 LICENSE / THIRD_PARTY / ASSETS 资料；仓库可读不等于所有内容都可自由再分发。当前仍有素材和核心对应源码的公开发行审核待办。

## 仓库访问

当前地址：[meilian258-dotcom/game-console](https://github.com/meilian258-dotcom/game-console)，仍为**私有仓库**。仅发链接不会授予访问权限。需要仓库所有者邀请 GitHub 账号并由对方接受；个人私有仓库协作者具有读写权限，不是只读分享。暂不开放公开下载或自动发布。

克隆后依赖仍须按构建文档准备；不能把“能克隆”当成“所有附属从空环境一键构建”。
