# Native / MAME 本地同步：FC32 契约只读审查

2026-09-12。审查基线：FC32 / SFC19 与对应 MAME 源码。本文为只读方案审查，不是新构建或实机验收。

## 结论先行

1. **首选修复/完整化 MAME 快照**，如果能够证明“原主持轨迹不断流”和“任意一端恢复后”的状态与音频/视频等价，现有 FC32 大部分服务器同步链可原样使用。只让两端一起恢复到相同的新音频相位，不满足现有“仅修客端，主持不断流”的合同。
2. **从规范开机重放服务器输入日志是另一种真实恢复路线，但不是现接口的无缝替代**。必须显式区分恢复方法与一致性摘要，不得将输入日志 SHA 当作核心状态 SHA。它有局龄/CPU追帧上限；超过限额只拒绝本次加入/修复，保留主持局，不能自动 reset、停主持或切到另一运行方式。
3. 当前 Native `open()` 的异步自由运行进程不能直接包成 `openSync()`：`pollFrame()` 是可丢视频帧的最新帧读取，不是“准确执行一帧”。两路线均须新增单步 IPC 和严格 request/frame 对应关系，保留原媒体运行路径。
4. 资格应绑定实际核心/DLL、helper、配置、驱动及依赖内容，按通过验证的游戏范围开放。两款 NeoGeo 测试不代表 CPS 或真实四人游戏已通过。

## 当前 FC32 的准确合同

| 项目 | 现有实现与边界 |
| --- | --- |
| 核心接口 | `cabinet/CabinetSyncCore.java:4–13`：同一个专属 worker 执行构造、`runFrame(p1,p2,p3,p4)`、save/load/close；每次 runFrame 必须准确一帧。状态最多 16 MiB。 |
| 接入/资格 | `client/cabinet/CabinetBackend.java:29` 默认拒绝 openSync；`cabinet/CabinetBackends.java:43` 须显式 registerSync。现资格为 backend 级，不含按驱动/内容选择能力。 |
| 初态 | `client/cabinet/CabinetSyncWorker.java:80` 对完整初始 saveState 取 SHA；`cabinet/CabinetSynchronizer.java:42–56` Hello 核对授权 ROM SHA、依赖 contentId、compatibility、fpsMilli、初始状态 SHA。MAME 初态跨实例状态字节不同，也会在这里被拒。 |
| 恢复精确性 | `CabinetSyncWorker.java:85–89` 先验证传输 SHA，再 load，随即 save 并要求 SHA 相等。不能仅让 unserialize 返回成功。Host beginRestore 明确禁止。 |
| 定期纠偏 | 每 300 帧对完整 saveState 取 SHA；服务端只接受至多落后 1,800 帧的摘要。与主持不一致时只恢复该客端；两秒冷却，超过三次纠偏移除该客端。 |
| 帧/历史 | `CabinetSyncTimeline.java`：40–80 fps，四端口独立 12 bit，32 个边沿 FIFO/端口；history 7,200 帧，批次最多 120 帧。worker 输入队列也为 7,200。60 fps 时约 120 秒，80 fps 时约 90 秒；不是无限历史。 |
| 快照/背压 | StatePart 最大 24,576 字节/分片；四房间最多四份旧缓存+四份装配，最多 128 MiB，读者共享缓存数组。单 worker 校验 SHA。每接收者每 tick 最多两片，名义约 960 KiB/s，但共享物理 Connection 的 196,608 字节实际发送完成窗口，不保证实际吞吐。 |
| 时限 | 服务端装配 900 tick；客端恢复到提交目标含搬运/追帧总计 900 tick（名义 45 秒）。上传端总 1,000 tick（50 秒），等 Grant 100 tick（5 秒）。这些 tick 在低 TPS 下不是严格墙钟秒数。 |
| 启动 | 正常 3,600 tick，只有精确授权且有进度的共享 ROM 任务可续到最多 6,000 tick，并有 400 tick 完成余量。不能拿游戏启动时限当任意长重放许可。 |
| 租约/输入 | RoomLedger 四房间；80 tick 心跳失效，40 tick 输入静默清该口；输入有 sequence 与速率限制。候选/修复中的端口不接受输入，恢复 ACK 绑定本次 token/目标帧/已发历史/期限。退出某 guest 只清其端口；Host 离开结束整局。 |
| 声音 | worker 独立 28,800 short = 300 ms 双声道 PCM 缓冲；追帧阶段抑制播放，恢复清旧缓存，但核心仍正常执行声音状态。不能把“静音播放”实现为跳过内部混音。 |
| 致命失败 | worker 核心异常/状态重存不等/gap/event 队列溢出结束本端；主持失败会结束整局。缓存落后接近 history 上限时当前服务端也安全结束整局，避免用不完整历史伪恢复。guest 超时/连续失步仅移除 guest。 |

相关生产文件均位于 `piq-fc-arcade/src/main/java/cn/piq/fcarcade/`。网络为 mandatory `cabinet-room-4` 和 `cabinet-sync-1`，以 room/member/epoch=1/实际 Connection 隔离；新局 UUID 更换。纯协议 UUID 不替代世界身份、距离、保护或主人审批。

