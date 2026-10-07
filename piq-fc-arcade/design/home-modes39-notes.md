# 家用机同步方式接入笔记

FC SERVER 已完成接入与定向编译，开放共有能力位；配置仍要求服主启用。

## 本轮范围

仅本地源码和测试产物；未安装、未部署、未启动或关闭用户 Minecraft/服务器。配置基础覆盖 FC 家用、FC 学习机、SFC 家用；此记录实际接通并验证 SFC SERVER_MEDIA。家庭 MEDIA 保持明确禁用，本轮不追加新的玩家 C2S 媒体授权路线。

## 配置入口与权限

- HomeEndpointBlockEntity.HomeSyncMode 按枚举名字持久化；只写 CONSOLE，缺失/未知字段默认 LOCAL_SYNC，不因策略临时禁用而改写默认偏好。
- 精确电源、重置和实体手柄按钮优先。双手空、非潜行右键主机机身打开原版 Button 设置页；潜行拆卡、持柄归还不变。电视不加菜单。/home-sync 也要求瞄准主机。
- home-sync-1 设置令牌绑定实际 Connection、维度、BE 实例、硬件 UUID、AV 配对及有效期；服务端重跑保护事件，并检查事件后物品/硬件/权限。配置需要管理员 OP2、整机空闲；不触发关机、退柄或存档写入。
- FC busy 包含待存档选择/开机意图；SFC busy 包含加载、运行、加入，以及关闭后的实际核心/存档收尾。空闲借柄不妨碍设置。
- allowLocalSync=false 会隐藏 LOCAL 可选位并拒绝 SFC 本地开机，偏好仍保留。FC 两处开机门禁由 server_core39 同步处理。SFC SERVER 需 serverHosting=true 和 factory 平台可用；暂时容量满只拒绝开机，不改支持位或偏好。未启用时界面说明配置原因。

## SFC 家用实际模式

| 模式 | 实际执行和网络 | 当前状态 |
| --- | --- | --- |
| LOCAL_SYNC（默认） | 已授权运行端本地运行同一核心，服务器有序输入、原快照入局/修复 | 保留原路径，尊重 allowLocalSync |
| MEDIA | 需要合法 host 上行媒体、独立校验/限流、控制成员接收与主持本地运行分支 | 明确不可选；旁观已有音画不等于控制端模式已实现 |
| SERVER_MEDIA | 服务端 SfcHostedWorker/ServerCoreRegistry 执行；主持与控制成员只接收音画，无本地 core/ROM 下载 | 已实接，默认需服主显式启用 |

SFC 家用仍是独立 SfcHomeServer Session/Host/physical Lease 系统；通用机柜 SFC 才复用 CabinetRooms。不把两套授权混用。

## SFC SERVER 生命周期与安全边界

- 开机保护终检后先预留 HostedServerLimits.Lease，再启动 ROM staging/core。启动中、运行中、关闭中共用全服额度；真实 core 终止和临时 staging 收尾后才释放。构造失败归还预留。ROM 从服务器库读取并重核规范化哈希，写到唯一临时子目录，不下载执行文件。
- 编码线程只有有界音画队列（8批），独立20fps图片和PCM48k；模拟器仍按实际50/60Hz运行。server tick 直接提交非阻塞 handle 的独立每端口FIFO，没有中间latest-state覆盖槽。普通按下/松开保序；强制释放只清该口，不清另一口边沿。
- 新 SfcHostedNetwork.Stream 仅服务端到已授权家庭成员，绑定 session/epoch/接收凭据/source/stream，连接仍由共同dispatch验证。不是Watch参与者禁令旁路，没有新增C2S媒体上行或额外ROM授权。
- 每个参与者副本发送前使用持有Lease的三参reserveMedia预扣按租约平分额度及全服带宽；音频/视频各自轮转参与者，避免固定先后饿死后排成员。CabinetMediaSender.sendPayloads 先为整批分片预留同一个物理Connection窗口。客户端有界24分片，旧/错凭据不进入解码；解压与音频在接收线程。
- 原Host审批仍必需；批准后接收端就绪、硬件/卡带/距离/租约再次确认，才接入控制口。服务端同一局不需要导出快照给P2，没有扩大参加者或公开旁观权限。
- SERVER旁观复用 WatchProvider.serverHosted 和 WatchService.relay，仍由WatchService检查来源活性、观察者权限、参与者隔离及预算；客户端不能上传服务器托管画面。
- 电源关闭只停运行，不收回物理手柄。归还/六格超距只清对应控制口，主机继续。重置经 supportsReset 检查后调用原核心 reset，清输入与接收PCM，保留Session和控制租约，不伪造重开/退柄。
- 当前保守限制：SFC家用开机主持离线、换连接、失去硬件权限仍按原hostValid安全停止。服务端计算不代表已支持主持离线后其它成员继续；管理授权模型保持不变。
- 新服务端存档位于世界内 piq-sfc-home/hosted-saves/server-hosted-v1/sfc/<开机人UUID>/<稳定主机UUID>/，由公共HostedSaveFile校验/锁定/写入SRAM。LOCAL原客户端恢复备份保留，不自动迁移或混同两套存档。服务端收尾失败会日志记录并提示原连接主持。

## 协议与验证

- SFC home registrar 5→6：Session增加模式和来源/流身份。保留旧Java构造函数供既有源码调用，但线上协议要求新版。新增home-hosted-1的Stream/Reset两种S2C payload。
- FC/SFC家用相关20个源/策略测试曾定向javac成功，只有现有NeoForge过时注解警告；未跑全量Gradle。
- tools/qa/SfcHomeHosted39Probe.java 实际运行SFC服务端worker、WASM、自制测试ROM、媒体codec及真实家用接收线程。测试双口画面效果、快按松、按口并行、单口强制释放、错误recipient/session/epoch/source/stream、重置/PCM清理、另一local-core租约不被占用或释放、真实终止后释放容量。
- 最终收尾后源码probe通过46断言：piq-sfc-home/build/home-hosted39-source-v3/report.json，同目录compile.log/run.log；报告记录精确源码SHA与借用前置编译目录。计数含接收到的媒体包codec检查，随实际收帧数略变；强制覆盖项不变。仍应重跑最终全模块编译测试。
- 重跑：piq-sfc-home/tools/run_home_hosted39_probe.ps1 -CoreClasses <公共核心类目录或JAR> -EvidenceName <新唯一目录名>。使用本轮已编译家用基础类及冻结38/22依赖，不是final-JAR-only证据。
- 未验证：Minecraft物理UI命中、保护模组回调、实际双客户端socket/断线、最终交付JAR类来源、声卡听感、真实游戏长期存档稳定性。probe不启动Minecraft、不开socket/声卡，不等同线上稳定性承诺。
- 家用链路probe未验证SFC SRAM跨重启回读或旧客户端备份迁移；自制ROM不代表真实游戏的SRAM行为。存档容器/原子写测试由公共核心代理单独汇总。

## 未完成项

家庭MEDIA本轮明确禁用。接通至少还需host本地core + 非host接收分支、带host token/epoch/Connection的受限C2S媒体入口和整帧验收/预算、入局快照分支调整、保存退出及断线回归；不能直接切旧FC ArcadeMode.STREAM 或借Watch观察者权限充当控制者权限。FC家用/学习机SERVER具体实现与回归由server_core39汇总。
