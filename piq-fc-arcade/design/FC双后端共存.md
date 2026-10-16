# FC 双后端共存

FC 首批接入保留现有 JNI Netplay，并提供独立进程 RetroArch Netplay。两者共用设备设置、席位权限、内容传输、存档归属和旁观入口，但不混用运行时、同步协议或即时存档。此项不将 RetroArch 嵌入 Minecraft JVM，也不修改任何模拟核心。

## 范围与基线

- 当前源码候选：FC76.58，保留 FC76.57／完整 SFC55 的输入热修、旧同步退役及公共设备接口。FC76.55 的历史结果单独保留在下文，不作为本批结果。
- 首批对象：Windows x64 的 FC 双手柄。光枪保留既有路径，需另行验证，不能将双手柄结果等同于光枪验收。
- 后续机型：通过可信本地后端声明适配；本次不宣称 SFC、MD、街机已切换到 RetroArch。
- 新设备默认仍为 JNI Netplay；已有设备的明确设置保留。玩家可以在停机时选择独立 RetroArch，不在运行中或失败后自动换后端。
- 本次新增一个独立的官方 FCEUmm 声明；不替换旧核心，不包含 GBA 交互修复、旧档转换、游戏安装、服务器部署或公开发行。

### 联机版本要求

FC76.58 使用 `netplay-6` 冻结本局后端，设备设置升为 `home-sync-7`，携带六种模式的禁用原因和明确的 FC 后端变体。服务端及所有客户端须配套升级主包，包括仍选择 JNI 的玩家；旧即时档兼容不代表旧主包网络兼容。`home-launch-3`、SFC55 协议 `15` 不因本项改变。旧 `Setting`、FC 后端和保存 Java 重载保留；旧五条原因补第六条“不可用”，附属不能靠通用掩码继承 FC 新模式。SFC、MD 等配套旧调用者已通过本批隔离专服加载回归，Minecraft 图形交互另验。

| 设备选择 | 存储与执行 | 兼容边界 |
| --- | --- | --- |
| mode 4：JNI Netplay | 原 `NetplayExperimental`／`NetplayJniTrial`；仍为新机默认 | 原 JNI 核心、协议和存档身份保留 |
| mode 3：旧 RetroArch Netplay | `NetplayPadVariant` 为空；使用原普通 Mesen | 旧 NBT 缺字段也按空串读取；不把已知恢复差异说成已修复 |
| mode 5：RetroArch 双手柄（FCEUmm） | 管理员空闲时明确选择，保存 `NetplayPadVariant=fceumm-v1` | 仅 FC 双手柄；未知字符串原样保留并拒绝开局，不回退；光枪或 JNI 与该变体组合均拒绝 |

设备菜单通常仍显示五行；已选退役 mode 1 时才增加说明行并收紧行距，不重新授予旧模式。修改设备设置不会转换进度，开局后各端跟随冻结的完整后端合同，运行中不能换核。

## 实现依据

采用现有 `NetplayProcess`、`NetplayRelay`、`NetplayVariant`、`NetplayLaunchSpec` 和存档通道，不另造模拟器、回滚算法或外部端口服务。RetroArch 自主推进时钟并执行其 Netplay；不能把轮询输出帧当作同步 `run()`，也不能假定其具备 JNI 的准备后激活接口。

原固定输入保持原摘要：RetroArch 1.22.2 PNP7 外层适配运行器 SHA-256 `1335c34b04d8c3e55afe010a421ff373d938929ec196a6e93a9657972fe034e1`、普通 Mesen、JNI Mesen r2 均未改动或重编。FC 锁表从 10 项增加为 11 项，唯一新增项为 `fceumm-retroarch-pad`；只允许已登记的本地资源，网络消息不能指定可执行文件或 DLL 路径。

