# 方块电玩画面与音频录像 API

## 主包公共回放合同 v1（FC76.53 起）

新的设备实现应优先依赖主包提供的三项合同，而不是补丁或 FC 的私有方法。它们随主包打包一次；附属仅编译依赖主包，不复制这些 class，没有安装 Replay 时为无服务状态。主包不能反向引用可选补丁的实现或 ReForgedPlay 类型。

| 合同 | 入口和责任 |
| --- | --- |
| 只读回放 | 纯 Java `cn.piq.retro.replay.ReplayGuard`：`playing()`、进入／退出、会话 `epoch()` / `valid(epoch)`、入口预检查、一次性资源退役 |
| 最终音频 | 纯 Java `cn.piq.retro.replay.ReplayAudio`：输出前取得代次 token，返回后提交实际输出字节，flush/close 前失效；无磁盘或设备操作 |
| 世界画面 | 客户端 MC1.21.1 `cn.piq.fcarcade.api.replay.ReplayPresentation`：屏幕材质、世界锚点、finally 清除锚点；保留最终变换后的绘制几何 |

三个 `API_VERSION` 当前都是 `1`，描述文件为 `META-INF/game-console/replay-api.properties`。补丁在加载前读取描述和实际 class 签名，不初始化设备类；缺失、错误签名或未知版本明确拒绝。描述文件不能替代真实合同。公共画面合同依赖 MC1.21.1，不是跨 Minecraft 大版本的稳定渲染 ABI。

### 提供者规则

- 正常设备入口先检查 `ReplayGuard.playing()`；回放中不启动／推进核心、不发送控制与存档、不处理设备联网载荷。调度异步工作时记录 epoch，执行和回调都复核 `valid(epoch)`；退出回放不会让旧 epoch 重新有效。
- 在 `registerEntryCheck(id, check)` 注册只读、快速、非阻塞的预检查。回放适配器先检查，成功后才 `enterReplay()`，构造失败／正常与异常退出要 `exitReplay()`。拒绝预检查不能改变正在保存的旧会话。
- `Retirement.block(cleanup)` 在该回放代次只完成一次成功退役。清理必须是有界的无保存释放，不允许调用会自动保存的旧 `stop()`；不能安全退役的运行器应在入口检查时拒绝，不能假装已经保护。
- `ReplayAudio` 沿用下文 PCM 与迟到 token 语义。FC 的 JavaSound 适配只接收 8～96 kHz、单／双声道 PCM16LE；这是已适配出口的范围，不代表其他格式已通过。服务回调不得等待设备、磁盘或网络，捕获失败不能改变原音频写入的结果。
- `ReplayPresentation` 仅在世界绘制线程调用，实际渲染范围使用 try/finally 清除锚点。观察者只能收集已有数据，不应修改材质、游戏状态或重入渲染。不在 GUI／第一人称任意套用世界坐标。
- `install(...)` 是录像提供者的初始化入口，只允许一个服务，不是每台机器调用一次。附属不得替换提供者或复制公共 API。

### 发布与演进

保持 v1 的签名和语义可以升级主包小版本而不更新补丁。删除／改参／改变行为不是普通内部重构，必须继续提供 v1 兼容入口或发布新版合同与适配器。未知 v2 不静默当 v1 使用。

主包 `check` 的 `packagedReplayApiCheck` 检查最终 JAR 的公共签名、调用点、单一归属及不可反向依赖；快照 staging／release 复用该检查。行为测试另外覆盖迟到写入、预检查拒绝、无保存退役和正常关闭，不能只加一个版本字段冒充兼容。新附属须自行完成画面、声音和保护三个部分；只用了公共电视并不保证其核心或存档已经受保护。

当前主包原有 14 个私有 Mixin 已退役，SFC／MD 等附属仍有有界旧适配，目标版本见 README。下一阶段逐个迁移这些适配，完成后才移除对应版本边界；不批量声称全部未来机型自动兼容。

## 补丁提供的轨道扩展 API

公共接口位于 `cn.piq.replaycompat.api`，当前 `ScreenReplay.API_VERSION` 为 `1`。设备提供已经产生的显示像素和世界空间四边形，兼容补丁负责把它们写入 ReForgedPlay 录像并回放。接口本身不依赖 Minecraft、Replay 或模拟器核心。

此接口只处理显示快照，不接收核心状态、控制指令、文件路径或网络会话。接入它不会自动阻止设备在回放时运行；设备必须另外实现本文的回放保护。

## 可选依赖和惰性加载

