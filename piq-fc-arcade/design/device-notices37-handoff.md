# FC37 设备提示清理交接

修改者：furniture_render 子代理（根任务统一维护手册留痕），2026-09-13。

## 范围

- FC、SFC、Native、GBA 常规开关机/领取归还/启动阶段/按键方案提示不再占用 actionbar/chat。
- 唯一物理设备瞄准提示为电源和重置；电源按服务端同步的可视状态显示「开机」或「关机」。TV 多格占位、Subor 占位解析回锚点；FC 与 ExternalHomeConsole（SFC）分别调用公开继承 getter。
- 已知 AV/数据线选择、连接、断开和家具操作教程提示精确隐藏；真实连接/家具错误保留短提示与诊断。
- 显式 ROM 传输的确认/百分比以及需要用户处理的邀请、拒绝结果保留；自动核心启动/同步追赶/修复阶段说明静默。不修改任何网络 payload、输入授权、时钟、存档或模型。

## 新 API

`cn.piq.fcarcade.client.ui.DeviceNotices`：

- `record(String device, String detail)`、`record(String device, String detail, Throwable failure)` 返回 `Entry`。
- `Entry(long id, Instant time, String device, String summary, String detail)`。
- `snapshot()` 返回最新在前、不可变、最多 32 条的列表；`last()` 可为 null；`clear()` 清空列表但保持 ID 单调。
- 相同诊断两秒内合并。JDK `System.Logger` 写完整上下文和原始 Throwable；GUI 内存中的 detail 最多 16KiB，多出部分明确提示查完整日志。无 MC 实例依赖，后台线程可安全记录。
- 摘要不暴露完整路径；严格区分 `neogeo.zip` BIOS 缺失和 `piqneogeo_libretro.dll` 运行库缺失。

`DeviceNoticesClient`：客户端自动 subscriber，单个独立 vanilla Toast 循环复用、限宽，指向「模组设置 → 运行环境」，不自动打开任何界面或下载文件。`message` 用于普通设备反馈，`workflow` 用于明确传输/邀请工作流；后者也过滤已知常规消息。

`DeviceNoticePolicy`：精确白名单（翻译键须本 mod 命名空间，字面量须完整匹配）；不知道的新键、新字符串、其它 mod 命名空间、玩家聊天、附加 sibling 的组合消息不会误删。少数带 P1/P2 的既有固定句式用锚定完整正则。已知本 mod 错误只从 overlay 转到短 Toast + 诊断；明确聊天命令的错误输出原样保留。

## 生产变更边界

- FC：HomeApplianceClient、ClientArcadeEvents、ClientArcadeSession、ClientRomTransfers、ClientSkinManager 的展示入口；CabinetClientBackends、CabinetSharedGames、CabinetJoinClient 的客户端展示入口；KeyboardInput 仅 notice() 的 display 调用移除，status 与冲突计算不变。
- FC ClientNesWorker、CabinetSyncWorker、Native NativeProcessSession、SFC SfcCabinetSession、GBA GbaProcessSession：仅异常 catch 增加完整 System.Logger，不改异常后的原状态转换或清理。
- NativeArcadeClient、GbaHandheldClient、SfcHomeClient：展示路由与原异常对象记录；关闭/释放网络操作的顺序和条件不变。
- SfcJoinClient、SfcRepairClient：只删除自动准备/修复提示或改工作流展示调用；GbaHandheldScreen 仅记忆路径失败时保留原 Throwable。
- 没改 piq-sfc-arcade 内部核心、服务端安全/协议、存档、输入映射/锁定、家具生产类或资源、旧模型或 PNG。

## 验证

- FC 定向 Gradle `test --tests cn.piq.fcarcade.client.ui.DeviceNoticesTest`：5/5 通过。第一次因原测试运行类路径未带 slf4j，改为无额外依赖的 JDK System.Logger 后通过；不需要修改构建依赖。
- 测试含摘要分类、严格白名单不吞未知/邀请/传输、异常完整堆栈、32 条边界与不可变快照、只有电源/重置悬浮。根任务全量构建复验最终补充的连线/家具断言。
- `tools/check_device_notices37.py --fc <冻结 jar> --report <新 JSON 路径>`：只编译独立 `DeviceNotices37Probe`，生产类必须来自给定 JAR；实际 Minecraft Component 与 NeoForge System 事件验证消费策略，无生产替身。最终报告由根任务给定冻结 JAR 后生成。
- 未启动 Minecraft、未实测 GPU Toast 绘制、实际瞄准判定、真实联机或 ROM。这里只声称代码/编译/定向事件验证，不声称已完成游戏内验收；未安装、未发布、未操作游戏实例或关机。
