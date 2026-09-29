# Native core adapter

这里将放置 PIQ 对 jgenesis `snes-core` 的薄适配器，而不是 jgenesis 的 SDL3/wgpu 桌面前端。

固定边界见 `CORE_ABI.md`。适配器职责仅包括：

1. 创建/销毁一个 SFC 实例；
2. 接受已经去除 copier header 的 ROM；
3. 把两个手柄的位掩码转换成 `SnesInputs`；
4. 驱动 `SnesEmulator::tick` 直到 `TickEffect::FrameRendered`；
5. 收集 RGBA 帧和 48 kHz 双声道 PCM16；
6. 导入/导出即时状态和 SRAM。

当前机器尚未安装 Rust/Cargo，上游源码克隆也曾被网络重置，因此本目录暂不放置一个无法编译的假实现。Java 侧合同通过后再引入固定 commit 的上游源码并建立真实构建。

