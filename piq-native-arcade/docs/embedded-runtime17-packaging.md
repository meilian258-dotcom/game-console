# Native17 内置街机运行库打包说明

Native `0.1.0-alpha.17` 以 FC 主模组 `0.31.0-alpha.42` 为最低依赖。
本轮只改变打包方式：从原 runtime37 ZIP 逐字节复制普通 MAME 与实验 snapshot 两组三件套，合计六文件、432,744,687 字节。没有下载、重编或执行核心，没有写入玩家实例。GBA 的三文件不内置。

## 资源布局

六个安装相对路径前统一加 `native-runtime/win-x64-v1/`，成为 JAR 内的资源路径：

| 安装相对路径 | 字节数 |
| --- | ---: |
| `piq-native-arcade/runtime/mame_libretro.dll` | 372431360 |
| `piq-native-arcade/runtime/piq-native-helper.jar` | 18571 |
| `piq-native-arcade/runtime/jna-5.14.0.jar` | 1878533 |
| `piq-native-arcade/runtime-snapshot-v1/piqneogeo_libretro.dll` | 56494080 |
| `piq-native-arcade/runtime-snapshot-v1/piq-snapshot-helper.jar` | 43610 |
| `piq-native-arcade/runtime-snapshot-v1/jna-5.14.0.jar` | 1878533 |

同前缀下 `manifest.json` 记录 schema 1、模组版本、原 ZIP 身份、六项输出与三项排除记录、四许可身份。它是审计清单，不是可任意指定目标路径的安装授权；FC 安装器仍需自己的固定目录与文件哈希校验。

`META-INF/licenses/native-runtime17/` 下包含原字节的 `MAME-COPYING.txt`、`MAME-GPL-2.0.txt`、`JNA-LICENSE.txt`、`JNA-Apache-2.0.txt` 和新增来源边界 `NOTICE.md`。项目原 `META-INF/LICENSE` 保留。未抹除作者、版权或许可。

这些是普通资源，不是 JarJar 依赖。不能展开 helper/JNA JAR，更不能把它们加入模组类加载器。运行方式仍是提取后的独立 helper 进程；内置不意味着权限沙箱。

## 输入与安全检查

原件为 `piq-fc-arcade/build/runtime-pack37-v1/piq-runtime-pack-v1.zip`，127,026,327 字节，SHA-256：

`681FDAB15CCF7BD3739B74598D1724E637415E6EB60C505DEEF0EFDAED0617AA`

脚本固定全部九项的名称、字节数与 SHA-256；即使 GBA 不输出，也验证它的三项原始身份。重复/新增/缺失条目、路径穿越、符号链接、加密条目、坏哈希均阻断。四许可也有固定身份。校验按流读取，不加载 DLL 或运行 helper。

只在本项目 `build/generated/embeddedRuntime17` 生成，不往 `src` 放大文件。首次生成先写独占临时子目录，回读并再验输入后才提交。已有准确目录可以复用；已有缺失、损坏或额外内容会失败，不擅自清理。输入原件缺失时不会复用旧输出绕过验证。

Gradle 的 `jar` 任务依赖 `prepareEmbeddedRuntime17` 并直接收取生成目录。没有把该目录注册到 `sourceSets.main.resources`，避免 Native ASCII 测试副本额外复制约 433 MB。相应地，普通 IDE 开发资源目录不带这些文件；只能以实际打包 JAR 验证嵌入资源可见性。

## 操作命令与冻结顺序

在 `piq-native-arcade` 项目目录，用 Python 3.10 或更高版本：

```text
python -B -m unittest discover -s tools -p test_prepare_embedded_runtime17.py -v
python -B tools/prepare_embedded_runtime17.py --plan
```

第一条仅运行小样本；第二条只读完整原 ZIP 与许可，不生成六个大资源文件。
先冻结源码和脚本输入，再由统一构建执行：

```text
gradlew.bat check jar --rerun-tasks -PembeddedRuntimePython=python
```

Python 默认命令为 `python`，也可用 `-PembeddedRuntimePython=<Python可执行文件>` 指定。
原 ZIP 可用 `-PembeddedRuntimeArchive=<原ZIP文件>` 显式指定，但仍必须匹配上述固定身份，脚本不会联网补齐。
正式构建是 Gradle 调用固定脚本，不靠预先手动提取的大文件。

最终需独立核对实际 JAR：六 payload 全部与原 ZIP 一致，manifest/四许可/NOTICE 存在，无 GBA payload、无新 JarJar 配置、无展开的 helper/JNA 类；模组自身类与批准变更范围一致。保留原 ZIP 构建前后 SHA 与源码围栏、实际测试结果、JAR 回读哈希。小样本测试与只读计划不是 Minecraft 实机验收。

## 当前边界

仅 Windows x64 核心执行端；安装引导、提取路径/覆盖保护、失败重试与服务器部署由 FC42 公共安装器负责，不能仅凭本脚本宣布已完成首装验证。实验 LOCAL 仍只有既定游戏/BIOS 身份与双人限制，内置资源不扩展兼容范围。

没有商业 ROM、BIOS 游戏包、用户存档或 GBA payload。包装步骤未重建二进制，也不证明字节可复现。外置运行库/补丁/helper 的准确对应源与完整传递许可仍需单独核对；四份许可与 NOTICE 不构成已完成公开再分发合规或 GPL 对应源履约的认证。
