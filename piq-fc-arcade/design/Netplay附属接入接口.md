# Netplay 附属接入接口

## FC76.36：逐源 JNI 多屏旁观（试验接口）

2026-10-03，配套 SFC44 / MD12；见[第一批实现与验证](公共JNI多屏旁观第一批-20261003.md)。本轮 `watch-3` 要求主包两端同升级，`content-card-6` 不改线格式。下面 FC76.35 的准备、激活、权限和保存合同继续生效，但只读下载的单请求限制由本节替代。

- `WatchProvider.isParticipant(server, source, player)` 和客户端 `DisplayAdapter.isParticipant(descriptor)` 必须检查这一台，而不是“玩家是否正在用任何同型机器”。旧重载保留作保守兼容。
- 操作开始调用 `WatchClient.controlStarting(wire)` 或精确 `WatchDescriptor` 重载，只退目标源；未迁移私人/旧链仍可用无参清全部入口，不能照抄到新公开链。
- 客户端每个来源独立租约、准备取消、心跳、纹理、音频及关闭回执；世界/连接/hostLease 改变不接受旧结果。只有真实 JNI 路径开放多源；MEDIA、独立进程 Netplay 仍为单源，不自动降级或混跑。
- `WatchClient.registerPendingControl(id, IntSupplier)` 在客户端 setup 注册尚未取得原生槽的控制准备数（0～4）；只读查询、不得在此加载核心。总预算包括正在关闭但尚未归还的实例，原生四槽门禁不变。`Available.capacity` 是可保留的旁观总数，不是空闲数，不可直接用空闲槽覆盖已有旁观。
- `NetplayWatchContent.Preparation` 的旧二/三参数构造保留；可选可信本地 `ProcessFactory` 仅用于保持 FC 原普通/光枪 JNI 协议身份，`open` 拒绝主持、玩家或非只读端口。不能接受网络传入类名、DLL 或工厂。常规适配沿用 48kHz 双声道；FC 44.1kHz 单声道只在输出呈现层转换。
- `ContentCardClient.expectDownload/cancelDownload` 现在维护最多四个只读 token，与普通播放 Runtime 分离。服务端 `ContentCards.downloadOnly` 独立只读表：每玩家四个、全局十六个，仍合计原 64MiB 在途预算，逐 GET/完成 ACK 检查原连接、请求、系统、位置及授权。取消一个不停止普通播放，也不取消另外三个。
- FC 内容继续复用原 hash 校验下载队列；SFC 用有界串行下载队列并允许同 hash 多读者，保留服务端限频。排队/下载成功不授予席位、输入或存档权。

这不是一人多主持或不受限多实例。四槽已满时新控制仍需等待释放后重试；无自动主持交接、低帧率预览或 Opus。

## FC76.35：公共 JNI 准备、激活及授权内容下载（试验接口）

2026-10-03。JNI Netplay 为适用模拟器机型的优先适配目标，见[通用制作规范 1.8](机器制作与交互标准.md)；不是把所有设备强制改为一种核心。以下是本轮公共接口，实际核心门禁与交付状态见 [MD 接入记录](../../piq-md-home/design/MD-JNI-Netplay-20261003.md)。不能把接口存在当作新机型已完成验收。旧 FC/SFC/街机调用未显式启用新选项时保持原有语义。

### 运行与权限

- 可信附属提供独立 `NetplayProfile` / `LibretroProfile`，固定所属模块、核心资源、SHA、选项、真实端口与输出采样率。`withJni` 才走共享 `JniNetplaySession`；不能把运行器 JNI 或 `NetplaySaveClient` 的名称当作联机模式。核心状态/SRAM/音频/动态画面元数据必须真实验证；不接受服务器指定 DLL 路径。
- 服务器权威输入可声明实际 1～4 个端口，未使用端口保持零。客户端的物理手柄租约仍由机型服务验证；只读模拟副本可以呈现其操作者的输入，但原生只读 ticket 本身不授予手柄或保存权。只有主持接收经过服务器复核的 `cabinetInput/cabinetRelease`；不能让旁观端自行变为操作者。
- 新增 `NetplayProcess(grant, content, sender, profile, auxiliary, cabinetAuthority, paid, waitForActivation)`。最后一项仅适用于 JNI 主持；旧构造默认 `false`。准备模式下 `ready()` 表示内容、原生核心和所选进度已准备好，尚未公开运行；上层须经过公共开局 `ready`、服务器写入授权，再对**原会话**调用 `activate()`。准备阶段不呈现游戏帧、不接收玩家操作、不周期/最终写档；取消只撤销准备。
- `NetplayNetwork.bind` 给主持安装已注册的公共 persistence factory，须在 `start` 前调用，不能再重复 `NetplaySaveClient.open`。`terminated()` 只在清理路径处理后完成；上层仍须独立等待服务器最终持久化结果，不把本机关闭或旧周期检查点当成最终保存成功。
- 客端下载、恢复种子期间也须保持输入归零；远端完成 `ready` 后由现有物理租约路径确认可操作，不能只检查主持全局已开机。取消、换连接、迟到 READY、旁观转操作者及旧 ticket 清理均按原连接/代次处理。

