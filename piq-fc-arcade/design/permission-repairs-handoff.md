# FC 权限与存档保护修复交接（2026-09-13）

负责人：`/root/fix_sfc_av`。本文件供 root 统一记入维护手册、构建及最终验包；不是安装或发布记录。

## 修复范围

1. **FCA-01：旧 NES 加入／重开及存档操作。** `Manager.legacyTransaction` 在任何受保护操作之前保存实际玩家、连接、世界、被点位置及机台锚点的状态／BE／已有硬件 UUID、ROM、当前会话和 epoch。两个端点各自经过真实 `RightClickBlock` 权限回调，最后再次检验上述事实；回调拒绝、异常、替换对象或重入都不能提交。加入、继续／重新开始选择、个人槽操作、管理员删除存档走同一门禁。不新增协议或静默关闭旧 NES 功能。
2. **FCA-02：个人槽迁移。** `PlayerSaveSlots.migrateLegacy` 必须显式接收活动目标槽谓词，目标未落盘但正在运行时也视为占用；提交前再查源活动状态、目标租用及磁盘占用。旧危险四参数重载已删除（该类为包内实现），所有运行调用都传入 `personalSaveKeyActive`。旧文件格式、个人／机器键及有效迁移行为不变。
3. **FCA-03：AV 两端权限。** 选择第一端不再充当第二次点击时的权限。连接及手动断开在提交前检查当前玩家、原物品对象和完整组件、两端当前对象／身份／状态／线 ID，以及当前双端 `RightClickBlock` 许可。端点被替换、线被换手或回调重入均不扣物／不连线。生命周期清理不经过玩家门禁，仍按原路径执行。

## 精确生产文件与 class 范围

修改：

- `src/main/java/cn/piq/fcarcade/server/ServerArcadeSessions.java`：行为变更 stems 为 `cn/piq/fcarcade/server/ServerArcadeSessions`、`ServerArcadeSessions$Manager`。其他已有 nested 类型如有行号／调试属性变化，应保护其旧方法行为，不能放宽整个 server 目录。
- `src/main/java/cn/piq/fcarcade/server/PlayerSaveSlots.java`：`cn/piq/fcarcade/server/PlayerSaveSlots`。
- `src/main/java/cn/piq/fcarcade/home/HomeHardware.java`：`cn/piq/fcarcade/home/HomeHardware`。

新增：

- `src/main/java/cn/piq/fcarcade/server/InteractionTransaction.java`：`cn/piq/fcarcade/server/InteractionTransaction`，无 nested class。

没有修改网络、资源、版本、模拟器核心、输入或音频；没有删除生产 class。

## 测试与独立工具

新增 `InteractionTransactionTest`（11）、`PlayerSaveMigrationSafetyTest`（7）、`HomeAvPermissionTransactionTest`（5）。既有 `PlayerSaveSlotsTest` 两个离线迁移调用仅增加明确的 `key -> false` 参数；既有两槽测试及七个 `HomeLinkLedgerTest` 也一起运行。

新增工具：

- `tools/check_fc_permission_repairs.py`
- `tools/qa/FcPermissionRepairRunner.java`
- `tools/qa/FcPermissionRepairWiringProbe.java`

最新源预检：`design/permission-repairs-source-v2.json`，SHA-256 `014B4D63F901E169275BA629E80ED8C8762D7E35E5F6B953B670E06A1CBEF50E`。**32 项真实 JUnit 测试通过，47 项真实编译 class 接线断言通过**。只定向编译四个授权生产源并链接真实 Minecraft／NeoForge API；未运行 Gradle 或启动 Minecraft。报告的 `production_compiled=true` 明确表示本轮是源预检，不冒充最终 JAR。

迁移回归实际使用临时目录中的合成存档，检查源字节与 mtime 保留、活动空槽、回调期间新占用、正常迁移及原四遗留档场景。旧冻结 FC33 缺陷复现保留于 `outputs/mod-audit-20260913/fc/migration-diagnostic.json`，没有覆盖旧证据。权限测试使用可控回调，AV 测试执行真实 `HomeLinkLedger`；ASM 另核对实际生产入口、事实重验及权限事件接线。

最终 JAR 复验命令（由 root 提供新冻结包，**不传 `--source`**）：

```powershell
& 'C:/Users/13498/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe' tools/check_fc_permission_repairs.py --fc '<最终 FC JAR>' --report '<新的 final 报告路径>'
```

工作目录 `piq-fc-arcade`。工具最终模式只编 QA；报告 `mode=final-jar-only`、`production_compiled=false`，并绑定给定 JAR 的准确路径／SHA。已有报告不覆盖。

## 边界与后续

- 已完成授权源／定向测试，可以由 root 统一全量构建。本组件生产和测试现冻结。
- 未启动实际 Minecraft 世界／领地插件，未实际派发世界事件；不能以 ASM 和纯回调测试代替实服兼容验证。
- 手动 AV 断线需要另一端已加载且当前有权限；不会为此强加载区块。区块卸载／设备移除等原有生命周期清理保持独立，玩家回收操作在两端可核验后进行。
- 原始不含 BE 的旧块没有可保存的硬件 UUID；对其保护的是原块状态、结构和精确当前位置事实，不能声称新增了世界持久身份格式。
- 此次未读取／修改用户 ROM 或存档，未安装、上传、发布或改变冻结 FC33 成品。根维护手册由 root 统一留痕。
