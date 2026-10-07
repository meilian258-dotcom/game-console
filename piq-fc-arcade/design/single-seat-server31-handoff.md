# FC31 单控制席服务器房间交接

日期：2026-09-12。仅本地源码和测试；不安装、上传、启动游戏或接触用户 ROM/存档。

## 结论与兼容边界

FC30 原 `registerNetwork`、Ledger、Assignment 及未连房间拓扑均要求至少 2 席，因此不能仅把 GBA 的 localOnly 改为 false，也不能把真实单口核心假声明为 2 口。FC31 允许真实 `registerNetwork(id, 1)`，客户端和服务端需成套升级到必选 `cabinet-room-3`（没有新增字段，但合法容量范围改变；旧 room-2 解码器不接受 1）。

GBA 由其负责代理注册 `register(BACKEND, description, false); registerNetwork(BACKEND, 1)`，实际核心仍为 1 口。单人或双人外形的未连机柜都只分配 P1。另一玩家右键得到“一个控制席位，可附近旁观”的提示，不产生申请/邀请 token、成员或 P2。任意两机柜串联容量至少 2，单口能力无法建立链接；既有持久链接失效时也不能把副柜降级为独立单席。旧 2–4 口附属未连仍为 2 席，原所有链接组合保持。

旁观完全复用既有 `WatchService`：host ready 后成为 source，独立观众 token 不占控制席，host 收到需求后发送原有有界视频/音频。旁观者不拿 ROM、不打开 GBA 进程，也不能调用控制输入。未改任何视频频率、音频、输入默认或流压缩。

## 精确生产修改

1. `cn/piq/fcarcade/cabinet/CabinetBackends`：`registerNetwork` 允许能力 1，保留注册存在、非 localOnly、非 NES、上限 4 与不重复限制。
2. `cn/piq/fcarcade/cabinet/CabinetSeats`：新增以下纯函数；`end`/`owns` 支持未连 1 席，旧未连 2 席/链接分区保持。
   - `capacity(int supported, boolean linked, boolean primaryDual, boolean secondaryDual)`：不可用为 0；未连 min(2, supported)，有链按实际物理总口数且不得超过支持数。
   - `validCapacity(boolean primaryDual, boolean linked, boolean secondaryDual, int capacity)`：独立 wire 拓扑验证；未连只许 1/2，有链必须精确等于物理口数。
3. `cn/piq/fcarcade/cabinet/CabinetRoomLedger`：`open` 最低容量 1。其 Member/Room/Change/Window、退出/超时/输入逻辑未改。
4. `cn/piq/fcarcade/cabinet/CabinetRooms`：`interact`、`topology` 用统一容量函数；单席不创建 `CabinetJoinGate`；`requestJoin` 立即拒绝单席申请。所有现有双端权限、绑定、连接/身份、重入、过期、host 关闭和 Watch 清理继续原路径。
5. `cn/piq/fcarcade/cabinet/CabinetRoomNetwork`：registrar room-3；`Assignment` 构造使用容量拓扑检查，其余 record/codec 字段不变。
6. `cn/piq/fcarcade/client/cabinet/CabinetClientBackends`：仅此记录改 `seat`/`buttons` 的实际 room.capacity 上限、`ownsRoom` 的多席邀请门禁、`visualInputs` 的可视口数。独立拥有 `startGame`/prepareFactory 改动，不应当成此处修改。
7. `cn/piq/fcarcade/client/cabinet/CabinetMenuScreen`：单口后端明确标注服务器单人、可旁观、不可串联。

无新增或删除生产 class，无模型/资源/核心/SFC/Native 生产修改。审核可按上述 class stems 比较，但内部类大部分应保持旧字节；Network 的 Assignment 构造是明确允许 delta。

## 测试

新增 `CabinetSingleSeatTest`（10 项实际纯状态测试）：能力/拓扑矩阵、四种物理组合禁串联、真实 P1 lease/ready、全范围拒 P2、伪造身份/序列/超长输入、同 tick 快按、强制松键、输入静默、过期不复活、退出后新身份隔离和房间限额。

新增 `CabinetSingleSeatSourceTest`（5 项集成源码合同）：保持注册约束、单席无邀请事务、共用 host/Watch 路径、客户端超容量包拒绝、必选新 wire 协议及身份约束。这些是源码检查，不能代替世界/网络测试。

更新既有 `CabinetRoomLedgerTest.capacityIsExplicitOneThroughFour` 和 `CabinetJoinSourceContractTest` room-3 断言，不删除原语义。独立 Java21 编译 4 个纯生产类和上述测试，连同旧 Ledger/Seats/JoinSource 共 **48 项通过**，输出 `design/single-seat31-source-v1.json`。报告明确 `production_compiled=true`、source-only；不冒充最终成品。未运行 Gradle，完整构建执行；独立 final-JAR 实际 outer codec/注册/Watch/Link 探针由 fix_sfc_av 负责。

## 原有运行/权限与存档归属

- 这是街机房间，不是家用电源后台：host 是 P1，离机柜超过原 8 格、身份/权限/硬件失效、掉线或主动退出会结束整局和旁观；无 host 迁移或全离线继续运行。
- 所有游戏和核心执行仍在 host 客户端，服务端只协调席位与有界音画；服务端没有读取 host 文件路径的权利，也不验证视频是否确由特定 ROM 生成。
- ROM 由 host 明确配置，`CabinetGameSelection` 将本机路径绑定服务器地址/维度/设备 UUID/后端；加载前沿用逐级安全检查及 GBA 自身上限/摘要。观众无需 ROM 或 native runtime。
- GBA 原预览存档是 host 本机按 ROM 摘要，不是服务器共享存档。新增 `CabinetBackend.prepareFactory` 捕获不可变开局上下文，GBA 代理据此隔离服务器/设备/玩家保存路径；这部分由二者负责，此记录未改存档代码。不得把“能在服务器运行”说明为“服务器托管存档/所有玩家共享进度”。
- 既有视距、16 格入/20 格出、每源 8 观众、全局来源/网络预算保持。未进行真实 Minecraft、保护插件、远程 socket、实体手柄或商业 ROM 游戏验收。

状态：此记录生产已冻结，48 项独立回归通过；等待完整构建与独立最终 JAR 验证。
