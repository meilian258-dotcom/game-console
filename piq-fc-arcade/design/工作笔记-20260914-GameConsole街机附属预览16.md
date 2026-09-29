# Game Console: Arcade 独立附属预览 alpha.16

记录者：像素匠（Codex root，docs/source 与 runtime audit 协作）  
时间：2026-09-14 16:44 +08:00

## 本轮请求与边界

用户要求街机也出一份独立附属预览。中文为“方块电玩：街机”，英文为“Game Console: Arcade”；仍依赖已有 FC41 主模组，不意味着可以脱离主模组单独运行。

本轮只变更 Native 版本、FC 编译/元数据依赖与已有英文展示资源，不更改 Java 业务、同步协议、输入、存档、模型、模拟核心或运行库。未安装/替换用户实例、上传发布、启动游戏/专服/核心或关机。旧冻结成品和原运行库不改。

## 生产文件与构建

- `piq-native-arcade/gradle.properties`：版本改为 `0.1.0-alpha.16`。
- `piq-native-arcade/build.gradle`：编译前置从 FC40 对齐到 FC41；已有英文 manifest title 进入本版。
- `piq-native-arcade/src/main/templates/META-INF/neoforge.mods.toml`：FC 前置最低 `0.31.0-alpha.41`。既有英文 description、en_us 与 pack 英文名称一起打包。原内部 ID、作者与许可保留。
- Java21，真实执行 `check jar --offline --max-workers=2`，11 秒成功，80 项测试、0 失败、0 错误、0 跳过。编译任务因 Java 源未变而 up-to-date，资源、元数据、测试与 JAR 实际执行；没有把测试称为实机验收。
- `piq-native-arcade/tools/build_arcade_preview16.py`：预构建源捕获，最终冻结与多重源/编译输入/测试围栏。工具依赖固定原冻结工具 SHA，不改历史工具。
- 最终 JAR 与 Native15 的条目集合相同，所有 `.class` 字节相同；仅 manifest、mod metadata、en_us、pack.mcmeta 四项变化。FC41 实际编译依赖与冻结 FC41 的所有 class 相同，运行时身份/重复类检查通过。

## 成品

目录：`piq-fc-arcade/build/review-arcade16-v1/`。

| 文件 | 字节数 | SHA-256 |
| --- | ---: | --- |
| `game_console_arcade-0.1.0-alpha.16.jar` | 160520 | `70DD0A32D460DBD8D8144DEA4F0E236066FD71F343427801E8BDD433C86E0AC8` |
| `Game-Console-Arcade-addon-preview-alpha16.zip` | 310397 | `DE10BB9C5ABC4979E841DA94A9FD5FF8E70170658071678C35727C6924A12F8B` |
| `Game-Console-Arcade-mod-source-candidate-alpha16.zip` | 129012 | `92F37A3EF9FE940F7FDDED878D46EEA4BC345A7A127F43BDEE81BD5D57DD3FBE` |
| `build-witness.json` | 23763 | `80172559BA8A27C0135D4EB718563E5DF8AD08E65DA73336072199B757617379` |

小预览 ZIP 有 15 项：Native16 JAR、模组层源码候选、五份文档、五份原许可、入口说明、下载身份和 SHA 表。没有 FC/SFC/GBA 模组 JAR、外置运行库或用户游戏/BIOS/存档。主模组与原运行库通过下载清单单独引用，不伪造公网下载 URL。

源码候选 55 项，包括 54 个固定源/文档/构建输入和清单；拒绝 helper、运行库、旧归档、个人目录路径，资源和 metadata 对齐最终 Native16，原 LICENSE/署名保留。仅覆盖模组层，不声称包含外置运行库的全部对应源码。

## 首装与模式说明

- 必需主模组 `game_console-0.31.0-alpha.41.jar`，30750439 B，SHA `27DA245957D64D88BE105F8AC3911C059A01FC60CAA2548776AC2A376390674D`，来自 `build/review-public41-v1/`。
- 本机实际执行街机核心时使用原 `piq-runtime-pack-v1.zip`，127026327 B，SHA `681FDAB15CCF7BD3739B74598D1724E637415E6EB60C505DEEF0EFDAED0617AA`，来自 `build/runtime-pack37-v1/`。不解压/不改名，放实际实例 `piq-runtime-packs/` 后在未进入世界的运行环境页补库。已有正确运行库可复用。
- 原 ZIP 必须保持九项；只装 Native、未装 GBA 时安装器只选择两个街机运行组的六文件。九项全部 CRC/内容 SHA 经独立只读复核，本轮没有重做或删减运行库。
- 当前原生执行为 Windows x64，普通 MAME 桥要求 JVM `os.arch=amd64`。没有自动联网下载运行库。仅收音画/旁观不执行另一份核心，仍须匹配模组。
- 玩家托管由真正开局的玩家执行普通 MAME 核心；服务器托管由管理员给实际服务器手工放普通三件套并显式配置，默认不开启，无服务端 GUI 自动补库。
- 实验 LOCAL 另使用固定快照组，仅固定身份的 `kof97.zip`/`mslug2.zip` 与 `neogeo.zip`、两人；不保证任意游戏四人本地同步。旧独立 Native 机柜路径仍有局部模式限制。

## 校验与后续

小预览打包器 `outputs/arcade-preview16/build_preview.py` 按固定原件 SHA/大小、源码内冻结文档比对、精确名单、源前后围栏生成；最终 15 项逐字节 CRC 回读通过。root 再独立检查 14 项 SHA 表与最终 JAR 身份通过。源码代理独立检查 55 项和源 SHA、资源、metadata、无意外打包通过。运行库/最终 JAR 的独立报告位于 `outputs/arcade-preview16/`。

16:49 最终独立审计完成：`outputs/arcade-preview16/final-jar-audit.md`，SHA `909736AC2010273A5F90647CF74CC0F925507B93701683DC8A4EB7D0AB2D8A5D`。标准库直接重读最终 JAR/基线/外层/源码，不调用生成函数；73 JAR 条目、64 类、69 捕获输入、12 XML、15 外层/55 源项与五条下载身份全部通过，无本轮文件阻断。成品字节与上表一致；本地预览交付完成。

这是发布形式预览，未完成真实干净机首装、双客户端/专服、游戏兼容性/手柄验收。外置运行库准确对应源码与许可、机柜资产再分发及主模组既有固件/构建路径限制仍需单独闭合；未宣称已经公开发行合规或通过平台审核。不要因为本轮改名/打包而扩大已有功能和发布承诺。
