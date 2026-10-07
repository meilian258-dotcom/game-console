# MAME 单步 helper / CabinetSyncCore 接入只读审查

2026-09-12。基于现有源码与 MAME 研究报告的只读审查，未重新启动核心、构建或修改生产代码。

## 结论

进程隔离和四口软件输入具备可复用基础，但当前媒体 helper 不是逐步核心。必须先解决固定 MAME 的快照语义门槛，再开放本地同步：旧研究真实观察到 load→save 全状态 SHA 不等、恢复 PCM 不等；FC32 的严格初始状态/恢复校验会正确拒绝这种 adapter。仅新增 STEP / SAVE / LOAD IPC 不等于已实现可用同步。

最低风险路线：保留现媒体 `NativeProcessSession/open`，新增固定版本的单步 helper 模式与对应 `NativeSyncCore`，仍只在子 JVM 加载 DLL；共用身份、文件暂存、生命周期守卫和按键转换。是否注册 sync 能力由真核验证结果决定，不降低公共 SFC 的严格检查，不静默回退同步模式。

## 已核对的真实路径

| 位置 | 当前行为 / 接入影响 |
| --- | --- |
| `helper/.../NativeCoreWorker.java:155–198` | 独立命令读取线程修改输入 FIFO；核心线程按 wall clock 自主反复 `retro_run`。没有请求帧号或逐帧 ACK。不能将发送 INPUT4 后等一张 picture 当作一次准确 step。 |
| `src/.../bridge/NativeProcessSession.java:59–90,130–147` | 父端异步队列写输入，读取线程保留最新画面并聚合音频，旧视频可丢。`pollFrame` 并非对应某次调用的核心帧。 |
| `src/.../client/NativeCabinetBackend.java:34–52` | 只实现媒体 `open`；无 `openSync`，返回原异步会话。common 仅 `registerNetwork(...,4)`，目前未 `registerSync`。 |
| `helper/.../NativeCoreWorker.java:13–25` | 生产 JNA Retro 接口没有 serialize API。研究探针自己的 States 子接口增加了它，不能说生产已支持保存。 |
| `src/.../bridge/BridgeProtocol.java:6–15` | 私有协议 v3；固定 DLL / JNA SHA、帧尺寸 / PCM / 旋转上限，没有 FPS、兼容性或状态消息。 |
| FC `client/cabinet/CabinetSyncWorker.java:80–88,107` | HELLO 包含初始实际状态 SHA；加载后再保存必须同 SHA；每 300 帧再次保存校验。不能用缓存输入状态伪造 saveState 通过该门槛。 |

这里与下文的 `src/...` / `helper/...` 均相对 `piq-native-arcade`。FC 路径相对 `piq-fc-arcade/src/main/java/cn/piq/fcarcade`。

## 固定身份必须包含哪些内容

现启动逐次校验：

- `mame_libretro.dll`: `6172A988AB67FE68F4177A6FC8FBB82619EB2044C330930F0F572F7B1EDC2301`
- JNA 5.14.0: `34ED1E1F27FA896BCA50DBC4E99CF3732967CEC387A7A0D5E3486C09673FE8C6`
- 交付 helper v3: `20F6F3028D76DAEB01212D1808BE90E35BFB5429D1E06153B7D8B32DD73E943C`

新 helper 必须是新文件 / 新身份，不覆盖旧交付。推荐 `compatibilityId` 使用固定版本标签加规范描述 SHA：DLL、helper/JNA、Windows x64 ABI、实际 driver 短名、ROM 与白名单 BIOS 内容 SHA、有效选项、输入映射版本、bootstrap 策略、实际 FPS 位值 / 48 kHz。长度满足当前公共接口 256 字符限制，可传描述摘要，不把所有字段拼成长文本。

重要细节：`Engine.options` 是 `SET_VARIABLES` 收到的默认值，`GET_VARIABLE` 又固定覆盖按钮 profile、thread mode、cheats、throttle、读写配置、auto_save 等为 disabled（helper:121–133）。因此仅哈希 `options` map 不是完整“实际有效选项”身份，应记录实际查询返回值或规范化后的固定 profile。研究中两个 map 相同仍是有用证据，但不能替代有效配置锁定。

ROM 身份不能只看主 ZIP：`NativeRomStaging` 额外复制同目录 `neogeo.zip` / `qsound_hle.zip`，每个最多 64 MiB、总 128 MiB，不解包。新模式必须把实际被暂存的依赖纳入身份，不读任意父集合、不隐式加载用户 NVRAM。服务器只同步已获授权的内容，DLL/helper 不进入 ROM 自动传输。

现 runtime 文件检查/哈希后仍从原路径启动，而 ROM/BIOS 有逐父目录拒链接和前后元数据校验。新同步模式可在自有目录复制并复核固定 runtime，避免 verify→load 间路径重定向；不要以“固定字符串 SHA”替代实际被加载文件的验证。无需为本任务扩大改动旧媒体路径。

