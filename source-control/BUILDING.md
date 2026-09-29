# 从 Git 源码构建：一期（FC 主包）

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
