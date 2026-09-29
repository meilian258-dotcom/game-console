# SFC 附属模块：家用硬件基座 API 计划

日期：2026-09-10。作者：subor_slim；root 集成确认。

状态：接口设计，尚未实施本页的 FC 生产改动。alpha.13 先独立冻结、打包；收到 root 放行后才实施服务端基座。SFC 核心 PoC、附属模块及客户端显示由 root 另行负责。本计划不是 SFC 可玩性、ROM 兼容性或联网实测报告。

## 范围与当前源码证据

目标是新 `piq-sfc-home` 家用附属模组复用 PIQ FC 的电视、AV 连线、受保护交互和生命周期；不复制整个 FC 模组，不把 SNES 数据伪装成 NES。原规划暂称 `piq-sfc-addon`，现按 root 确认使用 `piq-sfc-home`，避免和已有独立 `piq-sfc-arcade` 混淆。

已补读维护手册 2026-08-05 SFC 核心两阶段记录及 2026-08-06 alpha.6 记录，并核对现有 public API：`cn.piq.sfcarcade.core.wasm.WasmSfcCore`、`SfcCore`、`SfcControllerState`、`SfcFrameResult`、`cn.piq.sfcarcade.audio.SfcAudioPlayer` 和 `cn.piq.sfcarcade.rom.SfcRomRepository`。新附属拟依赖 FC alpha.14 小型硬件 API 与已有 `piq_sfc_arcade` alpha.6，只调用这些公开接口，不修改旧 SFC 负责人内部或撤掉旧街机。核心、立体声和 ROM 规范化不需要重新实现；新家用会话/卡片/控制器仍由附属自己管理。既有 SFC 工程标注 GPL-3.0-or-later，后续交付须保留其已有许可/依赖约定，不重复打包 Wasmtime。

当前不能只注册一个新机器就直接接入，原因如下：

- `HomeConsoleBlockEntity` 是 final，插入/加载固定校验 `FcCartridgeData.isPlayable`；`HomeEndpointBlockEntity` 为包内基类。
- `HomeHardware.connectedConsole` 返回 FC 主机；`mutual` 参数及 `stopEndpoint` 的强制转换仍绑定该具体类。电视右键/接好 AV 自动启动直接调用 `ServerArcadeSessions.startHomeConsole`。
- `HomeControllerService.grant` 固定发 `FC_CONTROLLER`，并只区分 FAMICOM/SUBOR；不是外部系统的控制器服务。
- `ArcadeInputPayload` 的输入使用 `writeByte` / `readUnsignedByte`。SFC 的 12 键不能复用这一包或偷偷改变其含义。
- `RomRepository` 要求 `.nes` 并调用 `INesHeader.parse`；`ServerCartridgeService.openAt` 只接受 FC 完整卡；这些限制继续保留。
- 电视外壳已经独立绘制，但动态画面仍在包内 `ArcadeBlockScreenRenderer.render(..., ClientArcadeSession, ...)`，由 NES 自己的会话表驱动。
- `HomeAvCableMesh` 已是公共类，但主机端坐标/外壳仍通过 FC/Subor boolean 选择；SFC 不应借用错误的 FC 插口坐标。

可复用的底层资产是已存在的双端 UUID、唯一连接账本、一次性线材退款、六种电视/代理方块解析、标准交互事件校验、存档同步和独立扫描线状态。

## 责任划分

FC 基座拥有：AV 账本和唯一连接、线材扣除/退款、电视实际身份及完整性、区块加载边界、公共保护事件、显示几何和扫描线。

SFC 附属拥有：自己的主机/卡片/手柄注册、卡片槽事务、ROM 校验和目录、模拟核心、音频、12 键输入、玩家与控制器租约、会话协议、同步/存档、游戏暂停和退出流程。SFC 的会话必须绑定下述连接身份，但不能修改基座 link。

本子任务只实施以下服务端 API 与 `HomeHardware` 的必要安全分支。客户端 `HomeVideoDisplay` 与可参数化线材外观由 root 分开设计，不能用本页冒充已存在的实现。

## 1. 外部主机 BE

新增公共类：`cn.piq.fcarcade.home.ExternalHomeConsoleBlockEntity`。

