# 以 SFC 为蓝本：方块电玩附属制作说明 v1

制作目标先读[机器与附属通用制作规范](../../piq-fc-arcade/design/机器制作与交互标准.md)第 1 节的完整统一流程与类型分支；实际界面、请求/权限、核心、保存、收尾及缺口查[全组件运行流程与复用接口总览](../../piq-fc-arcade/design/全组件运行流程与复用接口总览.md)。本文仅作 SFC41 的历史接入与兼容参考，不再作为新附属自行复制业务流程的模板。[旧基线 B/C 编号](../../piq-fc-arcade/design/附属功能基线与现状对照-20260930.md)供追溯已有制作记录。

历史版本说明：SFC41/FC76.15 使用进程路径；FC76.22 / 完整 SFC43 的早期 JNI 试验增加 `LibretroRuntime`、显式 `PROCESS` / `JNI_TRIAL` 和隔离保存，当时默认仍为进程。这不是当前新附属的默认路线：后续模拟器附属按[公共 JNI 迁移要求](../../piq-fc-arcade/design/JNI全面迁移-范围与验收.md)适配，并依当前源码核对实际能力；JNI 不等于 Netplay，也不自动迁移旧档。[早期一期说明](../../piq-fc-arcade/design/通用JNI一期-FC76.22-使用与附属接入.md)只用于理解协议演进，不能覆盖新目标。

日期：2026-09-28。面向能阅读Java/NeoForge代码、希望为方块电玩添加一个新系统的开发者。

源码蓝本：`piq-sfc-home`完整SFC41，沿用`piq-sfc-arcade`核心接口/历史注册core9；公共行为核对到主模组FC76.15。运行环境基线：Minecraft 1.21.1、NeoForge 21.1.236、Java 21；当前SFC原生核心路径是Windows x64。这里只交付说明，未构建新附属、未重跑历史测试。

距离、配置归属和管理终端的补充约束查[功能行为与配置细则](../../piq-fc-arcade/design/方块电玩功能行为与配置规范-v1.md)。以下“当前”“现有”均指上述历史源码基线；不能只复制 SFC 模型和核心启动就认为附属完成。

## 1. 现有框架能帮你做什么，不能自动帮你做什么

目前有可复用入口，但**还不是公开稳定、任意核心一键接入的SDK**。这里讲真实源码接线，不虚构`AddonBuilder`或万能配置文件。

| 主模组/公共层提供 | 附属仍需负责 |
| --- | --- |
| 家用设备注册钩子、电视/视频连接、公共输入捕获、私人模式入口 | 自己的方块/卡带/控制租约、服务端会话和核心状态、权限与退出收尾 |
| 通用机柜、后端注册、游戏库/网络/部分菜单 | 后端能力、支持格式、ROM读取、街机会话、同步核心适配 |
| Libretro独立进程桥、公共worker/JNA、临时目录治理 | 核心资源、SHA256/许可证、profile、按钮/音画转换、保存身份与兼容 |
| 公共旁观发现和显示/主持入口 | 对应系统的发布者、本地旁观、内容校验、Netplay准备及能力限制 |
| 管理终端、内容权限、全服街机规则 | 专属全服设置接入需协调主模组；尚无保证覆盖所有配置的插件扩展API |

`piq-retro-platform`是内部源码/构建边界，不是要求玩家额外安装的基础MOD。发行时公共代码在主包中一份，附属不要再复制桥/worker/同包名类。

## 2. 从这些真实文件开始阅读

以下路径均相对于`piq-sfc-home`；目录图只展示关键入口，不是可直接复制编译的空模板。