需要直接提供轨道帧时，只把 `game-console-screen-api-0.1.0-test.6.jar` 用作编译依赖，不把 API 类复制、打包或重定位进附属。运行时由 `piq_replay_compat` 提供唯一一份实现，玩家不单独安装 API JAR。test.6 沿用 test.5 的画面和音频 v1 轨道接口；音频入口从 test.3 起提供，不能对仅有画面 API 的旧版无条件引用。test.4 新增的 `ScreenFrame` 只读像素辅助方法仅供呈现管线使用，依赖这些方法须要求至少 test.4。

```groovy
dependencies {
    compileOnly(files("libs/game-console-screen-api-0.1.0-test.6.jar"))
}
```

把所有 `cn.piq.replaycompat.api` 引用放在独立的客户端适配类中。普通设备类的字段、方法签名、父类和自动订阅入口不要引用这些类型。在客户端初始化时，先检查 NeoForge 中是否加载了 `piq_replay_compat`，存在时才调用适配类；没有补丁时，不加载适配类。

下面是只建立回放状态门的最小结构。两个类均限客户端初始化；普通设备只调用 `OptionalReplay.playing()`，其余图像转接同样通过设备自己的、不含 API 类型的接口进入适配类。

```java
// OptionalReplay.java：正常设备代码可以引用本类。
import java.util.function.BooleanSupplier;
import net.neoforged.fml.ModList;

public final class OptionalReplay {
    private static volatile BooleanSupplier replayPlaying = () -> false;

    public static void clientSetup() {
        if (ModList.get().isLoaded("piq_replay_compat")) {
            ReplayScreenAdapter.initialize(); // 仅在 presence gate 后加载。
        }
    }

    static void installGuard(BooleanSupplier guard) {
        replayPlaying = guard;
    }

    public static boolean playing() {
        return replayPlaying.getAsBoolean();
    }
}
```

```java
// ReplayScreenAdapter.java：不得被无条件自动发现或初始化。
import cn.piq.replaycompat.api.ScreenReplay;

final class ReplayScreenAdapter {
    private static ScreenReplay.Publisher publisher;

    static void initialize() {
        if (publisher != null) return;
        publisher = ScreenReplay.register("example_device:world_screen", 1);
        OptionalReplay.installGuard(ScreenReplay::playing);
    }
}
```

把 `example_device:world_screen` 换成本附属拥有的稳定 ID，并只注册一次。设备不要调用 `ScreenReplay.install(Service)`；这是兼容补丁安装传输服务的入口。

`ScreenReplay` 类已经存在、但服务尚未安装时，状态查询返回 `false`，帧提交为空操作。这不等于“补丁未安装时可以直接引用 API 类”：缺少 JAR 时仍会发生类加载错误，必须保留外层可选存在检查与惰性加载。

## 提供者和屏幕身份

`ScreenReplay.register(providerId, version)` 返回该提供者专属的 `Publisher`。

| 字段 | 约束 |
| --- | --- |
| `version` | 必须等于 `1`；未知版本拒绝注册。 |
| `providerId` | 命名空间格式 `[a-z0-9_.-]+:[a-z0-9_/.-]+`，最长 96 个字符；例如 `example_device:world_screen`。 |
| 注册数量 | 同一个 ID 不能重复注册；总数最多 128 个。当前没有注销接口。 |
| `localId` | 提供者内部稳定且唯一，格式 `[A-Za-z0-9_.-]+`，最长 90 个字符；不允许 `/`、冒号或空格。 |
| 实际来源 | 由 `providerId + "/" + localId` 生成，设备不自行拼接其他提供者的来源。 |

`localId` 应代表一个物理显示面，而不是本次分配的纹理名称。不同维度、不同屏幕和同一设备上的多个显示面应使用不同 ID，例如 `overworld-tv-12-64-minus8-front`。同一个屏幕移动时继续使用同一个 ID，但提交更新后的世界坐标。不要把用户文字或外部路径直接当作来源 ID。

## 提交世界屏幕快照

在原显示面的客户端渲染拥有者处检查 `publisher.requested()`，只有返回 `true` 才准备录制所需的数据。它等价于正在录制且不在回放。传入的像素和顶点会被复制成不可变快照，调用结束后可以重用原始数组。

核心调用如下，应位于前述惰性适配类内部：

```java
if (publisher.requested()) {
    publisher.frame(localId, dimensionId, width, height, abgr, worldQuads);
}

// 原显示面消失、关机、卸载或切换身份时：
publisher.close(localId);
```

`frame` 自动使用当前录像时钟，不要使用 `System.currentTimeMillis()`、游戏 tick 或核心帧序号代替录像时间。不要在 Replay 回放中调用核心取图。只提交录制客户端实际应该看见的显示面，不把其他玩家的隐藏画面补入轨道。