```java
public abstract class ExternalHomeConsoleBlockEntity extends HomeEndpointBlockEntity {
    protected ExternalHomeConsoleBlockEntity(
            BlockEntityType<?> type, BlockPos pos, BlockState state,
            ResourceLocation systemId);

    public final ResourceLocation systemId();
    public final BlockPos televisionPos(); // 未连接时为 null；并不保证对端已加载。
    public boolean isHardwareComplete();  // 默认 true：首版单格 SFC；可覆盖纯硬件完整性检查。
    public final void notifyHardwareChanged();
}
```

以上为方法签名，采用现有 Minecraft 类型：`net.minecraft.world.level.block.entity.BlockEntityType`、`net.minecraft.core.BlockPos`、`net.minecraft.world.level.block.state.BlockState`、`net.minecraft.resources.ResourceLocation`。不是要求另造同名类型。

- 构造函数向现有包内父类传入 `type/pos/state`，包内实现 `kind()` 为 `HomeLinkLedger.Kind.CONSOLE`；不需要把整个 `HomeEndpointBlockEntity` 或账本公开。
- `systemId` 为构造时的 final 值，例如 `piq_sfc_home:sfc`；不能从可修改 NBT 选择 provider。禁止占用基座保留 ID `piq_fc_arcade:nes`。
- 原父类的公共 `hardwareId()/linkId()/maintenanceTick()` 和 BE 标准 API 继续可用。
- `televisionPos()` 只是只读 `peerPos()`；不公开 `attach/clearLink/peerId` 或账本写入。
- `notifyHardwareChanged()` 只允许当前已加载、未移除、服务器线程上的实际 BE 调用，内部走现有 `changed()`。SFC 卡片先完成自己的原子事务，再通知持久化/同步。
- SFC 自己覆写 `loadAdditional/saveAdditional` 并调用 super；基座不读写 SFC 卡片数据。
- SFC 方块提供服务器 ticker 调用 `maintenanceTick()`，并在 `onRemove` 按现有约定调用 `HomeHardware.removed(level, pos)`。只移除视觉方块而不走生命周期是不符合接口约定的实现。

## 2. Provider 注册与服务器 API

新增公共入口：`cn.piq.fcarcade.home.HomeSystems`。以下嵌套类型均位于该类。

```java
public enum StopReason {
    DISCONNECTED, REMOVED, UNLOADED, INVALID_CONNECTION
}

public interface ServerHooks {
    default void onLinked(ServerPlayer player, Connection connection) {}
    void onInteract(ServerPlayer player, InteractionHand hand,
                    Connection connection, BlockHitResult hit);
    default void onPlaybackStopped(ServerLevel level,
                    ExternalHomeConsoleBlockEntity console, StopReason reason) {}
    default void onRemoved(ServerLevel level,
                    ExternalHomeConsoleBlockEntity console) {}
}

public static void register(ResourceLocation systemId, ServerHooks hooks);
public static Optional<Connection> connection(ServerLevel level, BlockPos consolePos);
public static Optional<Connection> connectionForTv(ServerLevel level, BlockPos televisionPos);
public static boolean isCurrent(Connection expected);
public static InteractionResult interact(ServerPlayer player, BlockPos consolePos,
                    InteractionHand hand, BlockHitResult hit);
```

`ServerPlayer/ServerLevel` 来自 `net.minecraft.server.level`；`InteractionHand/InteractionResult` 来自 `net.minecraft.world`；`BlockHitResult` 来自 `net.minecraft.world.phys`；`Optional` 为 `java.util.Optional`。`Connection` 使用下述新嵌套公共类型，不依赖 SFC 类或 NES 核心。

### 连接对象

采用 final、只读 `HomeSystems.Connection`；构造函数由基座包内持有。公共 accessor 合同：

```java
public ResourceLocation systemId();
public ServerLevel level();
public ExternalHomeConsoleBlockEntity console();
public HomeTvBlockEntity television();
public UUID consoleId();
public UUID televisionId();
public UUID linkId();
```

`consoleId/televisionId/linkId` 是创建连接对象时捕获的 UUID，不是在 getter 中重新读取。BE 引用用于区分同坐标甚至同 UUID 的替换实例；对象不是可长期信任的租约，调用方每次提交/每 tick 使用 `isCurrent` 复验。

`connection` / `connectionForTv` 只返回外部系统连接，不把 NES 会话包装成 SFC。获取成功必须同时满足：

