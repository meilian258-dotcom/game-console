# Netplay 附属接入接口（FC69/70 实验）

> 历史接口笔记，保留当时版本的限制与协议说明。后续四人、投币及原生协议已有变化，文中“两人 / 免费模式 / PNP3”等不能视为 FC76.15 当前上限。新接入先读[功能行为与配置规范](方块电玩功能行为与配置规范-v1.md)及[SFC附属蓝本](../../piq-sfc-home/design/以SFC为蓝本-附属制作说明.md)，按目标版本源码、能力声明与测试确定支持范围；本页不是稳定 SDK 合约。[制作流程规范](方块电玩模组制作规范-v1.md)作为辅助材料。

## FC73 / SFC39 补记：只读 Netplay 旁观

共用 `WatchProvider.netplay(player, source)` 可返回 `WatchNetplay.Offer`，将现有附近观看租约绑定到原 Netplay relay。默认返回 null，旧附属与媒体观看不变。主包双方使用 `watch-2`；不是改 FC 主协议或原生 PNP3。

`WatchNetplay` 只用 `grantObserver`，每局最多7个只读远端，留一个远端名额给P2；按精确 ticket 撤销，不能以旧旁观 cleanup 撤销已升级的控制票据。各生产会话续期必须并入 `WatchNetplay.connections(server,sourceUUID)`，仍由 `WatchService` 校验范围、真实连接、设备身份与租约。不得用旁观身份获取手柄、编辑/上传/保存权限。

客户端通过 `NetplayWatchContent.register(provider, preparationFactory)` 接入：主线程捕获可信核心工厂和当前连接；后台只做有界内容读取/下载与哈希验证，回到主线程复核当前 grant/连接才创建 `Grant(host=false,player=false)` 并 bind/start。`Preparation.cancel` 必须取消当前下载，迟到任务不得跨连接生效。通用街机复用 `CabinetSharedGames` 当前机柜只读授权，SFC只允许下载当前公开会话的确切ROM哈希。用户进入控制会话前调用 `WatchClient.controlStarting()`，不能覆盖同 wire 的旧观察进程而不清理。

当前观察播放器限已验证48kHz立体声的软件渲染核心，处理空视频帧与最后一帧保留，不取得 `InputOwnership`。不是任意核心都已适配。SFC原LOCAL_SYNC旁观继续原有快照流程，Netplay用新流程，两者互斥；`/sfc-watch local`覆盖这两种公开本地旁观。FC自身旁观/光枪路径没有迁入此API。

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
