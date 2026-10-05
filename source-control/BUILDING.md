# 从 Git 源码构建：一期（FC 主包）

## 当前补充：内容库收尾与旧核心退役（2026-10-04）

本轮为 FC76.40、完整SFC47（家用47＋内部core10）、MD14、街机1.5.7；完整配套、最终制品及未验项见[本轮指南](../piq-fc-arcade/design/内容库收尾与核心退役-20261004.md)。这是源码与候选构建说明，不是部署/发布授权。默认编译NeoForge21.1.236、运行最低21.1.229、Minecraft1.21.1/Java21不变；core10现在也使用共同Gradle兼容策略，不再让旧core9元数据例外约束新包。

FC主包新增公共 `ServerContentPaths`（纯路径/scope，无IO/迁移/授权）和可选文件式 `LibretroContentFiles` / `LibretroRuntime.loadFiles`（按文件暂存、身份复核、取消与大小边界）。SFC家用与历史独立柜调用公共路径；Native JNI Netplay经机柜/公共Netplay接入文件包。其他运行器的默认 `loadFiles` 明确不支持，不能仅因接口存在就声明全部核心可加载大ROM/BIOS。

### SFC 源码输入与完整包边界

先构建主包，再构建内部core10及家用薄包；附属不会嵌入另一份FC公共层：

```powershell
.\piq-fc-arcade\gradlew.bat -p piq-fc-arcade check jar --no-daemon
.\piq-sfc-arcade\gradlew.bat -p piq-sfc-arcade '-PgameConsoleJar=../piq-fc-arcade/build/libs/piq_fc_arcade-0.31.0-alpha.76.40.jar' check jar --no-daemon
.\piq-sfc-home\gradlew.bat -p piq-sfc-home '-PgameConsoleJar=../piq-fc-arcade/build/libs/piq_fc_arcade-0.31.0-alpha.76.40.jar' '-PsfcCoreJar=../piq-sfc-arcade/build/libs/piq_sfc_arcade-0.2.0-alpha.10.jar' check jar --no-daemon
```

SFC两处默认开发FC输入已为76.40，家用 `sfcCoreJar` 可显式指定，默认core10；发布依赖也要求FC≥76.40、家用要求core≥10，不允许缺新API的旧主包启动。路径相对于各Gradle项目目录；只有已具备完整Maven缓存时才加 `--offline`。本次没有重新编译WASM，普通Java内容目录维护不要运行 `native/build-wasm.ps1`。

完整玩家包仍须经审计合并家用薄包、内部core10及批准的资源，保留 `piq_sfc_home`、`piq_sfc_arcade` 两个MOD ID；不要给玩家另装薄包或重复旧core9。重建前已将未修改源码独立构建与冻结core9逐条比较：52个class全部相同、无新增/缺失/改变，WASM原件SHA不变。core10仅放行内容路径/后台库操作/server-only选择等明确变更，以及依赖/版本和四个本地化键（1改3增）；模型、核心WASM及无关资源仍按白名单保护。冻结core9和审计基线JAR保留，不覆盖旧成品；不能继续把core9包内元数据覆写方案用作当前完整包的构建方法。

本机正式core10 `check jar` 通过ABI、ROM仓库/复制迁移/取消生命周期及两项真实WASM smoke；家用47薄包517项测试（516通过、1跳过、0失败/错误）。完整日志为 `outputs/content-library-retirement-20261004/sfc-core10-check-jar.log`、`sfc-home47-check-jar.log`；基线记录在 `piq-sfc-arcade/build/reports/core9-source-baseline-20261004.json`。Windows符号链接创建探针无法执行，明确保留跳过；图形客户端/真人多人和完整合包验收不能用薄包测试替代。

本轮产生的core9/core10/home47三个 `-audit20261004.jar` 已核对SHA后归档到 `outputs/content-library-retirement-20261004/audit-builds/`，不留在管理台扫描的 `build/libs` 冒充最新成品；原冻结core9、正式core10和home47薄包保留原位置。JNI桥如在同一批后续复核中变化，以总指南记录的最终FC哈希和最终重核结果为准，不用前一轮依赖哈希覆盖它。

### BlastEm 不再是现行构建输入

MD14当前只保留既有Genesis Plus GX与独立PIQ Netplay核心两颗固定输入。全仓现行BlastEm调用已退役，新MD包不得包含 `blastem_libretro.dll`；未知/退役核心标识必须明确拒绝，不回退为另一个核心。原DLL、对应源码、许可证、旧存档和历史JAR只作保留证据；历史重建工具 `piq-md-home/tools/build_core.py` 须明确 `--historical-only` 且不能输出到当前资源目录。退役不删除别的核心所用的JNI/进程运行器，不转换任何旧保存。

以下章节保留对应历史构建批次；旧版本号/测试数字、core9冻结例外不作为本轮安装与构建合同。

## 历史：NeoForge 编译版与最低运行版分离（2026-10-04上一批候选）

