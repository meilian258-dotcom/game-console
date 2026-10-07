# 独立 NES Zapper 核心实验（未接游戏内光枪）

本轮仅隔离核心能力与离线验证，不改旧会话、同步包、用户实例、ROM、持久存档或模型。

## 调用与隔离

`NesCore` 新增默认可选接口：

```java
boolean supportsZapper(); // 旧核心默认 false
void setZapperState(int x, int y, boolean offscreen, boolean trigger); // 旧核心默认拒绝
String stateNamespace(); // 旧核心 nes-legacy-v1
```

新 `core.wasm.ZapperWasmNesCore` 使用 `/core/nes_zapper_v1.wasm`。仅需要枪的后续宿主显式构造它，旧 `WasmNesCore` 和旧 `nes_rust_wasm_bg.wasm` 不改变。坐标是原生 256×240；屏外时不夹到屏幕边缘，不保留之前的光感；扳机与光感独立。当前只在第二端口 `$4017` 增加枪，普通 P2 的 D0 仍保留。

后续宿主必须按 `stateNamespace()` 分开状态存储，且只有枪会话可选择新模块。本轮没有改存储目录或自动选核心，也没有添加 MC 物品、瞄准射线、枪租约或联网瞄准包，不能说安装本版便可在游戏内拿枪游玩。

## 硬件模型与资料

依据 [NESdev 控制器读取](https://www.nesdev.org/wiki/Controller_reading) 与 [控制器检测](https://www.nesdev.org/wiki/Controller_detection) 的端口定义：D3 为暗场 1 / 检出光 0，D4 为扳机信号；$4017 写仍交旧 APU 路径，不改 $4016 双手柄 strobe。实现独立编写，没有复制 GPL Mesen 等模拟器源码。

[NESdev Nestech 控制器章节](https://www.nesdev.org/wiki/Nestech.txt) 描述光信号只维持约 2000 CPU 周期。因此首版采用 6000 PPU dot 的有限保持，每个 PPU step 包括消隐都衰减，不以一张缓存图无限认亮。每个真正输出的、已经经过精灵/背景优先级与 emphasis 合成的 ABGR 像素，在瞄准中心 ±8 像素范围内按 `(299R+587G+114B)/1000 >= 192` 补充光感保持；移开瞄准或屏外立即清保持。

半径、亮度阈值及 6000-dot 常量是本项目明确的数字近似，不是模拟真实光电管/谐振电路。CPU/PPU 调度沿用旧核心的指令粒度，不宣称新增了逐总线周期精确模拟；也不宣称所有光枪游戏兼容。

## 生产及构建范围

- 修改 `native/nes-rust/Cargo.toml`：新增默认关闭的 `zapper` feature；旧 workspace 默认编译不启用。
- 修改 `native/nes-rust/src/lib.rs`、`cpu.rs`、`ppu.rs`：仅 feature 保护的枪字段/方法、逐像素与逐时钟回调、$4017 读取 OR；APU 写与原控制器逻辑保持。
- 新增 `native/nes-rust/src/zapper.rs`（MIT）传感器及 8 个 Rust 单元测试。
- 新增独立 `native/nes-zapper-ffi/`（Cargo.toml、Cargo.lock、src/lib.rs），复用旧 FFI 源的 `include!`，增加 `nes_set_zapper`。
- 新增 `native/build-zapper.ps1`；仅生成新资源，构建前后强校验旧核心 SHA。不要运行旧 `build-wasm.bat`。
- Java 只修改 `core/NesCore.java` 默认接口，新增 `core/wasm/ZapperWasmNesCore.java`，没有修改旧桥。
- 新资源 `src/main/resources/core/nes_zapper_v1.wasm`：SHA `C8D8824E5CAF727678C642E6B0539DEAA7C0084F33524D96779D90C7B5DA79EF`。
- 旧资源仍 SHA `110711E30B64444414A8BE2D0A3B1AB45A442CC9B9452AC7D74DAAB508C933FF`。

新桥临时状态采用独立 magic `0x50515A31` / version 1，头部绑定模块 SHA、原 ROM SHA、初始内存 baseline SHA 和四个分配指针；先完整验证、严格有界解压并拒尾随/截断，再写入 WASM 内存。旧格式、别的 ROM、模块/版本/指针不一致全部拒绝。内存上限 64 MiB；每实例只加载一个 ROM；必须在构造线程调用。

## 已完成的离线验证

1. 独立 release WASM 实际编译成功（首次 11.42 秒）。本机 `cargo test --lib --features zapper` 因缺 MSVC `link.exe` 无法链接，8 个 Rust 单元测试没有冒称执行通过；没有为此安装系统工具。
2. `tools/check_zapper_core.py --fc <含运行时的现有FC包> --report <新报告>` 编译新桥/探针，真正运行独立 WASM 和内存生成的原创诊断 ROM。61 项断言通过：亮区/暗场/屏外、扳机独立、P2 D0 保留、消隐期光感到期、重置、实际状态回放、旧/新状态互拒、错误 ROM/模块/指针/截断/尾随拒绝且不修改活跃状态。证据 `design/zapper-core-diagnostic-v1.json`。
3. 《打鸭子》ROM 作为只读测试输入，不随报告分发；`tools/check_zapper_private_pair.py` 对照两实例各 720 帧，frame 661 瞄准 (98,138) 与屏外射击，六个射击前帧完全一致。瞄准后的计分区域变化，屏外仍零分，命中标记不同；人工查看对应真实模拟器帧为 001000 对 000000。10 断言通过，ROM 前后 SHA 一致，证据 `design/zapper-private-smoke-v1.json`。

私有 ROM SHA `6412CFDEAF5618C8352E1F8FB7DC226D8C280D5ECFA1FD3250858F1EC48E5907`。没有写出 ROM 或 raw-memory 状态。`design/zapper-private/**` 的画面仅为本地私测，**不得放进 MOD 或交付 ZIP**；公开报告只含摘要/坐标/结果。不把这些离线核心测试称为 Minecraft、网络权限、实体光枪或所有游戏实测。
