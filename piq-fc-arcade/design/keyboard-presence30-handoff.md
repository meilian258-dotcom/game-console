# alpha30 共享键盘输入交接

修改者：`fix_sfc_av`；2026-09-11。基线为冻结 alpha29；仅本地源码/QA，不安装、不发布、不启动游戏、不改用户配置或 ROM。

## 已实现

- 新 `KeyboardInput.attach(token, profile, keys, physicalPresent, runtimeAuthorized, forceRelease, edge)` 分离持物捕获与输入授权；旧六参 API 仍可调用，虚拟街机以原授权作为 presence。
- 新 `registerPresenceRefresh(Runnable)` 为唯一持物聚合器提供 HEAD/tick/poll 刷新点；有重入门禁。captured-only 不取得 `InputOwnership`，也不具有模拟器授权。运行 owner 可以取代 idle capture，反向不能抢占；旧释放回调不得清掉已变更的新 owner。
- 所有预设开始为 `PARALLEL`，手持即接管功能键；`Z`/配置切换键只在 `PARALLEL` 和 `LOCKED` 之间切换移动锁。旧 `FREE` enum 保留兼容，但不再由配置/切换产生。
- native NES/SFC/ARCADE 的方向都为 bits 4..7。未锁时方向与实际 Minecraft 移动绑定重合就仅留给世界；不重合的方向仍给模拟器。游戏功能别名优先，始终捕获。锁时额外阻止运动/跳跃/蹲下/疾跑，不屏蔽无关 L/E/T/Q/热栏/鼠标。
- 菜单、失焦、物理失效、授权变化、配置变化与 owner 交接都清零并重臂。未锁时正用于世界行走的方向键不阻止其它游戏功能重新就绪；锁定后这些键也必须先松开。
- `KeyboardMappingState.clearCaptured` 仅清选中的 KeyMapping transient `isDown/clickCount`；包括 ToggleKeyMapping。原绑定和 `options.txt` 不改。
- runtime/display/草稿共用 root 的 `effectiveLegacyKeys/effectiveLegacyExtras`。`displayLegacyKeys(Profile, KeyboardConfig)` 与 `displayLegacyExtraKeys(Profile[, KeyboardConfig])` 提供实际主键/额外键；旧鼠标主绑定保留负码展示。冲突 alias 本地替换提示不会误阻止保存有效设置。

## 精确生产范围

修改：`cn/piq/retro/client/KeyboardInput`、`KeyboardControlState`、`KeyboardRouting`、`KeyboardMappingState`。

`KeyboardHandlerMixin` 和 `KeyMappingStateAccess` 源码未改；本轮仍用真实 Sponge 对它们及缓存的 Minecraft 1.21.1 / NeoForge 21.1.236 类进行变换验证。Config/Store/GUI 由 root 负责，FC/SFC/Native 桥由 `sfc_cabinet_provider` 负责。没有新增网络或模拟器核心代码。

## 验证

`tools/check_keyboard_presence30.py --report design/keyboard-presence30-source-v2.json` 已 exit 0：67 项纯行为测试，95 项实际变换后的 KeyMapping/ToggleKeyMapping 断言，以及真实 KeyboardHandler HEAD 顺序/取消/原方法 suffix 字节码检查。

覆盖：自动功能键、原始 native 位序、快按/重复/别名/鼠标、中立门槛、WASD 与用户重绑方向、capture-only 0 mask、授权丢失/恢复、owner 优先级、映射 L 队列清除、未映射 E 与鼠标队列保留、锁定后的运动 SCANCODE 与 Toggle-sneak 清除、无绑定改写。

新工具可用 `--fc <最终JAR> --report <新路径>` 执行 final-jar-only：只编测试/probes，不编生产；实际 helper SHA 与输入包成员绑定。历史 alpha26 工具/交付/报告未修改。

## 边界

- 新桥必须对同一 runtime token 内的实际 receipt/手序/枪柄切换调用 pause 和原 gamepad pause，不能只靠 presence 布尔。已单独告知桥代理，idle provider 已带精确 hand 身份。
- 这次 QA 不启动 Minecraft 单例/窗口/世界/网络 socket，不冒充真实按键或实体手柄验收。
- HEAD 可拦标准 Minecraft/NeoForge KeyMapping 路径；不能阻止其它模组独立 GLFW 轮询或比本 Mixin 更早的自定义低层观察。位置锁不冻结服务器坐标、不免疫惯性/击退/重力。
