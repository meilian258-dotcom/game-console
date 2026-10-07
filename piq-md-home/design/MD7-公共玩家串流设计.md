# MD 公共玩家串流与双手柄接入（开发候选）

2026-10-02；本文件记录本轮实现设计和自动验证边界，不是 Minecraft 多客户端实测证明。

## 范围与复用

- 接通玩家主持的公开音画串流、两个实体控制器、附近自动旁观及服务器个人/卡带存档；保留明确的私人单人路径和旧本机档。
- 不开放本地同步、服务器托管或 Netplay：Genesis Plus GX 的旧回滚门禁未通过，音画串流不要求参与者运行确定性核心。
- 硬件仍用 `HomeSystems` / `HomeApplianceService` / `ExternalHomeConsoleBlockEntity`；控制继续用 `ControllerCapture`、`KeyboardInput`、`GamepadInput`、`InputOwnership`、现有双手姿势及线材渲染。
- ROM 仅传给主持，复用 `ContentCards.play` 与 `ContentCardClient.registerRuntime`，不另造下载器。其他操作端只传受租约授权的输入并接收媒体，旁观无输入/ROM权限。
- 旁观复用 `WatchProvider` / `WatchClient`；编码复用 `WatchMediaStream`，所有发送走 `CabinetMediaSender` 的同一物理连接窗口。2P 使用 provider 的 `controlRecipients` / `relayControls`，不借旁观令牌授予输入。
- 保存通道复用 `NetplaySaveServer` / `NetplaySaveClient` 的有界传输和确认机制；这只是保存传输复用，不伪造 Netplay 房间或宣称 Netplay 已适配。保存身份、槽位、选择和管理由 `MdPublicSaves` / `MdSaveCatalog` 接入，旧私人档不迁移。

## 权威与生命周期

服务端会话绑定实际服务器、精确玩家连接、主机/电视/link硬件身份、卡UUID/内容/保存模式快照、递增会话编号与随机主持票据。主持和端口操作者独立；归还/超距只释放对应手柄和输入，不停止仍有效的主持。主持离服/跨维度、电视关闭、拆线/拆机安全停止，不虚构无缝接管。

开机一次选择是否允许第二端口，受卡人数限制；不保存无需选择槽。借手柄与开机独立，预先借出的两只手柄在会话建立后按许可加入；另一玩家不能覆盖主持或已有端口。输入逐连接/会话/租约/单调序号/尺寸/速率复核，失焦和撤权清零；双方键位仍可配置。

核心所有 load/run/input/serialize/SRAM/restore/close 调用保持单一拥有线程；UI/网络回调不执行核心或磁盘IO。公开会话运行不因主持临时收起手柄而暂停，私人路径保持原规则。公开与私人媒体/保存绝不混流。

正常结束保留最终存档授权直到确认或有界超时；不能把请求发送当保存成功。保存失败保留原好档并提示。复制同卡/个人槽的并发写入由归属锁拒绝。

## 调研记录

已阅读共同 AGENTS、通用机器标准、SFC 蓝本和实际 SFC 公共播放/旁观实现。2026-10-02 查阅 Libretro 官方 Genesis Plus GX 文档与输入API（https://docs.libretro.com/library/genesis_plus_gx/ 、https://docs.libretro.com/development/input-api/），核对普通 Mega Drive 卡带、端口、保存和许可。沿用现有固定 GX SHA、两个 513 端口、原输入转换及 44.1→48 kHz 音频；本轮不换核心/放宽ROM校验。上游 Netplay 标记不替代本模组回滚测试。

## 验收与边界

须验证：P1/P2身份与短按、开局允许/拒绝、借还/6格/死亡/离服/菜单/失焦、输入重放拒绝、旧代次媒体丢弃、附近晚到/远离/返回、主机/电视独立断开、关机保存/重开/手动/检查点、同卡并发、旧私有档保留。专服启动、自动单元和真实核心探针不等于双客户端真人验收。

当前状态：MD7 与共用设备设置 SPI 的生产代码已冻结；`test jar` 成功，50 项 JUnit 测试零失败，另有 10 项模型回归通过。新增的实际行为检查覆盖公开/私人内容令牌路由、精确连接/端口/租约与序号限速、真实网络编解码、共享媒体编码→MD 控制媒体载荷→2P 解码/PCM、晚加入旁观全帧，以及卡带原地改 UUID/存档模式/人数、换副本、数量变化即撤销会话。保存模块的 11 项回归包含三槽归属锁、坏档/磁盘失败、旧档保留及管理撤销。32 种机身状态分开呈现两只借出手柄。

最终开发产物为 `build/libs/game_console_md-0.1.0-alpha.7.jar`，SHA256 `c7f6430515eef4a48aba9f49b1f28f3e68f738beca61701f9d67582ec274f3a7`。真实核心的精确冻结包重验和配套主包/专服启动收据汇总在[本轮交付指南](MD公开会话与服务器存档-20261002.md)；不能用前一构建的探针替代最终包收据。

待验：Minecraft 双客户端实体操作/视觉、实际两人短按与焦点切换、旁观近远返回、保护插件、主持失效和长时真实游戏。不将自动测试或核心探针写成真人验收/稳定版。
