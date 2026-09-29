# FC39 控制默认键与显示命名交接

修改者：Codex / controls_names39（像素匠协作代理）  
时间：2026-09-14 05:53 +08:00  
状态：源码与独立纯测试完成；待 root 统一构建、最终 JAR 复验和交付。未安装、上传、发布、启停游戏或关机。

## 改动

- 共享 `KeyboardConfig` 的默认位置/操作锁从 Z（90）改为 N（78），设置键仍为 F7（296）。FC、学习机、SFC、Native 街机和 GBA 的现有 `KeyboardInput` 接入共同生效，不增设第二套输入所有者。
- 配置保存格式变为 version=3；当前格式下用户显式保存 Z 或其他有效自定义键完整保留。加载仍只读，只有点击保存才原子更新 `config/piq-keyboard.properties`，不改 Minecraft `options.txt`。
- 旧 version=1/2 仅在可识别的旧默认快捷键（Z，或 v1 的 F8=297；设置键 F7）且所有正在使用的方案是固定预设时，在内存将锁键迁为 N。CUSTOM 或 LEGACY 跟随方案、非默认设置键及其他自定义快捷键整套保留并提示可手动选择 N。不会为了给 N 腾位而重排自定义游戏键。
- 旧格式没有“用户是否主动选过默认 Z”记录，因此不能完整区分主动选择与默认值；保守识别条件和只读迁移已经明确保留，不能声称旧版本所有自定义历史都可追溯。
- 设置页提示默认 N，状态文本继续使用实际当前锁键，非硬编码。FC 注册的 A 备用 X / B 备用 Z 未改；N 默认下 LEGACY 的 Z/X 原样参与游戏掩码。未绑定为游戏键时 Z/X 通过，不因锁定快捷键误吞；用户显式自定义 Z 锁仍按其选择工作。
- 五个项目十份 zh_cn/en_us 语言文件按批准命名更新，并去掉这些显示值中的 PIQ；注册/翻译键、命名空间、协议 ID、路径与法律署名不改。FC/SFC 卡带与手柄等配件统一用“游戏卡带/游戏手柄（系统）”及对应英文。没有改纹理 PNG、模型、版本或打包工具。

批准的主显示名：

| 设备 | 中文 | English |
| --- | --- | --- |
| FC | FC家用游戏机（FC） | Home Console (FC) |
| SFC | SFC家用游戏机（SFC） | Home Console (SFC) |
| 学习机 | 大霸王学习机 | DaBawang Learning Computer |
| GBA | GBA掌上游戏机（GBA） | Handheld Console (GBA) |
| 单人街机 | 单人街机 | Single-Player Arcade Cabinet |
| 双人街机 | 双人街机 | Two-Player Arcade Cabinet |

## 文件名单

- `piq-fc-arcade/src/main/java/cn/piq/retro/client/KeyboardConfig.java`
- `piq-fc-arcade/src/main/java/cn/piq/retro/client/KeyboardConfigStore.java`
- `piq-fc-arcade/src/main/java/cn/piq/retro/client/ControlSettingsScreen.java`
- `piq-fc-arcade/src/test/java/cn/piq/retro/client/KeyboardConfigTest.java`
- `piq-fc-arcade/src/test/java/cn/piq/retro/client/KeyboardMigration30Test.java`
- `piq-fc-arcade/src/test/java/cn/piq/retro/client/KeyboardDefaults39Test.java`（新增）
- `piq-fc-arcade/src/main/resources/assets/piq_fc_arcade/lang/{zh_cn,en_us}.json`
- `piq-sfc-home/src/main/resources/assets/piq_sfc_home/lang/{zh_cn,en_us}.json`
- `piq-sfc-arcade/src/main/resources/assets/piq_sfc_arcade/lang/{zh_cn,en_us}.json`
- `piq-native-arcade/src/main/resources/assets/piq_native_arcade/lang/{zh_cn,en_us}.json`
- `piq-gba/src/main/resources/assets/piq_gba/lang/{zh_cn,en_us}.json`
- `piq-fc-arcade/tools/check_controls_names39.ps1`（新增）
- 本交接笔记。

