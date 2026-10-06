# 方块电玩内部公共库

Retro Platform 是主模组与附属共用的 Java 源码库，负责核心运行边界、输入与音画数据、内容文件和会话状态等基础能力。

**它不是玩家单独安装的模组。** 公共代码随主模组打包一次，附属编译时依赖主包；不要把 `piq_retro_internal` JAR 放进 `mods`，也不要在每个附属里再复制一套桥。

## 哪些职责放在这里

| 职责 | 当前入口 |
| --- | --- |
| 选择与调用核心运行器 | `LibretroRuntime`、`LibretroRuntimes`、`LibretroProfile` |
| 带身份校验的内容暂存 | `LibretroContentFiles`、`RuntimeWorkspace` |
| 路径与保存基础能力 | `ConsoleStorage`、`ServerContentPaths`、`LibretroMemoryStore` |
| 开局和关闭的纯状态流程 | `DeviceSessionFlow` |
| 输入与回滚基础结构 | 对应输入域及 `RollbackTimeline` |

这些类型提供基础合同，不代替 Minecraft 世界权限、联网协议或界面。主模组负责玩家和设备会话、公共菜单、内容授权及旁观服务；附属负责自身核心、格式、BIOS、端口和保存适配。

本库不能反向依赖主模组或附属的实现类。内部类型可见不代表已经发布稳定 SDK；更改合同要检查已有调用方。

## 附属如何接入

1. 先看[制作规范](../piq-fc-arcade/design/机器制作与交互标准.md)，明确玩家应完成哪些操作。
2. 对照[全组件流程与接口](../piq-fc-arcade/design/全组件运行流程与复用接口总览.md)，找到相近的实际调用方。
3. 提供可信固定核心的 `LibretroProfile` 和附属自己的资源所属类，复用 `LibretroRuntimes`；不要另造原生桥或下载协议。
4. 逐项实现并测试开局、各输入端口、保存恢复、取消、旁观及关闭。注册成功或单人能运行，不代表联机完成。
5. 确实缺少公共能力时，再提出最小接口扩展及调用方回归范围。

使用 libretro 前必须阅读其官方文档及目标核心说明。官方现成核心优先；自行编译或修改核心需要明确缺口和验证依据。

## 当前能力边界

- JNI 直接在游戏进程内调用原生核心；进程运行器是另一条调用路线。二者都不自动等于 Netplay。
- 当前公共 JNI 平台为 Windows x64，最多持有四个原生会话；这是客户端资源上限，不是四个玩家席位，也不是允许一个玩家主持四台机器。
- 主包的旁观服务按设备管理订阅，具体哪些机型和模式接入以技术总览为准。
- `loadFiles` 是可选能力，未实现的运行器明确拒绝。路径计算不执行 IO，也不赋予读取或上传权限。
- 核心、游戏、BIOS、选项和保存格式共同影响兼容。失败时不静默换核心，不用新核心试读旧档。

详细参数和演进记录见[历史说明](README-history.md)。它保留早期单实例、旧 ABI 和旧默认值，仅供对应版本追溯，不能当作当前限制。

## 开发与验证

在仓库根执行：

```powershell
.\piq-fc-arcade\gradlew.bat -p piq-retro-platform check jar
```

只在依赖完整缓存时加 `--offline`。这里的测试是基础库检查，不是全模组或真实多人验收；完整依赖准备见[构建指南](../source-control/BUILDING.md)。

[返回项目首页](../README.md) · [文档导航](../piq-fc-arcade/docs/README.md)
