# 构建方块电玩源码

这份指南供开发者从当前源码构建主模组和附属。玩家安装成品请看[玩家指南](../piq-fc-arcade/docs/玩家指南.md)，不要把源码 ZIP 放进 `mods`。

**先确认你有固定版本的原生运行库。** Git 仓库不包含全部 DLL、WASM、运行器和附属打包输入，也没有自动下载并重建所有核心的入口。缺少这些文件时，仅安装 Java 和 Gradle 不能完成构建。

## 环境与源码

当前目标为 Minecraft 1.21.1、Java 21。构建默认使用 NeoForge 21.1.236，最低运行版本单独配置为 21.1.229；两者不是同一个参数。配置见 [neoforge-compat.properties](neoforge-compat.properties)。

下面的命令面向 Windows PowerShell。先安装 Git、Java 21、Python 3.11 或更新版本，然后在准备存放源码的目录执行：

```powershell
git clone https://github.com/meilian258-dotcom/game-console.git
cd game-console
git submodule update --init --checkout -- piq-pvz-addon/vendor/PvZ-Portable
python source-control/setup.py
```

子模块命令取得父仓库锁定的 PvZ 上游源码，不会自动升级到上游最新提交。只开发 FC 时不需要运行 PvZ 的构建脚本。

## 准备主模组的固定输入

[build-inputs.json](build-inputs.json) 列出 FC 构建所需的文件、大小和 SHA-256。哈希用于确认构建用的是预期文件，不代表该文件已经通过所有游戏或联机测试。

从[整套测试版](../piq-fc-arcade/docs/整套测试版-20261007.md)取得配套玩家 JAR。FC 锁定的二进制位于玩家主 JAR 内的 `core/` 子目录；按锁表的文件名与摘要提取、导入，不将整个 JAR 当成 DLL。项目源码见对应发行标签，其他原生组件按各自源码说明准备；不要用任意同名文件凑齐输入或关闭校验。附属仍需按自己的入口准备文件，不代表全部构建依赖已自动下载。

单项导入示例；把文件路径换成你实际取得的文件：

```powershell
python source-control/build_inputs.py import --id libretro-jni-abi2 --file "./approved/piq-libretro-jni.dll"
python source-control/build_inputs.py check
```

其余项目按锁文件中的 ID 分别导入。只有 `check` 确认全部必需输入齐全后，才继续构建。默认缓存为仓库根 `.build-inputs/`，不提交到 Git。

已有完整缓存可在构建时通过 `-PpiqInputsDir=../approved/build-inputs` 指定（相对于所构建的组件目录）。锁文件的 `legacyPath` 只用于导入历史构建材料；无需创建这些历史目录，Gradle 不会自动回退读取它们。

## 构建主模组

在仓库根执行：

```powershell
python -B -m unittest discover -s source-control -p "test_*.py" -v
python source-control/build_inputs.py check
.\piq-fc-arcade\gradlew.bat -p piq-fc-arcade --max-workers=2 check jar
```

首次运行需要下载 Gradle、Maven 和 NeoForge 依赖。只有依赖已经完整缓存时才加 `--offline`。

开发 JAR 位于 `piq-fc-arcade/build/libs/`。读取当前源码声明的版本来取得确切路径，不按目录里“版本号最大”的旧文件猜测：

```powershell
$fcVersion = ((Get-Content .\piq-fc-arcade\gradle.properties | Select-String '^mod_version=').Line -split '=', 2)[1]
$fcJar = (Resolve-Path ".\piq-fc-arcade\build\libs\piq_fc_arcade-$fcVersion.jar").Path
```

## 构建附属

先完成主包，再准备目标附属自己的核心和资源。部分附属构建脚本仍有历史 FC JAR 默认路径；使用 `-PgameConsoleJar` 显式传入本次主包，避免误用旧文件。

例如，准备好 SFC 自身固定输入后，先编内部核心组件，再编家用组件：

```powershell
.\piq-sfc-arcade\gradlew.bat -p piq-sfc-arcade "-PgameConsoleJar=$fcJar" check jar
$sfcCoreVersion = ((Get-Content .\piq-sfc-arcade\gradle.properties | Select-String '^mod_version=').Line -split '=', 2)[1]
$sfcCoreJar = (Resolve-Path ".\piq-sfc-arcade\build\libs\piq_sfc_arcade-$sfcCoreVersion.jar").Path
.\piq-sfc-home\gradlew.bat -p piq-sfc-home "-PgameConsoleJar=$fcJar" "-PsfcCoreJar=$sfcCoreJar" check jar
```

上面的 SFC 家用产物仍是开发薄包，**不是玩家使用的完整 SFC 包**。当前完整合包尚无可直接照抄的通用公开命令，需要维护者提供该版本的审核流程和配套材料；玩家应直接取得完整测试包。仓库内有锁定旧版本的历史合包脚本，不要拿它们处理当前产物。组件区别见 [SFC 说明](../piq-sfc-home/README.md)。

| 组件 | 继续阅读 |
| --- | --- |
| MD | [组件说明](../piq-md-home/README.md)、[普通 GX 输入](../piq-md-home/design/MD3-GenesisPlusGX.md)、[独立 Netplay 核心](../piq-md-home/native-genesis-netplay/README.md) |
| 街机 | [组件说明](../piq-native-arcade/README.md)，原生运行库和核心输入另行准备 |
| GBA | [组件说明](../piq-gba/README.md)，使用自己的构建入口，不套用上面的 SFC 命令 |
| 电脑和 PvZ | [电脑说明](../piq-computer/README.md)、[PvZ 说明](../piq-pvz-addon/README.md) |
| 内部公共库 | [Retro Platform](../piq-retro-platform/README.md)，不向玩家另发平台包 |

BlastEm 已退出当前 MD 的正常构建和运行路线。保留的历史重建脚本不是新开发的核心选择入口。

## 常见构建问题

- **缺文件或 SHA 不符**：检查锁文件、输入来源和导入结果；不要重命名随机文件或放宽断言。
- **找不到旧 FC JAR**：先构建当前主包，再传 `-PgameConsoleJar`。构建期路径和玩家运行依赖是两回事。
- **子模块为空**：执行上面的子模块初始化命令；GitHub 源码 ZIP 不会包含子模块的完整代码。
- **中文路径或缓存错误**：按目标组件的说明设置独立临时目录，保留首次失败日志；不要删除整个工作区或系统缓存来碰运气。
- **测试通过但游戏异常**：自动测试不能替代 Minecraft 中的图形、输入、多人和保存恢复检查。

## 从开发产物到交付包

`check jar` 成功不等于稳定发行。交付前还需核对核心与资源许可、最终包内容和依赖，完成实际运行验证，再按[命名与交付规范](BRANDING.md)准备玩家文件。

`piq_retro_internal` 不放进玩家的 `mods`；SFC 薄包不冒充完整包。ROM、BIOS、玩家保存和私密构建材料不提交 Git。源码中仍使用 `piq_*` 内部名称，是兼容安排，不是另一个项目。

旧批次命令、测试数字和迁移细节保存在[构建历史](BUILDING-history.md)，仅用于追溯对应版本。