## 实际验证

- 独立 Java21 / JUnit5：6 套、83 项全部成功，0 失败/错误/跳过；涵盖设置编解码、保存修订保护、旧格式迁移、自定义 N 游戏键不被改写、schema3 显式 Z 保留、三个输入档位 Z/X 路由和掩码、失焦/中立重新启用等。没有运行 Gradle，避免与其他代理并发编译。
- 10 份语言资源解析、重复键、全部翻译键与 `%s/%d/%%` 占位符对比 FC38 冻结配套基线通过；合计 910 键保持，59 个显示值改变，批准主机名称精确断言通过，显示值 PIQ 残留 0。
- 报告：`piq-fc-arcade/build/controls-names39-source-v2/report.json`，含测试源 SHA 与语言 SHA/变更键名。v1 因 PowerShell 未引用 `-Dfile.encoding=UTF-8` 将点号参数拆分，Java runner 未启动；已仅修测试脚本参数引用，保留失败目录，没有改变生产代码或断言绕过失败。
- `ControlSettingsScreen` 提示改动尚未实际 Minecraft API 编译；由 root 最终统一构建验证。未进行真实 OS 按键、Minecraft UI/世界、实体手柄、音频或多人联机测试，不能据纯测试宣称实机验收通过。

复跑（必须用新报告目录）：

```powershell
& 'G:/服务器/服务器Codex/piq-fc-arcade/tools/check_controls_names39.ps1' -ReportDirectory '<新目录>'
```

最终 FC JAR 冻结后只编测试、不编生产类：

```powershell
& 'G:/服务器/服务器Codex/piq-fc-arcade/tools/check_controls_names39.ps1' -FcJar '<最终FC39.jar绝对路径>' -ReportDirectory '<新目录>'
```

第二条仍检查源语言；root 的最终配套 JAR 审计应额外确认十份语言资源字节进入正确 JAR。此脚本不宣称验证全部配套 JAR 的资源打包。

## 追加：SERVER_MEDIA 房间账本

时间：2026-09-14 06:02 +08:00。root 在第一部分交接后明确分配；范围仅以下生产/测试，不改公共协调器、客户端或网络。

- `piq-fc-arcade/src/main/java/cn/piq/fcarcade/cabinet/CabinetRoomLedger.java`：Room 新增 final `streamHostId`（最初 host.id）及 `ownerId`（最初 host.player）；SERVER_MEDIA 的 `host()` 是当前第一个非空成员，只作为管理者，不改变端口或稳定播放/存档所有者身份。普通模式仍取 members[0]。
- SERVER_MEDIA 的默认 join 和显式范围允许空缺端口 0 被新成员重新领取；成员离开只删其席位，其他端口与输入序列不移动，最后一个成员离开才移除房间。MEDIA/LOCAL_SYNC 的 P1 离开整房结束旧分支保持。
- 新增 `removeRoom(UUID roomId)` 强制清理所有席位、成员/玩家/房间索引与 held-room 数组，返回不可变删除名单；重复或未知 room 返回空列表。实体拆除/核心致命错误由 root 在协调层调用。未修改 ready 身份授权方法。
- 新增 `piq-fc-arcade/src/test/java/cn/piq/fcarcade/cabinet/CabinetHostedRoomLedger39Test.java` 12 项：初始主持离开保留P2、P1重占、双柜范围、管理者再离开、最后退出、单席退出、强关/索引防串、旧令牌、普通模式原行为及范围/ready门禁。

实际验证：