## Native 适配两路线共同必需的接缝

- **单步 helper**：现 `helper/.../NativeCoreWorker.java:170–198` 自主循环 nextInputFrame→retro_run→写全帧→按 fps park；`NativeProcessSession.java:83` 的 pollFrame 只取最新图片。需新同步分支/入口：精确 STEP 请求（四口 mask、单调 requestId/frame）才调用一次 retro_run，回传匹配帧、几何和 PCM；不能复用“发 INPUT4 后碰巧读到下一图”。
- **四口语义**：同步传的是已经由服务器排好顺序的本帧四 mask，不应再被 helper 的异步按键边沿 FIFO 延迟一次。沿用 `NativeArcadeButtons.toMame` 的编号转换及 `mame_buttons_profiles=disabled`，不要改变选择/开始/方向位；0–3 口都适用。退出清零是服务器下一权威帧的一部分，而非客端自行 clear 造成失步。
- **身份/引导**：compatibilityId 至少含固定 DLL SHA、helper同步协议/构建、输出/输入转换版本、MAME选项与初始环境版本。ROM和BIOS内容走已有授权 contentId，不传 DLL。规范化 NVRAM/config/RTC/随机数/初始 DIP/内存卡；不能读每台玩家旧私人配置或个人存档当共同开机态。若引导需要先跑若干零输入帧，初始帧定义和 priming 数量必须明确、两端一致。
- **进程生命期**：现 Native watchdog 为“15 秒无帧” (`NativeProcessSession.java:175`)；同步等待输入时无帧是正常，不能照搬。应改同步专属按命令/进度判断，有限 request 超时+总期限；取消和超时只结束精确 owned child，直到 child 已退出才释放全局 Native owner。不能仅设置 Java worker closed 而让一个阻塞 native read 永远占用。
- **保护既有路径**：原 Native media / legacy 独立机柜继续用旧异步模式；同步单步/helper handshake 需明确版本，不让旧 helper 当作有状态能力。专服只加载公共元数据/授权/日志，不加载 JNA/DLL，也不执行 MAME。
- **资格不是泛用承诺**：现 backend 级 `supportsSync` 不够表达“指定驱动/BIOS/核心通过、其他未验证”。可增加只读 per-content/per-driver eligibility 描述，或先为 Native 明确实验白名单并在启动前拒绝不符；UI显示真实限制，不让所有 MAME ZIP 都看似已验证。

## 路线 A：修复 MAME 快照（推荐先验证）

最小公共接缝：若快照满足完整合同，只需 Native 新 openSync/同步 helper/固定运行库候选和通过后的 registerSync；FC32 同步格式/worker无需因 Native 特判放宽。原冻结 DLL/helper 不覆盖，新候选必须有独立 SHA 与相应源码/许可。

验收必须同时满足：

1. 两个独立进程同规范开机初态的状态字节一致，连续相同四口输入的帧/PCM/状态一致。
2. 在多个帧点 save 稳定；同实例/新实例 load 后立即 save 字节一致。
3. **不断流的原主持** 与仅恢复的客端在后续长序列中每帧完整 RGBA、PCM长度/每样本、状态一致。两端都执行 postload 后相互一致，不足以替代这一条。
4. 若 serializer 有被证实与仿真无关的地址/填充/调试字段，可在有明确布局和语义证据后规范化；不能盲删差异字节。规范化摘要必须反映真实状态，不允许“恒定摘要”或日志摘要冒充。
5. 音频状态应保存/恢复影响后续采样的混音、重采样相位/延迟样本、时钟/时间基准、效果缓冲等，不能只静音前几帧。哪些字段真正遗漏由 MAME 源码/双实例差异实验确定，本审查不臆断已修好。

风险：修 serializer/postload 涉及核心兼容与不同 driver 状态；未知合法状态尺寸仍受 16 MiB 上限。某些游戏 fps 在 FC32 的 40–80 之外应明确拒绝或单独设计，不因 Native helper原来接受20–240就放宽整个同步系统。

## 路线 B：从规范开机重放输入日志（可行但必须显式受限）

### 不可使用的伪兼容法

把 `saveState()` 改成“初态+输入日志”，loadState 从头重放，然后对同一日志再取 SHA，形式上会通过现有重存检查；但任何核心内部失步仍会得到相同日志 SHA，定期 Digest 永远不能发现失步。这不是现有完整状态合同，不应实现为不声明差异的适配器。

### 最小明确的设计变化

