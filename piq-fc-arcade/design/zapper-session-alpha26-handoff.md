# FC alpha26 光枪会话交接

2026-09-11；Codex / sfc_cabinet_provider。仅本地源码、测试与新证据；不安装游戏、不改用户 ROM / 存档、不启动 Minecraft 或服务器、不改历史冻结包。

## 玩法及边界

普通卡带仍是原卡。插卡启动的普通 FC 局必须先归还旧手柄结束；主手光枪右键有效连接的 FC 主机或电视，才明确建立独立 `ZAPPER_V1` 会话，不自动切换已运行核心。枪局仅一个 Minecraft P1，硬件 `$4017` port2 光枪不等于第二名 Minecraft 玩家；P1 原 8 位手柄输入保留用于菜单。已有被动旁观、权威帧、快照与历史通路继续使用。

主手收起、GUI、失焦、操作锁解除使输入清零；库存暂存保留局，绑定设备右键归还才结束。服务端独立 UUID 租约绑定当前 Connection、player、session、epoch、维度、主机/电视/AV link UUID；NBT 仅为渲染收据，唯一个人库存原物品（兼容正常槽位复制）才有权。复制歧义、掉落/外置容器、端点失效、断线或超距结束局；首版不支持投掷转交枪。

服务端重新用 `HomeZapperAim.sample(player,binding)` 计算真实眼位/视线、有效画面和世界遮挡；客户端 x/y 不用于决定命中。显式屏外输入保持屏外。按键/枪序列分开；重复旧包在库存/射线检查前拒绝，超过每 tick 32 包先清枪并返回、不做射线。forceRelease 仍能在限流时清零，陈旧 force 不清新状态。瞄准按收到的输入更新（客户端每 tick），不是每个 PPU dot 重发世界射线；NES 每帧应用服务端采样，光感继续由真实 PPU 亮度/时钟判断。

## 帧、核心与存档

- 主协议 `31 → 32`，旧客户端明确拒绝连接，不静默误读新字段。
- `ArcadeSessionPayload.variant` 指定核心；普通默认 `LEGACY`，只有明确枪启动是 `ZAPPER_V1`。
- `FrameStep / ArcadeFramePayload / LockstepInputRun / ArcadeHistoryPayload / ClientNesWorker.Input` 加 `zapperState`。旧 Java 构造保留默认 canonical neutral。所有历史合并比较枪状态，不跨不同瞄准/扳机合并。
- 枪状态打包 x8/y8/offscreen/trigger；屏外坐标必须为0。移动仅同扳机状态可合并，press/release 每条保留一模拟帧；32边沿上限，溢出暗场并等释放重臂。
- worker 只在原专属调度线程、每个真实 `runFrame()` 前设置权威枪输入。旧 `WasmNesCore`、`NesCore`、Rust、两份 WASM 这轮均不修改。
- 新状态键 `core|nes-zapper-v1/<moduleSHA>|player|<UUID>|<dimension,TV,mode>`；最新枪存档自动恢复，独立于普通三槽位。绝不读、删除或迁移普通槽。沿用旧存档容器和 2 MiB 传输上限，不扩大预算。
- 快照接收/加载加纯 header 检查：gun magic/version/module SHA/ROM SHA/内存声明长度（64 MiB）必须匹配；普通会话拒绝 gun magic。完整 bounded inflate、baseline/指针校验仍由原隔离枪核心执行。

## 精确生产文件归属

修改的旧外类（允许其编译产生的同 stem 内类差异；最终应逐条列实际差异）：

- `cn/piq/fcarcade/ArcadeSessionPayload`
- `cn/piq/fcarcade/ArcadeFramePayload`
- `cn/piq/fcarcade/ArcadeHistoryPayload`
- `cn/piq/fcarcade/FcNetwork`（新增 nested `ZapperSink`）
- `cn/piq/fcarcade/session/LockstepState`（既有 nested `FrameStep`）
- `cn/piq/fcarcade/session/LockstepInputRun`
- `cn/piq/fcarcade/session/LockstepTimeline`
- `cn/piq/fcarcade/server/ServerArcadeSessions`（既有 `Session` 字段与 Manager 逻辑；其他旧内部类不主动改）
- `cn/piq/fcarcade/client/ClientNesWorker`（既有 `Input` record）
- `cn/piq/fcarcade/client/ClientArcadeSession`

新增外类：

- `cn/piq/fcarcade/session/NesCoreVariant`
- `cn/piq/fcarcade/session/ZapperInput`
- `cn/piq/fcarcade/session/ZapperInputQueue`
- `cn/piq/fcarcade/home/ZapperBinding`
- `cn/piq/fcarcade/home/ZapperData`
- `cn/piq/fcarcade/home/HomeZapperService` 和 nested `$Grant`
- `cn/piq/fcarcade/ArcadeZapperInputPayload`
- `cn/piq/fcarcade/ArcadeZapperSessionPayload`