- `build/cabinet-hosted-ledger39-source-v1`：52 项 JUnit 返回成功，但 post-run 源围栏发现 root 正在修改 `CabinetJoinGate.java`，因此本轮不接受为冻结验证；保留目录/运行日志。
- `build/cabinet-hosted-ledger39-source-v2/report.json`：旧Ledger22+新增Ledger12+既有键盘10=44项成功；后10项已经包含前述83键盘测试，不能重复当新增覆盖。独立编译避开并发JoinGate，源围栏通过。
- root 确认 JoinGate/Mode 暂稳后最终重跑：`build/cabinet-hosted-ledger39-source-v3/report.json`，旧Ledger22+新增Ledger12+旧JoinGate18=52项成功，0失败/跳过；源SHA围栏通过。Ledger SHA256=`74B3ED2383479304C03FB16D137B9D81DF7B26DAA3AD009FCF985BF1591C82A1`。独立 javac/JUnit，不运行 Gradle、Minecraft、worker、网络或核心；纯账本结果不是托管全生命周期实测。

## 追加：托管外层只读审查

应 root 要求仅阅读 `CabinetHostedSessions` / `HostedCabinetWorker` 及其路径/媒体/核心契约，没有替 root 修改代码。已通知 root 两项确定修正点：

1. `CabinetHostedSessions.context` 初版将每局网络 room UUID 传给 `ServerCoreContext.roomId`，而 saveDirectory 把该字段追加进持久存档路径；同设备同玩家下局因此新目录，不能恢复上局。应传稳定 `target.identity()`（核心代理也确认此契约），网络room仅用于通信/临时运行目录。
2. `HostedCabinetWorker` 初版 createDirectory(staging) 因既有目录而失败后，finally 仍尝试删该空目录。需记录本轮创建成功再允许删staging，保护既有碰撞目录；已有 created 文件清单仍保留。

补充防御建议：外层finally调用可插拔core.close应防止异常越过关闭清理；必须真实isTerminated后才能删暂存或释放占额，不能用超时假报终止。核心代理确认其正常实现close非阻塞、内部保存/关闭异常有保护；不可强杀的WASM若永久卡住将继续占额，这个边界应明确，不能伪装已回收。

正向检查：manifest主文件/伴随BIOS白名单及文件名限制、按SHA读不可变对象、逐级无链接验证、CREATE_NEW/NOFOLLOW写临时副本与完整复制后再验、固定8个outbound batch和有界视频/PCM、只清理本轮created文件清单均存在。没有进行攻击性并发文件替换测试；父层后续修正、集成与测试由root统一验收。

## 追加：整批共窗 payload 发送与托管 P1 审批

时间：2026-09-14 06:13 +08:00。root 明确分配的新子任务；范围仅 Sender、新增测试/探针和本笔记，已与 `retroconsole_compare` 对接 SFC 整帧调用。

新增接口：

```java
public static boolean CabinetMediaSender.sendPayloads(
    Connection connection,
    List<? extends CustomPacketPayload> payloads,
    int[] conservativeBytes,
    boolean serverbound)
```

- 使用原 `WINDOWS`，与单包、机柜媒体和旁观发送共用同一物理 Connection 的 196608 字节在途上限，不增设独立协议窗口。
- 严格 1..6 包、每包估算32..32768字节、数量匹配、无null、方向/连接/可写正确；先复制并再次检查快照形状，再一次 reserve 整批，拒绝时不发送任何前缀。调用者须把编码字段及头部计入 conservativeBytes；本通用接口不自行编码任意 CustomPacketPayload。
- 成功/失败回调按单包释放；send 抛 RuntimeException/LinkageError 时保留已提交以及该次可能已提交包，`cancelUnsent(index+1)` 只释放从未尝试的后缀。发送途中失败不能回滚已提交包，返回false不等于完全未发出；只有准入拒绝保证零前缀。
- 原 `sendPayload`、私有媒体 `send` 和 release 语义未改。连接仍在线时 release 不抹去未完成票据，真实断线才清全部。

新增文件：