1. 服务器线程；两端区块已加载，不强载；TV 代理先解析已验证的 anchor。
2. 主机是实际未移除 External BE，provider 已注册；TV 为实际未移除 HomeTv BE。
3. 两端硬件完整性通过；外部 `isHardwareComplete` 抛异常时拒绝。
4. 同维度、双方 UUID/peer UUID、坐标和 link UUID 全部相互一致。
5. 原 `HomeLinkLedger` 中仍存在未关闭、归属精确一致的连接；端点距离遵守原 AV 8 格上限。
6. `isCurrent` 重新执行上述查询，并比较原始双方 BE 对象和捕获 UUID；不能仅比较坐标。

getter 只表示硬件有效，不代表某个玩家有权控制。SFC 仍需自己的玩家身份/手柄/距离/人数/输入序号/epoch 检查；异步 ROM 或存档回调提交前也要重复检查连接。

### 注册生命周期

- addon 在 common setup 的主线程工作阶段调用 `HomeSystems.register`；同 ID 重复注册、保留 NES ID、null、运行期替换均拒绝。
- 基座在服务器开始前锁定注册表；锁定后只能查询，不支持运行期热替换 provider。
- provider 无需包含任何客户端类，独立服务端加载不触发客户端/模拟器类初始化。
- 未知 system ID fail-closed：不连接、不启动、不退化为 FC、不尝试用 NES 解析其卡片。
- provider 回调异常在边界记录日志并隔离；不让一个 SFC provider 的运行时异常破坏原 FC tick、账本关闭或退款。异常不能让已付费连接再扣第二根线，亦不能回滚成可重复退款状态。
- 正常的 `onPlaybackStopped` 必须幂等。stop 可能由拔线、拆卸、对端失效和维护检查先后触发；不得重复退卡/刷手柄。

### 启动与交互权限

- `onLinked` 只在 AV 双端写入、原子扣线、清选中和账本落脏全部完成之后调用。provider 失败不撤销已经完成的物理连接。
- `HomeSystems.interact` 在服务器重新解析真实 External 主机和连接，要求玩家存活、非旁观、同维度、交互距离内、原版 `mayInteract` 允许；咨询 clicked 与必要的 anchor 标准 `RightClickBlock` 事件，检查取消、`useBlock/useItem=DENY`。
- provider 分发前后涉及外部事件时重验加载、BE 身份、连接和玩家状态；防重入 guard 用 finally 清理。未验证的客户端坐标不是权限凭据。
- 电视被右键时走原 TV/proxy 校验，再按实际连接 source 分流。External 命中只调用其 provider，不继续进入 `ServerArcadeSessions.startHomeConsole`。
- 此接口适配标准 NeoForge 交互保护事件，不宣称覆盖不参与该事件的任意私有领地插件。

## 3. 停止、卸载、拆除语义

root 已确认：**卸载停播放但保留物理连接；拔线/拆除才关闭账本并唯一退款。**

- 外部主机卸载：以传入的原 BE 身份调用 `onPlaybackStopped(..., UNLOADED)`；不要求此时还能查询到 TV，保持 link/NBT/已付线材状态。
- TV 卸载：若对端 External 主机仍加载，通知其停止；若主机也已卸载，它自己的卸载回调已停止播放。SFC 每 tick 的 `isCurrent` 仍作为必要失效门禁。
- stop 回调特意不要求 `Connection` 参数：连接失效正是停止的原因，不能因取得有效连接失败而跳过清理。
- 拔线：先发停止通知，再沿原账本 close/claim refund/清双方已加载端，恰好退一根线；provider 不接触退款。
- 主机拆除：基座 `beginRemoval` 防重入，停止及断线后独立调用 `onRemoved`，让 addon 原子取出并掉落自己的卡。异常不能跳过基座断线；是否取出过卡由 addon 自己的槽事务保证。
- TV/proxy 拆除、同坐标替换、身份不匹配：只影响旧连接，不停止或清除同坐标的新电视/新主机。
- 关闭服务器/玩家退出等 SFC 自身会话清理由 addon 拥有，不能依靠只在方块卸载时才执行的 hook。

## 4. HomeHardware 最小迁移面

保持现有公共 `connectedConsole/selectedRom/validPlayback` 的 NES 含义及返回类型。

新增内部通用端点验证，用同一账本检查 FC 或 External 主机；`connectedConsole` 对通用结果再检查 FC 具体类型。改 `mutual` 为包内端点身份比较，删除 `stopEndpoint` 对非 TV 的无条件 `HomeConsoleBlockEntity` 强转。

仅在以下位置加 External 分支：

