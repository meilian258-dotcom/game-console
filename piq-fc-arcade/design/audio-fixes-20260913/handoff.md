# 音频审查修复交接 · 2026-09-13

范围仅两项已确认音频缺陷，不修改版本、资产、输入映射、网络协议、核心、运行库或存档。

## 生产范围

- `piq-native-arcade/src/main/java/cn/piq/nativearcade/client/NativeArcadeClient.java`：在全局 `RenderLevelStageEvent.AFTER_ENTITIES` 且当前会话身份有效时泵一次音画；机柜 BER 回调只画已上传纹理。只有这个事件调用 `upload()`，tick/BER 不重复消费。维持原 `syncInput()`、纹理 UV/尺寸、PCM 与播放音量行为。无需改 NativeCabinetRenderer/NativeArcadeAudio/helper。
- `piq-fc-arcade/src/main/java/cn/piq/fcarcade/client/cabinet/CabinetClientBackends.java`：仅 `sync.restore(p)` 返回 true（接受新的 guest 纠偏起始事务）时 `audio.reset()`；host/重复 token/数据分片/断连接拒绝不触发清音。
- `piq-fc-arcade/src/main/java/cn/piq/fcarcade/client/cabinet/CabinetAudio.java`：4 块有界 PCM 队列绑定代际；reset 清队列并使已取块失效，音频 worker 对设备执行 stop/flush/start。写入仅完整立体声帧，最多 1920 bytes/10 ms 且不超过 available。reset/close 不操作设备、不等待 worker；晚到设备与旧实例只由自己的 worker 关闭。

类范围：修改 `NativeArcadeClient`、`CabinetClientBackends`、`CabinetAudio`；新增嵌套 `CabinetAudio$Chunk`、`CabinetAudio$LineFactory`。没有资源变更。

## 验证

`source-v3/report.json`：`mode=isolated-source`，明确只隔离编译上述 3 个生产源；38 条实际 API/字节码/真实 worker-mailbox-queue 断言，10 项真实 CabinetAudio + 受控 SourceDataLine 行为测试，0 跳过。SHA256：`3973FD3D8C1D6E41D784E54FBD488C936263B6EE23CC532F68CD086822C08965`。

覆盖正常 PCM 拷贝/端序/有界小块、4 块队列、打开设备前 reset、正在写入时 reset、flush 中再次 reset、无可写空间时 reset、close 与阻塞写/晚到设备竞争、旧会话 close 不影响新会话、设备异常静音、500 次代际切换。实际 MC Frustum 四朝向前后正反例与冻结 Native11 BER 调用负例保留。

早期 `source-v1` 因探针读取渲染 Stage 常量触发无头环境的未初始化 MC 注册表而失败；探针改为不初始化渲染静态对象的真实 API 字段核验后通过。该目录不是通过报告。

新增测试：`src/test/java/cn/piq/fcarcade/client/cabinet/CabinetAudioGenerationTest.java`。

新增工具：`tools/check_audio_fixes.py`、`tools/qa/CabinetAudioFixProbe.java`。默认最终 JAR 模式仅编译 probe/tests，核对三个修改类真实 CodeSource；最终复验命令（输出目录必须不存在）：

```text
python piq-fc-arcade/tools/check_audio_fixes.py --fc <最终FC.jar> --native <最终Native.jar> --output <新报告目录>
```

## 验证边界

未启动 Minecraft、物理声卡或原生核心。真实驱动若正在阻塞 write，reset/close 调用仍立即返回，但旧设备缓冲需在写入返回后由同一 worker flush；已实际播放的声音不能撤回。常规写入已按 available + 10 ms 分片避免主动大块阻塞，不能保证异常驱动永远返回。全局 Native 泵已验证订阅/单入口字节码与 API，不冒称做过游戏内听感测试。