即使像素没有变化，可见屏幕也必须在 **不超过 2 秒**的间隔内刷新；实际建议留余量，例如每秒一次。帧在时间达到最后提交时间加 2000 毫秒时过期，恰好压线可能产生空白。画面有变化时可更频繁提交，但应限速；test.4 自动捕获采用每个显示面 50 毫秒采样时钟，目标 20 帧/秒，卡顿不补录。公开 `Publisher` 不会替新附属自动限速，总轨道配额没有随帧率增加。

`close(localId)` 应立即关闭旧来源，不要仅停止发送并依赖超时。它只在录制中产生事件。切换维度或复用本地身份前先关闭旧显示面，再开始新显示面。

### 像素

- `width` 和 `height` 均为 `1..1024`，像素总数不超过 `1,048,576`。
- `abgr.length` 必须等于 `width * height`；按行排列，索引为 `y * width + x`。
- 每个像素是数值形式的 ABGR：`0xAABBGGRR`。例如不透明红色是 `0xFF0000FF`，不透明蓝色是 `0xFFFF0000`。
- 从 RGBA 字节转换时显式处理字节序，不要把 Java ARGB 图像数组未经转换直接传入。
- 提交已有 CPU 像素。不要为了录制加入全局 `glReadPixels`、同步磁盘压缩、核心状态快照或网络请求。

### 世界空间四边形

`worldQuads` 是 `List<ScreenVertex>`，每连续 4 个顶点组成一个四边形，顺序和 UV 应与原显示面一致。至少 4 个顶点，最多 4096 个，数量必须是 4 的倍数。

```java
new ScreenVertex(worldX, worldY, worldZ, finalU, finalV, argbTint)
```

- `x/y/z` 是最终世界坐标，不能是 GUI、相机相对坐标或尚未应用模型变换的局部坐标。设备旋转、移动和船体变换要先应用，再提交。
- `u/v` 是已经应用显示方向、裁剪或旋转后的最终纹理坐标；不要在回放中再次旋转。
- 顶点 `color` 使用 **ARGB**，与像素 ABGR 不同。不透明白色为 `0xFFFFFFFF`。
- 所有坐标和 UV 必须有限；`|x|`、`|z|` 不超过 `30,000,000`，`|y|` 不超过 `4096`，`|u|`、`|v|` 不超过 `16`。
- `dimensionId` 为命名空间格式，例如 `minecraft:overworld`，最长 128 个字符。回放只在匹配维度绘制。

提交完整世界四边形，因此不要求补丁知道设备方块类，也不要求按机型增加渲染白名单。但是第一人称或 GUI 屏幕不能通过传入局部坐标直接获得兼容；当前接口的播放路径是世界显示面。

## 回放保护必须由设备实现

`ScreenReplay.playing()` 表示当前处于 Replay 回放会话，不表示核心是否运行，也不表示屏幕轨是否有效。没有屏幕轨、损坏的轨道和暂停中的回放，同样不能启动真实设备补图。

设备的所有入口都要经过相同的保护，而不仅是在一个渲染方法里返回：

1. **启动和推进：** 回放时禁止启动核心、建立本地或远端游戏会话、推进模拟帧、运行旁观核心或启动设备后台任务。
2. **输入和网络：** 禁止设备按键采集、控制发送、连接与握手；消费录像中的设备载荷时也不得触发核心或会话副作用。不要拦截无关模组和 Minecraft 的正常载荷。
3. **保存：** 禁止自动保存、关机保存、状态导入导出及由设备回调引发的存档写入。不要让通用“关机并保存”方法成为进入回放的清理路径。
4. **进入回放：** 如果已有设备或后台会话尚未退出，先做一次只释放资源、无保存、无发包的清理，再阻止后续推进。单纯取消负责断线清理的 tick 会留下旧会话。
5. **退出回放：** 清除回放专用临时状态，允许玩家重新进入正常游戏；不要自动恢复旧核心或旧控制会话。正常退出和错误退出都要验证。

公共 API 的提供者注册不会自动扩展本补丁的旧设备网络命名空间列表，也不会扫描或阻止任意新附属。上述保护必须在新设备自己的核心、输入、网络和保存拥有者中完成，然后才可声称支持只读回放。

## 预算和错误处理

当前传输实现最多选择 16 个同时可见来源，整条轨道最多 1024 个来源、72000 帧、9000 个独立压缩块、2 GiB 压缩块数据，每块最多 8 帧。录制队列上限为 16 个普通待处理帧，并有约 48 MiB 的像素与几何预算；队列压力可能丢帧，来源关闭事件另有保留容量。

