# FC alpha29：物理设备、后台控制与个人存档

仅本地源码和候选包 QA，不安装、不发布、不操作用户 ROM/存档。

## 实现

- 现有未修改 Zapper WASM 支持 `$4016` P1 八键与 `$4017` 光枪并存。新光枪局保留 P1 手柄、第二口专属光枪；同一玩家可持有两个独立租约，主要角色优先 P1。普通 P2 手柄不获得第二口输入。旧普通双人街机路径保留。
- 每个物理租约独立输入序号、清键、取还；归还 P1 不清枪，归还枪不清 P1，不重置或重载核心。旧连接、租约、epoch 与权限复核保留。
- FC 手柄依据主机中心距离 `<=6` 有效，`>6` 归还该手柄。非 Host 客户端同样用主机中心；后台 Host 不受本地电视视距影响。枪保持原输入距离，不能把手柄六格保证推广到枪。
- `IDLE/session=0` 物理凭据不具输入权；关机可拿柄，不生成模拟会话。运行中未批准也可持有原物。Host 首次 ready 后接入自己实际持有设备；其他人继续原 Host 审批。关机降回 IDLE，物品不销毁；拆机、卸载、物权失效、手柄超距才回收。支架关机取枪只删两处 running 门禁，原物/receipt/双端保护不变。
- FC BE 按端口同步 player/lease 视觉对；取还/转交更新。仅 updateTag 发送 UUID，磁盘保存不写 UUID，加载不恢复输入权。
- 按目录 `NONE/MACHINE/PLAYER` 恢复真实保存策略。NONE 不加载/写入；普通 MACHINE 键不变；PLAYER 属于开机 Host，复用原三槽及命名/恢复/重开/删除界面。后台 Host 与输入设备独立，未取柄也能持有个人存档。
- 家用个人槽使用一次性 UUID 包装：实际连接、console/TV/link、ROM、枪类型与 120 秒期限均复核；消费/取消后旧 token 拒绝，新开同机同 ROM 也不能复用旧 token。普通街机原 payload 布局保持。FC 网络 **34**，客户端与服务器须成套更新。
- 枪存档独立 core namespace，不展示/覆盖普通 NES 槽。枪 MACHINE 改为机器键；新键缺失时只读兼容旧 alpha28 当前 Host 枪机器键，旧 bytes/mtime/损坏文件均不改。普通槽键与存档编码未变。
- 个人槽活动锁双向覆盖 home/旧 FC，按活跃 Session.saveKey 判定；不能用未首次落盘绕过同槽互斥。`HomeConsoleRuntime.pendingSave(player,console)` 供按钮准确显示待选槽。

## 精确组件范围

旧类：`ServerArcadeSessions`（Manager/Session 等以编译差异为准）、`HomeControllerService`、`HomeControllerLedger`（Phase 新增 IDLE）、`HomeControllerInventory`、`HomeRuntimeAuthority`、`HomeConsoleBlockEntity`、`HomeConsoleRuntime`、`HomeZapperService`、`ClientArcadeSession`、`FcNetwork`、`ClientArcadeSupport`、`ClientArcadeEvents`（仅开家用槽页新方法）、`ArcadeSaveSlotsScreen`、`ArcadeSaveStore`。

新增：`home/HomeSaveIntent`、`ArcadeHomeSaveSlotsPayload`、`ArcadeHomeSaveActionPayload`、`ServerArcadeSessions$HomeSaveRequest`。

`ZapperStandService.interact` 仅删两处开机门禁；该文件其它 cable/loanPlayer 变更归动态线代理。未编辑 WASM、模拟器核心、ROM、模型、贴图、原存档编码、旧 frame/history/input payload 格式。其他修改范围另列。

## 验证

- `design/home29-existing-core-proof.json`：alpha28 原 JAR 的枪核心 178 断言，P1 八键与枪同时输入、保存后两核帧/PCM/RAM 一致，无生产编译。
- `tools/check_home29_pure.py`：源纯类 65 项已过；17 旧 Host、14 共存/距离、9 IDLE、5 token、20 旧物理回归。后续源码及最终 JAR 以最新报告为准。
- `tools/check_home_runtime29.py --fc <jar> --report <new.json>`：只编 tests/probes，加载指定 JAR；65 纯测、普通双人旧 worker 场景、新 P1+枪双 worker/快照连续、178 真核、真实 NeoForge 外层 codec/token、新建临时 Store/Manager 的 7 测试。CodeSource 绑定 JAR，不编生产。
- 无 Minecraft 世界/socket/实际物理多人运行，不把纯类、真实核心或 codec 测试称作游戏内端到端验收。
- 本交接在最终构建前编写；最终计数/hash 以最终冻结包的报告为准，旧包/报告不覆盖。
