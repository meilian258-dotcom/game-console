# FC33 / Native11 对应源码

这是配套源码和构建证据，不是玩家必装文件，也不是自动安装脚本。

## 内容与路径

- `source/piq-fc-arcade/`、`source/piq-retro-platform/`、`source/piq-native-arcade/`：本次 Java 生产代码、测试、资源及 Gradle 定义。Native 的 `helper/src` 包含旧 step helper 的对应源码。
- `source/mame-4fc9a931-source.zip`：原始 MAME/libretro 固定提交的完整源码。
- `source/lab/patches/`：最终五补丁及历史诊断补丁。只按 `source/lab/tools/build-README.md` 的“最终五补丁”顺序应用；不要应用诊断补丁。
- `source/lab/helper/`：冻结 snapshot helper 的增量源码；`backend/`、`ui/` 一并保留，使原 `build_lab.py` 的源文件依赖完整。
- `source/prerequisites/piq-native-step-helper.jar`：构造冻结 helper 的原版输入，不是要放入 mods 的文件。
- `verification/`、`source/lab/build-records/`：原机构建及最终成品验证记录。记录中的绝对路径只用于追溯，不可在其它电脑上照抄执行。

## 重建边界

三个 Java 项目保持同级，在已准备 Java 21、Gradle 依赖与 NeoForge 21.1.236 构建环境的独立目录中，先构建 FC，再构建 Native。`--offline` 只适用于依赖已缓存的机器。本包不含系统 JDK、Gradle 缓存或便携 C++ 工具链，也不保证网络仓库永久保留同版本依赖。

`build_sync33.py` 等冻结工具保留本次审计流程；它们还要求原 FC32/Native10 基线及历史校验输入，不能视为任意新电脑的开箱构建入口。FC 源码中有两项历史手柄草案资源；本次分发 JAR 按既有规则恢复上一版资产。逐项记录见测试包的 `verification/build-witness.json`，不要把普通 `build/libs` 直接当成本次冻结实物。

重建 snapshot helper 可参考 `source/lab/tools/build_lab.py`：需要 Java 21、固定 JNA 5.14.0（与测试包同 SHA）及上述 prerequisite。脚本保留原机路径和发行包 fallback；请只在新的构建副本中配置实际工具路径，不改原包记录，不覆盖已冻结文件。它同时构建独立验证 UI，UI 并不是 Minecraft 运行必需组件。

重建原生 DLL 的源码身份、宏、补丁、工具链和命令，完整说明在 `source/lab/tools/build-README.md`。新构建物必须重新做完整状态、视频和 PCM 恢复验证。运行时刻意固定已验 DLL/helper/JNA 的 SHA，因此自行编译的新文件不能直接替换后宣称兼容，须建立并验证新的 profile。

本次证据不等于跨机器逐字节可重现构建，也不等于两台 Minecraft 公网真人验收。包内没有商业游戏 ROM、BIOS 或玩家存档；上游许可证与法律说明在 `licenses/`。