这些是安全上限，不是建议负载。设备应合理限制分辨率、发送频率和可见面数量。`frame` 不返回持久化成功凭据；提交成功也不保证该帧最终保存，不能用它驱动游戏逻辑。

非法 API 版本、重复提供者、非法 ID、像素长度错误、越界坐标或顶点数量错误会抛出异常。接入者应在开发时修正数据；若运行时可选录制支路失败，应隔离该支路、记录可定位的错误，保留原设备正常绘制，不把异常传播为普通游戏崩溃，也不要无限重试同一坏数据。

`ScreenFrame` 和 `ScreenVertex` 是用于边界校验的不可变数据类型。底层 `ScreenReplay.submit/close/at` 与 `Service` 供兼容传输实现使用；一般设备只使用 `Publisher` 和状态查询，不安装第二份服务、不自行写 `.mcpr`、不依赖内部轨道编码。API 版本与内部轨道版本分别管理，不从同为 `v1` 推断可自行修改归档。

## 新机型验收

音频接入规则见下节；音频与画面必须分别验证，某一路线录到了画面不代表录到了声音。

至少覆盖以下条件，再在该附属的交付说明中记录实际通过的版本和模式：

- 不装补丁时可正常加载和游玩，没有 API 类加载错误；专用服务器不会加载客户端适配类。
- 装补丁但不录制时，不复制像素、不创建录像任务，核心、输入、网络和保存行为不变。
- 录制单屏、多屏、静止图像、旋转或移动显示面，核对像素色序、UV、坐标、维度、稳定身份和关闭事件。
- 回放、暂停、前后跳转、旧录像和损坏轨道都不会启动核心、发送设备输入或写存档；超过 2 秒没有新帧时不残留旧画面。
- 正常退出与异常退出后再进入游戏，验证清理没有永久阻止设备运行。
- 剪切、分段和短片导出与原录像时间相符；不要只验证交互预览。

当前 API 的纯 Java 契约测试入口是 `cn.piq.replaycompat.ContractTests`，必须用 `java -ea` 执行。完整构建入口和本候选的目标版本见 [README.md](README.md)。纯 Java 测试不验证 Minecraft 渲染、Mixin 应用或设备核心副作用；这些仍需隔离客户端实测。

## 最终音频输出接口

`AudioReplay` 只接收设备已经准备实际播放的 **16 位有符号小端 PCM**，支持 8000～192000 Hz、单声道或双声道。按原设备最终音量和距离衰减之后提交；不能从核心生产端和最终消费端各录一次，也不能额外录系统混音或麦克风。

接入类须沿用前述可选版本检查和惰性加载。一个稳定的 `source` 对象表示一个最终输出端；不要每帧创建来源对象。归档使用内部顺序编号，不序列化对象名、本地路径或用户身份。一次最多 65536 字节且不超过 500 毫秒、必须完整采样帧对齐。接口会复制已接受片段；调用方随后可复用数组。

阻塞输出必须在调用之前取得代次令牌，返回后只提交实际接收的字节：

```java
Object token = AudioReplay.captureToken(outputIdentity);
int accepted = originalOutput.write(pcm, offset, length);
AudioReplay.capture(token, outputIdentity, pcm, offset, accepted, sampleRate, channels);
```

该代码放在最终输出拥有者，不新增模拟器采样、网络请求或同步归档操作。没有录制时令牌为空；录制结束、来源关闭或 reset 后，旧阻塞调用迟到的数据会被拒绝。可选采集失败不能改变原输出的返回值或异常。

原输出 flush/reset 时调用 `AudioReplay.reset(outputIdentity)`，关闭时调用 `AudioReplay.close(outputIdentity)`；关闭后用新对象标识新输出。非阻塞、立即提交的出口可用 `AudioReplay.capture(source, pcm, offset, length, rate, channels)`。设备不得调用 `AudioReplay.install(Service)`，也不自行写音轨。

内置旧机型通过各自 `SourceDataLine` 最终出口薄适配，不钩全局 Java 音频类；已经经公共机柜音频出口的附属不要再注册一份重复采集。新输出技术（例如非 JavaSound 后端）应显式调用同一 API，不依赖白名单自动兼容。

回放将记录混合为 48 kHz 双声道，以录制视点音量在正向 1× 下播放。暂停、跳转、变速、导出和退出会清除旧声音；不提供自由镜头空间重混音、变速音频或有声视频导出。test.4 区分可恢复的输出背压与坏轨/设备不可用，恢复仍从当前录像时刻开始，不重放已过时音频。验证录制/停止/重开、切服、短写和失败写、迟到输出、多来源、剪切分段、暂停拖动退出，以及未安装补丁时原声音正常。