```text
build.gradle / gradle.properties
src/main/templates/META-INF/neoforge.mods.toml
src/main/java/cn/piq/sfchome/
  SfcHomeMod.java                    总入口及公共能力注册
  registry/SfcHomeRegistries.java    方块、物品、方块实体
  world/SfcHomeConsoleBlockEntity.java  设备状态、NBT、可视租约
  layout/SfcApplianceControls.java   电源/复位/手柄区域命中
  server/SfcHomeServer.java          家用业务钩子、控制与会话生命周期
  server/SfcControllerAuthority.java 手柄权威与范围校验
  server/SfcRomStore.java            服务端游戏内容
  server/SfcWatchProvider.java       旁观服务端入口
  server/hosted/SfcServerCoreFactory.java  服务器托管核心工厂
  core/LibretroSfcCore.java           原生核心桥、音画/保存适配
  core/SfcNetplayProfile.java        Netplay专用profile
  client/SfcExecutionCore.java       家用/同步核心执行包装
  client/SfcHomeClient.java          客户端网络入口、输入捕获
  client/SfcPlayback.java            家用播放会话
  client/SfcClientFiles.java         本地ROM与缓存校验
  client/SfcRecoveryBackups.java     本地恢复备份
  client/SfcWatchClient.java         旁观显示/主持/Netplay内容注册
  client/cabinet/SfcCabinetProvider.java  共用街机的客户端后端
  client/cabinet/SfcCabinetSession.java   街机会话
  client/cabinet/SfcCabinetSyncCore.java  街机本地同步核心
src/main/resources/                 模型、贴图、语言等资源
src/test/java/                      单元/源码契约测试
```

建议按“入口→服务端→核心→客户端→旁观→保存→打包”顺序读，不要先从庞大的渲染器开始盲改。

## 3. 第一步：先决定做哪种附属，声明能力

先填[附属能力声明模板](../../piq-fc-arcade/design/附属能力声明模板.md)，逐项决定：家用主机、共享街机后端、手持或电脑程序；人数；文件格式/体积；客户端/服务端平台；串流、本地同步、服务器托管、Netplay、旁观、存档是否支持。

SFC同时走“自己的家用机”和“共用街机后端”两条线。新附属可以只做其中一条；只做街机后端就不必虚构卡带、电视线和两只家用手柄，但该后端的权限、退出、菜单、资源清理一样要完整。

给新系统分配自己的MOD ID、后端ID、物品/方块ID和保存命名空间。不得复用`piq_sfc_home:sfc`冒充SFC，也不得把已经发行的旧SFC注册ID删掉来省文件。

## 4. 第二步：搭项目，分清编译产物与玩家安装包

参考[build.gradle](../build.gradle)、[版本属性](../gradle.properties)、[MOD元数据](../src/main/templates/META-INF/neoforge.mods.toml)。当前SFC构建使用Java21和NeoForge21.1.236，本地文件依赖为：

```text
../piq-fc-arcade/build/libs/piq_fc_arcade-0.31.0-alpha.76.jar
../piq-sfc-arcade/build/libs/piq_sfc_arcade-0.2.0-alpha.9.jar
```

这是现有工作区的编译接线，不是公开Maven坐标。新开发者拿不到这些文件时，须先按主工程流程取得对应开发产物；**不能声称克隆一个空项目就能直接编译**。SFC依赖core9是历史演进结果，新附属是否需要它应根据实际接口决定，不应为了“照着SFC做”一并依赖SFC。

当前元数据主包范围为`[0.31.0-alpha.76,0.31.0-alpha.77)`，旧SFC核心范围为`[0.2.0-alpha.9,0.3.0)`。这只是加载声明，不等于每个范围内版本均经过测试；发行说明仍需锁定实际验证的完整配套。

SFC两端安装依据：服务端有方块/卡带、控制租约、权限、网络和会话处理，客户端有渲染、输入、播放核心；不能只看依赖声明的`side`推断安装端。

当前`gradle.properties`版本是alpha.41。`build.gradle`里遗留core7/旧审计脚本注释是历史文字，不能当作现行打包依据。

## 5. 第三步：完成注册，但不要把注册当作完成运行

总入口：[SfcHomeMod](../src/main/java/cn/piq/sfchome/SfcHomeMod.java)。下面列的是现有调用关系摘录，**不是完整可编译模板**：

