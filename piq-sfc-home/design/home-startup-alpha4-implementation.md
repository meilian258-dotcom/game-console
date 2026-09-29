# SFC 家用手柄与启动状态：alpha4 交接

修改者：`sfc_cabinet_provider`，2026-09-10。维护手册总记录、完整构建、成品审计和安装由根代理统一执行。本文件只记录本代理负责的代码边界，不代替成品发布结论。

## 交互与人数策略

- 空手右键主机领取 P1 手柄后自动启动，不再要求第二次右键才能开机。
- 手持已借用手柄普通右键归还；Shift + 右键仍然兼容。P1 归还结束本局，P2 归还只退出自己。
- 明确配置为单人的卡带：P1 即开，不开放 P2 领取。
- 明确配置为双人的卡带：保留 P1 租约并提示等待 P2；P2 领取后自动进入两客户端准备流程。
- 没有人数元数据的旧卡：无 P2 租约时仍可单人立即开始，不因新增人数选项永久等待 P2；已有 P2 时按双人开始。
- 不支持开局后无损中途加入 P2。运行时新领取会明确提示先结束本局再按双人方式开始，不自动重启丢进度。
- 同一玩家同一服务端 tick 内共享交互门禁，覆盖 useOn/use 重复事件、刚领取又被归还、刚归还又被旧空手包领取。不同玩家互不阻塞。

## 保持同步与权限

原有连接、设备 UUID、双方端点事件授权、玩家位置、手持租约和客户端 Ready 校验保留。服务器仅等待实际参加本局的端口，不硬性要求两个客户端。

运行中 P2 离开：删除其服务端未来输入队列和 ready/port 引用，仅向 P2 下发停止；P1 保留当前帧号、时钟和已排好的权威帧继续执行。已发送给 P1 的历史帧不回滚。

准备期 P2 离开：旧 barrier 取消，此时尚未开始权威游戏帧；明确双人卡的 P1 租约继续等待重新领取 P2，其他情况明确提示 P1 归还后重领。不会静默重启正在玩的游戏。

## 启动阶段与真实成本

客户端按实际工作报告：缓存查询、下载、缓存校验、手柄/电视数据同步、上一核心退出、WASM 构造、ROM 加载、初始状态校验、声音初始化、本机 Ready、游戏开始。进入阶段时立即提示，长操作约每两秒更新阶段/总耗时；READY 不重复覆盖服务器的等待另一客户端提示。

WASM 仍只在专属线程构造和关闭。旧核心正在退出时等待真实 `SfcCoreLease` 释放，而不是误报永久占用；关闭 UI/会话不提前释放实际核心生命周期锁。仅将加载器私有 ROM 字节数组的所有权移交给播放器，去掉主线程的额外大数组复制，未改变 ROM、模拟核心、复位或状态哈希算法。

`startup-timing-20260910-v1.json` 已由根代理运行真实冻结 SFC alpha6 核心，使用项目原创 32 KiB 65816 诊断 ROM，不使用商业 ROM。一个新 JVM 内连续构造两次：

| 阶段 | 第一次 | 同 JVM 第二次 |
| --- | ---: | ---: |
| 核心构造 | 863.887 ms | 554.348 ms |
| ROM 加载 | 2.137 ms | 1.560 ms |
| reset | 1.457 ms | 1.492 ms |
| 初始状态哈希 | 15.031 ms | 0.722 ms |
| 探针总计 | 906.922 ms | 578.491 ms |

首次哈希也包含 Java 初始化/JIT 成本，不能宣称各阶段均恒定为几毫秒。两次初始状态 SHA-256 相同，输出 256×224、60.0988 fps。此数据不含 Minecraft 渲染、音频设备、网络下载和双端 Ready barrier，不是完整游戏内启动耗时；本次没有消除模拟核心冷启动成本，不能宣称“秒开”。主要体验修复是领取即开、正确阶段提示、旧核心退出等待，以及避免主线程复制 ROM。

## 生命周期与键盘

缓存后台 IO 使用单 worker、容量 4 的有界队列；迟到回调同时验证会话对象和 Minecraft connection 身份。会话关闭释放自己持有的 `CabinetClientOwner`，不释放其他机器的 owner。核心准备时尚未收到首批权威帧，不发送普通操作输入。失焦/关闭继续走原输入清零逻辑。

共享机柜 owner 防止 SFC 家用与通用机柜同时采键；对于没有接入该 owner 的旧 FC/NES 控制流程，使用公开 `ClientArcadeEvents.isControlling()`：接收 SFC 会话前拒绝，tick 启动/采键前退出 SFC，`acceptsInput()` 同时阻断两个 tick 之间的按键回调。没有改旧 FC/NES 控制内部。

## 本代理生产边界

修改：

- `server/SfcHomeServer.java`（`$1`、`$State`、`$Lease`、`$Session`）
- `client/SfcHomeClient.java`（`$Setup`）
- `client/SfcPlayback.java`（`$Picture`）

新增：

- `server/SfcHomeStartPolicy.java`（`$Plan`、`$InteractionGate`）
- `client/SfcStartupProgress.java`（`$Stage`）

没有修改旧模拟核心、资源/模型、`SfcInputTimeline`、编辑器/卡带数据或网络 record；写卡人数/封面由另一个代理负责，调用其 `maxPlayers` 和 `hasExplicitPlayerCount`。

## 验证与限制

- `tools/check_sfc_home_startup.py`：21 项纯 Java JUnit 测试通过，报告 `home-startup-alpha4-qa-v2.json`；覆盖人数策略、同 tick 门禁、P2 时间线清零、阶段计时、旧 FC 控制排除及生命周期源码接线契约。v1 的 20 项旧报告保留。
- 根代理已报告加旧 FC 门禁前的 SFC 完整 `compileJava`、`check`、`jar` 通过；门禁补丁交根代理重新统一构建。本代理未独立执行 Gradle。
- `tools/run_sfc_startup_core_probe.py`：上述真实核心计时探针通过；仅编译探针和项目原创诊断 ROM fixture，不重编生产核心。
- FC 工程 `tools/probes/Alpha17SfcStartupProbe.java` 提供最终 SFC JAR-only 探针，检查 5 个实际生产类的 protectionDomain 来源。已对一次本地候选 JAR 试跑 7121 条启动策略/门禁/阶段时钟断言通过；最终独立验包器须针对根代理最终冻结 JAR 重新执行。该探针不编译生产源码，不启动 Minecraft 或 native core。
- 尚未实际运行 Minecraft 双客户端、对等网络断线/重连场景或商业 SFC 游戏。纯策略测试和源码契约不是完整游戏实测，需在用户实例中验收一次领取即开、明确双人等待/开始、P2 归还后 P1 持续运行。

当前状态：生产交根代理冻结和独立验包；如根代理指出编译/审计失败，仅定点修复，不自行打包安装。