### 只取内容，不另开普通播放引擎

`content-card-6` 新增 `DOWNLOAD_ONLY=24`；沿既有授权、GET/DATA 分片、文件上限、完整 SHA 和适配器校验，不是新的任意文件接口。

1. MC 客户端主线程先调用 `ContentCardClient.expectDownload(system, requestUUID, pos, hash, size)`，捕获当前真实连接并得到 `CompletableFuture<byte[]>`。`size=0` 可用于只含哈希的旁观授权，但仍受机型大小上限约束。
2. 机型通过自身获准请求向服务端申请。服务端复核当前席位或只读观看授权，调用 `ContentCards.downloadOnly(player, system, requestUUID, pos, entry, stillAuthorized, completed)`。返回实际传输 UUID 或繁忙时的 `null`；后续 GET 和完成确认仍重新检查授权。
3. 工作线程只能读取/校验内容，不能接触世界或授予席位。完成回主线程复核请求、连接和取消状态后，future 才交出内容；`STARTED` 在此 lane 仅为**内容校验完成 ACK**，绝不是核心 READY。
4. `ContentCardClient.cancelDownload(requestUUID)` 可从 worker 请求取消；主线程调用会立即释放该请求槽位，旧回调不能清除后来的请求。FC76.35 历史版只接受一个内容下载；FC76.36 已按上节改成独立有界只读表，超限仍明确报繁忙，不悄悄覆盖。

附近 Netplay 旁观仍使用原 `WatchProvider.netplay`、`WatchNetplay` 和 `NetplayWatchContent.Preparation`。prepare 时核对传入连接，cancel 回收当前下载，读完内容不能绕过观看范围或自动取得写存档权。

### 共用设置页

`HomeSystems.ServerHooks.jniNetplaySettingsAvailable()` 默认 `false`；须同时声明支持 mask 的 bit 4（数值 16），附属实体实现持久化的 `netplayJniTrial`，且服主允许本地核心同步，才开放 JNI 选项。它与 bit 3 的 RetroArch 实验标志分开；未声明的旧附属不会自动出现 JNI 功能。

逐项禁用原因通过 `synchronizationUnavailableReason(level, console, int menuMode)` 返回。关机、没有待确认开局/保存、原操作权限仍需服务端复核。只支持某一联机方式不应假造其余方式；本机 opt-out、平台不支持、服务器禁用、未适配和当前忙碌须分别说明。

## 历史 FC69/70 记录

> 历史接口笔记，保留当时版本的限制与协议说明。后续四人、投币及原生协议已有变化，文中“两人 / 免费模式 / PNP3”等不能视为 FC76.15 当前上限。新接入先读[功能行为与配置规范](方块电玩功能行为与配置规范-v1.md)及[SFC附属蓝本](../../piq-sfc-home/design/以SFC为蓝本-附属制作说明.md)，按目标版本源码、能力声明与测试确定支持范围；本页不是稳定 SDK 合约。[制作流程规范](方块电玩模组制作规范-v1.md)作为辅助材料。

## FC73 / SFC39 补记：只读 Netplay 旁观

共用 `WatchProvider.netplay(player, source)` 可返回 `WatchNetplay.Offer`，将现有附近观看租约绑定到原 Netplay relay。默认返回 null，旧附属与媒体观看不变。该历史版主包双方使用 `watch-2`；FC76.36 已升级 `watch-3`，不是改 FC 主协议或原生 PNP3。