```java
HomeApplianceService.registerControls(SfcHomeConsoleBlockEntity.SYSTEM_ID, SfcApplianceControls::hit);
SfcHomeRegistries.register(bus);
bus.addListener(SfcHomeNetwork::register);
SfcHomeServer.register();
CabinetBackends.register(CABINET_BACKEND, "SFC / Super Famicom", false);
CabinetBackends.registerNetwork(CABINET_BACKEND, 2);
CabinetBackends.registerSync(CABINET_BACKEND);
ServerCoreRegistry.register(CABINET_BACKEND, new SfcServerCoreFactory());
SfcWatchProvider.register();
```

完整入口还注册修复/本地旁观网络与创造物品等，照源码核对不能漏。只有实现并验证对应能力后才注册`Sync`、服务器托管等；不要为显示按钮提前宣称支持。

方块/物品/实体看[SfcHomeRegistries](../src/main/java/cn/piq/sfchome/registry/SfcHomeRegistries.java)。SFC有主机、卡带、手柄，手柄是借用控制物，不是简单在创造栏发一个无租约的物品。新物品加入现有分类顺序，避免随意另插第一页或重复放历史测试物品。

## 6. 第四步：做家用设备的服务端业务

如只做街机后端可跳过家用外观，但不能跳过后端服务端校验。

设备实体参考[SfcHomeConsoleBlockEntity](../src/main/java/cn/piq/sfchome/world/SfcHomeConsoleBlockEntity.java)，继承公共`ExternalHomeConsoleBlockEntity`。保存卡带、设备设置等持久状态；玩家UUID/租约显示只服务动画与可视同步，**不能用客户端传来的可视租约授予控制**。SFC服务端加载时会重置可视手柄租约，不把旧NBT当作活连接。

主要业务参考[SfcHomeServer](../src/main/java/cn/piq/sfchome/server/SfcHomeServer.java)里`HomeSystems.register(..., ServerHooks)`：

- `onPowerOn/onPowerOff/onReset`：先校验人、机器和内容，再改变运行状态；失败返回具体原因。
- `onController/onControllerDock`：借出/归还与玩家、端口、会话、租约绑定；及时清空旧输入。
- `isRunning`与同步设置可用性：按真实会话/停止中状态回答，不只看电源NBT。
- `onPlaybackStopped`：运行结束与手柄归还分开；不能粗暴重置所有借用物。
- `onRemoved`：停止核心、释放手柄/观看关系、按规则退出卡带；服务器停止也需清理。

当前SFC操作者业务校验有近设备/电视条件，有线租约另外使用距主机**6格**。应沿用公共行为语义，不把“菜单8格”和“线缆6格”合成一个随意改的常量。操作者和计算主持是不同角色；移交/退席不等于核心必然停止。

物理按钮和动画同时做：[SfcApplianceControls](../src/main/java/cn/piq/sfchome/layout/SfcApplianceControls.java)负责命中，客户端硬件/手柄/线缆渲染负责表现。四向旋转、桌面摆放、电视关联、邻近设备遮挡、按钮动画与权限拒绝后的回弹都要验证。

## 7. 历史第五步：SFC41 进程核心接入

SFC41 历史调用见下方示例。阅读当前[LibretroSfcCore](../src/main/java/cn/piq/sfchome/core/LibretroSfcCore.java)与公共[LibretroProcess](../../piq-retro-platform/src/main/java/cn/piq/retro/libretro/LibretroProcess.java)时，以实际签名及技术总览为准；不能要求所有新附属继续默认创建进程。SFC 使用 Mesen-S；无论进程还是 JNI，可信核心来源、拥有线程、生命周期和资源预算都必须遵守，不在 Minecraft 渲染线程直接调用任意 DLL。

该适配器实现的是历史`cn.piq.sfcarcade.core.SfcCore`接口，ROM、帧、输入等类型也来自core9；它不是所有新附属必须实现的通用`SfcCore`标准。新系统应使用适合自己的类型，再对接公共街机/托管等接口，不能仅改类名就宣称已经与旧SFC依赖解耦。

