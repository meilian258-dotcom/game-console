# 方块电玩内部公共库

Retro Platform 是主模组与附属共用的 Java 源码库，负责核心运行边界、输入与音画数据、内容文件和会话状态等基础能力。

**它不是玩家单独安装的模组。** 公共代码随主模组打包一次，附属编译时依赖主包；不要把 `piq_retro_internal` JAR 放进 `mods`，也不要在每个附属里再复制一套桥。

本库自有 GPL 程序代码的 Snes9x 组合权限见[有限链接许可](../piq-fc-arcade/SNES9X-LINKING-EXCEPTION.txt)。它只覆盖所列自有代码和固定 Snes9x，不替第三方授权；其他 GPL 条款保留。

## 哪些职责放在这里

| 职责 | 当前入口 |
| --- | --- |
| 逐帧核心运行器与现行兼容路线 | `LibretroRuntime`、`LibretroRuntimes`、`LibretroProfile` |
| 带身份校验的内容暂存 | `LibretroContentFiles`、`RuntimeWorkspace` |
| 路径与保存基础能力 | `ConsoleStorage`、`ServerContentPaths`、`LibretroMemoryStore` |
| 开局和关闭的纯状态流程 | `DeviceSessionFlow` |
| 输入与回滚基础结构 | 对应输入域及 `RollbackTimeline` |
| 可选只读回放与最终 PCM | `ReplayGuard`、`ReplayAudio`，FC76.53 起的版本化 v1 合同 |

这些类型提供基础合同，不代替 Minecraft 世界权限、联网协议或界面。主模组负责玩家和设备会话、公共菜单、内容授权及旁观服务；附属负责自身核心、格式、BIOS、端口和保存适配。

本库不能反向依赖主模组或附属的实现类。内部类型可见不代表已经发布稳定 SDK；更改合同要检查已有调用方。

FC76.53 的回放公共合同是明确版本化的局部接口，不把整个内部库升级成稳定 SDK。世界渲染部分留在主包客户端 `ReplayPresentation`，纯保护与音频入口留在本库；可选 Replay 补丁注册服务，设备无需引用补丁实现。迁移范围、调用顺序和破坏性变更规则见[回放公共接口](../piq-replay-compat/API.md)。

## JNI 与 RetroArch 共存边界

FC76.49／街机1.5.11 后续新增公共机柜的 `NetplayVariant`／`NetplayLaunchSpec` 与 RetroArch 文件内容适配，由原版 FBNeo 首批试验；这些 Minecraft 选择／房间接口仍留在主包，不反向放进本库。完整状态一致性尚未通过、旧 JNI 保持，见[试验接口与边界](../piq-fc-arcade/design/RetroArch-Netplay-街机试验接入.md)。下文 LOCAL 首批不等于此 Netplay 试验。

现行方向是 JNI 与独立 RetroArch 共存，复用公共会话边界并逐机型适配；不是把所有运行器改成同名枚举，也不要求删除现有 JNI。FC 双后端独立候选尚未通过真实核心同步／恢复验收，不能据接口存在推定本源码已包含该候选。FC76.47 的主包入口 `cn.piq.fcarcade.retroarch.RetroArchSession` 复用 `NetplayProcess.Role.LOCAL`，由 RetroArch 自主计时，提供输入、音画、检查点及异步关闭；LOCAL 不启用 Netplay 网络。它不是 `LibretroRuntime` 实现，不提供逐帧 STEP、暂停、RESET 或运行中恢复。

纯基础类型、可信资源描述和 `LibretroContentFiles` 继续留在本库；与现有进程协议、主包服务有关的会话留在主包，不为调用它引入本库到主包的反向依赖。`LibretroRuntimes` 的现行逐帧 JNI／旧进程路线保持兼容；需要逐帧内存读取、状态封装、准备后激活或特殊外设的调用者须分别适配，不能直接替换工厂。

LOCAL 首个调用者仅为原生街机公共机柜的客户端 MEDIA，通过本机属性 `-Dpiq.arcade.mediaBackend=retroarch` 显式试用，默认 JNI 不变。LOCAL 使用独立保存身份，街机适配器尚未接入进度持久化；不会读取或迁移旧 JNI／Netplay 档。固定 MAME 的隔离音画／输入／回收首验通过，但 FC、SFC、MD、GBA、服务端托管、其他同步／旁观路线仍需分别适配，MC 与长局仍待验，不据此宣布完整共存或稳定。详见[历史制作记录与能力声明](../piq-fc-arcade/design/统一RetroArch后端-20261009.md)。

## 附属如何接入

1. 先看[制作规范](../piq-fc-arcade/design/机器制作与交互标准.md)，明确玩家应完成哪些操作。
2. 对照[全组件流程与接口](../piq-fc-arcade/design/全组件运行流程与复用接口总览.md)，找到相近的实际调用方。
3. 提供可信固定核心、资源所属类和真实能力声明。自主运行按主包 `RetroArchSession.Config` 接入；仍需兼容逐帧合同的路线使用 `LibretroProfile`／`LibretroRuntimes` 并明确迁移缺口。不要把 LOCAL 伪装成逐帧实现，也不要另造原生桥或下载协议。
4. 逐项实现并测试开局、各输入端口、保存恢复、取消、旁观及关闭。注册成功或单人能运行，不代表联机完成。
5. 确实缺少公共能力时，再提出最小接口扩展及调用方回归范围。

使用 libretro 前必须阅读其官方文档及目标核心说明。官方现成核心优先；自行编译或修改核心需要明确缺口和验证依据。

## 当前能力边界

- JNI 直接在游戏进程内调用原生核心；旧逐帧进程运行器与 RetroArch 自主进程是不同合同。它们都不自动等于 Netplay，进程隔离也不等于操作系统沙箱。
- 当前公共 JNI 平台为 Windows x64，最多持有四个原生会话；这是 JNI 客户端资源上限，不是四个玩家席位，也不是允许一个玩家主持四台机器或启动四个 RetroArch 进程的依据。
- 开发中的公共加载层支持固定共享运行库：运行库由桥保持至进程退出，核心仍按会话卸载。严格拒绝旧桥与新资源混装、摘要错误及外部同名库冲突，失败保留已加载文件并要求重启；不下载游戏指定的 DLL。详见[桥的依赖合同](../piq-fc-arcade/native/libretro-jni/README.md#fixed-shared-runtime-dependencies)。该能力已通过隔离测试，不等于完整 MAME 修复验收。
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
