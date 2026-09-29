# FC alpha28：家用电源与输入租约分离

修改者：`/root/fix_sfc_av`；日期：2026-09-11。根维护手册、版本、冻结和交付由主代理汇总。本子任务不安装、不启动 Minecraft、不操作用户 ROM/存档或服务器。

## 接口与职责

`cn.piq.fcarcade.home.HomeConsoleRuntime` 是电器公共层入口：

- `powerOn(ServerPlayer, HomeConsoleBlockEntity): boolean`：只按电源创建后台会话；通过 `ZapperStandService.connected(console)` 选择普通 FC / 光枪 variant。
- `powerOff(ServerLevel, HomeConsoleBlockEntity)`、`reset(ServerPlayer, HomeConsoleBlockEntity)`、`running(ServerLevel, HomeConsoleBlockEntity)`。
- `takeController(ServerPlayer, HomeConsoleBlockEntity, int)`：端口 0/1，必须已经开机并收到实际客户端 worker 就绪。
- 光枪：`HomeZapperService.take(player, console, realStack)`、`returnGun(player, realStack)`。支架原物权/receipt 由 `ZapperStandService` 独立保存；审批等待不生成复制枪。`canBind`、`bind`、`authorized` 都验证 `validOrigin(player, stack, targetConsole)`。

计算 Host 独立保存 UUID 与实际 Connection 身份；开机不创建 P1、不发隐藏手柄、不占 `InputOwnership`。Host 可自行领取任一空口；他人申请指定口后由 Host 接受/拒绝。审批含单次 UUID token、申请端实际连接和期限，批准前重新执行权限/物品/连接/席位检查。

手柄 P1/P2 与枪归还仅清本人当前值/FIFO/序号及精确租约，不关电源、不提升 P2 为 P1。显式电源关闭、Host 断开或换维度、源 chunk/结构/AV/TV 电源失效结束后台会话。Host 同维度可自由移动；实体操作者仍受原 8 格范围和实际持物门禁。每人最多运行一台 FC 家用后台；原街机仍使用原 roster/会话分支。

## 生命周期与授权

新增纯 `HomeRuntimeAuthority<C>` 将 Host 和两个物理 socket 分开；所有输入租约按 UUID + Connection 对象身份 + lease + port 同时匹配。重置保留物理借出关系但推进 epoch/revision，清全部输入和旧 snapshot；不会偷偷读回旧保存状态。

`ArcadeHomeReadyPayload(session, epoch)` 由真正计算 Host 的 worker ready 后发送。服务端未 ready 时不推进时间、不允许领取；超时 1200 tick 关闭，避免加载前跑空历史。

`sessionForParticipant` 已收口：当前实际连接检查之后，home 会话不能经旧 memberships 回退绕过严格 socket 检查。20-tick 回收对离线/换连接使用保存的旧 socket 身份释放，并按原 session 回收实体 ledger；迟到新连接不能继承旧输入权限，也不能释放新租约。logout 不重新添加断线旁观者，待审批请求只按保存的原 Connection 精确删除，不阻塞空席到超时且不误删新连接请求。

客户端没有物理控制权时不会 attach 键盘/手柄；计算 Host 仍运行、回传 snapshot 并渲染。真实 controller/gun 条件决定输入，计算身份仅决定计算/保存。计算 Host 的同世界/维度/连接检查先于本地显示 chunk 检查，不能因 Host 走远卸载客户端电视 chunk 而关闭仍有远端玩家使用的核心；设备卸载/拆除由服务端权威检查结束。TV 音量使用 `HomeApplianceService.audioGain`；Host 不被自身旁观跟踪再次加入。审批失效时可关闭自己仍在前台的旧对话框，但不发送决定、不关闭替换 GUI。控制器/枪 `useOn` 先调 `tryButton`，TV 非按钮只提示；旧右键 TV / `startHomeConsole` / `startHomeZapper` 不得隐式创建旧模式家用会话。

## 协议与存档

