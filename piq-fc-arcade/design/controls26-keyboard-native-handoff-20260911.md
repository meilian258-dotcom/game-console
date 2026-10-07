# Alpha26 键盘冲突与 Native 按钮编号交接

未安装、未改用户配置、未运行 Minecraft。

## 确认的原因

- 用户实例实际运行 FC25 / SFC14 / Native9，三个 SHA 与 controls25 冻结包一致，不是旧包未更新。最后保存的三个 profile 都是 NUMPAD；L 是原版进度键。日志不记录按键/操作锁，不能断言用户当时的模式。
- 旧 LOCKED 只拦截移动键和当前游戏映射键；未映射的 L/E/T/Q/热栏仍可进入 Minecraft。WASD 初始 FREE 也按设计不拦截，必须明确提示 F8。
- 实际 ToggleKeyMapping 在 toggle 模式下 setDown(false) 不清 isDown。仅调用 setter 和 consumeClick 的旧清键方法会留下切换式潜行/冲刺。
- Native helper 直接把 host mask 当作 libretro ID，UI 的按钮 2/3 语义与固定 MAME 默认 B,A,Y 顺序不符。不是 SFC B/Y/A 位序错误。

## 键盘变更

- LOCKED：取得有效当前控制权且游戏窗口活跃时，所有普通键盘事件禁止触发 Minecraft 动作。Esc、有效设置/模式热键、鼠标保留。PARALLEL 仍只接管映射键；FREE 不接管世界键并暂停本人键盘/手柄输入。
- 新 client-only KeyMappingStateAccess 只写 isDown/clickCount；KeyboardMappingState 不改 key/defaultKey/options，整批清理跳过鼠标绑定。菜单、失焦、切换与退出的现有 owner flush/FIFO 路径保留。
- CLASSIC 初始 FREE；状态文字直接说明当前键盘/手柄是否工作及 F8 的用途。
- displayLegacyKeys 单独提供真实 LEGACY 主绑定，包括鼠标编码。之前 displayKeys 本就读取 legacy(p)，不存在“读取当前其它 preset”的缺陷；新接口只解决鼠标被显示为未绑定的问题。

精确生产范围：KeyboardInput、KeyboardControlState、KeyboardRouting；新增 KeyboardMappingState、mixin/KeyMappingStateAccess；mixins JSON 增加一个 client accessor。没有修改会话授权、网络、Gamepad、GUI 或用户 options。

## Native 变更及依据

[固定 MAME input_retro.cpp](https://github.com/libretro/mame/blob/4fc9a9312baaf34963847f884961ad9793fbbc1d/src/osd/modules/input/input_retro.cpp) 的默认按钮顺序是 B,A,Y,X,L,R，游戏专用布局仅在 buttons_profiles 开启时应用。

重要纠正：[固定 core options](https://github.com/libretro/mame/blob/4fc9a9312baaf34963847f884961ad9793fbbc1d/src/osd/libretro/libretro-internal/libretro_core_options.h) 的 mame_buttons_profiles 默认已经 disabled；不能说用户当前被自动 KOF/CPS 布局重排。现显式锁 disabled 以保持固定语义，再在 helper 唯一边界交换 bits 1/8，把宿主按钮 1..6 转成 MAME 默认顺序。四个端口、键盘、实体手柄共用此边界；投币、开始、方向和其它位不动。

精确 Native 主 JAR 范围：BridgeProtocol、NativeProcessSession；新增 NativeArcadeButtons。私有 pipe VERSION=3，布局/网络消息数据格式不改。新 helper 固定 SHA 校验，旧 helper 启动前明确拒绝。helper Engine 只改输入 callback 与固定选项。旧 DLL/JNA/FIFO/清端口算法不改。

新 helper：`piq-native-arcade/build/review-numbered-buttons26-v1/piq-native-helper.jar`

SHA256 `20F6F3028D76DAEB01212D1808BE90E35BFB5429D1E06153B7D8B32DD73E943C`，18571 字节；旧 helper 与旧交付目录原样保留。

## 验证和边界

- keyboard-conflicts26-source-v1.json：57 真实纯行为测试；实际 Sponge HEAD 转换 40 断言，原 382 条指令不变；实际 KeyMapping accessor 转换/实例/队列/Toggle 77 断言，包含真实 L 的 consumeClick→进度界面门禁、鼠标队列保留、绑定对象不变。
- native-buttons26-source-v2.json：14 真实纯测试（全部 65536 个 mask 无损排列）；4185 真实 helper 四口/parser/callback 断言；251 真实固定 MAME 初始化/GET 选项/120 原创帧和 PCM；15 实际 NativeProcessSession 多口/快按/退出；5 实际旧 helper 启动前拒绝断言。
- 未测试商业 ROM 的六按钮游戏动作、真实联机或用户键盘/手柄；原创 invaders 诊断只能覆盖该硬件有效输入。不能把这些报告称为 KOF97 实机游玩验收。
- Mixin 测试使用缓存 NeoForge 21.1.236 / Minecraft 1.21.1 类；没有完成用户实例 NeoForge 21.1.250 与其它 mixin 的实机组合验收。

## 最终包复验命令

`tools/check_controls26_final.py --fc <FC26> --sfc <SFC15> --native <Native10> --report <新JSON>`：只编测试/probe，CodeSource 来自最终 JAR，包含真实 Mixin/KeyMapping 队列验证与 FML/外层 codec。

`piq-native-arcade/tools/check_native_buttons26.py --jar <Native10> --helper <新helper> --report <新JSON>`：只编测试/probe，使用最终 helper、固定未改 DLL 和原创诊断，再验四口与旧 helper 拒绝。
