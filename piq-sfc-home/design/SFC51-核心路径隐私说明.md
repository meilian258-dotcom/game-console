# SFC51 核心路径隐私说明

作者：方块电玩维护组。日期：2026-10-07。适用完整包 `game-console-sfc-0.1.0-alpha.51.jar`，包含家用 alpha.51 与历史核心 alpha.11，配套 FC76.44。这是测试候选，不是全游戏或 Minecraft 多人环境的稳定认证。

## 改动范围

历史 jgenesis WASM 的 59 处 Rust 诊断源路径前缀改为等长公共占位路径。只改变 WASM section 11 的指定字符串字节；文件长度、指针、导出、执行代码及其他 section 保持不变。这些字符串属于运行时诊断数据，不称为可直接删除的调试 section。

完整包只修改 WASM、两个模组版本对应的 TOML 和 Manifest，新增公开派生记录。全部 Java 类、Mesen-S DLL、模型、纹理、声音、网络协议和默认 JNI 运行方式保持原字节；不切换核心，不安装或迁移玩家数据。

- 原 WASM SHA-256：`5e310012b259039ae5a0a45d3681bbeb44c2963ad6a6f325ffacde5c80f63648`。
- 公共派生 WASM SHA-256：`8bff9655f9c41ceb418ca21c32d9707e84b3ccb5f4151141f32c06240c898e5a`，长度 1,913,067 字节。
- 公共材料只提供派生 WASM，不提供含原构建路径的旧模块。

## 保存兼容边界

WASM 的即时状态由 Rust `bincode` 编码 `SnesEmulator.to_save_state()`，采用小端、固定整数编码，不是整段 WASM 线性内存转储；路径字符串不属于该序列化结构。Java `WasmSfcCore` 沿用 ABI v1，直接读写该序列化状态，未用模块 SHA 作为状态头。本次保持该状态格式，双向互读验证结果见下节，不创建新的玩家保存命名空间。

当前旧独立机柜的 `LocalSfcSession` 只运行本地会话，没有接通玩家存档持久化。核心 API 的保存测试不代表新增旧柜存档功能。

家用仍使用 Mesen-S，`LibretroSfcCore.BUILD` 保持 `mesen-s-piq1-8aca17e7`，保存命名空间仍为 `sfc-libretro-state-v1/mesen-s-piq1-8aca17e7`；JNI 私人档继续使用原有前缀。WASM 与 Mesen-S 档仍不自动转换，也不因这次隐私清理合并两种格式。

## 验证范围

固定输入 SHA、完整 JAR CRC 和逐 entry 比较确认只有上述预期差异，所有运行类及原生 DLL 不变。共享路径处理工具同时验证等长替换、只动数据 section，以及匹配范围外的全部字节不变。

隔离 JVM 使用原创、无第三方游戏素材的测试卡带完成旧版和新版 WASM smoke、核心契约、180 帧逐帧视频与 PCM 比较、6 个检查点的双向即时状态导入导出、软硬重置和关闭后重开。另用同一原创程序的 SRAM 卡带配置核对 8 KiB 电池数据、60 帧执行与双向状态恢复。具体结果以随包验证 JSON 为准。

没有启动 Minecraft 客户端，没有商业游戏或原厂 BIOS 测试；没有把这些核心检查写成实机、网络长测或完整游戏兼容承诺。

## 构建和对应源码

派生工具为 [package_sfc_privacy51.py](../../piq-sfc-arcade/tools/package_sfc_privacy51.py)，复用 [共享 WASM 路径工具](../../source-control/wasm_diagnostic_paths.py)。它固定检查旧完整包和旧 WASM 摘要、59 处匹配及新摘要，只生成新候选，不覆盖输入。原始敏感前缀由固定输入识别，公开脚本和记录不保存其内容。

拥有旧本地输入的维护者可执行：

```text
python piq-sfc-arcade/tools/package_sfc_privacy51.py --input <SFC50完整包> --output <新候选目录>
```

公开构建应使用同批材料中的 `piq_sfc_wasm-public-paths.wasm`，校验上述新摘要后放入 `piq-sfc-arcade/src/main/resources/assets/piq_sfc_arcade/core/piq_sfc_wasm.wasm`；不得从未知旧包或未校验下载替换核心。完整对应 Rust 适配与 jgenesis 源码、锁文件、GPL 和第三方许可仍随源码材料提供。`build-wasm.ps1` 是历史源码重编入口，未经显式路径 remap 的新构建不能直接作为公开成品。

隔离验证入口为 [verify_sfc_privacy51.py](../../piq-sfc-arcade/tools/verify_sfc_privacy51.py)，参数通过 `--help` 查看。其 scratch 目录包含原始对比模块，不应整目录公开；只发布新候选、新 WASM 和脱敏验证结果。完整玩家包仍同时包含两个注册 ID，不以家用薄包替代。