`WatchNetplay` 只用 `grantObserver`，每局最多7个只读远端，留一个远端名额给P2；按精确 ticket 撤销，不能以旧旁观 cleanup 撤销已升级的控制票据。各生产会话续期必须并入 `WatchNetplay.connections(server,sourceUUID)`，仍由 `WatchService` 校验范围、真实连接、设备身份与租约。不得用旁观身份获取手柄、编辑/上传/保存权限。

客户端通过 `NetplayWatchContent.register(provider, preparationFactory)` 接入：主线程捕获可信核心工厂和当前连接；后台只做有界内容读取/下载与哈希验证，回到主线程复核当前 grant/连接才创建 `Grant(host=false,player=false)` 并 bind/start。`Preparation.cancel` 必须取消当前下载，迟到任务不得跨连接生效。通用街机复用 `CabinetSharedGames` 当前机柜只读授权，SFC只允许下载当前公开会话的确切ROM哈希。用户进入控制会话前调用 `WatchClient.controlStarting()`，不能覆盖同 wire 的旧观察进程而不清理。

该历史观察播放器限已验证48kHz立体声的软件渲染核心，处理空视频帧与最后一帧保留，不取得 `InputOwnership`。不是任意核心都已适配。SFC原LOCAL_SYNC旁观继续原有快照流程，Netplay用新流程，两者互斥；`/sfc-watch local`覆盖这两种公开本地旁观。FC76.36 已将 FC 家用 JNI 普通/光枪旁观生命周期迁入公共 API，并保留 FC 既有核心构造和同步身份；FC 旧独立进程及 NES 机柜链不由此推定完成迁移。

## FC72 补记：光枪专用路径

共用 `NetplayProfile` record 构造和 `NetplayProcess` 既有构造、Frame、普通 input/inputRetroPad 接口不变；SFC38/街机013 不需要复制或替换桥。主包与客户端必须同为 FC72，子协议 netplay-2、内置桥 PNP3（每帧 NES/RetroPad mask 后新增 XY 和 flags）；不要单换旧 exe。

仅 `NetplayProfile.fcZapper()` 使用端口1设备257、端口2 Mesen Zapper262。光枪模式主持独占原生两口，其他原生端一律旁观，普通本机 input 调用不生效。只有主持接收服务器认证后的 `GunFrame`，按连接、会话、epoch、ticket 校验，再调用 `authoritativeGun(revision,sequence,buttons,aim)`；该消息仅 clientbound，禁止开放同名服务端入口。MC 物理枪/P1 授权仍由原服务负责，不能用“原生旁观”决定玩家是否可领取枪。禁止把用户鼠标/外部坐标直接注入主持以绕过服务器射线检查。

队列32项，合并相同按钮/扳机状态的连续移动、保留按键边缘。安全清除修订递增用于丢弃旧队列；超时750ms及溢出松开，并等待释放再重启输入。持枪者的 P1 菜单键只在实体 P1 空闲或持有正式 P1 租约时按既有规则路由。只对 FC 光枪启用，不改变 SFC/街机按键路径。完整主包/附属兼容及未验证项见 FC72 测试指南。新增主持只读 CRC 日志仅在已有快照捕获后观察，不更改哈希协议或掩盖失步。

FC70/街机0.1.3补记：共用公开构造与SFC38调用保持兼容；街机新增固定FBNeo的可信profile，不接受任意DLL。CabinetGameManifest最多5文件（游戏+4份声明BIOS），游戏库协议为cabinet-game-4，其他协议未变。同目录PGM/QSound允许共享但不得自动扫描/搜寻或下载；辅助文件仍受16 MiB每份边界。构建脚本修正GET_AUDIO_VIDEO_ENABLE：PIQ的null驱动实为有效IPC显示，正常帧应允许输出，重演阶段继续抑制。不要移除快照CRC校验以掩盖差异；FBNeo CRC差异及画面序列补充验证见outputs/netplay70，不能混同完整确定性证明。FBNeo单独非商业许可另列，不由主模组GPL覆盖。以下为FC69接口基线。

适用 FC 0.31.0-alpha.69，2026-09-24。不是通用稳定 ABI，不表示任意 libretro 核心都能联机。现有调用方：SFC38 家用机、街机0.1.2 通用机柜后端。真实 Minecraft 多客户端验收待完成。