- `piq-fc-arcade/tools/qa/CabinetPayloadBatch39Probe.java`：真实 Connection 和 EmbeddedChannel write promise；仅在异常场景用明确子类钩子在真实 `super.send` 前/后抛异常。双方向最大整帧、共享单包占额、超限零前缀、重试不增长、准确payload次序、错误输入/方向/不可写/断线、调用者数组/list后改不影响快照、异常首包/中包、未尝试后缀取消、真实完成/断线释放及重复回调不影响新批次。
- `piq-fc-arcade/src/test/java/cn/piq/fcarcade/cabinet/CabinetHostedJoinGate39Test.java`：新增6项，SERVER_MEDIA可申请空P1但仍先同意邀请、普通两参/false构造拒0、身份/令牌/超时/撤销不削弱、非法端口/自申请拒绝、批准P1后P2位置和初始stream/owner保持。

实际验证与限制：

- `build/payload-batch39-source-v1/report.json` 初版436断言成功；随后增加复制后形状复验及对应2断言，最终 `build/payload-batch39-source-v2/report.json` 438断言全部成功，源SHA围栏通过。只编当前Sender和Probe，其余生产依赖来自固定FC38，使用实际MC/NeoForge Connection，不是最终FC39全包集成。
- 具体payload为纯QA fixture，传输验证到真实Connection/Netty出站promise层；没有宣称任意SFC codec、socket网络、Minecraft世界或真实用户联机通过。没有启动模拟核心或安装。
- `build/hosted-join39-source-v1/report.json` 4套58项JUnit成功、0失败/跳过、源围栏通过；其中52项已在前一报告计数，本轮新增6项，不重复统计新增覆盖。
- 最终JAR复验：只编 `CabinetPayloadBatch39Probe.java`，classpath 将冻结FC39放在生产类来源，main传该JAR路径；Probe会额外校验 `CabinetMediaSender` / `CabinetSendWindow` 的CodeSource确来自传入JAR（因此final模式预期多2来源断言）。勿把隔离源码编译classes置于最终JAR前面。

接口已向SFC协作代理说明并确认就绪，root仍需统一实际构建、SFC整帧接线和最终成套验证。没有改版本、模板、PNG或根维护手册；本轮三段统一留痕由root写入维护入口。

## 托管接线只读审计与每端口输入修复

时间：2026-09-14 06:31 +08:00。只读检查 CabinetRooms、CabinetClientBackends、WatchService、CabinetHostedSessions、HostedCabinetWorker、HostedInputQueue；随后 root 单独授权修改 ServerCoreWorker 和新增纯测试。

- 确认 SERVER_MEDIA 的固定 streamHostId/初始 owner 与动态管理者分离；P1/P2 都上送输入，P1 重占走接收器与管理者更新，旁观不绑定原主持 Connection。此为源码路径核查，不是断线/多人实机验收。
- 已向 root 报告：配置菜单仍用客户端 runtime 判断可用，可能阻断服务器有核心但客户端无 runtime 的首次配置；客户端音画接收器 15 秒无首帧退席，与服务器核心 60 秒启动期限不一致。root 接手修复，本文不预先宣称最终修复或验收通过。
- 发现 ServerCoreWorker 原全房间快照 FIFO 每模拟帧只取 1 项；2/4 人各40变化/秒会超过60项/秒总消费能力。改为4手柄与光枪各自最多128边沿，每帧并行消费各流下一边沿，跨玩家不再互相排队。同口短按先后保持；多口快照先全部检查容量再入队；满任一口拒整组。release 单口清队列/held/offered，P2 release 同时清光枪；reset/clear/close 清全部，保持原 owner 线程与媒体生命周期。
- 保留 server_core39 同时新增的 offerFrameInput 入口并使用相同整组容量检查，没有改 Registry/Handle/factory。原第一次大块补丁因该新入口导致上下文不匹配而安全失败，没有覆盖其他代理的代码。
- 新增 `src/test/java/cn/piq/fcarcade/server/hosted/ServerCoreInputPorts39Test.java` 9项：2/4人各40边沿/秒、60模拟秒无累计队列；每口128容量/短按；整组拒绝无前缀；精确松键、清空和真实 owner stub 同帧多口、P2光枪释放、reset不复活。
- 独立 Java21 编译 Worker/当前Handle/新旧Worker测试，其他原20项生产依赖使用 core 代理先前隔离classes与冻结FC38；JUnit 29/29，0失败/跳过。classes与参数文件：`C:/Users/13498/AppData/Local/Temp/piq-server-ports39-v1`。Worker 测试前后 SHA256 均 `BAA98C1A13C07926F025534015C3E688A2C2F68DC987A809C00725F55D677834`；新测试 SHA256 `FDE548BA0D4C5ACA32DF9D38AE2822A53215A534E9B69E17A4ABC6E6D7AFA3BB`。最终整包测试需加入新测试类；旧 ServerHosted39Tests 只列原4类。
- 没有启动 Minecraft、服务器、用户ROM、WASM或native核心；stub owner 测试不等于实际模拟器性能测试。

