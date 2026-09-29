# 实体手柄客户端输入（2026-09-10）

本轮子任务：`sfc_cabinet_provider`。此功能随 FC 主模组唯一内置，附属复用同一 API；不新增独立 platform mod、驱动、SDL、JNI 或网络协议。

## 宿主接口

```java
import cn.piq.retro.client.GamepadInput;

int result = GamepadInput.mix(sessionIdentity,
        GamepadInput.ProfileKind.SFC, nativeKeyboardMask, playingAndFocused);
GamepadInput.pause(sessionIdentity);   // 暂停/失焦/菜单：清实体源，不转交 owner
GamepadInput.release(sessionIdentity); // 会话结束：释放实体源
minecraft.setScreen(GamepadInput.settings(parentScreen));
```

`ProfileKind` 只有 `NES`、`SFC`、`ARCADE`。传入与返回位序相同：NES8 为 `A,B,Select,Start,Up,Down,Left,Right`；SFC/现有街机为 libretro12 的 `B,Y,Select,Start,Up,Down,Left,Right,A,X,L,R`。现有网络发送、每帧模拟、服务器租约和 P2 端口分配均由宿主原流程继续处理。

宿主沿用现有 tick、render 与键盘边沿捕获时机调用 `mix`。调用一次即同步轮询标准 GLFW gamepad；不添加另一个模拟线程、网络发送器或 Minecraft 键盘回调。Keyboard mask **不经过** 新手柄的中立门槛，没有额外一帧等待。

`active=false`、未启用、设备缺失或仍等中立时返回原键盘 mask；无真实实体按键参与时不改写原键盘的相反方向语义。实体源参与时与键盘 OR 后中和相反方向；任一源松开不会吞掉另一源仍按住的同一键。

## 所有权、失焦和异常

- GamepadMixer 按对象身份拥有一个实体源。不得把两个本地玩家自动映射为 P1/P2。
- 另一 owner 无法抢走实体源，但原键盘返回不受影响。pause 保留 owner；只有宿主 release 才交还。配置变化保留 owner，重建过滤器并要求中立。
- 不再次 acquire `InputOwnership`，以免与宿主的 `CabinetClientOwner` 已持有的其他 identity 冲突。宿主仍须执行自己现有的会话互斥。
- `mix` 只允许 Minecraft 主线程、窗口有焦点、没有 Screen、没有暂停且宿主允许 active 时采样；异常捕获后清实体源并返回原键盘，不抛给核心/网络。
- 日志警告最多一分钟一次。设置中读取失败也不会启动或终止游戏。
- 选设备、断开、恢复焦点、修改映射和死区，都需要真实松键/居中样本后再接收实体按下。不会伪造中立样本来绕过该屏障。
- 每次枚举只看 GLFW 的 16 个有限 joystick slot。观察到断开或身份变化会更新连接代次，不接收旧状态。没有替换全局 GLFW joystick callback，以免破坏别的 mod。

## 标准映射与边界

实现按 [GLFW 官方 Gamepad input / Gamepad mappings 文档](https://www.glfw.org/docs/latest/input_guide.html#gamepad) 使用标准 15 按钮、6 轴数据。仅 `glfwJoystickIsGamepad` 为 true 的设备可选择。原始 joystick 的按钮数量或顺序不会被猜测成标准手柄。

默认物理位置：面键下→SFC B，右→A，左→Y，上→X。即 Xbox A / PS 叉位于下方，对应 SFC B；不按不同厂商印刷字母硬匹配。十字方向注意 GLFW 的顺时针顺序 `Up,Right,Down,Left`，已由独立转换测试验证。轴 X 正为右、Y 正为下；GLFW 扳机 `[-1,1]` 转为领域层 `[0,1]`。

Xbox、PS4/PS5 走 GLFW 的标准设备映射；Switch Pro 只有系统/GLFW 能识别为标准 gamepad 才能使用。未引入陀螺仪、震动、合并 Joy-Con 或自定义驱动。GUID 与 slot 是 GLFW 提供的连接标识，不是厂商唯一序列号；同型号设备的编号变化可能需要用户重新选择。两个采样之间完全发生且结束的按下，或同 GUID/slot 在采样间瞬时重连，单纯轮询无法还原；没有宣称解决所有系统驱动或硬件事件丢失。

**本轮没有 Xbox / PS / Switch Pro 真机测试，也没有启动 Minecraft 做窗口实际绘制、多人游戏或实体连接验收。** 交付是正式客户端路径与纯逻辑验证，不把模拟测试写成真机通过。

## 设置与持久化

- 默认实体输入关闭，设备未选择；用户选设备并保存才启用。
- 紧凑原版 Button 样式；在 320×240 及以上分栏分页，支持 GUI 缩放后保留草稿/页码。
- 点击游戏按钮后，先松开/居中，再按实体键完成重绑；右键禁用该绑定。NES / SFC / ARCADE 各自独立。单个系统可恢复默认。
- 死区可点击以 5% 步长调整，Shift 点击反向；进入值 5%—75%，退出值为进入值减 7 个百分点、最小 0。按键捕获中立检查也遵守所选死区。
- Esc 捕获时仅取消本次捕获；否则关闭并丢弃草稿。取消、刷新、切页、缩放、切系统、选择设备不会写文件。
- **唯一写入**是用户点击保存，目标为实例 `config/piq-gamepad.properties`。只存本地设备/启用/死区/映射，不上传服务器。
- 配置读取限 64 KiB，逐父路径拒绝链接/特殊节点，不因打开设置自动建目录。保存使用同目录临时文件与原子替换；不支持原子替换则明确失败，保留原配置。
- 打开时保存修订 SHA，写入前再次核对；若其他程序修改了配置则拒绝覆盖并要求重新打开设置。损坏配置临时禁用，不自动修复原文件。

## 精确生产边界

主工程新增 `src/main/java/cn/piq/retro/client/`：

1. `GamepadInput`，内部 `ProfileKind`、`Device`。
2. `GamepadSettingsScreen`，内部 `BindingRow`；枚举 switch 可能由 javac 生成 `$1`。
3. `GamepadConfig`。
4. `GamepadConfigStore`，内部 `Loaded`。
5. `GamepadMixer`。
6. `GamepadSettingsLayout`。
7. `StandardGamepadState`。

内部库已有 `LocalInputSession` 仅新增 `(float deadzoneEnter, float deadzoneExit)` 构造器；原无参构造保留默认 .25/.18 行为。不改旧宿主、核心、网络、模型、资源或实例文件。

新增测试 `GamepadMixerTest`、`GamepadConfigStoreTest`、`StandardGamepadStateTest`、`GamepadSettingsLayoutTest`、`GamepadClientSourceContractTest`。本机 Java21 独立编译通过；连同原输入领域测试使用本地 JUnit5.13.4 执行 53 项：52 通过，1 项因 Windows 不允许创建符号链接而中止，0 失败。覆盖所有 4096 键盘状态无手柄时原样保留、每系统映射、模拟的断开/失焦/代次、所有 GLFW 标准按钮、500 次快速边沿、原子配置与并发修改保护、320×240 起的布局边界，以及客户端采样/保存/渲染边界源码契约。Minecraft 类的实际 compileJava / 成品审计由 root 统一完成。
