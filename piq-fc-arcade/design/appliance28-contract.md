# alpha28 电源 / 显示 / 控制器分离

2026-09-11，用户明确同意制作。仅源码、验证与新测试包，不安装 HMCL，不改 ROM/用户存档/历史交付，不发布，不关机。

## 行为

- 电视状态独立：关机黑屏无声；开机无运行信号显示静音彩条；开机有信号显示当前局。音量 0–100，10 一档，默认 60，按电视同步；玩家主音量/方块音量和距离衰减仍有效。
- 主机实体电源开始后台运行，不发手柄、不抢输入。主机重置重新开始当前 ROM，清旧输入/枪扳机/旧加入事务，不从旧快照恢复。
- 右键实际停放手柄领取该端口；持柄右键主机机身归还，不停止局，不影响另一端口或旁观。
- 电视/主机电源关闭结束连接局并归还外设；电视开关不再从屏幕/机身普通右键启动游戏。电视关闭后主机运行状态同步停止。
- 运行宿主与输入租约分离：没有手柄占用仍运行，宿主仍在线；宿主掉线、硬件失效、卸载时安全结束，不实现无客户端运行或宿主迁移。
- 光枪支架使用用户原始素材，可放置、绑定 FC、领取/归还；只有一把真实枪，不复制。连接外设用于明确选择光枪核心，不在运行中静默切核。光枪使用硬件第二口，不能与普通 P2 同时占用。
- 沿用领地保护、精确连接和硬件 UUID、距离、服务器线程/回调复验及迟到帧拒绝。重置和关机影响整局，权限仍由设备保护控制。
- 旧世界 ID/卡带/AV 接线不迁移；新运行态默认停止，已有电视没有电源字段默认开启保持旧外观，音量使用默认。

## 分工 / 接口

- root：HomeApplianceService、HomeHardware 路由、HomeSystems API、电视状态/显示/音量、实体按钮点击/提示、版本和合包。
- fix_sfc_av：FC Host 与 Controller 解耦、HomeConsoleRuntime、HomeZapperService、FC 服务端/客户端与网络；不改 root 文件。
- sfc_cabinet_provider：SFC 同等状态分离、运行/控制协议、共享入口适配和测试。
- cabinet_reuse_review：光枪支架 Block/BE/Item/绑定/模型/线/唯一物品、Mod 注册；直接与 FC 代理协调枪服务，不改共享语言文件。

HomeSystems.ServerHooks 新增 default：
`boolean onPowerOn(ServerPlayer, Connection)`、`void onPowerOff(ServerLevel, ExternalHomeConsoleBlockEntity)`、`void onReset(ServerPlayer, Connection)`、`void onController(ServerPlayer, Connection, int)`、`boolean isRunning(ServerLevel, ExternalHomeConsoleBlockEntity)`。

FC `HomeConsoleRuntime` 由 FC 代理提供：
`boolean powerOn(ServerPlayer, HomeConsoleBlockEntity)`、`void powerOff(ServerLevel, HomeConsoleBlockEntity)`、`void reset(ServerPlayer, HomeConsoleBlockEntity)`、`void takeController(ServerPlayer, HomeConsoleBlockEntity, int)`、`boolean running(ServerLevel, HomeConsoleBlockEntity)`。

共享物理交互：`HomeApplianceService.interactConsole(ServerPlayer, BlockPos, BlockHitResult)`；外部主机点击区域使用 provider hook，参数本地逆朝向射线，具体 API 随同文档补充。

## 验证与交付

纯状态/点击几何/权限负例；实际最终类/codec；FC 与 SFC 宿主无控制、空席、两人归还/重入/重置/停机、旁观；光枪放置/连接/唯一物品；最终 JAR 回读与依赖/资源哈希检查。真实 Minecraft/真人网络/实体手柄没运行就不得宣称通过。