SFC41 基线的进程调用示例（非新附属默认工厂）：

```java
new LibretroProcess(profile(), LibretroSfcCore.class)
```

第二个参数指定**核心资源归属的附属模块**；worker、清单和JNA仍来自主包。新附属换成自己的类/资源/profile，不要把SFC类名或主包worker复制过去。

当前SFC核心资源为`/core/sfc-libretro/windows-x64/mesen-s_libretro.dll`，代码内固定SHA256和BUILD身份。自己的核心需要另行固定来源、版本、架构、摘要、许可证和对应源码材料；不能仅给一个随机DLL路径或下载“最新版”就作为可复现发行。

需要自己实现/核验的核心适配：

1. ROM规范化和内容身份；只接受声明的格式、大小、结构。
2. 输入端口与按钮映射。SFC profile使用两个设备ID257的端口，这是SFC的具体选择，不是所有系统都应该照抄的通用值。
3. 视频像素格式、尺寸、宽高比和旋转。SFC当前旁观渲染假定rotation为0；新竖屏/旋转核心必须另行适配，不能默认照抄就正确。
4. 音频采样率、每帧采样数、50/60Hz时序；参考SFC重采样器，不把一个MC tick当成一个模拟器帧。
5. 一条拥有核心的执行线程、超时、取消、关闭及异常回收；所有核心访问须符合其线程约束。
6. 实际支持的原生保存与即时状态；格式必须带游戏/核心身份，恢复前校验。

执行包装在[client/SfcExecutionCore](../src/main/java/cn/piq/sfchome/client/SfcExecutionCore.java)，而不是`core`目录。它负责启动探测/时序及状态检查，被家用和街机同步路径复用。新核心不具备确定性时，不能仅实现一个`runFrame`就开放本地同步。

## 8. 第六步：输入、界面与共用街机后端

客户端入口看[SfcHomeClient](../src/main/java/cn/piq/sfchome/client/SfcHomeClient.java)：接收SFC网络消息，向`ControllerCapture`注册输入捕获，用公共键位配置和租约回执校验，并接入私人家用会话。退出/失焦/暂停菜单时清空按键，禁止游戏键穿透成Minecraft操作。

当前`KeyboardConfig.Profile.SFC`是既有公共profile，不是可任意填字符串的开放注册表。新增平台按钮布局不适合现有profile时，应协调公共接口扩展，不能改坏SFC绑定，也不能硬编码另一套不可配置键位。

共享街机路径读[SfcCabinetProvider](../src/main/java/cn/piq/sfchome/client/cabinet/SfcCabinetProvider.java)：

- 客户端setup注册到`CabinetClientBackends`；服务端后端注册与客户端实现缺一不可。
- 声明`.sfc/.smc`、最大32MiB、公共本地SFC ROM目录与占用原因。
- `open`创建普通街机会话；`openSync`创建同步核心；共享核心租约需要处理“正在旁观时请求操作”等冲突。
- 普通会话通过`LibretroSfcCore::new`建核心，同步路径复用`SfcExecutionCore`；详细实现看同目录`SfcCabinetSession/SfcCabinetSyncCore`。

特别注意：**当前SFC街机provider没有覆写`prepareNetplayFactory`**。SFC家用Netplay已接入，不代表共享街机后端也自动支持同样Netplay路径。每种外壳、每种模式要单独声明和验证。

菜单按钮、服务器/本地内容切换、权限、全服设置只读说明按行为规范接入；专属全局字段要在管理终端有入口和服务端保存/同步。当前没有“写一个注解即可自动出现终端按钮”的API，新增需求要由对应组件负责人配合实现。

## 9. 第七步：旁观、同步、Netplay分开接线