## 旧 LEGACY 默认快捷键实时绑定迁移补充

root 复核实际升级诉求后，补充而非扩大无条件迁移：`KeyboardConfigStore.load(gameDir, rawLegacy, rawExtras)` 允许 facade 传入实际未重映射的游戏按键、别名和附加功能键。仅 schema1/2、可识别的旧默认快捷键、没有CUSTOM profile，且每个LEGACY profile原始映射完整、N未被占用，才在内存中改为N。无映射的纯 store.load/decode 仍保守保留LEGACY，不能凭静态猜测迁移。schema3显式Z、非默认快捷键以及任何CUSTOM profile仍原样保留。

`KeyboardInput` 的首次读取与设置快照均使用实时绑定 overload；注册旧映射的profile读实际KeyMapping，未注册映射沿用现有固定legacy默认，当前owner有独立supplier时设置快照也包含该supplier。N被主键、别名或Reset/Mute等附加键使用时保留原锁键，并在设置及设备状态提示原因；不改Minecraft绑定。仍仅显式保存写version3，并保留revision/原子写保护。

新增7项到 `KeyboardDefaults39Test`：原始Z/X别名安全迁移、N主键/别名、N附加功能阻止迁移、缺实际绑定、schema3显式Z/非默认快捷键、CUSTOM保留、只读迁移与显式保存。`build/controls-names39-source-v3/report.json`：6套90项JUnit通过，0失败/跳过，10语言910键校验通过。另用实际MC/NeoForge依赖独立编译 KeyboardConfig、KeyboardConfigStore、KeyboardInput 成功；这一编译不是客户端GUI/实体实际按键测试。

模式只读核查时：普通机柜保存值优先，无配置才按服务器托管可用、SFC本地同步可用、玩家媒体顺序选择；进入房间再拒绝被禁用/缺核心的路线，不静默切换已选模式。家用默认LOCAL；当时SFC家用mask为LOCAL加可用SERVER，未虚启MEDIA。已提醒root Native界面“仅kof97/mslug2、最多两席”说明应只针对LOCAL，且SFC家用是否应遵循allowLocalSync需明确配置作用域。FC服务端家用/旧机柜接线由另一代理继续，不得根据本审计声称所有机型三种模式已支持。

## 共享限额的失败启动收尾修复

时间：2026-09-14，root 追加授权，仅改 `ServerCoreRegistry.java` 与 `ServerCoreRegistryTest.java`。

只读限额审计确认 `HostedServerLimits.Pool` 的并发 acquire、幂等 close 和各收件人字节收费使用同一pool；root已把普通机柜、FC家用、SFC家用接入。另发现 factory 已返回真实handle而后续capability或停服generation校验失败时，Registry 原来立即抛回外层，使外层赋值仍为null并过早关闭共享lease，尽管 Registry 的后端ACTIVE还记录真实core。

