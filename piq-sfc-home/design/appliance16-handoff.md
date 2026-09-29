# SFC16：电源、后台运行与手柄租约分离

负责人：`/root/sfc_cabinet_provider`；日期：2026-09-11。仅本地源码与离线验证，不安装、不运行 Minecraft、不修改用户 ROM/存档。

## 运行契约

- `HomeSystems.ServerHooks` 电源开机创建独立 `Host`（玩家 UUID、实际 Connection、随机 runtime token），`ports` 初始两项均为空。插卡、点击电视机身、点击主机非按钮不自动开机。
- 开机者不自动得到手柄、不取得键盘或实体手柄所有权。Host 的实际核心在收到服务器逐帧输入后运行；空口输入为零，Host 仍产出画面、声音及旁观媒体。
- 点主机上的 P1/P2 手柄只领取指定口。开机者可自领，其他玩家须经 Host 审批，通过现有初始状态核对、固定帧快照、分片、导入摘要与提交后加入；P1 与 P2 均可作为远端口。
- 归还任一手柄只删除该端口 FIFO/Ready/租约，不重置 `frame`，不关闭 Host，不改变另一手柄口。非 Host 控制玩家关闭自己核心后可重新成为被动旁观者。
- 主机机身可归还，电视非按钮只提示；物理电源、重置、音量优先于柄归还。卡带和 AV/街机通讯线/光枪支架线/光枪保持其独立物品事务，不因瞄到按钮误开机。
- 重置明确关闭旧 epoch 的各运行端，创建新 Host token 和新 epoch，保留现有控制器租约；全部输入队列、时钟、Ready、加入事务重新建立。旧帧/旧 Host 回调拒绝。重置是冷重置，不假称已恢复服务器存档。
- Host 在线、同一维度、实际连接与玩家对象不变、AV/主机/ROM有效且区块加载时可继续后台运行，不要求站在8格内；每个控制租约仍按有效主机/已连接电视最近8格及完整权限事件复验。Host 退出、换维度、核心失败或硬件失效安全停机，首版无 Host 迁移。
- 每人最多一个 SFC 后台 Host；经 root 明确确认，与 FC 可各一台后台，但玩家输入所有权仍互斥。

## 协议/API

- 两个 SFC registrar 一并从4升5，需客户端、服务端同版。
- `SfcHomeNetwork.Session` 新增末尾 `boolean executionHost`。`controllerLease` 保留旧名称，但现在表示运行能力：Host 为独立 runtime token，远端为对应控制口 token。真实 Host 的 `port=-1`；远端控制口为0或1且 `executionHost=false`。原12参构造保留供历史测试工具。
- 新 S2C `Control(sessionId, epoch, lease, port, active)` 只对已有 Host runtime授予/撤销物理控制。`SfcControlGrantGate` 按实际连接、session、epoch 保存最多256项退役token；迟到 grant 不得重新取得输入所有权。
- Ready/Leave 用既有带 UUID 的 `ControllerReady`/`ControllerLeave` 外壳；服务端区分 Host runtime能力与控制租约。所有实际P1/P2输入都用既有 `ControllerInput` 外壳；原无租约 Input handler 空操作，不存在 Host token 输入旁路。
- Host 旁观媒体受 exact runtime/connection/token/descriptor 与服务器需求租约绑定，不要求主机仍在 Host 客户端可见区块内；接收旁观者仍验证本地实际电视结构。
- 本机及旁观声音均乘 `HomeApplianceService.audioGain`；停机调用 `refresh` 更新电视信号。

## 生产文件白名单（本代理）

所有路径以 `src/main/java/cn/piq/sfchome/` 为根，class stem包括相应内部类：

- `server/SfcHomeServer`（新增内部 Host，调整 Session/Lease/Joining/hooks）
- `net/SfcHomeNetwork`（新增 Control，扩 Session/ClientHandler/注册）
- `net/SfcJoinNetwork`（registrar5，无修改各原记录的格式）
- `client/SfcHomeClient`
- `client/SfcPlayback`（仅角色判别取代P1口判别，不改核心实现）
- `client/SfcJoinClient`
- `client/SfcWatchClient`
- `client/SfcWatchPublisher`
- `client/SfcStartupProgress`（等待硬件文案不再声称须等待手柄）
- 新 `client/SfcControlGrantGate`
- `world/SfcHomeConsoleBlock`（仅 useItemOn 和工具优先判断；shape不变）