## 最小单步 IPC 合同建议

单一子进程核心线程负责 init/load/run/serialize/unserialize/unload/deinit；命令读取线程只可读取并投递有界命令，不得与 `retro_run` 并发调用 state API。

建议独立私有协议 v4（或独立 magic），不用把新二进制记录混入旧 v3：

- `HELLO`：版本、随机进程实例 ID、兼容性摘要、driver、maxPlayers=4、原 double FPS、48000 Hz、初始几何、状态能力与大小。
- `STEP(requestId, generation, expectedFrame, p1..p4)` → 一个恰好同 ID / generation / frame 的 `FRAME`。每请求只调用一次 `retro_run`，不 park、不自行补帧、不多跑到“有画面为止”。
- `SAVE` → 有界 state 字节 / SHA / 当前 frame；`LOAD` → 已验证身份、尺寸和 SHA 的 state，ACK 成功后另一次真实 serialize 核验其实际恢复语义。不得返回收到的缓存 state 代替真实序列化。
- `CLOSE` 及有界 `ERROR`。未知消息、重复/跨代/乱序 ID、坏长度、EOF、部分包、native callback 异常全部使本进程失败关闭。

父端最多一条进行中的同步 RPC（或很小固定容量），所有公开 core 方法只从 `CabinetSyncWorker` 同一线程调用。专用 reader 可避免 Java blocking pipe read 永久阻塞；reader 不调用核心，只解析并唤醒匹配请求。画面/PCM 直接返回这一 RPC，不经过旧 `latestFrame` 和 341 ms PCM 聚合队列。

状态上限沿公共 `CabinetSyncCore.MAX_STATE_BYTES=16 MiB`；blob 分段 IPC 或一次长消息均应在分配前检查 total、offset、chunk，禁止不受限 readAllBytes。帧仍检查当前 `MAX_DIM/MAX_PIXELS/MAX_PCM`，逐字段验证然后分配。原生内存不受 `-Xmx256m` 限制，进程隔离不是 OS 沙箱。

### 第一帧与视频重复

现真实研究的第一轮 run 可能没有视频 callback。生产媒体 helper 在 `pixels!=null` 才发 FRAME / 清 `pcmCount`（helper:186–194），这在同步中会丢失 step 对应关系，甚至把前一 run 的声音并入下一帧。

新分支必须每次 run 前清本轮 PCM 计数，精确返回本轮全部 stereo samples；null video 只表示没有新画面，不表示少执行一帧。需要明确规范化 bootstrap / 无画面结果：可以扩展内部帧记录的 hasVideo 并复用最后已知画面；初始无画面需明示策略，不能造一张图并在研究中称为核心输出。若统一接口必须非空，可采用明示、固定、受限的中性 bootstrap，并将实际步数/输入纳入身份与初始状态定义。不能偷偷多次 `retro_run` 却只计一帧。

### 四口输入

公共同步输入是 12 bit，4 个端口同时属于一条权威帧。`NativeInputPorts` 当前容许 16 bit，新的 STEP 应明确拒绝高 4 bit，避免让未公开按键绕过公共协议。

`NativeArcadeButtons.toMame` 将 host 编号动作键位 `0,1,8,9,10,11` 转为 MAME `0,8,1,9,10,11`，即只交换 bit1 / bit8；投币 bit2、开始 bit3、方向 bit4..7不变。只在 helper callback 一处转换，4 口相同。公共时间线已经消费输入边沿，STEP 直接设置这帧的 `current[4]`，不能再经过 NativeInputPorts FIFO 延迟或二次交换。

关焦点 / 归还某口只由下一条服务器权威 STEP 清该口，不从异步 CLEAR / RELEASE_PORT 改正在锁步的原生状态。旧媒体分支仍保留独立 FIFO 的行为。

### FPS / PCM

研究实际 FPS 为 `59.18560791015625`，不能硬编码60。新 HELLO保留 double 原值；同配置必须完全匹配，运行中若核心通过环境回调改变 FPS，首版应明确拒绝 / 结束，不能一端静默换钟。

公共 FC32 使用 `round(fps*1000)` → 59186 构造服务器帧时间线，worker保留double。双方按同一个权威帧序列执行，因此毫赫兹量化本身不会让两端输入序号分叉；但它并非精确原时钟，约比该核心多1.41帧/小时。最小接入可保持现约定并记录；若要精确分数协议应由公共同步负责人统一修改，不在 Native 内私自另设时钟。

PCM 数组是 short 数量（偶数），不是 stereo frame 数；两种 audio callbacks 都必须累计本轮实际样本并在每一步重置，保留空 PCM 帧。不能因丢弃 / 重复 video 而丢声音。追帧是否播放声音由公共 worker 决定，adapter仍需提供真实本轮 PCM 供严格验证。

## 进程生命周期与保存隔离