Registry失败路径现在将未返回handle保留在ACTIVE，再signal close并在异步调用线程无锁等待exact isTerminated后才让原始异常返回；close/termination查询异常不替换原始失败，未知终止不归还名额，线程中断不会提前结束等待而在确认终止后恢复中断状态。保持成功打开路径及注册/后端上限语义，不用超时冒充真实终止。

旧停服竞态测试更新为在handle未终止时future不能完成；新增2项覆盖capability mismatch、close抛异常仍保留外层finally/名额，以及interrupt不能提前释放。隔离 Java21/JUnit：原20项+ports9项+Registry2项，共31/31，0失败/跳过；使用上述 `piq-server-ports39-v1` 隔离classes与实际缓存依赖，未运行真实核心。Registry SHA256 `9A493A350F2984F11DD4346543FDA32CD60964D13FBD7780CC555973DF9FA904`，RegistryTest SHA256 `261988E2D5872654BAC6931C80B337CBAA7910874525622334A5986A7BAB4B83`。

尚向root报告但未自行修改：共享字节桶在普通机柜固定room/P1→P4顺序收费可能使后续玩家在配额紧张时持续饥饿，需公平轮转/调度或明确降级；这不是未限流或无界队列。root负责公共限额/调度收尾与最终整包验证。

## 同步39独立冻结脚本（尚未执行真实打包）

新增 `tools/build_sync39.py`，不调用旧build_audit34的版本常量或Gradle流程；目标FC39/Native14/SFC23（两modId，core7）/GBA7。入口依次为 `--capture-inputs NEW.json`（真实Gradle前的源码SHA见证）、`--report NEW.json`（不编译不打包预检）、显式 `--freeze --source-witness PATH --source-witness-sha256 SHA`（默认新建build/review-sync39-v1）。root负责执行真实Gradle `check jar --rerun-tasks`，脚本要求JUnit XML时间晚于见证、0失败/错误、基线最低测试数及原有跳过上限。

脚本固定release38四包与其source-witness SHA；所有非自有class资源保持冻结包原始字节，唯十个精确lang、版本与显示元数据允许更新。当前编译JAR的自有class必须逐字节匹配build/classes/java/main、有真实Java源码归属且不比对应源码旧；SFC两薄包只共享metadata/manifest并合并两modId；GBA只在显式freeze时使用实际Java21及缓存MC/NeoForge依赖重新javac，不复制旧生产classes，不重建helper。第三方runtime classes同样留原包，不任意替换。

已知源码/编译资源与38冻结资源差异逐项记录但保留baseline；原38 source-witness未变的历史源文件即便原包已省略仍保持省略。新资源或相较38源码出现非授权resources改动阻断，无任何写回src/旧包。最终核验源码前后SHA、编译包SHA、测试XMLSHA、重复class/modId、依赖版本、零ROM/BIOS、输出JAR逐entry及SHA回读。原发布、安装、服务器目录均不在写入路径。

输出附source-witness/build-witness与英文简要限制；root编写的 `design/测试说明-同步与N锁定-20260914.md` 也被纳入见证并逐字节复制到新交付文件夹。限制明确：未实现家用MEDIA、旧FC街机LOCAL-only、GBA手持local-only/无通讯线，不凭注册factory宣称所有物理形态三模式齐全。

`tools/test_build_sync39.py` 使用纯合成bytes，未调用脚本main/capture/preflight/freeze/Gradle/javac：15项工具函数测试通过。首次运行有一项Windows ZipInfo会标准化反斜杠的测试失败；随后为JAR写入器也加入严格原始entry名验证，重跑15/15，未降低读入验证。AST语法检查通过。到本记录时尚未生成任何真实新交付JAR，根脚本执行需root确认冻结后进行。