| 路径 | 当前SFC入口 | 新附属的核对要点 |
| --- | --- | --- |
| 公开旁观服务端 | `SfcWatchProvider` → `WatchProviders.register` | 是当前授权设备/内容、同维度、有效范围；不是广播给所有在线玩家 |
| 旁观显示 | `SfcWatchClient` → `WatchClient.registerDisplay` | 对应真实电视/主机连接、帧身份、显示距离、声音衰减、停止后释放 |
| 主持供流 | `WatchClient.registerHost` → `SfcWatchPublisher::demand` | 按实际需求发布；停止显示主持本地画面不能误停其他人的流 |
| 本地旁观 | `SfcLocalWatchClient/Server` | ROM授权、核心占用、输入时间线、加入/退出/重同步；不让观看者控制 |
| 家用Netplay旁观内容 | `NetplayWatchContent.register` → `SfcNetplayWatchContent::prepare` | 校验并准备当前授权游戏、独立profile、准备取消、连接身份变化 |
| 服务器托管 | `SfcServerCoreFactory` → `ServerCoreRegistry` | 服务器OS核心可用性、资源上限、保存和退出；注册不等于Linux也支持 |

源码入口：[SfcWatchClient](../src/main/java/cn/piq/sfchome/client/SfcWatchClient.java)、[SfcWatchProvider](../src/main/java/cn/piq/sfchome/server/SfcWatchProvider.java)、[SfcServerCoreFactory](../src/main/java/cn/piq/sfchome/server/hosted/SfcServerCoreFactory.java)。

玩家串流发送音画，本地同步运行本地核心，两者对核心可用性/确定性/ROM要求不同。Netplay还需专门profile、端口/会话策略及失败诊断；不是把“同步支持”开关打开就完成。

当前实验Netplay不读写游戏进度。旁观首次需要的游戏可按授权内容通道获取，但不能承诺大ROM已有断点续传；断点续传仍是待办。不得为解决黑屏把所有ROM先传给所有在线玩家。

## 10. 第八步：内容、缓存、正式保存和恢复备份分开

参考[SfcClientFiles](../src/main/java/cn/piq/sfchome/client/SfcClientFiles.java)、[SfcRecoveryBackups](../src/main/java/cn/piq/sfchome/client/SfcRecoveryBackups.java)、服务端`SfcRomStore/SfcCartridgeEditorService`。

- 本地SFC读取`.sfc/.smc`，最多32MiB，容许需要规范化的512字节头；规范化后计算SHA256，不能只拿文件名作游戏身份。
- 缓存通过`ConsoleStorage.root(game)`进入`game-console`统一根；SFC缓存子目录`piq-sfc-home/cache/roms/<sha>.sfc`。新附属用自己的子目录，不往系统Temp无限解压ROM/核心。
- 文件检查使用有界读取、普通文件/不跟随链接等防护；上传、写卡、获取服务器内容要走内容权限服务。上传本机内容是独立授权动作，不默认公开所有磁盘文件。
- 当前SFC原生保存接入SRAM；RTC非空会明确拒绝，不声称完整RTC支持。保存限额8MiB，即时状态有单独限额/校验，不直接复用其他核心的数字。
- 核心保存命名空间含`sfc-libretro-state-v1`与BUILD身份；旧WASM存档不自动迁移、不覆盖，新核心版本不能静默读取不兼容状态。
- 本地恢复备份不是自动恢复；服务器托管使用`HostedSaveFile`的独立`sfc-libretro-v1`路径/游戏与核心身份。别把私人备份、共享卡带状态、托管SRAM混成一个`save.dat`。
- 删除临时目录前核对会话锁/进程归属；不自动删除用户ROM原件、正式存档或未知旧格式。

## 11. 第九步：测试与构建，最后才做发行包

已有配套开发依赖和构建缓存/仓库访问准备好后，可在`piq-sfc-home`执行：

```powershell
.\gradlew.bat test jar
```