- `loadedEndpoint` 已接受父类子类，本身无需另造无校验 resolver。
- `useCable` 的主机完整性/已注册检查、连接完成后的 external `onLinked`。
- `interactTv` 对 external 连接的分流。
- `stopEndpoint/reconcile/unloaded/removed` 生命周期分流。
- 一条受保护的 external `interact` 入口。

FC 原有插卡、取卡、自动开局、手柄、ROM、存档、会话和网络不重写；AV 全部原始数据格式与退款算法不改。现有 `HomeConsoleBlockEntity` 不去掉 final、不改成泛型卡片容器。

## 5. 客户端和电脑的后续接口边界

root 负责一个公开、窄的 TV 画面绘制入口，输入是 TV anchor 与 addon 自有帧纹理/尺寸/显示比例；内部复用 TV 四朝向、偏移、黑边和扫描线。SFC 自己驱动 RenderLevelStageEvent，不插入 NES `ClientArcadeSession` 表。

双方客户端应按 system ID / 双端 UUID / link ID / SFC epoch 检查显示来源；连接或会话失效立即清纹理，不允许两系统同时占一块电视。

AV 几何的后续新增参数应为主机插口/向外方向/机壳范围描述；现有 FC/Subor 重载保持。未收到 SFC 坐标之前不虚构插口或借用旧机器坐标。

卡带电脑初期可以复用物理 `CartridgeComputerBlockEntity` 的稳定身份，但 SFC 卡片走独立编辑会话、文件校验、目录和 payload。保留 FC 编辑器的 `FcCartridgeData` 白名单，不把 `.sfc/.smc` 放进 `.nes` 库。后续可设计 editor provider，不能仅绕过 FC `openAt` 的物品检查。

## 6. 迁移和回归测试计划

所有“通过”以实际执行结果为准；本页列的是待实施项目，不预先宣称成功。

### 纯策略与注册

- ID 一次注册、重复/保留/未知 ID、注册锁定后写入拒绝；不同 addon 不互相覆盖。
- provider 启动/完整性/stop/remove 抛异常不穿透；停止幂等调用不会产生第二次物品结算。
- 连接身份：同位置替换、同 UUID 不同 BE、换维度、闭合旧 link、错 peer、超距、任一端未加载全部拒绝。
- 重新查询不能强载；stop 在不可取得有效 Connection 时仍按原主机身份到达。

### 现有 AV/FC 回归

- 原 FC/Subor × 六 TV × 四朝向，连线、重复点击、已占用、接同类端点、距离边界和代理解析全保留。
- creative 与生存各扣一根线；重复拔线/两端先后拆/代理与 anchor 重复移除，只退一根。
- 卸载不退线，重载保留连接；拆除才退线；已加载替代实例不被旧账本清理。
- 单人格子/多格 FC 和 NES 插卡、游玩、退出、P1/P2 原测试不变；不放宽 byte mask 或原 ROM 类型断言。

### 外部 provider 验证

- 使用无模拟核心的测试 provider 和一格测试 External BE，证明 API 不加载 NES/SNES 图像或原生 DLL。
- 与真实已有 TV 连接后，getter 返回精确双方实例；provider 回调异常后 AV 仍为单条有效付费连接。
- TV 右键只有 external provider 被调用，NES start 计数为零；插着 NES 卡的原机器仍只走原 NES 路径。
- 权限取消/双位置 DENY/事件中替换或卸载机器/事件中换维度均 fail-closed。
- 移除/unload/invalid link 通知附属清会话和画面；退出服务端不会残留全局强引用。

### 验证层级

本子任务允许纯 javac、独立 JUnit 和源码布线测试；统一 Gradle、完整回归及打包只由 root 执行。后续还需要 Minecraft 实机验证：普通玩家权限、实际领地插件组合、双人加入/离开、TV 卸载、跨区块连线、拆卸和重登。离线 probe 不能写成真实联网测试。

## 7. 分阶段交付门槛

1. alpha.13 独立冻结并交付，SFC 生产接入尚不混入。
2. 复验既有 public SFC 核心/音频/ROM 仓库在新依赖组合下的 PoC，不另选或重造模拟核心；PoC 不要求改 FC。已有旧版本验证不能冒充新家用附属实测。
3. root 放行后实施本页服务端基座和客户端窄接口，完成旧 FC 回归。
4. addon 接一格 SFC 主机和现有 TV，再实现自己的卡片、手柄、输入/会话/存档。
5. 明确测试版限制后再形成独立 addon 与对应 base 成品；不向旧 FC 包静默注入 SFC 协议，也不以新增功能为由改商业 ROM 分发范围。
