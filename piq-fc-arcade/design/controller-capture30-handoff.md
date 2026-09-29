# 自动手持捕获与光枪菜单接缝（alpha30 开发交接）

本轮仅本地源码/测试。未安装、发布、启动真实 Minecraft、修改 ROM/存档、模型/核心或旧冻结包。根维护手册由 root 汇总。

## 接口与行为

- 共享 `KeyboardInput` 的配置/路由由 fix_sfc_av 制作；本实现消费七参 `attach(token,profile,keys,present,authorized,clear,edge)` 与 `registerPresenceRefresh`。六参旧机柜/Native 入口仍可用，无需改 Native 源码。自动 PARALLEL 的游戏功能键捕获及 Z 位置锁均由共享层决定。
- 新 `client.ControllerCapture` 注册 FC、支架借枪及 SFC provider，仅在已加载端点的同步 player/lease/port 与唯一真实手持物品吻合时建立 capture-only owner。候选绑定实际 connection、BE 对象及手编号；同物品别名不重复计数，背包/光标复制拒绝。关机捕获不会创建 core、取得 InputOwnership 或自行发输入包。
- HEAD/tick/poll 调用唯一聚合刷新，先重验运行 session 的七参 attach，再尝试关机 capture-only。真正运行 owner 的初次 attach/权限变化仍调用原 force-zero 安全回调，所以不能宣称整个聚合刷新绝无网络副作用；无授权候选自身两个回调严格 no-op。
- FC/SFC 原会话、连接、实际租约和 held 验证保留；SFC runtime 继续要求 started、6 格、旧 FC 排他和 InputOwnership。后台 Host 和旁观不被 idle 捕获变成控制者。active 手位/设备变化按稳定 `(lease,port,hand,gun)` 清键并中立重臂，ItemStack 同身份复制不反复重置。
- FC 有权光枪的八位键盘可借用空闲 P1 输入线，但不创建实体手柄或占 P1 socket。正式 P1 存在时只能使用其精确正式 lease；如是另一个玩家，枪不能写它。本人 P1 暂存而手持有效枪可按正式 P1 lease 操作，服务器仍核现有枪租约/物品/范围/AV/权限。
- `HomeRuntimeAuthority.buttonPort` 是纯路由；`recordButtons` 仅在真实 `LockstepState.acceptInput` 接受序列后记录 P1 输入来源。枪归还/失焦/收起/force-release 只清其最后注入来源，新 P1 接入会清 fallback；其他玩家 P1 队列不会被旧枪清掉。
- `HomeInputSequences` 保存最多两个设备的 next button sequence，解决 gun→P1→gun 仍是同枪 lease 时序号回到零的拒收；只有新 epoch/关闭才清。瞄准序号、锁步/快照/history 格式、服务端原时间线都不改。

## 生产范围

FC 新增 stems：`client/ControllerCapture`（Provider、Candidate、两个匿名内类）、`client/ControllerCapturePolicy`（Held）、`client/HomeInputSequences`。

FC 修改 stems：`client/ClientArcadeEvents`、`client/ClientArcadeSession`、`home/HomeRuntimeAuthority`、`home/HomeZapperService`、`server/ServerArcadeSessions`。没有生产资源或协议字段修改，没有旧 class 人为删除。最终内类精确条目由 root 冻结差分确认。

SFC 仅修改 `client/SfcHomeClient` 及原 Setup/新 Setup 匿名 provider 内类；不改 server/net/Playback/core/模型。SFC 第三人称姿势属于 root 的独立范围。Native 无生产改动。

## 验证与边界

- 新增 FC JUnit：ControllerCapturePolicyTest 11、HomeInputSequencesTest 4、HomeGunKeyboardFallbackTest 14、ControllerCaptureSourceTest 3。
- 原 HomeGunController29Test 14 保留执行。SFC 三份旧合同按七参/拆分谓词和自动 PARALLEL 更新，保留原连接/排他/6 格/精确租约语义，并在 SfcUnifiedKeyboardTest 新增2项 provider 与换手 source 合同。
- `python tools/check_controller_capture30.py --source --report design/controller-capture30-source-v1.json`：43 个实际纯生产/JUnit 测试通过（不包含3个独立 source 合同）。报告明确 production-source，不作为最终 JAR 证据。
- 最后补强普通非枪模式不接受 gun 来源标记后，`design/controller-capture30-source-v2.json` 同43测试全部通过；root 统一 `compileJava` 已过，未发现新MC API错误。生产已提交冻结，剩余统一全check/最终候选由root处理。
- 最终用法：`python tools/check_controller_capture30.py --fc <冻结FC.jar> --report <新的JSON路径>`。只编 tests/probe，5 个纯生产类 CodeSource 必须是原 SHA 的输入 JAR，输入 path/SHA 写入报告。不能覆盖旧报告。
- 真实 Minecraft 输入入口、服务端世界/保护插件、实际物理手柄、枪同时控制实玩仍未验收。pure receipt/租约/FIFO测试不是完整多人服务器测试。统一 Gradle 与最终 JAR 证据由 root 发起后补充。
