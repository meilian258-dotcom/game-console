# 独立逐帧 helper 技术记录

2026-09-12。生产与测试源码冻结阶段记录；完整构建与最终成品验收仍待完成。SFC、旧 NativeCoreWorker／BridgeProtocol／helper 及游戏内容不变。

## 精确新增生产范围

- `src/main/java/cn/piq/nativearcade/bridge/NativeStepProtocol.java`
  - stem `NativeStepProtocol`；内部 `Hello,Request,Reply,Frame,State,Loaded,Closed,Failure`。
  - 源 SHA256 `34D2D485F83AA6FFB83CCF960B484A2C5D6526AF5D20B8DFB852A6052415AF45`。
- `helper/src/main/java/cn/piq/nativearcade/bridge/NativeStepWorker.java`
  - stem `NativeStepWorker`；内部 `States,Session`。编译产生的其他嵌套以实际产物清单为准，不通配放行旧 helper 类变化。
  - 源 SHA256 `3338E7CC62A6E876CDEC4A068BFFB9F789006496F8A3175A692DC075642E5A6A`。

新 helper 在固定旧 helper 基础上仅追加上述新类；旧 Engine、回调和输入编号转换 class 必须逐字节不变，原 `piq-native-helper.jar` 不覆盖。父端 NativeStepSession／NativeProcessSession 的三个包内占用接口须单独核对。

## 已实施 IPC

PIQS (`0x50495153`) v1，big-endian，`DataInput/Output`；从 request ID 1 连续递增，只容许一个在途命令。

- `Hello(double fps,double sampleRate,int maxPorts)`，load 时实际值，48 kHz、4 口。
- `NativeStepProtocol.step(id,p1,p2,p3,p4)`；四个 12-bit 掩码，恰好一次 `retro_run`。`Frame` 仍附本帧实际 fps / sampleRate，可能与初始化 HELLO 不同。
- `save(id)` → `State(id,frame,bytes)`；真实 serialize，1..16 MiB。
- `load(id,frame,bytes)` → `Loaded(id,frame)`；真实 unserialize，先清旧前端输入/画面/PCM，不伪造后续 save 结果。
- `close(id)` → 核心卸载和 deinit 成功后才发 `Closed`。
- `writeCommand` / `writeRequest`、`readRequest(in,expectedId)`、`writeReply`、`readReply(in,expectedId)` 为父端直接使用入口。

Frame 携 `hasVideo/freshVideo`。首轮没有 callback 明示空图，不多执行、不画假图；每帧 PCM 都单独清计数/完整返回。后续 NULL video 只复用之前真实帧。数组不可变，读包先检查尺寸、状态和 PCM 上限。LOAD 后下一 STEP 从指定帧号 + 1 开始。错误记录有界，callback / 错误命令 / EOF 进入失败清理。

全部 init / load / run / serialize / unserialize / unload / deinit 在同一 helper 线程。独立 main 保持 stdout 二进制隔离；只有成功完成真实 native teardown 的专属 helper JVM 才可 `halt`，不是杀游戏进程。父端目前 close 为精确 owned-child 强制终止，不能将其称为已走正常 unload。

## 源 QA

1. `tools/check_native_step_worker.py --report design/native-step-worker-source-v1.json`
   - 149 个真实新 Session + 固定旧 Engine 回调断言、12 个 JUnit 协议测试。
   - 回调生产器是 fake core；旧 Engine 从固定旧 helper JAR 加载并验证 CodeSource。没有加载 DLL。
   - 覆盖一请求一帧、空闲不推进、首空画面、实际 FPS 变化、四口转换一次、PCM 不积攒、SAVE/LOAD 计数、错误命令/视频/状态、成功与异常 teardown。
2. `tools/check_native_step_lifecycle.py --report design/native-step-lifecycle-source-v2.json`
   - 308 断言，9 个隔离父 JVM 测例，21.625 秒。真实编译的 NativeStepSession + NativeProcessSession 与指定 SHA 的 fake-child JAR；不是替换父生产类。v1 原证据保留，v2 绑定修复 watchdog 竞态后的父端源。
   - 真 15 秒无 HELLO / 命令超时、16.25 秒合法空闲、阻塞取消、双会话拒绝、错误 hash / ID / 尺寸 / EOF、非法本地参数不消耗 ID、真实子进程退出后释放共享槽、仅自有暂存清理、无关 sentinel 不被杀。
   - 每例都重新获取真实 slot 并成功 STEP；四口 48 个单一位经实际 pipe 往返。
   - 支持 `--mod <final native jar>`，该模式只编 probes / fake helper，生产仅从指定 JAR 加载并校验 CodeSource；报告写 `mode=final-jar-only, production_compiled=false` 与路径/SHA。源模式明确不标 final。

新增测试/工具：`NativeStepProtocolTest.java`、`tools/qa/NativeStepWorkerProbe.java`、上述两个 Python runner、`tools/qa/NativeStepLifecycleProbe.java`、`tools/qa/fake-step/.../NativeStepWorker.java`。fake-step 目录绝不加入生产构建。

## 边界与待父端处理

- 父端 watchdog 原有读取旧 deadline 后结束/新命令竞态；现已将 start/endDeadline 和超时身份复核放在同一 lifecycle 锁内，复读确认。v2 的 308 断言全部重跑通过；不把它冒称纳秒级竞态穷举证明。
- 生命周期报告只读 DLL/JNA 做固定身份校验，不加载；生成 22-byte 空 ZIP 不是游戏 ROM。
- MAME raw-state / postload PCM 确定性仍是独立研究门槛。本 helper/IPC 测试通过不等于 MAME 可注册 `openSync`，不能忽略未知状态字节或声音不一致来通过。
- 没有真人 Minecraft 双机、真实网络中途加入或实际游戏内验证；现有媒体默认未改。

根维护记录建议摘要：新增隔离请求式 step IPC/helper，不替换原运行时；149 回调 + 12 协议 + 308 父端生命周期验证通过；MAME 同步资格仍待严格真核状态/PCM 证据。
