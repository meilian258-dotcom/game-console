# SFC alpha19 同步技术记录（2026-09-12）

状态：生产与 JUnit 源已冻结；完整构建 SFC 333 项全过，FC 1363 项（7 skip）无失败。以下四个最终 JAR 探针全部通过。无安装、发布、游戏实例操作。

## 行为

- 家用仍由服务器分发按实际 PAL/NTSC 时序生成的逐帧输入；Host 与有权控制端各自运行原 SFC6 WASM。ROM、核心标识、FPS、初始状态检查仍保留。
- 每 600 个实际执行帧计算全状态 SHA-256（NTSC 约 10 秒，PAL 约 12 秒）。Host 保留最近两个检查点；从缓存应答，不因远端请求临时暂停并序列化核心。
- 服务器保留 8192 帧输入历史；一次只隔离一个异常控制端，清该端输入后传检查点并追帧，不停止健康 Host。完成后恢复原输入租约；没有重新分配权限。
- 每会话修复启动间隔至少 1200 tick（60 秒），每次最多 600 tick（30 秒）。检查点不存在、历史过期、重复失步、无效确认或修复超时，归还异常端，Host 继续。Host 自身失效仍按既有安全路径停止全局，首版不迁移 Host。
- 非 Host 帧缺失 / 队列跟不上也可请求修复；请求等待立即清本端输入，等待最多 30 秒。未提供修复回调的离线 `Host` 测试接缝仍失败关闭，正式 `MinecraftHost` 明确启用。
- 首次邀请仍保留既有批准与短暂停表流程，不宣称首次中途加入已改成完全无暂停。
- 旁观仍只接收 Host 音画，无 ROM、核心或输入权。
- 通用柜 `openSync` 使用相同 `SfcExecutionCore` 初始化、FPS、状态往返校验，执行时钟归公共 `CabinetSyncWorker`；原媒体 `open` 和旧 SFC6 核心不替换。

## 资源与限额

- 状态最大 16 MiB；家用 Host 检查点最多两份，服务器每会话最多一份修复状态。
- 上 / 下行每 tick 最多两片 30 KiB。原首次邀请与新修复都用公共 `CabinetMediaSender.sendPayload`，与所有媒体共享同一个每 Connection 196608 B 的真实 write-completion 窗口。单片估算包含 2048 B 头；拒绝发送不推进偏移，下一 tick 有界重试。
- 重放每包最多 256 帧、每 tick 两包；完全追到当前服务器边界后有序发送 Resume，再恢复普通 Frames。追帧不重复播放 PCM；健康 Host 的音画保持原路径。
- 新必选协议 `sfc-repair-1`：Digest、Fault、Begin、Request、State/Upload、Restored、Replay、Resume、Done、Cancel。实际 Connection、会话、epoch、端口 lease、token、状态 hash/顺序均复验；旧连接回调不能推进新连接。
- 重置显式结束旧修复、新 epoch；控制器归还取消该端修复，不撤销 Host。日常控制租约、6 格线距、ROM 文件授权及个人存档策略不变。

## 精确生产范围

修改的 class stems（包含各 stem 的 nested / anonymous classes）：

- `cn/piq/sfchome/SfcHomeMod`
- `cn/piq/sfchome/client/SfcPlayback`
- `cn/piq/sfchome/client/SfcHomeClient`
- `cn/piq/sfchome/client/SfcJoinClient`
- `cn/piq/sfchome/client/SfcWatchPublisher`
- `cn/piq/sfchome/client/cabinet/SfcCabinetProvider`
- `cn/piq/sfchome/server/SfcHomeServer`

新增 stems：

- `cn/piq/sfchome/client/SfcExecutionCore`
- `cn/piq/sfchome/client/SfcCheckpoints`
- `cn/piq/sfchome/client/SfcRepairClient`
- `cn/piq/sfchome/client/cabinet/SfcCabinetSyncCore`
- `cn/piq/sfchome/server/SfcRepairLedger`
- `cn/piq/sfchome/net/SfcRepairNetwork`

metadata：`gradle.properties` 版本 alpha19；`build.gradle` 依赖本地 FC32；`src/main/templates/META-INF/neoforge.mods.toml` 最低 FC32。模型、UV、PNG、WASM、旧 SFC6 内容全部不变。

