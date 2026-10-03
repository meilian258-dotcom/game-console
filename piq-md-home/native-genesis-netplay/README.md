# 独立 Genesis Plus GX JNI Netplay 核心

此目录只产生独立的 `genesis_plus_gx_piq_netplay_libretro.dll`，不得覆盖普通 GX DLL、私人/玩家串流核心或旧存档。运行时名称为 `Genesis Plus GX PIQ Netplay`，版本为 `v1.7.4c2838c7-piqnp1`；只为 MD 双六键手柄配置补完整状态，不宣称 SMS、Mega-CD 或全部商业游戏验证通过。

## 输入与重建

- 上游：[libretro/Genesis-Plus-GX 固定提交](https://github.com/libretro/Genesis-Plus-GX/tree/c2838c7dc4236fc2fe94e5dbd08b41486067918e)。原始 ZIP：[下载固定源码](https://codeload.github.com/libretro/Genesis-Plus-GX/zip/c2838c7dc4236fc2fe94e5dbd08b41486067918e)。必须 SHA-256 `dd4f5ef7ad3bae410854da8d0d7b99c99f13caa1df77f4ef1f2b8f59be3b6977`。
- 工具链：现有便携 `llvm-mingw-20250910-ucrt-x86_64`（LLVM/Clang 21），使用其 `bin/clang.exe` 和 `bin/mingw32-make.exe`；Python 3。无需安装全局工具。
- 运行 `python build.py <全新输出目录> <llvm-mingw的bin目录> --source <原始源码ZIP>`。源包校验失败或目录存在时停止。省略参数仅使用本工作区旧缓存；Git clone 本身并不包含该缓存或工具链，不能宣称无依赖直接构建。
- `build-receipt.json` 保存实际编译器版本、SHA、命令及成品/完整对应源码 SHA；DLL 时间戳关闭，源码 ZIP 时间戳固定。全新目录重复构建后比较 SHA。
- 脚本提取固定源包，在隔离副本中应用本目录补丁，再使用上游 `Makefile.libretro`。不修改原始归档、旧 DLL、用户 ROM 或存档。

## 修改与验证边界

快照使用独立 `PIQGXNP` schema 1，固定 1,560,608 字节，包含原 GX 状态及漏存的 68K 刷新/预取、六键握手、64 KiB SRAM、EEPROM 协议、FM 缓冲与时序、完整 blip 音频历史、滤波和前端几何状态。YM2612 派生连接/DT 指针与 Z80 回调不进入快照；加载时使用原核心的索引/本实例回调重建，绝不拷贝另一实例的地址。

`provenance.json` 列出已阅读官方文档和参考来源。GUI fork 的 rollback 仅用于检查字段遗漏，其整块 CPU memcpy 不适合跨实例，没有照搬。只保存 MD 实际使用的音频域，不把 SMS OPLL 的宿主指针纳入状态。

工具入口：`../tools/run_rollback_gate_probe.py`（原失败序列与增强原创 ROM 的 exact state/video/PCM/SRAM 比较），`../tools/run_authority_netplay_probe.py`（真实公共 JNI 会话、48 kHz 输出、准备/激活、两端口权威输入、只读旁观、晚加入、重连、主持存档与重开）。输出目录必须全新；不下载 ROM，不强杀原生所有者。探针完成不等于 Minecraft 双客户端实测或所有映射器全覆盖。

## 分发必须附对应源码

`genesis-plus-gx-piqnp1-source.zip` 包含完整修改后上游源码、各组件原许可与本目录的补丁/生成器。分发新 DLL 时必须同时提供该包，并保留原版权声明；GX/Nuked 含非商业使用及完整对应源码要求，blip_buf 为 LGPL-2.1-or-later，不得将整个核心重新标成 MIT/GPL。原许可见源码包，来源信息见 `provenance.json`。
