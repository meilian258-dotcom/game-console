# SFC 家用双人申请与统一写卡界面技术记录

日期：2026-09-10。本记录描述 SFC 双人申请与统一写卡界面；完整构建及最终包审计须另行完成。

## FC 原有体验核对

实际 FC 不是 P1 点名发邀请。`ServerArcadeSessions` 创建 P1 会话后发送 `ArcadeMultiplayerOfferPayload`；P1 确认“允许第二位玩家向你申请加入吗”；P2 点击同台机器发申请；P1 收到 `ArcadeJoinApprovalPayload`，同意/拒绝。服务端再验证 P1 身份、申请存在、人数、距离、位置保留及 `HomeControllerService.canJoin/grant`。只有批准后才发 P2 手柄，已有锁步局通过当前快照同步。未修改这套 FC 内部实现。

## 本轮 SFC 行为

- P1 空手领取即启动，双人卡不会硬性等 P2；人数为 2 的卡会询问是否允许申请。选择私有不会向陌生人开放。
- P2 空手右键同台主机/连接电视会发送申请；不直接领取，不抢端口。每台仅一份申请、每个申请者同时仅一份，P1 批准/拒绝。对话框使用共享 DeviceUi，Esc/被其他界面替换按拒绝处理。
- 批准后才发候选 P2 手柄，并加载同 ROM。候选保存在 `Joining.second`，不占 `Session.ports[1]` 的已提交输入通道；P1 继续运行。
- P2 上报真实初始 Ready 后，检查 ROM、核心标识、初始状态 hash、帧率一致；全服一次只搬运一份快照。服务器暂不发新帧，将 `s.frame` 固定为下一帧边界。P1 原有 worker 处理完已发队列，在这个精确边界 saveState。
- 分片经 P1 → 服务器 → P2：SHA-256、会话/epoch、随机交易 UUID、P1 原租约、主机/AV/卡带快照、当事玩家和权限必须保持有效。状态上限 16 MiB，分片 30 KiB，双向每 tick 最多 2 片；申请/加载最多 120 秒，真正停表最多 30 秒。
- P2 原有专属 worker loadState 后再次 saveState/hash；只有完全相同才原子提交 P2 端口，并从原帧号继续。P1 从不 loadState/reset，不丢当前进度。
- 同步失败/超时/取消/乱序仅撤销候选 P2、归还手柄、清空传输预算与暂停输入、恢复 P1 原帧。P1 身份/权限或机器失效则按原安全规则结束，不能继续越权游戏。
- P2 运行中退出只退出自己；可再次申请，需要重新批准和同步。P2 新租约必须使用新增 `ControllerInput(leaseUUID, 原 Input)`；旧无租约 Input 仅接收 P1。重新提交 P2 时重建 P2 输入序列，隔离旧 P2 消息，不重置 P1 序列。
- 所有提示仍为物品栏上方 actionbar，不写聊天。

## UI

`SfcCardEditorScreen` 直接使用 `DeviceLayout.browser(width,height,2)` / `DeviceUi`：名称与人数在上方、ROM/封面切换和搜索/文件夹/刷新同一工具条，列表只选中、右侧详情（窄屏下置）、独立主要写入按钮、底部次要翻页/清空/恢复/关闭和状态栏。保持原来的本地文件目录、后台扫描、exact-token/same-connection/snapshot 校验、上传限额、写入/改名/封面服务器流程，未改 `SfcCartridgeEditorService`、Data、CoverService 或文件读写安全。

## 网络兼容

SFC 主网络 registrar 从 2 升为 3；新增 `SfcJoinNetwork` 也使用 3。旧 Session/Ready/Input/Frames/ROM/Editor/Cover 的字段与 codec 不变；旧客户端不能与新服务端混用。新增 Offer / Allow / Approval / Decision / Capture / State / Upload / Applied / Result / ControllerInput。无模拟核心或本机库改动，无公共 FC 邀请接口改动。

## 生产修改名单

修改：

- `net/SfcHomeNetwork.java`（仅 registrar 与新网络注册）
- `server/SfcHomeServer.java`（包括 State / Lease / Session / Joining 等内部类）
- `server/SfcHomeStartPolicy.java`（人数允许加入，但不强制等待）
- `server/SfcInputHealth.java`（新增分端口 expiredPort；旧 expired 委托保留）
- `client/SfcHomeClient.java`（含 Setup）
- `client/SfcPlayback.java`（增加 Restore；Picture 未改行为）
- `client/SfcCardEditorScreen.java`

