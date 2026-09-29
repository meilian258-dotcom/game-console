# SFC 家用接入统一键盘与操作模式

修改者：Codex / sfc_cabinet_provider；2026-09-11。仅本地源码与离线测试，根维护记录由 root 合并。

## 生产范围

- `client/SfcHomeClient.java`：共享 `KeyboardInput` 注册、采样、暂停/释放，以及无 GUI 条件的实时授权回调。
- `client/SfcHomeKeys.java`：保留方案 1 的原可配置 12 位映射；去掉 `SAVED` 临时解绑及 journal 写入。旧版本崩溃留下的恢复 journal 仍只在首次启动检查时恢复尚未绑定的键位，成功保存后删除 journal；常规游玩不保存/改写 Minecraft 键位。
- 无新增 SFC 生产类。`SfcHomeClient.Setup` 的键位注册增加共享注册；其他内部类不改变行为。没有修改服务端、租约、网络包、Playback、核心、自动旁观、模型、资源、版本或游戏实例。

## 输入合同

- `INPUT_OWNER` 是现有会话的 `InputOwnership` 身份；共享键盘使用同一 owner，不新增第二份会话权限。实体手柄仍以原 `playback` 对象管理。
- `ownsController()` 保留精确 playback/connection、started、存活/非旁观、维度、双手原柄和共享所有权门禁，但不把 GUI/焦点纳入会话寿命。`acceptsInput()` 再加 GUI、窗口焦点和暂停条件。
- 初始化注册原 SFC `KeyMapping[]`，使未进入会话的设置页也能显示方案 1 的实际绑定。四方案的定义、持久化和切换键只由 FC 主包共享服务负责，SFC 不复制一套配置。
- 原 tick、键边沿、鼠标边沿、render 都经 `sendInput()`。先取共享键盘 Sample；仅 `enabled && armed` 才混合实体手柄。自由模式、GUI、失焦、收起手柄和重新进入的中立等待都发送原 forceRelease 零输入并暂停实体手柄。
- 清零沿用 `SfcHomeNetwork.Input`；P2 仍走含本人 lease 的 `ControllerInput` 包。序号与只由 tick 推进的心跳不变，不改服务端 FIFO，不退出会话或停止其他玩家/旁观。
- 模式变动直接回调原 `sendInput(true)`；共享服务负责同步回调的重入保护。会话清理先释放共享键盘，再释放现有 owner；若连接已失效，回调不会发包。
- SFC 不再取消鼠标/攻击事件。鼠标视角、攻击、普通右键以及绑定主机/电视归还手柄继续由原版/现有物品流程处理。

## 验证

- 新增 `SfcUnifiedKeyboardTest` 9 项；联合旧焦点、发送策略、FC 交互源合同共 25 项通过。
- 原有输入队列/心跳/邀请状态/发送合同另跑 41 项通过，报告 `design/unified-controls-sfc-network-v1.json`；与上述 25 项有发送策略测试重叠，不把两者直接当成独立新增数量。
- 可复现：`tools/check_sfc_unified_input.py --report <新的报告路径>`；首轮报告为 `design/unified-controls-sfc-v1.json`，包含生产/测试源码 SHA。
- 行为测试执行实际共享纯 Java 键盘状态和 SFC 焦点/发送策略；宿主生命周期部分是源合同检查。不是 GLFW 实体按键注入、Minecraft 客户端或真人联机实测。
- 完整 SFC check 等待包含共享 API 的新 FC 依赖，由 root 协调；本记录不提前宣称完整构建成功。