该批七组件开发线为Minecraft1.21.1/Java21。共同 [neoforge-compat.properties](neoforge-compat.properties) 保留默认编译 `neo_version=21.1.236`，独立最低 `neo_min_version=21.1.229`。FC/SFC家用/MD/街机/电脑/PvZ使用 [共同Gradle策略](neoforge-compat.gradle)，`-Pneo_version=21.1.229`或235只指定编译测试目标，发行最低不跟着改变；拒绝非21.1、低于floor、其他MC与命令行最低值覆盖。GBA自建从同一策略展开最终元数据，但真实依赖目录仍须显式匹配该次测试版。

开发版本为FC76.39/完整SFC46/MD13/街机1.5.6/GBA15/电脑12/PvZ12；完整SFC须保留冻结core9，只在新合包元数据中白名单调整其NeoForge下界。策略检查入口是 `python -B -m unittest discover -s source-control -p "test_neoforge_compat.py" -v`；[只读最终包检查器](neoforge_compat.py)支持七次 `--jar`，覆盖完整SFC的两个依赖owner，不代替Maven依赖/实际加载测试。

已验证：229/236六Gradle组件每轮3,686项（3,676通过、10跳过、0失败/错误），GBA每轮六探针32,389检查全部通过且调用真实mGBA JNI；七个源码构建JAR两轮逐件同SHA（SFC此处为开发薄包）。七件完整冻结候选在229/235/236隔离专服均核验8 ID/版本、Done、正常stop与exit0，QA原53文件全恢复、无遗留进程。54项Python工具单测为52通过/2跳过，7项真实配置正负门禁和68项Maven边界/互依赖检查均达到预期。仍非稳定版：图形客户端、多人与Iris未验；不能把235配置/专服通过说成235整套源码重编通过。完整七SHA、core9保留、PvZ仅来源登记文档例外及229首轮检查器误报复测说明均在下方指南；最新目录/管理台同步以另行回执为准。

原236及更高兼容21.1用户无需主动降级；整合包其他MOD的更高最低要求仍有效。229下界的官方安全修复依据、同批安装规则、旧core9例外及明确未验项见[本轮完整说明](../piq-fc-arcade/design/NeoForge兼容范围-20261004.md)。Flash/J2ME/旧独立SFC core不纳入此批，原固定输入与构建授权门禁不变。以下保留构建标准化一期的历史范围与证据，不把旧测试数计为本次通过。

适用：2026-09-29，FC76.24/76.25源码、Java21、项目wrapper锁定Gradle9.2.1与NeoForge21.1.236。这是开发构建说明，不是游戏升级通知。

FC76.25 的功能修复将第9项 JNI Netplay 核心更新为 `mesen-jni-netplay-r2`，仍共9个输入；r1缓存不删除且不能代替r2。原生重建入口见 [Mesen r2](../piq-fc-arcade/native-mesen-netplay/README.md)，导入使用 `python source-control/build_inputs.py import --id mesen-jni-netplay-r2 --file "实际重建目录/mesen_piq_jni_netplay_r2.dll"`。游戏行为/存档变更另见 [FC76.25指南](../piq-fc-arcade/design/FC76.25-JNI游戏加载修复.md)。下方第3、4节保留最初76.24构建迁移的历史证据，不作为76.25未修复的结论。

## 先说明边界

本期将FC的9个外部二进制输入接到固定SHA/大小的清单与离线缓存，构建不再依赖维护者的outputs目录、源码树里未提交的WASM/DLL或默认JNI dist。**依赖缓存仍须由维护者提供合法制品或按对应源码构建，尚不是“只克隆就能下载并重建所有原生核心”**。本工具没有网络下载或执行二进制的功能。

其他附属尚未全部迁移：SFC/街机/电脑/PvZ仍有旧FC JAR依赖，Flash使用历史FC61，GBA使用自定义构建。不要把一期当作全部机型的标准化已经完成，不要为了构建通过随意替换旧依赖或放宽版本声明。

## 1. 准备固定输入

审核 `build-inputs.json`；缓存默认仓库根 `.build-inputs/<sha256>/<filename>`，不进入Git。`external-artifacts.json`仍保留上一轮历史盘点，不是本次的构建锁。

维护机一次性导入旧文件（源只读，不移动/删除）：

```powershell
python source-control/build_inputs.py import --from-root "G:/服务器/服务器Codex"
python source-control/build_inputs.py check
```

其他协作者可直接使用维护者提供的这个缓存，或逐项导入明确的文件，例如：

```powershell
python source-control/build_inputs.py import --id libretro-jni-abi2 --file "D:/approved/piq-libretro-jni.dll"
python source-control/build_inputs.py check --cache "D:/approved/build-inputs"
```