- 原 `NativeProcessSession` 的 ACTIVE 只在精确子进程真正退出后释放（159），关闭只杀自己的 Process。新增同步分支必须复用同一全局 lease/所有者，不能各自新建 ACTIVE 导致两个原生会话并存或误报可用。
- 当前15秒“没有帧就超时”（178）适合持续媒体，不能直接搬到同步：同步等待权威输入或初始客端等待快照可合法长期不跑。应仅在 INIT / STEP / SAVE / LOAD 请求进行中计时，空闲不累计。
- `CabinetSyncWorker.close()` 是置位+unpark，若 core.runFrame 卡在 blocking IPC，finally 中 core.close 尚不能执行。新 adapter 必须有进行中请求 deadline / 可取消响应等待，必要时通知专用生命周期管理器终止精确 owned child；不要依赖空闲无帧计时，也不要在 Minecraft 渲染线程阻塞卸载 DLL。
- 可尝试同线程有界 CLOSE/unload 后父端强制回收自己的 child；等待逾期保留占用状态直到确认退出，不允许新进程覆盖 ownedProcess。原 waitFor finally 是无限等待保守占用，不应把清 ACTIVE 提前“解决”。
- 研究 helper 在实际 unload/deinit 完成后使用 `Runtime.halt(0)`，因为上游线程可能残留。那是独立 QA JVM 的受控结束，不是自然退出已通过；新生产退出流程需单独实测。所有 Runtime halt/destroy只能针对新启动的专属进程，不能碰现有 Java/Minecraft。
- 现媒体禁读写配置与自动存档，在 fresh owned directory 中运行并清理，无玩家持久存档API。同步即时状态是会话临时资料，不可写入用户旧保存目录或自动作为永久档。新模式也从新目录启动，隔离用户 NVRAM、memcard、cfg、state。

## 当前真核证据与下一轮门槛

已有工具 `tools/check_native_determinism.py` + `tools/qa/NativeDeterminismProbe.java` 直接加载固定 helper 的 Engine 回调；探针独立扩展 serialize 接口，没有重新编译生产。原研究报告见 `design/native-sync-study/README-20260912.md`、kof97-v3/mslug2-v1。

两个游戏均两实例从头2800次运行音画一致；保存后本机 / 跨实例恢复600次的声音不能全部一致，load→save及跨实例原状态SHA也不全一致。不能通过屏蔽不明字节或忽略PCM降低门槛。现探针 inputs 只非零使用P1/P2，不能把这组研究称为真实P3/P4输入证明；四口逻辑另有纯/实际 helper 旧测试。

建议追加验证顺序：

1. 先定位非确定 state 字段与音频 postload；比较“原轨迹vs恢复”、“两端同一状态共同恢复”，以及明确有效选项。只有解释了实际可观测影响才考虑规范化，不能无依据去字节。
2. 单步IPC独立测试：恰好一请求一run、首轮无视频、同帧快按序列、4口一热位 / 独立释放、可变 PCM、空闲>15秒不杀、截断/大包/乱序/旧generation、读写堵塞/close及新旧media互斥。
3. 真实两个子进程：cold bootstrap identity，随机及游戏实际输入，运行状态保存/单端恢复/中途加入/取消重试，RGBA+几何+完整PCM+实际状态验证；读写保存/restore不得跨线程。按游戏/driver/profile/依赖范围列资格，不宣称所有 MAME driver。
4. 最后通过真实 `CabinetSyncWorker.Factory -> Native openSync`，再做独立最终 helper/JAR/DLL SHA 绑定与真实网络包回压/权限验证。无真实 Minecraft双机时必须继续标明边界。

从开机输入历史重放可以作为另一路研究，但不是现API的无缝替换：当前 `CabinetSyncTimeline` 只保留7200帧（约2分钟）尾部，长局无法从零重建；重新启动核心后追帧也要明确总量/CPU/时间上限。只哈希输入历史的“快照”不能检测实际核心失步，不能冒充完整运行状态检查。若要采用重放胶囊，需要公共协议/验证语义明确另立合同，而不是暗改 saveState 返回值。

## 预计最小代码范围（仅建议，未实施）

- 新私有 step 协议与 new helper entry / mode；复用现 callbacks、有效选项和唯一按键映射。
- 新 parent `NativeSyncCore implements CabinetSyncCore`，或 `NativeProcessSession` 的受控同步分支；共享原生进程占用/精确关闭守卫。
- `NativeCabinetBackend.openSync`、common sync 注册资格（仅验证通过后）、新 helper 精确SHA与可重复build工具。
- 不改 SFC，不替换固定 DLL，不改模型/输入UI/已有媒体默认，不安装/发布。原 `build_native_helper26.py` 包含当前 NativeArcadeButtons；更早 `build_native_helper.py` 缺这一源，不应当成当前helper可靠重建入口。

本文件只是审查记录，不代表新同步功能已可用。
