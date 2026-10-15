# Wasmtime4j 46.0.1-1.2.0 原生许可补充材料

本目录用于随 `ai.tegmentum:wasmtime4j-jni:46.0.1-1.2.0` 的既有 Windows x86_64 / Linux x86_64 原生运行库提供版权与许可材料。这里没有运行库二进制，也不表示现有玩家 JAR 已重新打包或已经包含本目录。

## 已绑定的发布与文件

- [Wasmtime4j v46.0.1-1.2.0](https://github.com/tegmentum/wasmtime4j/releases/tag/v46.0.1-1.2.0)，源码提交 `a377267cc70f891bb7c9618259251380ba26e57b`。
- 原 Maven JNI JAR SHA-256：`c4e55d01c334c2f9c5700e4d75077d79cc8d6172825ff1fa02e03694034f5530`；与该 Release 的资产摘要一致。
- Windows 原生库 SHA-256：`ab44831073d409ed84fee85746b81226feb28dfe5b29ef179fac6eac4ecb65eb`。
- Linux 原生库 SHA-256：`81242001c26dad865c5cfff5f497b6710deb36d62a42e8adebcf6c4213eca95f`。
- [固定发布工作流](https://github.com/tegmentum/wasmtime4j/blob/a377267cc70f891bb7c9618259251380ba26e57b/.github/workflows/release.yml) 使用 `x86_64-pc-windows-msvc` / `x86_64-unknown-linux-gnu`，执行 `cargo build --release --target ...`；对应 [native Cargo.toml](https://github.com/tegmentum/wasmtime4j/blob/a377267cc70f891bb7c9618259251380ba26e57b/wasmtime4j-native/Cargo.toml) 的默认 features。没有把可选 WASI-NN 扩展推定为本版本的已启用依赖。

## 材料范围与使用

`observed-crate-notices.json` 逐项列出从上述二进制可确证的 121 个 crate/version、原 crates.io 包 URL、注册表 SHA-256、原许可声明、通知文件路径和摘要。`crate-notices/` 保存 250 份未经改写的上游许可、版权或通知文本。包内没有许可文件的 10 项，使用该已发布包内 `.cargo_vcs_info.json` 指向的固定提交补取；补取文件另有固定原文 URL 与 Git blob 校验值。

本材料保留上游实际声明及附加通知，不以一份通用 Apache 或 MIT 文本覆盖全部依赖。应将整个材料目录与对应分发件一同提供，并继续保留项目已有 Wasmtime4j Apache-2.0 原文、Wasmtime Apache-2.0 WITH LLVM-exception 原文和其他既有第三方声明。MIT、BSD、ISC、Unicode 等组件的版权、许可条件和免责声明不可仅以名称清单代替。

特别需要保留：

- `encoding_rs-0.8.35` 的 Apache/MIT 选项之外，还有必须并存的 BSD-3-Clause/WHATWG 通知。
- `ring-0.17.14` 的 Apache-2.0 与 ISC 条件并存；其 BoringSSL、once_cell、fiat 原文均在对应目录，不能只保留根 `LICENSE` 索引。
- `icu_collections-2.2.0`、`icu_normalizer-2.2.0` 的 Unicode-3.0 原文。
- `ittapi-0.4.0` 明确提供 `GPL-2.0-only OR BSD-3-Clause`。本候选清单选择 BSD-3-Clause 分发路线并保留其 Intel 版权、三个条件与免责声明；附存 GPL 备选原文不表示选择 GPL 或将整个项目改为 GPL。

上述 121 项的声明未出现只能选择 copyleft 的许可证；这一结论只覆盖明确列出的版本及其声明，不是对整个原生二进制所有代码的法律认证。

## Rust 标准库

两个原生库均有 Rust 源码提交 `31fca3adb283cc9dfd56b49cdee9a96eb9c96ffd` 标记，Linux 文件另有 `rustc version 1.96.1 (31fca3adb 2026-06-26)` 标记。`rust-std-1.96.1/` 保存官方固定 Rust 1.96.1 分发件中的 COPYRIGHT、Apache/MIT 原文、`COPYRIGHT-library.html` 与其适用的原始许可文本；两个 `rust-std-*-provenance.json` 给出官方发行清单 URL、原归档 URL、SHA-256 与原成员位置。

`COPYRIGHT-library.html` 是上游生成的标准库源码及依赖汇总，不是这两个 DLL/SO 的链接对象表。它也覆盖其他目标平台：例如其中的 Fortanix SGX 项不能据此算入 Windows MSVC/Linux GNU 目标。不包含 Rust 编译器、字体、工具文档的无关许可库存；没有安装或更换任何运行库。

## 仍保留的精度限制

这是一份可确证版本的通知补充，不是完整 SBOM。发布提交明确忽略 Cargo.lock；该 Release 没有发布锁文件或 SBOM，历史 native job 日志已过期。Wasmtime 自身同版本仓库的锁与上述二进制可见版本有 65 项不匹配，不能替代 Wasmtime4j 的历史锁；没有运行今天的依赖解析去伪造历史结果。

若需要声明完整原生依赖库存已核清，还需要同一次发布、两个相应 target 的历史 Cargo.lock/构建依赖清单，或与上述二进制摘要绑定的完整 SBOM/上游完整通知材料，以确认未出现在二进制版本字符串中的成员。没有把完整 SBOM 或字节一致重编本身当成一律适用的许可证要求，也没有把这一剩余来源精度问题表述为已证实的侵权或隐藏对应源。