## 共用边界

`cn.piq.fcarcade.netplay.NetplayProcess` 是唯一 RetroArch 进程/IPC 实现，附属不要复制其内部实现或自行打开公网房间。前端、Java 授权转发在主包，核心 DLL 从附属模块读取。

- `NetplayProfile` 只能由可信附属代码构造：资源所属 `Class`、绝对资源路径、固定 SHA-256、合法内容文件名、核心选项、设备 ID、采样率及 ROM 上限。不能从网络请求、外部清单或任意文件路径构造核心资源。`owner` 必须来自附属所属模块，不能换成 FC 类。
- `NetplayProcess(Grant, contentSupplier, sender, profile, auxiliarySupplier)` 在后台私有目录提取并校验；辅助文件最多 4 份、每份最多 16 MiB。不会恢复或持久化用户保存进度。许可与对应源码仍须逐核心核对。
- `Frame` 为 RGBA、动态尺寸/纵横比、立体声有符号 16 位 PCM（交错 L/R）及采样率，保留 FC 的 mono 字段。必须处理空视频的重复帧，不要因为音频帧没有新像素就清空画面。
- `inputRetroPad(mask)` 接 RetroPad 低 16 位；`input(nesMask)` 仅供旧 FC 位序转换。附属不要再次把 RetroPad 当成 NES 位序转换。
- `ready()` 只表示音画桥已有帧，不代表新客机已经完成网络状态恢复；街机中途加入可能需要数秒。不能据此宣称已经追上主持进度。

## MC 授权与生命周期

服务端由现有设备/席位/手柄权限决定谁能加入；客户端传来的角色不是授权依据。取得新 `NetplayNetwork.nextAddonId()` 后建立 `room`，按真实连接 `grant`、`authorize`；每 tick 用当前获准连接 `renew` 与 `prune`。归还/退出撤销，主持失效结束本局，服务器停止 `retire`。授权超过 1 秒未续期会拒绝新数据。

客户端在 MC 主线程捕获当前 `Connection` 和既有会话身份；异步读取内容后再次检查连接、会话、席位、generation，不能让迟到回调启动新世界的核心。启动前 `NetplayNetwork.bind(connection, process)`，发送用同一个连接的 `upstream`；关闭时必须 `unbind(connection, process)` 并关闭进程。使用实例条件解绑，避免旧回调删除新会话。

共用转发层限制片段、序号、队列、速率和角色；原生只能监听回环地址。不要通过直接 TCP 或自己发送控制包绕过服务器。新增核心需验证快照确定性、只读旁观不能夺席、P2 重进后的同局校验与退出后的松键，而非仅看到画面就判定联机通过。

## 通用街机入口

附属在共同端注册 `CabinetNetplay.register(backendId)`，客户端实现 `CabinetBackend.netplayUnavailableReason()` 与 `prepareNetplayFactory()`。工厂准备在主线程捕获目录；`open(Path)` 后台验证内容并返回 `NetplayContent`，不要在工厂内启动核心。共用 `CabinetNetplayEmulator` 负责绑定、原生启动、输入、音画和退出。

当前服务器限制已注册后端、最多两席和免费按键投币。设备设置保留原 `CabinetSyncMode` 枚举，以独立实验标志存储；不能改变旧 ordinal。协议为 FC45、CabinetRoom7、CabinetSync6。原独立原型柜不走此入口。

街机内容先经过既有 `CabinetSharedGames` 授权共享库；它可能把上传游戏同目录的 BIOS 列进文件清单，Netplay 没有改变此规则。成品不含 ROM/BIOS，用户须有权使用和分享文件。

## SFC 参考

`SfcNetplayProfile` 提供附属核心身份；`SfcHomeServer` 维护会话到 relay 的映射，`SfcHomeNetwork.NetplayStart` 发获准身份，`SfcPlayback` 绑定并驱动共用进程。SFC 协议11；NTSC、主持 P1、另一玩家 P2，不读写实验存档。旧模式和音画旁观独立保留。GBA 尚未接入此接口。

自动证据在 `outputs/netplay69`：真实多进程、生产 MC codec、资源模块边界及旧模式回归。只有实际 Minecraft 双机验收后才能扩大可用范围。