`SfcWatchPublisher` 唯一变化是将 `watchServerbound` 的实际接纳 boolean 回填 `stream.transportResult(batch, admitted)`，不改观看身份/播放逻辑。

## 验证

已完成源候选：

- `gradlew.bat compileJava --offline` 成功。
- 第一轮完整 check 324 项，其中 3 项旧源码字符串断言失败，真实行为测试无失败。已保留原权限/单份帧/工作线程语义更新断言，并补 6 个接线合同、3 个账本行为，等待统一完整复跑。
- `design/repair19-core-source-v1.json`：两真实 WASM，2409 断言，767 帧 RGBA + 完整双声道 PCM 比对，故意偏离后恢复；Host 同时前进 120 帧。实际状态 1,294,513 B，测得一次 save+SHA 约 2.71 ms（单次诊断，不是硬件性能保证）。
- `design/repair19-workers-source-v1.json`：两个隔离 JVM，实际 `SfcPlayback` 与 `CabinetSyncWorker.Factory -> SfcCabinetProvider.openSync`。134 协调断言 + 4287 worker 断言，672 实际 codec 往返。Host 连续 1208 帧，异常端恢复时 Host 前进 120 帧；恢复后 120 帧完整音画一致，下一周期全状态 SHA 相同。通用柜在 300 帧状态恢复、追到 600 帧，两端状态与相同输入家用 600 帧完全一致。

上述源候选不是最终 JAR 证据；首次 worker 结果之后仅补默认无能力 Host 的 fail-closed 门禁、构造失败 finally 释放、重置显式取消、历史原子校验，已用最终 JAR 复跑。

最终证据目录：`piq-fc-arcade/build/review-sync32-v1/checks/`。

- `sfc-core-final.json`：2409 断言，767 帧完整 RGBA / 双声道 PCM；状态 1,294,513 B，单次 save+SHA 2.5503 ms。
- `sfc-workers-final.json`：134 协调 + 4287 worker 断言、672 codec 往返；Host 连续 1208 帧，独立异常端恢复及公共柜真实 openSync 路径通过。
- `sfc-watch-final.json`：38 断言，旁观回调失败后 Host 继续，P2 不发布媒体，暂停恢复序列递增。
- `sfc-multiplayer-final.json`：790 协调 + 823 worker 断言、320 codec 往返；原两次邀请 / 取消重试、24 端口按键、同 tick 快按松、0–35 ms 管道抖动、后台 Host、重置及旧 epoch 门禁通过。另真实 appliance outer codec 3661 断言。该原探针未启用新修复 Host 能力，缺帧场景保留旧 fail-closed；新修复场景由前一 worker 探针验证。

四份报告均为 `mode=final-jar-only`、`production_compiled=false`，自动绑定：

- FC32：`090781E86821275A82E786494B9F98CF905B8CC8EED1478C3F06356ADDAFD16B`
- 合并 SFC19：`DC50C9378C83E38907C259373CE92B06FAD403B3779D0E4B6772D94AA0343BC3`

最终命令（使用冻结 FC／合并 SFC 路径，报告必须用新文件且自动绑定输入 path/SHA）：

```text
python tools/check_sfc_repair_core.py --fc <final-fc> --sfc <final-sfc> --report <new-report>
python tools/check_sfc_repair_workers.py --fc <final-fc> --sfc <final-sfc> --report <new-report>
python tools/run_sfc_playback_multiplayer_probe.py --appliance --fc <final-fc> --sfc <final-sfc> --report <new-report>
python tools/check_sfc_watch_playback.py --fc <final-fc> --sfc <final-sfc> --report <new-report>
```

无 `--production-source` 时禁止重新编译生产类，核心/工作线程 origin 必须来自最终输入 JAR。工具仅编译测试诊断 ROM 和探针。

## 真实边界

这不是实际 Minecraft 客户端/服务器/socket、权限插件、真人双机或商业 ROM 兼容性实测。两 JVM 用管道协调真实工作线程与真实网络记录 codec；`SfcHomeServer` 世界权限、`SfcRepairClient` 游戏线程路由及实际 Connection 背压另由纯账本/接线合同/公共网络探针验证。没有假称完成完整远程联机实测。

只使用原创输入敏感 65816 诊断 ROM。没有接触玩家 ROM、存档、线上服或安装目录。
