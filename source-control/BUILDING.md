# 构建方块电玩源码

这份指南供开发者从当前源码构建主模组和附属。玩家安装成品请看[玩家指南](../piq-fc-arcade/docs/玩家指南.md)，不要把源码 ZIP 放进 `mods`。

当前源码的开发配套为 **FC76.54、完整 SFC54（内含 core11）、街机 0.1.5.12、电脑 prototype.13**，沿用 MD16／GBA15／PvZ12 作为本地回归组合，可选 Replay test.7。公开成品按审核通过的部分分批测试发布，附件范围见[候选说明](../piq-fc-arcade/docs/设备体验候选-20261012.md)；本地合装成功不代表这套组合已全部公开或成为稳定版。

启动协议沿用 `home-launch-3`，机柜协议为 `cabinet-room-12`，SFC 协议为 `15`。SFC54 最低要求 FC76.54，参与测试的服务端和客户端须使用同批协议配套，不能用旧 SFC 成品补位。SFC 从 53 起的新 JNI 联机进度接入固定、未修改的 Snes9x；Mesen-S 核心、旧身份和旧进度恢复路线仍保留，不跨核迁移或覆盖旧档。原 Mesen-S 回滚失配并未被改核或屏蔽校验修复。SFC54 的公开来源与许可审核门槛仍保留，最终包与 Minecraft 多人验收状态见 [SFC 当前状态](../piq-sfc-home/README.md#当前状态)。

可选择两种构建方式：**普通开发构建使用固定核心**；**快照构建仅为明确列出的四个普通核心下载官方 nightly 并生成匹配身份，其余核心保留固定输入**。SFC 的 Snes9x 与 Mesen-S 均不参与 nightly 替换。Git 不保存全部 DLL、WASM 和运行器；固定输入需要按清单导入，不能假设快照会自动下载所有依赖，也不会重编所有第三方原生核心。

## 自动快照

当前街机修复开发分支的主包已改用依赖 API 1 公共桥及固定 libc++。
旧 `full-test-20261007-r1` 不包含这套输入，快照准备会在下载前明确拒绝，
不能回退旧桥或用 nightly 覆盖它。此分支暂不能合入自动发布的 `main`。
FC76.54 与街机 0.1.5.12 固定候选及源码配套已按独立分批发行提供；
但 `snapshot_inputs.py` 的旧 bootstrap 固定表及它与 `snapshot_build.py` 的
身份尚未完成审核更新，仍在下载前拒绝；分批发行不解除自动快照门槛。普通本地开发可按锁表导入
审核过的新桥和官方运行库后构建。街机修复还使用独立固定的 MAME 核心，不能用普通 nightly 补位。

街机从 `0.1.5.8` 起（当前 `0.1.5.12`）的普通构建需显式传入 `-PmameLifecycleCore=<已核验 core.dll 的路径>`；
默认查找该组件的 `build/verified-mame-lifecycle/core.dll`，没有文件就停止，不自动下载。
`native/mame-lifecycle/runtime.json` 固定源码、两份补丁、核心摘要及共享运行库身份；
`prepareMameLifecycleRuntime` 只校验并打包既有 DLL，不重新编译 MAME。
对应核心源码与构建说明应随候选保留。旧 MAME/FBNeo 资源仍使用原身份。

街机从 `0.1.5.11` 起（当前 `0.1.5.12`）的原版 FBNeo 试验使用独立固定 DLL 和源码 ZIP，普通本地候选已完成
`check jar`；这不表示自动快照或云端构建已可用。普通构建显式传入
`-PfbneoStockCore=<已核验原版 DLL>`、`-PfbneoStockSource=<固定源码 ZIP>` 及
`-PembeddedRuntimePython=<Python 路径>`。快照输入准备额外要求成对传入
`--fbneo-stock-core <原版 DLL>`、`--fbneo-stock-source <固定源码 ZIP>`，按
`snapshot_stock.py` 的固定大小与 SHA 导入同一回执；构建与临时源码复制后会复验，并将
回执中的路径显式传给街机 Gradle。缺件或身份不符即停止，不读取私有 vendor 缓存、
下载最新核心或跳过 stock 资源。固定候选单独公开不等于旧 bootstrap 固定表已更新，现有 JNI bootstrap
下载前阻断仍保留；本分支自动 CI 尚未通过，不能据本地候选成功声称云端已就绪。

SFC54 沿用 SFC53 的固定输入，额外需要 [sfc-stock-inputs.json](sfc-stock-inputs.json) 固定的 Snes9x DLL 与参考源码 ZIP。
快照准备支持成对传入 `--sfc-snes9x-core`／`--sfc-snes9x-source`，或从仓库根
`.build-inputs/` 读取已导入的同一固定对；不会读取任意 vendor 目录或下载新的 `latest` 补位。
两件输入写入 `fixed/sfc-snes9x-v1/` 并纳入 `receipt.json`；构建检查、临时源码复制后再次核验，
再将回执中的绝对路径通过 `sfcSnes9xCore`／`sfcSnes9xSource` 显式交给 SFC Gradle。
临时源码不需要依赖原 checkout 的忽略缓存。新核心仍为本地候选：即使其余 bootstrap 障碍解决、
整套编译成功且源码干净，`snapshot_release.py --validate-only` 也会拒绝含
`core/sfc-snes9x/` 的公开快照，并提示待完成的来源与许可审核。此门禁不阻止本地编译，
也没有通过编辑成品清单、传命令行开关来跳过的路径。

快照准备和 Gradle 前置检查都要求新 MAME DLL 与公开 NOTICE 同时存在，且与固定清单完全一致。
即使修改输入回执，也不能跳过清单中的核心和补丁校验；两项资源缺失、替换或不配套都失败。
尚未审核更新匹配 bootstrap 固定表时，保持原下载前阻断，不使用旧公开包假装包含这次修复。

[snapshot.yml](../.github/workflows/snapshot.yml) 在进入 `main` 的每次 push 后触发，也支持在 Actions 中手动选择 `main` 运行。只有七个成品全部编译、检查、打包且发布校验通过，才会创建独立预发行；失败不会覆盖已有发行版。当前上述 bootstrap 与 Snes9x 候选门禁均未解除，不能把此流程当作本批已可自动发布。workflow 合入 `main` 后才启用，不在拉取请求中使用发布权限。

构建使用 GitHub 的 Windows 2022 runner、Java 21 和 Python 3.12，依次完成主模组、完整 SFC、MD、GBA、街机、电脑和 PvZ 七个玩家 JAR。SFC 由本次编译的两个内部组件合成一个完整玩家包；PvZ 仍是可选开发验证组件，不因自动打包变成稳定机型。

普通核心从以下官方目录下载，每次运行只解析一次 `latest`，校验归档、架构和体积，并记录最终 SHA-256：

| 用途 | 快照来源 |
| --- | --- |
| FC Mesen | [Windows x64](https://buildbot.libretro.com/nightly/windows/x86_64/latest/mesen_libretro.dll.zip)、[Linux x64](https://buildbot.libretro.com/nightly/linux/x86_64/latest/mesen_libretro.so.zip) |
| MD 普通 Genesis Plus GX | [Windows x64](https://buildbot.libretro.com/nightly/windows/x86_64/latest/genesis_plus_gx_libretro.dll.zip) |
| GBA mGBA | [Windows x64](https://buildbot.libretro.com/nightly/windows/x86_64/latest/mgba_libretro.dll.zip) |

定制 FC 和 MD Netplay 核心、公共 JNI 桥、街机定制运行库、PvZ 原生组件、旧 SFC Mesen-S 及保留的 WASM 不用同名 nightly 文件替换。历史 bootstrap 提取路线由 `snapshot_inputs.py` 按固定摘要读取 `full-test-20261007-r1`，只取白名单内的原生资源，不把旧模组 Java 类当成本次构建结果；它仍不能提供本分支新增的 JNI 依赖，必须先解决前述公开输入阻碍。新 SFC Snes9x 与原版 FBNeo 使用各自独立的固定核心／源码对，不从该旧包冒领。当前 GBA helper、街机 helper 和公共 worker 仍从源码编译。

这些定制或历史运行库仍受固定接口和摘要约束。修改街机 helper 等相关源码时，若新产物与运行器目录的身份不一致，构建会停止，需要同时审核并更新对应构建约束；不会仅因 `main` 有新提交就跳过兼容校验。旧离线运行库的校验表单独保留，不重新解释为 nightly 包。

**快照不是固定核心版的原位升级。** 只有上表四个普通核心的临时源码摘要、版本断言和保存身份随实际下载文件一起生成；正常源码的固定默认值、旧成品和旧存档保持不动。SFC 的旧 Mesen-S DLL、`mesen-s-piq1-8aca17e7` 身份及新 Snes9x 固定身份都不改写；旧 Mesen-S DLL 即使随回执被重新标注为另一个摘要也会校验失败。升级前备份，在独立测试实例中使用同一快照的主包和附属；不要要求不同快照或固定核心版相互联机、读取旧即时档。下载 Linux Mesen 不代表整套附属或 JNI Netplay 已支持 Linux。

成功的发布标签是 `snapshot-<run_id>-<attempt>-<commit前12位>`，不会移动旧标签或替换稳定版。附件只有七个 JAR、`manifest.json` 和 `SHA256SUMS.txt`。JAR 内的 `META-INF/game-console/snapshot-cores.json` 记录源码提交及本批核心身份；内部 MOD 兼容版本沿用源码版本，以免破坏依赖范围，因此区分快照应查看发行标签、清单和哈希，不能只看文件名。

构建任务只有只读权限；单独的发布任务使用短期 `GITHUB_TOKEN` 的 `contents: write` 权限，不需要配置个人令牌。发布先创建草稿，上传并核对所有附件后才公开。仓库或组织策略若禁止此权限，任务会失败，旧 Release 不变。参见 [GitHub workflow 权限说明](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax#permissions)。

在 Windows 仓库根复现本地快照的流程如下。`D:/approved/` 仅为已获准、摘要匹配输入的示例目录；不得用其他同名 DLL 凑数。**当前分支会首先因尚未审核更新的 JNI bootstrap 固定表明确停止**；下面不是解除门禁的操作，也不表示云端已验证：

```powershell
python -B -m unittest discover -s source-control -p "test_*.py"
python source-control/snapshot_inputs.py --output .snapshot-build/inputs `
  --fbneo-stock-core D:/approved/fbneo_libretro.dll `
  --fbneo-stock-source D:/approved/FBNeo-a251c76229f1637e433b93e29845039752771b6d.zip `
  --sfc-snes9x-core D:/approved/snes9x_libretro.dll `
  --sfc-snes9x-source D:/approved/snes9x-fae2fea08f74180759ef540ee94259213f503480.zip
python source-control/snapshot_build.py --work .snapshot-build --output snapshot-dist
python source-control/snapshot_release.py --directory snapshot-dist --validate-only
```

设置 `JAVA_HOME` 指向 Java 21，使用实际安装的 Python（先确认 `python --version` 有版本输出，不能使用空的系统商店占位程序）。输入和临时源码保留在 `.snapshot-build/`，成品在 `snapshot-dist/`。本地未提交改动可用于构建排错，但候选会标记 `sourceDirty=true`，不能通过发布校验；正式快照要求干净的已提交源码，干净也不自动解除 Snes9x 候选门禁。失败后保留日志；重新完整构建时为 `--work` 与 `--output` 选择新的空目录，不覆盖前一批。`--bootstrap-dir` 可复用六个摘要完全一致的公开 JAR，上表四个普通核心仍按本次 nightly 下载；两个固定 SFC 核心不走该下载路线。

自动检查不替代 Minecraft 多人、画面、音频、存档及长时间运行验收。nightly 发生不兼容变更时，应修复适配或明确更新构建约束，不能删校验、跳过失败测试后继续发布。

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

## 开发构建工作目录

当前开发配套中的以下五个项目共用 [build-workspace.gradle](build-workspace.gradle)：`piq-fc-arcade`、`piq-retro-platform`、`piq-sfc-home`、`piq-sfc-arcade` 和 `piq-md-home`。这套目录规则从 FC76.52／SFC53／MD16 配套起使用，为开发 classpath 副本及测试临时文件选择独立目录，不改变玩家 JAR 的 `build/libs/` 输出位置，也不表示本批已完成游戏内验收。

可以使用环境变量 `PIQ_BUILD_WORK_DIR` 为后续构建指定基础目录，或对单次 Gradle 调用传入 `-PpiqBuildWorkDir`；两者同时存在时，Gradle 参数优先。使用绝对、纯 ASCII、本地专用目录，建议位于非系统工作盘；不能指向盘根、用户主目录、源码根目录或经过符号链接／重解析点的路径。以下 `D:/game-console-build` 只是示例，请换成实际工作盘：

```powershell
$env:PIQ_BUILD_WORK_DIR = 'D:/game-console-build'
.\piq-fc-arcade\gradlew.bat -p piq-fc-arcade printBuildWorkspace
# 也可不设置环境变量，只为本次构建传入：
.\piq-fc-arcade\gradlew.bat -p piq-fc-arcade -PpiqBuildWorkDir=D:/game-console-build --max-workers=2 check jar
```

未指定时，Windows 使用主 Git checkout 所在非系统盘根下的 `game-console-build`，而不是当前 worktree 的系统盘位置。例如主仓库位于 D 盘，则默认基础目录为 `D:/game-console-build`。主仓库在系统盘、网络路径或无法确定安全工作盘时停止并要求显式配置，不静默回退系统 Temp。非 Windows 默认使用 checkout 内的 `.build-work`。各 checkout 再按路径摘要、项目和任务隔离，避免不同工作树的 classpath 与测试目录互相覆盖。

所有上述项目的 Gradle `Test` 任务，以及构建脚本中显式调用 `piqUseBuildWorkspace` 的 `JavaExec` 核心／打包探针，会在各自子 JVM 设置 `java.io.tmpdir`、`jna.tmpdir`、`wasmtime4j.native.tmpdir` 和该子进程的 `TEMP`／`TMP`。JNA、Wasmtime 的解包目录各有子目录。**ModDev 实际游戏任务（如 `runClient`、`runServer`）不应用这套测试设置**，游戏运行环境与数据目录保持原合同。

这不是全局临时目录迁移：Gradle 本身、编译器、未接入项目及外部工具的所有临时文件不在统一保证范围内。脚本不会清理旧系统 Temp、旧批次目录或修改系统环境；classpath 的同步清理只限当前 checkout 的专用副本目录。排错时保留原失败日志，不通过删除系统缓存或玩家数据解决构建错误。

## 准备主模组的固定输入

[build-inputs.json](build-inputs.json) 列出 FC 构建所需的文件、大小和 SHA-256。哈希用于确认构建用的是预期文件，不代表该文件已经通过所有游戏或联机测试。

从与当前锁表相匹配的发行附件或已审核固定材料准备输入。FC 锁定的二进制位于玩家主 JAR 内的 `core/` 子目录；按锁表的文件名与摘要提取、导入，不将整个 JAR 当成 DLL。[2026-10-07 整套测试版](../piq-fc-arcade/docs/整套测试版-20261007.md)只提供历史输入，不能满足当前新增的全部 JNI 依赖，也不能当作快照 bootstrap 门禁已解除。项目源码见对应发行标签，其他原生组件按各自源码说明准备；不要用任意同名文件凑齐输入或关闭校验。附属仍需按自己的入口准备文件，不代表全部构建依赖已自动下载。

单项导入示例；把文件路径换成你实际取得的文件：

```powershell
python source-control/build_inputs.py import --id libretro-jni-abi2 --file "./approved/piq-libretro-jni.dll"
python source-control/build_inputs.py check
```

其余项目按锁文件中的 ID 分别导入。只有 `check` 确认全部必需输入齐全后，才继续构建。默认缓存为仓库根 `.build-inputs/`，不提交到 Git。

已有完整缓存可在构建时通过 `-PpiqInputsDir=../approved/build-inputs` 指定（相对于所构建的组件目录）。锁文件的 `legacyPath` 只用于导入历史构建材料；无需创建这些历史目录，Gradle 不会自动回退读取它们。

## 构建主模组

FC76.53 起，`check` 包含最终 JAR 的 `packagedReplayApiCheck`，使用 Python 3.11+。不在 PATH 时传 `-PreplayApiPython="<Python可执行文件>"`，或设置本次构建进程的 `PIQ_BUILD_PYTHON`；快照沿用 `snapshotPython`。检查的是公共回放 v1 的真实类签名、调用点和唯一提供者，不仅是描述文件。新版本或无法识别的主包缺合同会拒绝 staging／release，旧 FC 的历史例外不适用于新快照。

可独立检查：`python -B source-control/replay_api.py --require --jar "<主包JAR>"`。若要升级合同，先提供兼容层或新版补丁并补齐回归，不删除门禁、不伪造 `API_VERSION`。详见[公共回放演进规则](../piq-replay-compat/API.md)。

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

### SFC54 的固定输入与许可配套

SFC54 沿用 SFC53 的两套不同资源，不能将文件互相重命名、改写旧 SHA 或迁移旧即时档：

- 旧 Mesen-S：`core/sfc-libretro/windows-x64/mesen-s_libretro.dll`，3896832 字节，SHA-256 `8aca17e76efbd7a70b0c247b42aaba04d0c1c90f693213bd1e76573986670b42`。继续按原组件固定资源准备方式保留；新工具不下载或重写它。
- 新 Snes9x：`core/sfc-snes9x/windows-x64/snes9x_libretro.dll`，3840000 字节，SHA-256 `5ce01616184f255623405793e192f79aef5089fa796b29bc11ddc5e052060124`，实际版本报告为 `Snes9x 1.63 fae2fea`。参考源码 ZIP 为 `snes9x-fae2fea08f74180759ef540ee94259213f503480.zip`，760336 字节，SHA-256 `7f6dee7ab1a367da86bc86a87a204bf8fe0be389d62e7cfbd568dc2ccac32055`。

固定来源、原归档摘要、取得时间及限制见 [输入清单](sfc-stock-inputs.json) 和 [NOTICE](../piq-sfc-home/src/main/resources/core/sfc-snes9x/NOTICE.txt)。原 DLL 来源是官方 Buildbot 的可变 `latest` 地址，不保证未来仍提供相同字节；若已变化，须自行提供匹配的既有输入，不能重新取最新 DLL 代替。参考源码是固定提交的官方源码 ZIP，短版本一致不等于已证明 Buildbot 的精确源码、编译选项或可复现二进制。工具只验证和复制，不下载、不重编、不执行核心。

在仓库根导入 DLL 与源码包的固定对，然后检查缓存：

```powershell
python source-control/sfc_stock.py import `
  --core D:/approved/snes9x_libretro.dll `
  --source D:/approved/snes9x-fae2fea08f74180759ef540ee94259213f503480.zip
python source-control/sfc_stock.py check
```

默认复用仓库根 `.build-inputs/<sha256>/<filename>`，与 FC 缓存共用目录但使用独立清单，不向 FC 锁表增加 SFC 依赖。可用 `--cache D:/approved/build-inputs` 导入／检查外部缓存，并在 SFC Gradle 传 `-PpiqInputsDir=D:/approved/build-inputs`；快照入口不接收该缓存参数，使用外部缓存时须将两条具体文件路径显式传给 `--sfc-snes9x-core`／`--sfc-snes9x-source`。

`prepareSfcSnes9xRuntime` 在 `processResources` 前重新核验固定对并生成新 DLL、来源清单、原文顶层许可证、完整 LGPL 2.1 文本及带来源路径的版权／许可注释；资源也进入测试 classpath。源码 ZIP 不嵌入 JAR，保留为同批候选的独立配套文件，不能只交付 DLL 而丢掉源码资料。输出若存在不匹配或额外资源会失败，不静默覆盖；查明原因后使用新的干净构建输出，保留失败记录。

原文许可证保留非商业使用／再分发条件；公共 GPL 前端／JNI 桥与独立核心分别声明，不意味着 GPL 能覆盖或取消核心限制。固定 libretro 参考树包含 NTSC、SPC DSP 的 LGPL 通知及 libretro-common 的 MIT 通知；顶层许可证还提及 JMA、xBRZ，但该树不含二者代码或许可证。`reference-only/` 的补充文本来自另一个明确固定的官方源码树，不证明实际编入 DLL或适用例外。公开前仍须核对实际构建组成、适用的对应源码／重新链接义务及非商业分发条件；这不是法律合规结论，当前公开快照门禁保留。

例如，准备好 SFC 自身固定输入后，先编内部核心组件，再编家用组件：

```powershell
.\piq-sfc-arcade\gradlew.bat -p piq-sfc-arcade "-PgameConsoleJar=$fcJar" check jar
$sfcCoreVersion = ((Get-Content .\piq-sfc-arcade\gradle.properties | Select-String '^mod_version=').Line -split '=', 2)[1]
$sfcCoreJar = (Resolve-Path ".\piq-sfc-arcade\build\libs\piq_sfc_arcade-$sfcCoreVersion.jar").Path
.\piq-sfc-home\gradlew.bat -p piq-sfc-home "-PgameConsoleJar=$fcJar" "-PsfcCoreJar=$sfcCoreJar" check jar
```

如不使用缓存，可以给 SFC 家用构建显式传入固定对；`sfcRuntimePython` 指向实际 Python 可执行文件，以下均为示例路径：

```powershell
.\piq-sfc-home\gradlew.bat -p piq-sfc-home `
  "-PgameConsoleJar=$fcJar" "-PsfcCoreJar=$sfcCoreJar" `
  -PsfcSnes9xCore=D:/approved/snes9x_libretro.dll `
  -PsfcSnes9xSource=D:/approved/snes9x-fae2fea08f74180759ef540ee94259213f503480.zip `
  -PsfcRuntimePython=D:/tools/Python/python.exe check jar
```

`sfcSnes9xCore` 与 `sfcSnes9xSource` 必须同时提供或同时省略；错摘要、缺件不回退到旧核心或任意历史产物。

上面的 SFC 家用产物仍是开发薄包，**不是玩家使用的完整 SFC 包**。上方快照构建入口会编译两个组件并核验合包；单独运行这些 Gradle 命令不会自动合包。仓库内锁定旧版本的历史合包脚本不用于当前产物。组件区别见 [SFC 说明](../piq-sfc-home/README.md)。

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
- **中文路径或缓存错误**：上述五个项目先核对 `printBuildWorkspace`，按“开发构建工作目录”设置 `PIQ_BUILD_WORK_DIR` 或 `-PpiqBuildWorkDir`；其他组件按自己的说明处理。保留首次失败日志，不删除整个工作区或系统缓存来碰运气。
- **测试通过但游戏异常**：自动测试不能替代 Minecraft 中的图形、输入、多人和保存恢复检查。

## 从开发产物到交付包

`check jar` 成功不等于稳定发行。交付前还需核对核心与资源许可、最终包内容和依赖，完成实际运行验证，再按[命名与交付规范](BRANDING.md)准备玩家文件。

`piq_retro_internal` 不放进玩家的 `mods`；SFC 薄包不冒充完整包。ROM、BIOS、玩家保存和私密构建材料不提交 Git。源码中仍使用 `piq_*` 内部名称，是兼容安排，不是另一个项目。

旧批次命令、测试数字和迁移细节保存在[构建历史](BUILDING-history.md)，仅用于追溯对应版本。