- 增加恢复策略能力，例如 `EXACT_SNAPSHOT` / `BOOT_REPLAY`，并把摘要算法明确区分为 `STATE_SHA256` / `AV_TRACE_SHA256`（如后者被选）。SFC仍走原完整状态路径，不降低其校验强度。
- 对 Replay，**服务器保存自己生成的完整权威输入日志**及块哈希；不信任 Host 重新上传“历史”。可用固定四 uint16（8 B/帧）+起始帧/块长/rolling hash，所有口一起保存；RLE/压缩只能是编码优化，必须限制解压后的帧数与字节数。
- Host 提供独立的实际执行结果摘要：每固定连续窗口完整像素+尺寸/DAR/rotation+PCM长度/样本+帧号的滚动 SHA，或有证据的完整语义状态摘要。输入日志 hash 只校传输完整性。AV trace 可发现可观测失步，但**不能宣称证明所有隐藏状态相同**；需持续复核及已验证驱动范围。若产品要求原“完整状态等价”强保证，路线 B 单靠 AV trace 不满足，仍需状态规范化或路线 A。
- 新 GuestRestore/ReplayBegin 需绑定策略、boot/content/core identity、room/member/epoch、唯一 token、目标帧、日志完整范围/总上限/哈希、期限；每页都复验当前连接/租约。此为真实语义变更，建议独立 `cabinet-replay-1` mandatory 或 sync协议2，不能用旧字段暗塞。
- server 为单个 guest 持有有界日志读取 cursor；guest只在有容量/进度信用时取下一批，不能一次塞入现 7,200 worker 队列。Host照常推进；guest追旧日志不抢占正常控制者的帧发送/输入与共享 Connection 完成窗口。
- 故障端自己的 helper 从规范boot重建并高速重放。不要对Host发load/reset；候选未完成之前P2/P3/P4输入一律0。到固定已发送目标的**执行摘要**与Host参考相等后，继续追增量至允许落后阈值，再原子激活该端。当前ACK只有token/frame，不能当成新的replay结果比对已完成。
- REPLAY内仍准确逐帧执行 core/audio，只不把历史PCM送扬声器；可不回传每幅历史图，而在 helper 内累计AV摘要并按固定批次回进度。不得打开会改变内部状态的“无音频计算”加速开关。入实时前清音频和图片积压，避免历史声音爆发。
- 不要每300帧把全部“从boot到现在”日志重新保存/上传；那会随局龄反复复制与传输，整体工作量近似二次增长。服务器增量日志与分段摘要足够，不需要Host上传同一日志。

### 资源和实时可行性

四口 8 B/帧、60 fps 的未压缩按键数据约 480 B/s，约 **1.65 MiB/小时**；16 MiB只算日志约容纳9.7小时。但 Java Step 对象队列不是这个紧凑大小，现7,200限制也不会自动支持一小时日志；宜紧凑块+明确内存总预算，必要时有界临时文件而非玩家存档。输入日志仍含玩家操作轨迹，不对旁观者开放，不跨服务器/新连接复用为授权。

主要瓶颈是追帧算力：设已经运行 T 秒、Host速率 r fps、客端最高回放速度 v fps，且Host不断流，忽略启动/网络开销的最小追赶时间为 `r*T/(v-r)`，v<=r时无法追上。按k=v/r记，即 `T/(k-1)`。

| 实测回放倍速 k（仅示例，尚未测得） | 现45秒期限理论可补的最大局龄 |
| --- | --- |
| 2× | 45秒 |
| 5× | 3分钟 |
| 10× | 6分45秒 |

30分钟老局若要45秒追上，理论至少41×，还没计启动/网络。不能通过无限延长期限或暂停Host来掩盖；必须测实际driver的unthrottled throughput，并设最大可加入局龄/重放帧数/墙钟期限/单guest预算。超界显示具体原因、允许继续旁观或下局重新选择，**不自动换模式或重开原局**。到日志上限后可停止接纳新guest并保留已同步玩家继续，或另有用户明确同意的局时限；不能静默删前缀后宣称仍可从boot恢复。

现快照缓存过期会结束Host的通用检查必须按恢复策略分开：Replay不能让永远frame0的boot快照触发现有7,190帧关闭Host，也不能直接取消所有历史保护。日志范围完整、读者追赶和总期限要用Replay专属不变量替代。

## 下一步门槛（执行由对应负责人另行确认）

1. 先比较不间断轨迹 / 仅一端恢复 / 两端共同恢复，区分音频状态、postload副作用、非仿真字段；明确是否能通过路线 A。
2. 若A不可行，测规范boot双独立helper至少多时段/长输入、四口变化/短按/单口释放、同内容不同进程；测真实回放倍速分布，而非只测无视频输出速度。
3. Replay至少注入一次可观测核心失步，证明结果摘要不等、只重放异常端并重新收敛；同时证明相同日志hash不会掩盖这个失步。候选取消/旧token/旧连接/丢块/重复/乱序/压缩炸弹/超帧数/超时均不改变Host。
4. 验证中途加入时Host帧号和PCM连续增长；guest历史PCM播放量为0；入局后新增输入只出现在服务器激活后的权威帧。真实四口游戏需独立证据，不能用KOF双人画面代替。
5. 新helper/DLL仅独立候选、版本/哈希/协议明确；最终JAR-only、真实helper进程生命周期、关闭/取消、专服common不加载native及旧SFC路径回归后再谈测试包。

**本审查不宣布任一路线已经实现或可靠；建议先走A的有界验证，只有A失败且用户接受明确局龄/驱动/摘要保证范围时，才将B作为独立实验能力实现。**