2026-10-17 复核 [RetroArch Netplay 协议](https://docs.libretro.com/development/retroarch/netplay/)、[FCEUmm 官方功能与控制器说明](https://docs.libretro.com/library/fceumm/)、[Mesen 功能声明](https://docs.libretro.com/library/mesen/)及候选参考源码。官方 Netplay 标记只作为筛选依据；FCEUmm 经下列独立恢复、最终包真实三端及逐次恢复分段验证后，用于新增候选路线。此结论限已测场景，不代替 Minecraft 多人或稳定发行验收。

### 固定核心与选型结果

- 官方 Windows x64 FCEUmm DLL：4,162,048 字节，SHA-256 `a04f7fa28bdd46f602bb62750ab9b5e360d8e8d8ece97de8ad3ef1b669257ffd`；运行报告 `FCEUmm (SVN) ac0d00a`。[官方 ZIP](https://buildbot.libretro.com/nightly/windows/x86_64/latest/fceumm_libretro.dll.zip) 地址会变化，不允许以不同字节静默替代。
- [参考源码 ac0d00a7fc01166f84c7bac01d1084ebfd2b865e](https://github.com/libretro/libretro-fceumm/tree/ac0d00a7fc01166f84c7bac01d1084ebfd2b865e)与运行报告的短提交一致；这不是上游二进制可复现构建证明。归档摘要和 GPLv2／附带组件许可原文见[包内来源说明](../src/main/resources/core/fc-retroarch/README.md)。
- 声明限定 `.nes`、文件式加载、NTSC、两口 Gamepad `257/257`、48 kHz、软件画面；不提供外部 BIOS、HD 包、四人或新光枪能力。固定选项见 [FcRetroarchPad](../src/main/java/cn/piq/fcarcade/netplay/FcRetroarchPad.java)：NTSC／48KHz／Low 音质，低通关闭、默认调色盘、四边 overscan 为 0、无限精灵／超频／Game Genie 关闭、turbo 为 None；其余使用该固定 DLL 默认值。更多 mapper 和外设需要另验。
- 普通 Mesen 保留为旧路线，其恢复失配没有被改核或忽略。Nestopia 未采用：独立 JNI 恢复通过，但现有 PNP7 种子恢复顺序下，两端首次取得的状态长度分别为 21,831／21,822 字节；相同帧较短部分完全相同，较长端多九个零，能精确解释完整 CRC 差异。本次没有修改核心、调整运行器时序或放宽 CRC 来采用它。

FCEUmm 能直接使用现有固定 PNP7 与通用 Netplay 保存／中转接口，因而无需重新开发核心、回滚算法或另一套传输服务。核心能力、运行器合同、MC 权限及呈现仍分别验收。

## 流程与隔离

| 环节 | 复用与边界 |
| --- | --- |
| 设备设置 | 保留螺丝刀菜单、服务端距离和管理权限检查；忙碌时拒绝改后端。 |
| 开局 | 服务端冻结内容、后端、核心、端口和存档归属；客户端只解析可信声明，身份不同即拒绝。 |
| 双人 | 复用 1P/2P 授权及中转；RetroArch 双手柄 2P 使用其原生参与者输入，旁观不获得输入权。 |
| 准备 | 沿用存档准备、读取及 READY 门禁；RetroArch 的本机画面就绪不等于其他端已同步。 |
| 存档 | 原 JNI 与 Mesen RetroArch 身份不变；新路线使用 `core\|nes-netplay-fceumm-v1\|` 独立域，保留个人或卡带归属。选档仍为 1～3，不增加第 4 槽；历史各域文件保留，不宣称磁盘上所有旧新格式合计最多三份。禁止自动跨后端转换。 |
| 旁观 | 绑定同一房间的后端及内容；退出旁观不能关闭主持游戏。FC 提前声明执行类型，RetroArch 仅允许空闲单源，JNI 沿用四槽上限；保留先准入来源，第二路与混合来源在内容下载前静默拒绝。 |
| 退出 | 释放输入、撤销旧票据、确认最后保存及进程实际退出；失败保留最近确认的存档。 |
| 提示 | 标明实际后端与不兼容原因，不把进程启动等同于联机成功，不悄悄改回 JNI。 |

## 验证门槛

自动检查覆盖后端声明、内容与保存身份、错误后端拒绝、既有调用者及网络注册。独立探针使用本次最终 JAR 的固定运行器与核心，以及原创诊断 ROM，验证主机、真实 2P、只读观众、保存重开、输入释放及进程回收。同帧完整 CRC 不同仍判失败，不忽略真实差异。

Minecraft 双客户端的界面、权限、跨距旁观、声音与长时间运行须另列实测结果；隔离探针不能代替游戏内验收。正常关闭与强制结束分别记录，不能用已调用 `close()` 代替资源回收证据。

## 当前状态：FC76.58 候选

最终 FC76.58 自动构建、包内核心恢复、0／75 ms 真实三端及隔离专服加载已通过下列范围，作为本地开发候选交付。旧探针在重连接入期间报出的 CRC 差异及诊断记录保留；修正观测阶段判定后，对同一最终 JAR 重新完成全套验证。不能标为稳定或已完成 Minecraft 多人验收。

- 固定 FCEUmm 的独立 JNI 恢复检查：10 次原生打开，6,028 次完整状态／SRAM／RTC／像素／PCM 比较，零差异；同实例重演及新实例预运行后恢复分别覆盖。这里借公共 JNI 桥检查核心状态，不代表把新生产路线改为 JNI。
- 真实 RetroArch HOST／2P／只读观众筛选：0 ms 和有序单向 P2→HOST 75 ms 两轮各 256 项检查通过；共 214 条同帧完整 CRC，零差异。覆盖新开、双端口输入、晚加入观众、P2 断开重连、保存并重开第二代会话；14 个子进程均正常退出，专属工作目录回收。
- 范围仅原创 mapper 0 诊断 ROM。初版 ROM 的定长音自然结束曾使“恢复后非零 PCM”检查失败；独立控制实验确认后，只将原创 ROM 改为持续音，严格音频和 CRC 断言均保留，核心及运行器未改动。
- 最终 `check jar` 通过：3,089 项 Java 测试，3,077 通过、12 条件跳过，零失败／错误；构建链 Python 检查 249 项，246 通过、3 条件跳过。固定输入、既有运行库与公共 Replay 合同检查通过。最终开发 JAR 为 40,667,649 字节，SHA-256 `bccc1563b2b9552f1aa61ce9d16a856083e06b59d192d1b422d057ce8e3eb0ad`。
- 最终冻结探针自检 92 项、协议解析器 320,238 条断言通过，使用最终包再次完成 JNI 6,028 次比较／10 个实例，零差异。真实三端 0 ms 和有序单向 P2→HOST 75 ms 各通过 452 项检查；共 16 个 LOAD 恢复段、254 条恢复后同帧完整 CRC，全部相同，无缺失配对，各段至少两条。两轮没有初始化差异豁免样本；14 个子进程全部正常退出 0、未强杀、专属工作目录回收，并独立核对 PID 与启动时间对应的进程已消失。原始流、状态命令、分段、CRC 与退出证据分别保存。
- FC76.58／完整 SFC55 及五个既有附属的 NeoForge 21.1.236／MC 1.21.1 隔离专服合装通过：七个 JAR、八个模组 ID 正常加载，启动至 Done 后以 `stop` 正常保存世界并退出 0，QA 启停总时长 17.51 秒。原实例、运行库、现用包及源 JAR 哈希不变；这不代表 Minecraft 图形多人验收。
- Minecraft 图形界面、两名真人操作、实体权限／跨距返回、实际音画、弱网丢包／抖动、长局、更多 mapper 均待验；75 ms 有序延迟不等于这些场景已通过。新路线光枪明确不支持；旧光枪路径保留但不由双手柄探针认证。

### 功能说明 1.3／N10～N15 对照

| 项目 | 本轮实现和隔离证据 | 仍需验收 |
| --- | --- | --- |
| N10 共存与真实运行 | `FcNetplayBackend`／`NetplayVariant` 冻结可信声明；旧 JNI／Mesen 与新官方进程路线独立，最终 Java 回归、0／75 ms 真实三端及旧附属隔离专服合装通过 | MC 待验；不扩展为全机型共存 |
| N11 恢复与确定性 | 核心完整状态、SRAM、RTC、呈现像素与 PCM 分开比较；RetroArch 执行自己的恢复／输入同步，同帧完整 CRC 不掩码、不归一化 | 诊断 ROM 之外 mapper、长时间和丢包／抖动待验 |
| N12 开局和端口 | 复用 `HomeLaunchServer`；冻结设备 2P 许可、内容、变体与保存域；两端口在真实进程探针分别驱动 RAM／画面，释放后恢复静止输入 | MC 真实入口、不存档／空槽／已有槽、取消及失焦流程待验 |
| N13 只读旁观 | 复用 Watch 授权与 `NetplayWatchContent`，观众跟随冻结后端；探针验证晚加入、无操作权、P2 重连与保存后新会话观众 | MC 离远返回、多人席位变更及旁观准入实测待验 |
| N14 保存与归属 | 复用 `NetplaySaveServer` 的准备／激活／确认；新独立域，旧身份和 1～3 槽保持；最终包 0／75 ms 原生保存重开、存储回归及冻结旧 JNI 文件读写检查通过 | MC 周期／手动／最终保存、个人／卡带闭环待验 |
| N15 失败和收尾 | 不兼容、未知变体和新路线光枪拒绝；设备忙碌拒绝修改；最终单元回归通过，最终两轮 14 个原生进程均有身份和正常退出／目录回收证据 | MC 断线／晚包、弱网长测待验；原首次失败缺失的 PID 不补造 |

本候选未安装、部署或发布。通过的核心筛选不解除最终包、公共接口、许可来源或游戏内验收要求。

### 初始化差异与观测器修正

首次最终包 75 ms 复验在 g0 重连接入的帧 1920 报失败：主持 CRC `0f370fc0`、重连端 `676ca8cf`。该轮没有捕获实际 SYNC SRAM，原失败不改写为通过。固定核心冷态第 17 帧加模拟 SRAM 计数 126 可唯一复现这个完整 CRC，支持初始化解释，但不是远端完整状态的逐字节证明。

源码及独立受控诊断确认：PNP7 的 SYNC 先设置帧号与 SRAM，完整 LOAD_SAVESTATE 随后才到；诊断 CRC 输出发生在原生有效性筛选之前。受控诊断只在测试中延后首个完整 LOAD，实际旁录 SYNC SRAM 的冷态重放精确复现初始差异；该 CRC 被观察时，LOAD 尚未交付，之后 13 条恢复后 CRC 全部与主持相同。此诊断仍保留失败退出码，未改动生产核心、运行器或 JAR。

正式[探针](../tools/qa/RetroArchNetplayProbe.java)按完整有序协议旁录每次恢复，不按 mode 或任意等待过滤。初始 LOAD 帧为 S 时，C>S 必须与主持同帧完整 CRC 相同；C==S 的差异默认失败，仅有确切的“CRC 已观察、LOAD 尚未完整交付”证据时才可归入初始化。初始化差异还须用该次真实 SYNC SRAM 冷重放完整 CRC 精确匹配，所有样本保留。每个恢复段至少两条严格配对；未配对、截断、后续恢复遮盖未验段、加载错误、NAK 或非预期断开均失败。负例使用相同判定函数，原 Mesen／Nestopia 的恢复后失配仍会失败。

最终两轮的冻结探针 SHA-256 为 `d467153a4506b7e14429651f6dc7749fde4e28da4d79200e5b095723ddb84353`，只以其编译类及上述最终 JAR 为类路径，没有替换原生资源或声明。两轮全部 254 条严格配对通过，未使用初始化差异豁免；不以一次新的成功掩盖原失败或扩大验证范围。

### 复现最终包探针

在 Windows x64、Java 21、仓库根执行。先按[构建指南](../../source-control/BUILDING.md)构建 FC76.58；将 `$fcJar` 指向待验证的实际主包。`$qaRoot` 使用全新的本地目录，保留已有结果；无需商业 ROM、额外核心或 Minecraft。原生检查会启动隔离子进程，测试完成后应核对 `children.tsv`、`result.txt` 和退出码。源码 ZIP 不包含固定原生输入，仍需按锁表准备。

```powershell
$ErrorActionPreference = 'Stop'
$fcJar = (Resolve-Path 'piq-fc-arcade/build/libs/piq_fc_arcade-0.31.0-alpha.76.58.jar').Path
$qaRoot = Join-Path (Get-Location) ('qa-fc-' + [guid]::NewGuid().ToString('N'))
$classes = Join-Path $qaRoot 'classes'
$temp = Join-Path $qaRoot 'temp'
New-Item -ItemType Directory -Path $classes, $temp | Out-Null
Get-FileHash -Algorithm SHA256 -LiteralPath $fcJar
javac --release 21 -proc:none -encoding UTF-8 -cp $fcJar -d $classes piq-fc-arcade/tools/qa/RetroArchNetplayProbe.java
if ($LASTEXITCODE -ne 0) { throw '探针编译失败' }
$probe = 'cn.piq.fcarcade.netplay.RetroArchNetplayProbe'
java "-Djava.io.tmpdir=$temp" -cp "$classes;$fcJar" $probe --self-test
if ($LASTEXITCODE -ne 0) { throw '探针自检失败' }
java "-Djava.io.tmpdir=$temp" -cp "$classes;$fcJar" $probe --restore-diagnostic (Join-Path $qaRoot 'restore')
if ($LASTEXITCODE -ne 0) { throw '核心恢复检查失败' }
foreach ($delay in @(0, 75)) {
    java "-Djava.io.tmpdir=$temp" -cp "$classes;$fcJar" $probe (Join-Path $qaRoot "delay-$delay") $delay
    if ($LASTEXITCODE -ne 0) { throw "原生三端检查失败：$delay ms；保留全部输出" }
}
```

重新构建的 JAR 不保证与本次交付逐字节相同；应记录实际摘要。探针仍强制核对内置核心与运行器身份，不允许用其他来源覆盖类或 DLL 来凑齐检查。

## 历史状态：FC76.55／2026-10-16

FC76.55 接线已完成，但**原生同步验收未通过，不作为可靠联机更新交付**。

- 最终固定输入 `check jar`：3041 项 Java 测试，3030 通过、11 条件跳过、零失败/错误；包含新增后端合同、实际网络注册和旁观准入测试。打包运行库、旧 worker 与回放 API 检查通过。
- 最终 JAR 配原六附属在 NeoForge 21.1.236 隔离专服中正确加载 8 个 MOD ID，正常启动并以 `stop` 退出，exit 0；原测试实例完整恢复。这不是 Minecraft 图形多人验证。
- 原生 HOST/2P 的同帧完整 CRC 不一致。随后用最终 JAR 内相同、未修改的普通 Mesen 在隔离 JVM 复现跨实例恢复后的状态差异；原创读手柄 ROM 的 `_lagCounter` 多 1，不读手柄的等周期对照无差异。固定参考源码的 [`ControlManager::Serialize`](https://github.com/libretro/Mesen/blob/0102910c39ad1a62bc3f784466f3f67ca9eae335/Core/ControlManager.cpp#L335) 未保存影响该计数的 `_isLagging`，与实测相符。仅此差异不证明玩家已见错帧或崩溃，但严格恢复身份门槛未通过。
- 诊断为了匹配上游合法选项，仅在隔离测试中把四项 overscan `0` 改为 `None`；生产选项和旧保存身份不变。六次诊断核心关闭后均检查四个 JNI 槽位空闲，共享桥目录按既有设计保留，未宣称全部临时目录已清空。
- 三端、弱网、保存重开、光枪、Minecraft 多人及长局未完成验收。没有屏蔽 CRC、生产预热绕过、修改核心或拿旧补丁核心冒充原版。

当时结论为先解决固定核心的恢复身份问题，再考虑其他未经修改的上游核心和独立保存身份；没有转换旧档或发布该候选。上方 FC76.58 是后续新增明确路线及重新筛选的记录，不将这里的失败改写为通过。
