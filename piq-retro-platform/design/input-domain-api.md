# 内置输入领域 API（2026-09-10）

此目录是由 FC 主模组唯一承载的内部 Java21 源码库，不是需要额外安装的模组。这里不包含 GLFW、Minecraft、驱动、JNI、SDL 或本机设备枚举；实际设备采样与 UI 配置由主集成层负责。

## 本轮文件边界

仅新增 `src/main/java/cn/piq/retro/input/` 下的：

- `GamepadState.java`：含 `DeviceId`、`Control`。
- `RetroButtons.java`：含 `Button`。
- `InputProfile.java`。
- `StickDeadzone.java`：含 `Sample`、`Trigger`。
- `InputMappings.java`。
- `LocalInputSession.java`。

对应新增五个 JUnit 测试类：`GamepadStateTest`、`InputMappingsTest`、`InputProfileTest`、`StickDeadzoneTest`、`LocalInputSessionTest`。`InputOwnership` 属于独立的输入所有权组件。

## 坐标、设备与按键

`GamepadState(DeviceId, connected, buttons, leftX, leftY, rightX, rightY, leftTrigger, rightTrigger)`：

- 两根摇杆取值 `[-1, 1]`，X 正向是右，Y 正向是下。
- 扳机取值 `[0, 1]`，松开是 0。原始设备范围不同，需要采样层先换算；不能直接把 `[-1,1]` 的扳机传入。
- `buttons` 只包含 `Control.physicalButton()` 的位；模拟方向和扳机控制位由过滤层产生。
- 非有限值、越界值、未知位拒绝；`connected=false` 会归零所有按键和轴。
- `DeviceId(String id, long generation)` 由采样层提供。`id` 应能区分两个同型号设备；每次重连换一个 `generation`，旧代次样本会被忽略。不自动选择第一台设备或自动分配本地 P2。

默认映射使用物理位置，不匹配外壳上印刷的字母：

| 物理位置 | 常见外壳标识（示意） | SFC / logical12 |
| --- | --- | --- |
| SOUTH | Xbox A / PS 叉 / Nintendo B | B |
| EAST | Xbox B / PS 圆 / Nintendo A | A |
| WEST | Xbox X / PS 方 / Nintendo Y | Y |
| NORTH | Xbox Y / PS 三角 / Nintendo X | X |
| LEFT_BUMPER / RIGHT_BUMPER | L / R | L / R |

十字键和左摇杆方向同时映射方向键。右摇杆、扳机、摇杆按下和 GUIDE 默认不分配游戏功能，可通过 `InputProfile.with(Button, Set<Control>)` 重映射。空集合禁用，多个控制项以 OR 合并；配置深拷贝、不可变。

逻辑位顺序固定为 `B,Y,SELECT,START,UP,DOWN,LEFT,RIGHT,A,X,L,R`。`InputMappings.sfc12()` 和 `arcade12()` 保持该顺序，不改现有核协议；`nes8()` 将 A/B 转为 NES 顺序并忽略多余 SFC 按键。`arcade6Explicit()` 是单独、明确选择才使用的另一种表示，**不可用它替换现有街机十二位协议**。

## 集成调用

所有状态更新方法返回 `boolean`；非当前 owner 的请求返回 false、读取返回 0。owner 按 `==` 比较，不能用值相等的另一个对象操作原会话。

```java
var input = new LocalInputSession();
Object owner = new Object();
input.acquire(owner);
input.selectDevice(owner, new GamepadState.DeviceId("chosen-device-slot-and-guid", connectionGeneration));
input.profile(owner, InputProfile.defaults());
input.focus(owner, true);

// capture thread / client adapter, in actual capture order:
input.keyboard(owner, logicalKeyboardMask);
input.gamepad(owner, standardizedGamepadSnapshot);

// once per emulated input frame, not only once per 20-Hz Minecraft tick:
int canonical12 = input.poll(owner);
int nes = InputMappings.nes8(canonical12);
int sfcOrExistingArcade = InputMappings.sfc12(canonical12);

// lost window focus / opened a blocking gameplay menu:
input.focus(owner, false);
// regained actual gameplay focus; held input is not immediately accepted:
input.focus(owner, true);

// stopping the owned game:
input.clear(owner);
input.release(owner);
```

新 owner 起始 `unfocused`。获得焦点后，键盘必须先提供一次 0，选中手柄必须先提供一个全中立样本，之后才允许按下。中立包括未绑定按钮、两根摇杆和两个扳机。不要为了“马上启用”伪造中立样本，应等待真实设备读数。

`LocalInputSession` 的各方法同步；集成层仍必须按实际捕获先后投递事件，并在接收窗口失焦、菜单退出、连接关闭时及时调用清零，不依赖下一次设备采样。

## 安全清键与短按

- 键盘和手柄源各自存状态，最后 OR；一个源松开不会吞掉另一个仍按着的同一键。
- 上下同时按、左右同时按最后中和。原始源不被破坏，松开一边后另一边会恢复。
- `current(owner)` 返回即时最新态，适合显示；`poll(owner)` 每次消费一条已经捕获的合并边沿，队列空时保留持续按下状态。不要在同一模拟帧把整个队列排空，会抹去短按。
- 已捕获的按下、松开不会仅因 Minecraft tick 之间发生而丢失。但这个 API 无法复原两次设备采样之间完全未被捕获的点按；采样频率、键盘事件捕获与核心消费频率仍由集成层保证。
- 队列上限 256。溢出立即归零、丢弃积压并要求两源重新中立，`overflowCount()` 记录次数；不会无限缓存或粘键。
- 失焦、`clear()`、释放 owner 清两源及排队边沿。恢复后先释放/居中再启用。
- 断开选中手柄、切设备、改 profile 只清手柄源，保留真实持续按住的键盘。由于排队快照已经合并，安全切换会清除所有排队边沿（包括尚未消费的键盘短按），不能让旧手柄按下在切换后迟到重播。
- 同 owner 重复 acquire、同设备重复 select 是幂等的，不重置已经正常按下的按钮。
- 非选中设备、旧 generation、旧 owner 的输入不会控制当前会话。外层跨 FC/SFC/街机的所有权由主集成者维护；这个类只拥有一个本地输入通道，不替代联网租约授权。

## 死区

摇杆径向进入阈值 0.25、退出阈值 0.18，斜向依半径计算；激活后有角度迟滞（22.5° 进入、17.5° 退出）防八方向边界抖动。模拟输出半径重新缩放且不超过 1。扳机阈值独立，0.55 进入、0.35 退出。两个过滤器都提供自定义阈值构造器，`LocalInputSession` 首版使用默认值。

## 验证与未验证范围

本机 JDK `21.0.11` 的独立 `javac --release 21` 已通过；使用本地缓存 JUnit Jupiter `5.13.4` / Platform `1.13.4` 独立执行 **31 个测试，31 通过，0 跳过、0 失败**。未运行 Gradle、Minecraft 或模拟核心。

覆盖所有 4096 种十二位状态的 SFC/街机原位与 NES 转换、所有状态的方向中和、物理位置映射、配置不变性、径向/角度/扳机迟滞、设备代次、owner 隔离、失焦/断开清键、中立重启、双源合并、100 条连续短按边沿和 256 条队列溢出。

**Xbox、PS、Switch Pro 均未在此子任务进行实体设备测试。** 当前交付为平台无关输入框架与可验证映射，不声明系统驱动、无线连接、GLFW 识别或旧宿主完整接线已完成。