Root 另拥有 `SfcHomeMod`、新 `layout/SfcApplianceControls`、版本/依赖/元数据。所有模型/mesh/UV/PNG/核心6/写卡UI/存档格式不在本代理改动范围。未删除任何旧生产class文件。

## 验证入口

- SFC项目 `gradlew.bat check jar --no-daemon --console=plain`：完整JUnit及源码合同（最终数量待本轮冻结后附录）。
- 新 `SfcControlGrantGateTest`：连接/epoch/旧租约重放/256上限。
- 新 `SfcApplianceSeparationSourceTest`：Host权限不可当控制口输入、实际连接/玩家/维度、两口独立回收、空口无心跳要求、Host-only审批/发布、音量、工具优先。
- 更新旧 startup/join/watch/keyboard source合同保留安全语义，移除“P1必是Host、归还P1必停机”的旧前提。
- `tools/run_sfc_playback_multiplayer_probe.py --fc <最终FC28> --sfc <最终mergedSFC16> --appliance --report <新路径>`：只编译测试代码，生产来源严格final JAR；两个真实 `SfcPlayback`/WASM/JVM（Host port=-1、远端P1=0），实际视频/PCM逐帧一致、同tick快按、延迟/jitter、快照加入、远端退出再加入、取消、核心错误隔离、明确新epoch冷重置、Host-only媒体tap。附带 `SfcApplianceProtocolProbe` 实际新Session/Control和NeoForge外层codec、授权退役/端口过期机制。
- `tools/check_sfc_watch_playback.py --fc ... --sfc ... --report <新路径>`：既有真实工作线程媒体异常隔离/序号验证。

## 冻结构建

2026-09-11 本轮 `check jar` 已退出0：292 tests，0 failures，0 errors，0 skipped。thin JAR `build/libs/piq_sfc_home-0.1.0-alpha.16.jar` SHA-256 `83A4EA365ECEA8D71F6689D386862AF89543389D1E26A4820D52CF0AFCF8B2F3`。这不是发布用合并包；最终 JAR-only 核心探针须对 root 合并候选另出新报告，不用本次编译结果冒充真人联机测试。

预检合并包 `piq-fc-arcade/build/review-appliance28-preflight-v1` 的 SFC SHA为 `5F3AD5427EF780D9A35DD00E8EF26577B6D091BBDEFB4DAE45EFEDED8A35E872`。`checks/sfc-runtime-preflight.json` 已通过790协调断言、823实际worker断言、320次真实record codec roundtrip和3661实际生产注册/外层codec/权限gate断言；184帧RGBA及PCM逐帧相同，24双口按键，Host无控制口持续到248帧，远端P1退后重新同步，另新epoch2重置8帧通过。报告SHA `EE45AF3815928FE89A1F6146E13371783E0AEA9BF5FB29108479609095AD15EA`。旁观实际worker38断言通过，报告 `checks/sfc-watch-preflight.json` SHA `4B9FFDB30BED755D48478E530B7BDF000B5C8B4A17DCE20E70B6625CE9D2BAE3`。预检首次工具缺Minecraft静态注册表bootstrap，已仅修工具并调用生产网络注册后通过；没有因此修改生产代码。最终FC包仍会变化，需对正式候选复跑，预检不是最终交付哈希证据。

## 测试边界

双JVM夹具的协调者替代服务器与真实网络；它不声称已执行 `ServerPlayer`、保护插件回调、真实世界点击或完整SFC服务器状态转换。Host平台回调/扬声器/备份为测试sink。仅原创65816诊断ROM，不读取商业游戏。实际玩家联机、物理手柄、音量听感、游戏内准星按钮点击仍待人工实机验收。核心冷启动成本仍存在。

Root明确保留的失败关闭边界：重置后已经占口的远端重新Ready若出现ROM/core/FPS/初始状态不一致，整台安全停机，尚未实现隔离该坏端后继续重置。正常远端归还/离线/核心失败Leave仅退出本人；新加入候选不一致也只取消候选，不停Host。