本代理未删除旧 class、未改资源/版本/旧核心；最终 compiler 因方法修改产生的 synthetic method 在原 class 内。主代理改 ClientArcadeEvents 桥、版本、GUI；模型代理独占 HomeZapperItem/HomeZapperAim/client/zapper/资源，不纳入本代理白名单推断。

## 调用接口

`HomeZapperService.useOn(ServerPlayer,UseOnContext)` / `inventoryTick(ServerPlayer,ItemStack)` 给枪物品；其他 server 方法只供本局事务与 tick 调用。

`ZapperBinding(long sessionId,int epoch,UUID lease,ResourceLocation dimension,BlockPos consolePos,UUID consoleId,BlockPos tvPos,UUID tvId,UUID linkId)` 是完整授权收据。`ZapperData.binding(stack)` / `matches(stack,binding)` 不赋予服务器权限。

`FcNetwork.ZapperSink`：`acceptsConnection(Connection)`、`start(ZapperBinding)`、`stop(UUID lease)`，客户端通过 `setZapperSink` 注册；调用发生在游戏线程，捕获实际原连接。

`FcNetwork.sendZapperInput(ArcadeZapperInputPayload)`：session/epoch/lease/sequence/x/y/offscreen/trigger/forceRelease。world ray 与扳机动画由模型代理实现；server 仍全部重新验证。

`ClientArcadeSession.join(...,reset,NesCoreVariant)`、`authorizedZapper(binding)`、`visualZapperTrigger(binding)` 由主代理的 ClientArcadeEvents facade 调用。授权包括 P1、当前 Connection、worker ready、当前主手枪、共享键盘模式 enabled/armed；远端动画仅取已完成权威帧，不向核心补视觉输入。

## 测试与执行

- 新 `ZapperLockstepTest` 10 个纯 Java 测试：所有原生像素、同tick两次快按、移动合并边界、满队列、旧epoch/seq/force拒绝、P1输入隔离、3600帧历史、namespace。
- 新 `ZapperSessionSourceContractTest` 5 个源码合同：连接、服务端重新瞄准、明确开局/独立存档、核心/历史、唯一物品与重入。
- 旧 `FcNetworkCompatibilityTest` 更新协议32；新增方向/连接合同和纯非法状态验证。涉及真实 MC 静态 codec 的五项非法 payload 构造放到下面实际运行器，不在缺 MC runtime 的 Gradle test 环境伪造类型。
- `tools/check_zapper_session26_final.py --fc <新最终FC.jar> --report <新报告.json>`：只编3个 probe 与10个纯测试，production CodeSource 必须 final JAR；实际双 `ClientNesWorker` + 原隔离WASM，原创诊断ROM，P1第60帧快照、P2延迟加入、90帧追帧逐帧RGBA/PCM/RAM/枪输入一致，P1共150帧且未重启。真实注册 NeoForge outer codecs、3600条最坏history、截断拒绝、实际 Connection/EmbeddedChannel 与生产异步handler。
- 原 `tools/check_zapper_final.py --fc ... --report ...` 可继续跑原61核心诊断+4来源。

完整 Gradle / 最终候选测试由 root 统一调度；最终执行结果另附新报告，不覆盖 alpha25 证据。新工具的报告绑定 exact input path+SHA 并校验运行前后字节不变。

尚未验证：真实 Minecraft 双端、真实屏幕帧率/世界鼠标操作、其他模组保护事件集成、实体光枪硬件、所有商业枪游戏。服务端世界授权部分有源码与纯合同，不冒称已构造真实 ServerPlayer/完整服务器。私有 ROM/帧/状态未进入工具或资源。

## 最终 v2 执行结果（已冻结）

执行命令：`python tools/check_zapper_session26_final.py --fc build/review-controls26-v2/piq_fc_arcade-0.31.0-alpha.26.jar --report design/zapper-session26-final-v2.json`，退出码 `0`。输入最终 FC SHA-256：`74E16FEF0F69C88191C0A64DA4FCCDEFD2F3B18B231FC56C97839E60B885FBE5`；工具运行前后保持一致，未编译任何生产类。

报告 `design/zapper-session26-final-v2.json` SHA-256：`94231434445835F424131C9C592A5C13BF666C3F80C5F11E0A37FA8ADD567CA6`。真实双 worker / WASM `308` 断言和 `10` 个纯测试通过；P1 第60帧状态 `8595` 字节，继续至150帧未重启，延迟加入旁观端90帧的画面、PCM、RAM、权威枪状态逐帧一致。同tick press/release 经真实 worker 保留。真实注册 outer codec 和异步 Connection handler `806` 断言通过；3600条变化枪状态历史 `19777` 字节，未放宽既有预算。

此前 `design/26-preflight-v1.json` 仅为中间候选预检，不作为最终交付证据。生产、测试工具与本交接到此冻结；最终结果仍不等于 Minecraft 世界、真人联机或鼠标命中的实机验收。
