# SFC 单附属交付接缝（工具完成，尚未出包）

- 日期：2026-09-10。范围：SFC 单一玩家安装包。
- 当前组件架构：FC 主模组内置公共平台，SFC 为可选附属；不需要独立安装 platform 模组。
- 本次只新增 `tools/merge_sfc_addon.py`、`tools/test_merge_sfc_addon.py` 和本文。没有修改生产 Java、模型、运行库、旧 SFC 工程或历史冻结包；没有运行 Gradle、安装、启动 Minecraft、复制实例文件。
- 此记录未修改版本、Gradle 或依赖配置，也未接入实体手柄输入或生成新的完整安装包。

## 真实包所有权核对

旧 SFC6：`piq_sfc_arcade-0.2.0-alpha.6.jar`，SHA256 `38FA46C5D283EAD9E1F6666D01398F495E2E1A3260A1517BE8EE959710963363`，50 个 class，唯一 WASM `assets/piq_sfc_arcade/core/piq_sfc_wasm.wasm`（1,913,067 字节）；不含 Wasmtime 类或原生运行库。

家用5：`piq_sfc_home-0.1.0-alpha.5.jar`，SHA256 `82578C8DEF9567B1408D8B7F384E8DCC92D388498091ADC0EC308D56AF5D811C`，120 个 class；不含 WASM、Wasmtime 类或原生运行库。

两包非目录路径重名仅 `META-INF/MANIFEST.MF` 和 `META-INF/neoforge.mods.toml`，不存在类或资源重名。

## 合包契约

内部正常家用6 thin JAR + 哈希固定旧 SFC6 JAR → 一个 `piq_sfc-0.1.0-alpha.6.jar`。保留 `piq_sfc_arcade`（旧核心版本不变）与 `piq_sfc_home` 两个 modId，保留现有注册 ID、全部旧 class/asset/AT/WASM 字节。合并的 TOML 经标准解析器验证其两组 mods/dependencies 与输入语义完全一致；仅合成 TOML 和 manifest。不复制 FC 公共平台/Wasmtime/native，因此运行时仍只有主模组拥有公共运行库。

工具要求家用输入的实际 SHA256（必传）、家用6版本及 FC19 最低依赖，拒绝独立 platform 模组依赖；当前家用5输入直接使用会被拒绝，不能把旧包直接冒充新版本。未知元数据字段不静默丢弃；任何额外类所有权、重名文件（即使字节相同）、嵌套 JAR、多版本类、签名文件、ROM/程序资源、非法 ZIP 路径、软链接/junction、大小越界均拒绝。固定 ZIP 时间/顺序/压缩设置，可重现字节；输出和报告用独占创建，不覆盖任何现有交付。报告含逐项原 SHA256 及实际输入/输出 SHA256。

取得对应 thin 家用6后可运行：

```text
python tools/merge_sfc_addon.py --home <真实家用6.jar> --home-sha256 <实际SHA256> --output <新目录/piq_sfc-0.1.0-alpha.6.jar> --report <新目录/merge-verification.json>
```

`--audit-only` 可独立复核已有合包，必须使用新报告路径。工具从不安装。将来安装时不能同时保留旧独立 `piq_sfc_arcade`/`piq_sfc_home` JAR 与合包，否则同 modId 重复；具体备份/替换须按实际安装范围执行。

## 验证结果与边界

2026-09-10 已运行：在 `piq-sfc-home/tools` 下，使用配置的 Python 3.12 执行 `python -m unittest -v test_merge_sfc_addon.py`，**20 项全部通过，0 失败、0 错误**，最后一轮 2.506 秒。

覆盖：真实冻结核心 + 合成家用6元数据夹具的两次合包哈希一致；两 modId 与核心完整元数据保留；逐个原始文件内容相等；错误核心/家用 hash；旧版伪装新版；已有输出保护；class/WASM/AT/资源篡改；条目增删；重复类/运行库/平台类；依赖降级或变成可选/仅客户端；独立平台依赖；未知 TOML 字段；资源冲突；ROM/程序注入；非法/重复 ZIP 路径；签名、嵌套 JAR、多版本类；manifest 注入；操作后两个冻结原件 hash 不变。测试修复了 Python Windows ZIP 名称自动规范化会掩盖反斜杠的问题，生产工具现在先校验 `orig_filename`，拒绝 NUL/控制符和任何规范化差异。

测试夹具仅修改临时副本的版本/依赖元数据，仍使用家用5字节码，明确不作为家用6交付；测试目录已由 TemporaryDirectory 清理。本轮没有实际家用6成品，没有 NeoForge 两 modId 加载/模块排序运行验证，没有实体手柄或 Minecraft 实机验证；不能宣称新架构或控制器已可交付。
