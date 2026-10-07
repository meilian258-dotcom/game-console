# 方块电玩文档导航

按你要做的事选择入口，不需要先读所有技术资料。组件首页说明当前用途；带日期的专项文档记录对应版本，不自动覆盖当前操作。

## 我是玩家或服主

| 要解决的问题 | 从这里看 |
| --- | --- |
| 这是什么，需要装哪些 | [项目首页](../../README.md) |
| 第一次安装和游玩 | [玩家指南](玩家指南.md) |
| 想玩哪一种设备 | [主包](../README.md)、[SFC](../../piq-sfc-home/README.md)、[MD](../../piq-md-home/README.md)、[GBA](../../piq-gba/README.md)、[街机](../../piq-native-arcade/README.md)、[电脑](../../piq-computer/README.md) |
| 服务端 ROM 和 BIOS 在哪里 | [玩家指南的目录部分](玩家指南.md#服主怎样找-rom-和-bios) |
| 什么是串流、JNI Netplay 和旁观 | [模式区别](玩家指南.md#联机和观看有什么区别) |
| 闪退、光影、菜单或游戏不能运行 | [反馈清单](玩家指南.md#遇到问题时提供什么) |

[GitHub 整套测试版](https://github.com/meilian258-dotcom/game-console/releases/tag/full-test-20261007-r1)提供主模组与当前配套附属共 7 个独立 JAR。首次安装与升级请看[本套版本、安装步骤及限制](整套测试版-20261007.md)；旧 MD15 补丁仅保留作历史下载。

## 我想编译或贡献代码

1. [构建指南](../../source-control/BUILDING.md)：先准备固定输入，再构建主包和目标附属。
2. [Git 工作流](../../GIT_WORKFLOW.md)：分支、提交、检查与 PR。
3. [共同协作规范](../AGENTS.md)：组件边界、安全、验证和交付。
4. [内部公共库](../../piq-retro-platform/README.md)：基础职责，及哪些事情不应该写进附属。

## 我想制作新设备

阅读顺序：

1. [机器与附属通用制作规范](../design/机器制作与交互标准.md)：设备应该怎样使用，内含参考图。
2. [全组件流程与接口总览](../design/全组件运行流程与复用接口总览.md)：已有哪些界面、服务和适配，哪些仍未完成。
3. [附属能力声明模板](../design/附属能力声明模板.md)与[功能说明模板](../design/功能制作说明模板.md)：把目标和边界写清。
4. [制作验收清单](../design/功能制作验收清单.md)：分别验证基础逻辑、真实核心、Minecraft 实机和多人。

使用 libretro 的设备，开始前还要阅读 [libretro 官方文档](https://docs.libretro.com/)和目标核心说明，优先寻找现成方案。模板和接口存在不等于机型已经完成。

## 我在查技术细节或旧问题

- [功能与配置规则](../design/方块电玩功能行为与配置规范-v1.md)：权限、距离和设置归属。
- [构建输入锁](../../source-control/build-inputs.json)：固定文件身份，不是游戏下载清单。
- [命名与交付规范](../../source-control/BRANDING.md)：内部名称和玩家 JAR 的区别。
- [主包第三方声明](../THIRD_PARTY_NOTICES.md)：核心与依赖许可，附属另看各自声明。
- 各组件的 `README-history.md`：整理前保留的版本日志，内含早期实现和旧限制。
- [构建历史](../../source-control/BUILDING-history.md)、[仓库迁移历史](../../source-control/GIT_WORKFLOW-history.md)：仅供复查对应批次。

“制作规范”说的是目标，“当前组件说明”说的是现状，“验收记录”说的是某次实际检查结果。这三者不能互相替代。

[返回项目首页](../../README.md)