单项导入默认写本仓库缓存；如想写其他缓存，同时传 `--cache`。导入所有选中源之前先检查大小/SHA及已有目标；同哈希文件复用，不覆写冲突。中断可能留下不完整缓存文件，后续校验会拒绝；先人工核对/保留该具体文件，再重新导入，不清空整个缓存。拒绝链接/重解析点，禁止拿随机同名DLL补缺项。

`legacyPath`仅用于明确运行`import --from-root`的迁移工具，Gradle构建不会读它。固定SHA表示选择了这些字节，不代表核心的所有来源/许可证缺口已闭合。许可及原生源码构建说明仍见主包THIRD_PARTY_NOTICES、native/libretro-jni、netplay-native和core对应README；公开分发前另行审查。

## 2. 构建与校验

在新的Git克隆根、安装Java21后：

```powershell
python source-control/setup.py
python -B -m unittest discover -s source-control -p "test_*.py" -v
python source-control/build_inputs.py check
.\piq-fc-arcade\gradlew.bat -p piq-fc-arcade --max-workers=2 check jar
```

缓存不在默认目录时，Gradle加 `-PpiqInputsDir=D:/approved/build-inputs`；相对值相对FC项目目录解析，建议跨克隆用绝对路径。依赖缺失/大小或SHA错误在配置阶段明确拒绝，不回退历史路径。旧`-PjniRuntimeDir`明确拒绝；要用新DLL须审核修改清单和对应源码/许可/测试，再导入新哈希，不能悄悄换桥。

第一次Gradle运行需要正常获取wrapper/Maven/NeoForge依赖；只有已完整缓存时才加`--offline`。本期不新建公网CI、不上传缓存，不保证尚未测试的平台也能构建。`check`使用原有测试及包级冒烟，不替代Minecraft真人联机、JNI启动及长期运行验证。

构建结果在 `piq-fc-arcade/build/libs`。这是当前源码开发中间件，可能包含尚未获准发行的源素材草稿，**不得自动替换已交付JAR、安装或标稳定**。正式候选仍走素材白名单、许可、最终包/运行验证、成品摘要和发布授权。

## 3. 实现与后续

对外命名补充（2026-09-29）：`build/libs` 的原内部文件名保留，以免破坏已有固定版本编译依赖。经过完整交付审核的 JAR 再用 [Game Console 命名工具](BRANDING.md) 生成正式名称的字节相同副本；工具不补运行库、不合并 SFC、不更改版本或兼容范围，也不把开发构建自动认证为可发布。

`locked-inputs.gradle`是可复用构建期解析器；FC worker/JNA、Mesen两平台、共用JNI、Netplay EXE、三个WASM及FC JNI核心由缓存复制到生成资源，原src中的旧二进制保持但不再优先使用。运行时格式/路径/SHA合同不变，游戏核心没有重新编译。

后续分批处理：附属源码依赖/完整打包、原生工具链及可取得的对应源码/许可证、空缓存CI与远端备份。各阶段单独测真实构建，不用“已写清单”替代“已构建通过”。本期验证证据会登记在工作区 `outputs/build-standard-phase1-20260929/` 和维护手册/工作台。

## 4. 本期实际验证（2026-09-29）

- 独立 Git 克隆加本次精确源码覆盖，4287 个源码文件；没有旧 outputs、默认原生库或 `.build-inputs`，只显式指定维护机的已校验缓存。Java21、已有 Maven/NeoForge 缓存、离线运行 `check jar` 成功。
- JUnit 2558 项：2548 通过，10 跳过，0 失败/错误；原有三个包级冒烟随 `check` 通过。工具/真实 Git 检查19项：18通过，1项因 Windows 符号链接创建权限跳过。
- Gradle 真实缺件、同大小坏 SHA 均明确拒绝；JAR ZIP 完整且无同名重复项，9个固定输入 SHA 正确，345份源资源与包内字节相同。在隔离克隆的原资源目录加入4份错误二进制，重新处理资源仍只选缓存中的正确字节；未修改维护机原件。
- 保留两个失败尝试：第一次暴露首次 Git 的 world 忽略规则漏纳源码，原样补纳64个既有 Java 文件；第二次暴露替换资源根会妨碍 ModDev 自动发现 access transformer，已保留真实资源根并仅排除旧二进制副本。没有改游戏 Java 代码来掩盖构建问题。
- 验证包：`outputs/build-standard-phase1-20260929/attempt3/source-check-1/piq-fc-arcade/build/libs/piq_fc_arcade-0.31.0-alpha.76.24.jar`，SHA256 `9794ae2182cae56d420fa3cf21a6b9860b9987d975e892535ba087f22b7a6e78`。这不是新发行文件，不装入 mods，不替换已交付包；版本号未升级。

证据在上述 `attempt3/verification.json`、`resource-verification.json`、`fc-check-jar.log`，属于维护机本地验证资料，不包含在源码克隆中。没有进行 MC 实机、全附属构建、空 Maven 缓存或原生源码全量重编，也没有解决此前 JNI 启动反馈。
