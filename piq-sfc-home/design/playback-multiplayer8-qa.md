# SFC 8 双端真实播放线程测试

日期：2026-09-10。范围：多人播放 QA 工具与验证，不代表 Minecraft 实机多人验收。

## 本轮结果

最终证据：`design/playback-multiplayer8-v1.json`，`passed: true`，`mode: final-jar-only`，`production_compiled: false`。

- SFC 候选：`build/review-multiplayer8-v1/piq_sfc-0.1.0-alpha.8.jar`，SHA-256 `3C678DC03EF9479564BC3F7005210E9DE868792A8D29DD0CDD62CAB3C79BC1E5`。
- FC 20：SHA-256 `CAC47CAE12E7183A76CF8874C6E8B8C4242435C01759C797EE78A5BD8A80C864`，未修改。
- 两个独立 JVM，各自运行最终包中的真实 `SfcPlayback` / `WasmSfcCore` / 单实例 `SfcCoreLease`。不旁路 lease、不使用伪模拟核心。
- 1,578 条断言：协调器 782 条，worker 796 条；315 次真实记录 codec 往返，包括新增 `ControllerReady` / `ControllerLeave`。
- 第 28 / 208 帧精确快照，每份 1,294,513 字节、43 个 30 KiB 上限分块；真实 `loadState` 后再次散列一致。
- 加入后的 184 帧：两端逐帧 RGBA、PCM、PCM 长度和分辨率一致。
- 原创 65816 ROM 确认 P1 / P2 各 12 个键位，共 24 个端口/按键用例；该 ROM 读取 `$4016/$4017`，按端口改变画面颜色。
- 同一协调周期进入真实 `SfcInputTimeline` 的短按/松开顺序为 `1,0,256,0,2,0,512,0`；没有合并丢边沿，最终释放为零。实际生成的帧输入也交给两端 worker 执行。
- 使用固定随机种子的 0 / 3 / 8 / 15 / 35 ms 帧传送抖动、80 ms 快照传送延迟以及再次加入后的 55 ms 延迟。
- P1 保持原 worker 连续运行到 248 帧，重启次数 0；P2 关闭后重新建立 worker/新 lease，再从当前快照加入。
- 明确取消尚未到达的快照边界后不导出快照；真实排队的 Ready 回调在关闭后被生产 `running/isCurrent` 检查抑制。
- 故意给 P2 缺帧：真实播放线程拒绝并停止，P1 继续；双方关闭后真实核心 lease 均释放，只有 P1 执行最终恢复备份回调。

## 运行

```powershell
python piq-sfc-home/tools/run_sfc_playback_multiplayer_probe.py --fc <FC20.jar> --sfc <SFC8.jar> --report <新的报告路径.json>
```

默认仅编译三个诊断源文件，生产 `SfcPlayback`、状态机、输入时间线、协议记录和 WASM 类均来自候选 JAR。探针检查实际 `CodeSource`，并检查引擎只来自指定 FC JAR。候选 JAR 被复制到临时目录后执行，运行前后校验 SHA。报告以独占创建方式写入，不覆盖旧证据。

开发模式可追加 `--production-source`；此时报告明确标记编译生产源码，不能作为最终包证明。`playback-multiplayer-20260910-source-v1.json` 仅是早期开发诊断，不替代最终包证据。

新增文件：

- `tools/qa/SfcPlaybackWorkerProbe.java`：同包注入 `Host`，使用真实记录 codec、真实邀请 gate、实际播放线程。
- `tools/run_sfc_playback_multiplayer_probe.py`：两 JVM 管道协调、可控延迟、结果逐帧比较和不可覆盖报告。
- 本文及两个 JSON 证据报告。

## 严格边界

这里没有启动 Minecraft 客户端、专用服务器、socket、声卡、OpenGL 窗口或实体手柄。`Host` 只把主线程调度、音频输出和备份位置换成测试接收器；真正的帧队列、节奏时钟、核心运行、快照生成/加载及 lease 没有替换。

Python 协调器模拟服务器发送顺序，使用真实 `SfcJoinGate` 与真实 codec，但不运行 `SfcHomeServer` 的玩家/租约/保护事件/网络派发。因此 **P2 退出不影响 P1 的 worker** 已验证，**实际服务端退出路由是否正确** 仍须由服务端权限/生命周期测试及真正双客户端联机验证，不能混为一谈。

30 秒 gate 截止点使用逻辑 tick 前进验证，并非真的挂起网络 30 秒。`SfcJoinClient` 上传与取消 UI 路由不由本夹具驱动。只使用仓库原创诊断 ROM，不读取商业 ROM，也不把这份诊断结果宣称为所有商业游戏可中途双人。

模型、贴图、实例 mods、存档、ROM 库、线上服务器及已交付包均未修改。没有运行 Gradle。