这是参考项目的构建命令，本次文档工作**没有执行**。离线环境只有依赖完整缓存时才可加`--offline`；缺依赖要报告，不能拿上一版JAR冒充这次产物。SFC测试对Windows中文路径有专门ASCII临时依赖目录处理，迁移构建脚本时应保留或重新验证，不能随意删除。

至少覆盖以下组；测试类名称是蓝本，不是本次通过证明：

| 测试组 | SFC参考类 | 新附属验收内容 |
| --- | --- | --- |
| 核心/音频 | `LibretroSfcCoreTest`、`SfcPcmResamplerTest` | 启动/错误/关闭、像素/时序/音频、合法和损坏状态 |
| 控制/权限 | `SfcControllerAuthorityTest`、`SfcControllerCableTest`、`SfcContentPermissionSourceTest` | 过期租约、超距、断线、重复包、非授权内容 |
| 输入与动画 | `SfcUnifiedKeyboardTest`、`SfcButtonAnimationTest`、`SfcApplianceControlsTest` | 键位隔离、按钮位置、四向命中和释放 |
| 文件/保存 | `SfcClientFilesTest`、`SfcClientIdentityTest`、`SfcBackupStatusTest` | 超大/截断文件、摘要不符、跨游戏/跨核心身份、备份错误 |
| 同步/旁观 | `SfcInputTimelineTest`、`SfcLocalWatchTransferTest`、`SfcWatchSourceTest` | 晚加入、退出、重入、无ROM、取消准备、旁观不能输入 |

单元/源码契约测试不能代替真实核心和Minecraft测试。另做真实核心探针、专服启动、多人操作/旁观、缺运行库、主持断线、跨距、重复进出、各模式保存测试；记录系统、核心摘要、主包/附属版本和结果。

### 完整包是一个必须单独验收的步骤

当前SFC的`build/libs/piq_sfc_home-*.jar`是**薄开发产物，不等于玩家用的完整SFC包**。完整SFC41包含家用层与core9的历史接口/注册/资产，最终名为`game_console_sfc-0.1.0-alpha.41.jar`。不能同时给玩家安装完整包和重复的旧核心包。

现有完整包的本地配套流程记录在工作区`outputs/storage76/package.py`、`freeze.py`及对应审计资料；这些是固定版本交付脚本，含工作区假设，**不是新附属通用发布器**。准备开源时应把必要打包步骤整理进可复现的版本化构建任务，公开所需源码/资源/许可和依赖取得方式，不能要求贡献者猜历史outputs目录。

新附属发行包要验证：注册唯一、公共桥不重复、核心资源存在且摘要正确、客户端/专服可加载、依赖范围准确、许可证/对应源码齐全、没有ROM/BIOS/私人路径/用户存档。未核清分发许可的核心不随包发布；不得提供游戏或BIOS的未授权下载。

## 12. 给后来开发者的逐步交付清单

1. **能力表**：先定外壳/模式/人数/平台/保存，不支持的写清原因。
2. **可加载空壳**：独立namespace，注册唯一，客户端与专服启动成功。
3. **单机最小闭环**：选内容→授权→开机→输入→音画→退出→重开；资源能回收。
4. **设备适配**：电视/手柄或共用机柜、命中、动画、物品分类；不做没有业务的假按钮。
5. **菜单与全局配置**：按行为规范逐项核对，服务器权威和生效范围明确，管理终端可找到全局入口。
6. **联网能力逐项开放**：先验证一种再登记一种；家用与街机分别测，不能互相借用“已支持”结论。
7. **保存与异常恢复**：按模式证明保存语义，保证旧数据不损坏；不支持则禁用并说明。
8. **完整包与文档**：从最终发行件验证，记主包配套、两端安装、核心版本、测试结果、已知缺口；实机未验则保持候选。

在当前框架下可以按此路线开发，但公开给第三方前仍需补齐稳定API边界、可复现依赖/完整打包、终端扩展入口、最小示例工程和兼容测试矩阵。**本说明提供的是可追溯的SFC蓝本，不把这些待办说成已经有的SDK能力。**
