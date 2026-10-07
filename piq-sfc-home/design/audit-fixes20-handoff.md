# SFC 审查修复技术记录 · 2026-09-13

状态：生产与 `src/test` 源码冻结阶段记录；完整构建、合包和最终 JAR 回归仍待完成。未运行 Minecraft，原模型与 WASM 不变。

## 已修两项

1. 旧专用 SFC 街机下载：先核实际 Connection、当前 owner/session、原 BE/维度/ROM、8 格、存活与非旁观、边界及保护事件；回调后再次核身份。未知摘要、旧连接、重复事务、速率超限在权限回调/IO 之前拒绝。正常授权只读取该会话的原文件名，安全路径、大小有界、完整 ROM 摘要再核，不再从下载请求扫描整库。
2. 家用修复完成：旧 Resume 帧只是最低目标；必须目标已达到且待执行权威帧不超过 6 帧才 Done。已经 dequeue 但尚未运行的批次也计入。服务器根据本局实际 history head，要求 `0 <= head - ack <= 120`，并继续核 token、phase、lease/成员等原门禁；过慢端被原失败关闭路径退出，Host 不停。初态、恢复后完整 state SHA 和核心规则不变。

下载使用独立单 daemon worker，最多 2 个读取/发送事务（每份仍受原 ROM 大小限制），30 秒事务期限。完成 IO 后/发送期间继续验证 session/connection/硬件权限；释放、断线、服务器停止取消该任务。繁忙、下载失败或权限撤销会明确结束这次旧机启动请求，用户可再右键重试，不静默留在下载中。不会向同 UUID 的新连接发送旧会话的关闭消息。

## 精确生产白名单

家用模块：

- 修改 `piq-sfc-home/src/main/java/cn/piq/sfchome/client/SfcPlayback.java`：`finishRepairFrame` 及其两个调用点，仅完成门禁和剩余帧计数。stem `cn/piq/sfchome/client/SfcPlayback`，既有 nested 随编译；没有新 nested。
- 新增 `piq-sfc-home/src/main/java/cn/piq/sfchome/client/SfcRepairProgress.java`：纯 6 帧完成策略。stem `cn/piq/sfchome/client/SfcRepairProgress`。
- 修改 `piq-sfc-home/src/main/java/cn/piq/sfchome/server/SfcRepairLedger.java`：新增 `MAX_ACTIVATION_LAG`，Repair 捕获所属 ledger，`done` 复验当前 head。stems `cn/piq/sfchome/server/SfcRepairLedger`、`SfcRepairLedger$Repair`；其余 nested 语义不变。
- 修改 `piq-sfc-home/src/main/java/cn/piq/sfchome/net/SfcRepairNetwork.java`：仅 registrar 改为 **`sfc-repair-2`**。payload 类型、字段、codec 均未改。完整帧值语义不同，所以新旧 repair 端必须拒绝混用，而非静默兼容。

旧街机模块：

- 修改 `piq-sfc-arcade/src/main/java/cn/piq/sfcarcade/server/SfcServerManager.java`：新三参 `requestDownload(player,sha,source)`，原二参保留；session 捕获 actual Connection/原 BE 和下载 gate；下载调度/取消/复验；旧 session 重连拒旧身份。stem `cn/piq/sfcarcade/server/SfcServerManager`，修改 nested `$Session`、`$OutgoingDownload`，其余既有 nested 保留。
- 新增 `piq-sfc-arcade/src/main/java/cn/piq/sfcarcade/server/SfcDownloadGate.java`：每 session 的纯身份/摘要/100 tick 重试/并发/回调重入门禁。stem `cn/piq/sfcarcade/server/SfcDownloadGate`。
- 修改 `piq-sfc-arcade/src/main/java/cn/piq/sfcarcade/net/SfcNetwork.java`：仅 DownloadRequest handler 在 enqueue 前捕获实际 source/player。旧协议 **`3` 保留**，没有改变旧下载包或正常 `.sfc/.smc` 传输格式。
- 修改 `piq-sfc-arcade/src/main/java/cn/piq/sfcarcade/rom/SfcRomRepository.java`：新增 `readNamedVerified(fileName,sha)` 精确文件有界读取，下载不调用 `find/list`。原目录维护/上传逻辑不改。

没有删除生产类、没有新增或修改 assets/data/WASM；没有修改 SfcHomeServer、SfcRepairClient、watch、模型渲染、存档格式或 FC 主项目生产。版本／依赖元数据变更须单独核对。

## 测试、工具与结果

- 新增 `SfcRepairProgressTest`，修改 `SfcRepairLedgerTest`、`SfcRepairWiringTest`：超过目标仍继续追帧、6/7 帧边界、包含取出批次、live head 的 120/121 边界、未来/旧 token 拒绝、强制新协议。
- 新增 `piq-sfc-arcade/src/test/java/cn/piq/sfcarcade/server/SfcDownloadGateSelfTest.java`；由原 `SfcRomRepositorySelfTest.main` 调用，因此独立旧模块 `check` 会覆盖，不增加 JUnit 或构建依赖。
- 新增 `piq-sfc-home/tools/check_sfc_audit_fixes.py` + `tools/qa/SfcRepairBacklogFixedProbe.java`。支持 `--production-source` 隔离直编；默认只编 probe、从最终 JAR 加载生产，并绑定 JAR path/SHA/探针围栏。

已执行：

```text
python piq-sfc-home/tools/check_sfc_audit_fixes.py --fc <冻结FC33> --sfc <冻结SFC19> --production-source --report piq-sfc-home/design/audit-fixes20-source-v2.json
python piq-sfc-home/tools/check_sfc_repair_workers.py --fc <冻结FC33> --sfc <冻结SFC19> --production-source --report piq-sfc-home/design/audit-fixes20-workers-source-v1.json
```

- 最新 v2：8 个修改/新增生产源在独立临时目录成功编译；**266** 真 WASM worker/ledger 诊断断言、**10,016** 下载 gate/真实单文件 IO 断言通过。10,000 次未知摘要权限回调 0、文件读取 0；正常授权读取和带 512B copier header 的 `.smc` 精确成功。
- 真积压：600 帧真实快照，旧目标 720，权威 head 920。现在 760 帧仍不 ACK；实际 **916** 才 ACK，服务器接受已追上 ACK、拒 720 和未来 921。旧失败结果不计为通过。
- 双 JVM 原回归：**134** 协调断言、**4,287** worker 断言、**672** 真实 codec 往返；Host 连续 **1,208** 帧未重启，修复期间前进 120 帧，恢复的 120 帧完整 RGBA/立体声 PCM 相等，下一周期完整状态相等；取消异常端不停止 Host；真实通用 SFC factory/openSync 也通过。
- 首次局部编译仅测试文件误引用了 package-private 原创 ROM 测试类，已改为自含合成下载数据；该次未运行核心，也未生成成功报告。之后编译/运行均成功，没有用修改生产来绕过测试。

以上均为 **production-source** 验证，明确不是最终成品证据；最终包就绪后执行同工具但移除 `--production-source`，再跑原双 JVM 回归。未执行全项目 Gradle。

## 未实机边界

真实 WASM/worker 和真实 Java 文件 IO 已执行；没有启动完整 Minecraft 客户端/服务器，没有真实权限插件、真人异机网络、声音设备或客户端 UI 验收。下载 session/world/protection API 已真实类型编译与只读路径核查，但测试中的纯 gate 不能冒充真实世界端到端授权测试。

保留原设计：后台 Host 与手柄分离；首次邀请短暂停表；SFC 本地恢复备份不自动加载；Host 不迁移。未把这些既定限制改成新功能。