新增：

- `net/SfcJoinNetwork.java`，包含 Client、Offer、Allow、Approval、Decision、Capture、State、Upload、Applied、Result、ControllerInput。
- `server/SfcJoinGate.java`，包含 Phase。
- `client/SfcJoinClient.java`
- `client/SfcJoinScreen.java`

不含硬件 renderer／模型资源变更。未修改版本/Gradle/注册物品/模拟核心/旧交付目录。

## 验证

1. `tools/check_sfc_join.py --report <新路径>`：15 项通过（9 真实 Gate、2 实际共享布局覆盖多种 GUI 尺寸、4 服务端/worker 接线契约）。报告 `design/sfc-join-final-gate-layout-20260910.json`。
2. `tools/check_sfc_card_editor.py --report <新路径>`：25 项原写卡草稿、选择、异步失效与上传改名回归通过。报告 `design/sfc-card-device-ui-20260910.json`。其中旧纯布局测试覆盖历史 helper；本轮实际布局另由上面的共享布局测试覆盖。
3. `tools/check_sfc_join_packets.py --report <新路径>`：直接 javac 本轮 10 个 SFC 生产类及 2 个 FC 共享 UI 类，对真实 MC/NeoForge API 编译通过；生产 codec 37 项通过。通过真实 `NetworkRegistry.register` 注册生产 Upload codec，并调用真实 NeoForge 21.1.236 `ServerboundCustomPayloadPacket.STREAM_CODEC` 验证外层包，30 KiB 块 + 最宽 varint + 通道 ID 总计 **30856 B**，低于 32767。报告 `design/sfc-join-final-packets-20260910.json`。支持 `--jar <最终SFC包> --fc <最终FC包>` 仅编译 probe、直接验最终包。
4. `tools/run_sfc_join_core_probe.py --report <新路径>`：两个同时存活的冻结 SFC6 `WasmSfcCore`，使用仓库原创 32 KiB 65816 诊断 ROM。P1 先跑 127 帧，导出 **1,294,513 B** 状态并通过当前 Gate 30 KiB 分片/哈希/身份门禁；P2 真实 loadState，然后继续 **240 帧**视频、音频与周期全状态一致；**792 断言**，比较 55,050,240 B 视频、383,368 个 PCM short。取消交易不改变 P1，P1 可以继续单独推进。报告 `design/sfc-live-join-final-two-core-20260910.json`。冻结核心 SHA `38FA46C5D283EAD9E1F6666D01398F495E2E1A3260A1517BE8EE959710963363` 不变。

限制：以上不是 Minecraft 双客户端实机、不是保护插件实装联机测试，也未进行屏幕截图验收。真实核心探针证明 save/load 与后续帧连续性，但不直接执行 SfcPlayback 的游戏线程调度；游戏自身是否允许中途切换双人仍由该游戏决定。应完整 Gradle check/jar 后再次用 `--jar` 跑协议外层探针。

状态：局部生产源码完成；整体编译及独立成品审计仍待完成。

## 最终审查增补

- 两个 Screen.init 已调用公共新增的 `DeviceUi.prepare()`，仅共享安全合并 Modern UI 白名单，不覆盖未知配置。
- 修复原 SFC 全局 watchdog 把 P2 断流误当作整局故障的问题：P2 超时、超频、序号异常只释放 P2，P1 原局继续；P1 故障仍终止全局。`SfcInputHealth.expiredPort(int,long)` 公开只读查询，旧 `expired(long,boolean)` 保持兼容，重新批准 P2 后健康与 P2 序号重置。
- 最新窄套变为 **24 项全部通过**：9 Gate、9 分端口健康、2 实际布局、4 接线契约。新报告 `design/sfc-join-final-p2-health-20260910.json`，不覆盖前面旧证据。
- 最终纯 JAR 探针已提供 `piq-fc-arcade/tools/probes/Alpha18SfcJoinProbe.java`（package `cn.piq.sfchome.server`）；仅编译探针，通过 CodeSource 校验 SfcJoinGate/Phase 来自传入最终包，检查身份、批准、分片、摘要、两级超时、一次提交、取消；不启动 MC 或核心。独立成品审计时调用。