FC 网络版本 **33**，不与旧 32 混连。新增 C2S `ArcadeHomeInputPayload(lease, oldInput)` 与 `ArcadeHomeReadyPayload`；普通 home 拒绝无 lease 的旧 Input。Session 附加 homeRuntime、computeHost、controllerLease、controlRevision；JoinApproval/Decision 附加可空 requestToken（旧街机仍 null）。保留旧 Java 构造器和原嵌套输入 codec，并验证新外层真实 NeoForge 编解码。

客户端 session/frame/history/snapshot/persistent state/approval 入站通过捕获的真实 Connection 复验后排队执行。Host snapshot 仍验证 variant 状态头、ROM SHA、epoch、帧边界/范围和 pending request，未修改 NES 模拟核心、WASM、锁步帧算法和状态序列化格式。

普通电源读取/保存原 `machineKey(dimension, TV anchor, LOCKSTEP)`，光枪保持 `core|variantNamespace|player|hostUUID|machineKey` 隔离；只写当前 session.saveKey。旧个人槽选择、封存和存储实现未改，开电源不自动选择个人槽、不覆盖其 key。保存仅有服务端最近确认 snapshot，不宣称拔电能保存未确认最后一帧。

## 本子任务生产范围

新增：`home/HomeRuntimeAuthority`（含 `Control`）、`home/HomeConsoleRuntime`、`ArcadeHomeInputPayload`、`ArcadeHomeReadyPayload`。

修改：`server/ServerArcadeSessions`（含 Manager/Session、新 HomeRequest）、`home/HomeControllerService`、`home/HomeZapperService`、`session/SessionRoster`、`client/ClientArcadeSession`、`client/ClientArcadeEvents`、`ClientArcadeSupport`、`FcNetwork`、`ArcadeSessionPayload`、`ArcadeJoinApprovalPayload`、`ArcadeJoinDecisionPayload`。内嵌 record/lambda/Nest 调试 class 差异由最终 bytecode 审计识别，不以整个 client/server 目录豁免。

未改：模型/资源/注册/版本、HomeHardware/TV 实体/根公共 Appliance 实现、旧普通/光枪 WASM、核心/JNI/Native/helper、用户实例。

## 验证与复跑

`HomeRuntimeAuthorityTest` 17 个真实纯生产类行为测试覆盖零 socket 开机、端口独立取还、原街机自动提升不变、120 帧空席中立、短按 FIFO、旧租约/同值不同连接、断线残留回收、reset 旧 epoch 拒绝。

补充源合同更新仅用于原协议与权限接缝保障，不能代替真实行为：`FcNetworkCompatibilityTest` 的协议预期更新为 33；`ZapperSessionSourceContractTest` 明确旧入口只取枪、power 才创建隔离机器会话、输入须持物但 snapshot 由独立 Host。

工具：

```text
python tools/check_home_runtime28.py --fc <final-fc28.jar> --report <new-report.json>
```

只编译 QA/test，不编译任何生产 class；原件与 ASCII 临时副本先后 SHA 一致；生产 CodeSource 必须来自传入 JAR；报告拒覆盖。

已通过候选报告 `design/home-runtime28-candidate-v1.json`，输入 raw28 SHA `FA6901DACE5BFE567258D841A433F6BC4A8F9DA4978DCD32D6A78AA1852DEE18`（早于最后两项连接清理补丁，最终必须重跑）：

- 17 纯行为测试；两个真实 WASM module + `ClientNesWorker`，341 断言。
- 每 variant 的 Host 连续运行 150 帧；前 30 帧无柄中立；普通局测试 P1/P2，枪局只测试单枪授权；取还重启数 0。
- frame 90 snapshot 导入另一真实 worker，后 60 帧 RGBA / PCM / CPU RAM / 输入逐帧一致；显式 reset 各重建一次并拒旧 epoch。
- 实际 NeoForge 注册与完整 outer packet codec：1261 断言，14 包，最大 143 bytes；协议 33、方向、租约、token、所有截断长度拒绝。

限制：没有启动真实 Minecraft 世界/服务器/socket；没有执行完整容器/保护插件/按钮或 GUI 实机交互。探针是实际 worker/核心/codec 加真实纯授权状态协调，不伪称完整联机实测。根代理负责完整 Gradle、最终冻结与成包证据。当前生产可统一重构建，最终报告另新增，不覆盖候选报告。
