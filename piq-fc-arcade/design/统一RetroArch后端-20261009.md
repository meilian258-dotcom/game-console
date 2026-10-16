# 统一 RetroArch 后端：首批制作记录

本文保留 FC76.47／街机1.5.9 及所列后续批次的历史实现、测试和当时选型。现行方向已调整为 JNI 与独立 RetroArch 共存，见[制作规范第 1.8 节](机器制作与交互标准.md)；下文“统一 RetroArch、JNI 暂留”不再作为新开发的替换要求。FC 双后端独立候选未通过真实核心同步／恢复验收，未合入当前 FC76.57 源码；本文的 LOCAL 探针通过不能代替该候选的联机验收。

后续 FC76.49／街机1.5.11 已增加独立的[原版 FBNeo RetroArch Netplay 试验](RetroArch-Netplay-街机试验接入.md)，使用公共后端声明、文件内容与整组选择；不改变本文首批 MEDIA 的历史范围。新路线完整状态一致性验收仍未通过，不保存、不默认替换旧 JNI。

2026-10-10 后续候选 FC76.48／街机1.5.10：补齐本机 RetroArch 启动失败的角色文案、关闭前本机诊断保留及 MAME 明确缺件报告；设备按钮统一写“玩家音画串流”（原“玩家托管”）。固定核心、默认 JNI、输入／保存身份和其他运行模式不变。不是补齐 ROM／BIOS 或所有游戏可运行的声明；下文 2026-10-09 测试数量属于首批，后续验证见对应交付清单。

原生输出只存有界本机内存诊断，关闭前经 `CabinetEmulator.failureDiagnostic()`／`RetroEmulator.failureDiagnostic()` 留存，复制诊断前应检查私人路径。接口默认空字符串，旧调用者行为不变；不给服务器发送原生日志。公共层负责区分 LOCAL／Netplay 并有界等待输出排空，MAME 专有缺件解读留在街机适配器，未知退出不猜原因。不改文件上传或共享清单授权。

该后续候选验证：主包 2,892 项、街机 165 项自动测试零失败／零错误，11 项既有跳过；固定 MAME 原创媒体三次各 120 帧及坏 ZIP 明确拒绝通过，核心／素材／许可保持。实际缺件内容能保留原生原因、原件不变，但快速失败场景的严格进程身份观测未通过，完整退出验收仍失败，不据此宣称该场景全面修复。Minecraft 图形／多人／长局与补齐游戏数据后可玩性待验；不得标为稳定。

日期：2026-10-09。维护：像素匠。适用开发候选：主包 `0.31.0-alpha.76.47`、街机 `0.1.5.9`。本记录按[制作规范](机器制作与交互标准.md)、[制作模板](功能制作说明模板.md)和[验收清单](功能制作验收清单.md)填写；固定资源及逐模式能力见[能力声明](统一RetroArch后端能力声明-20261009.md)。

结论：整个模组统一公共 RetroArch 后端是后续方向，JNI 暂时保留兼容。首批已接公共 LOCAL 自主会话和街机客户端 MEDIA 显式入口，默认 JNI 不变。主包最终全量测试、街机自动测试、双方构建及固定 MAME 隔离适配器的音画／输入／回收首验通过；Mesen LOCAL 真实保存重开探针通过，但 Native MEDIA 仍未绑定保存。尚未完成其他机型迁移、Minecraft 人工验收或长局验证，不标稳定。

## 1. 范围与需求

本批复用既有 `NetplayProcess` 的可信资源提取、PNP7 音画／输入协议、内容暂存和进程管理，增加不走 Netplay 网络的 `LOCAL` 角色及独立公共外观 `RetroArchSession`。原生街机公共机柜客户端 MEDIA 是第一个调用者。没有替换核心或 ROM 集，没有删除 JNI，没有改全局默认，没有迁移旧档。

| 编号 | 预期结果与实现位置 | 验收与当前状态 |
| --- | --- | --- |
| R01 | 主包 `retroarch/RetroArchSession` 包装 `NetplayProcess.Role.LOCAL`；自主计时，不伪造 STEP | 源码／合同及隔离真实核心首验通过；MC 待验 |
| R02 | LOCAL 不创建 Netplay 房间／通道，不传 `--host`／`--connect`；仅保留认证本机音画 IPC | `RetroArchLocalContractTest`；不等于 MEDIA 无 Minecraft 网络 |
| R03 | 固定可信核心／依赖与 `LibretroContentFiles` 后台暂存，摘要、名称、大小、取消边界不放宽为任意文件执行 | 合同测试及固定 MAME 隔离首验通过；不推定任意内容兼容 |
| R04 | LOCAL 独立保存身份；`terminated()` 成功需子进程退出和工作目录移除 | 身份／收尾合同测试；不等于街机已接持久化或优雅原生卸载 |
| R05 | `NativeCabinetBackend.open` 显式选择 `NativeRetroArchMediaSession`，复用四口输入与公共 MEDIA | `NativeMediaBackendTest`、`NativeRetroArchMediaSessionTest`；MC 席位／投币／旁观待验 |
| R06 | 规范改为统一 RetroArch、JNI 兼容；实际与目标分别记录 | 已同步主包／公共层 README、制作规范及总览；历史事实保留 |

不在本批范围：FC/SFC/MD/GBA 及其他运行路线迁移，RetroArch Netplay 接通，服务端托管切换，逐帧同步，光枪／模拟轴，暂停／RESET／运行中恢复，街机三槽或 NVRAM 业务，以及部署、发布、服务器重启。

### 1.1 基线覆盖

以下按[基线 B01～B16、C01～C06](附属功能基线与现状对照-20260930.md)逐项记录。此处“沿用”不代表本批重新验证旧功能。

| 基线 | 适用性与复用入口 | 本批改动／缺口 | 验证状态 |
| --- | --- | --- | --- |
| B01 方案复用 | 适用；官方 Libretro 文档、现有 NetplayProcess／公共层／Native MEDIA | 增加最小 LOCAL 分支，不复制桥 | 调研和源码核对完成，见第 2 节 |
| B02 装载依赖 | 适用；主包与街机双方安装，公共库只由主包提供 | 提升配套版本；没有独立公共库 MOD | 主包／街机构建通过；最终 JAR 专服与客户端启动待验 |
| B03 内容入口 | 适用；公共机柜游戏库、CabinetSharedGames | 上传／选择／授权沿用；本地文件暂存适配 | 文件合同测试；MC 上传、取消、撤权待验 |
| B04 实体介质 | 本街机入口不适用卡带；选游戏绑定机柜 | 不造假卡带；FC/SFC/MD/GBA 介质迁移未做 | 家用／掌机仍列后续范围 |
| B05 接线业务／可见线 | 联柜数据线适用；家用 AV 不适用此入口 | 原 CabinetLinks／主副柜映射不改 | 四向、断线、卸载、扣还待 MC 回归 |
| B06 电源／物理互动 | 适用；公共机柜实体电源与命中 | 只换显式选中的 MEDIA 核心适配 | 真实开关／灯／音效／再次开机待验 |
| B07 控制／动画 | 适用；四个端口、NativeJniInputBuffer、公共输入／席位 | PNPI 时取输入；保留短按、投币及单次 MAME 映射 | 输入合同测试；真人各端口与动画待验 |
| B08 配置／诊断 | 适用；螺丝刀、公共设备页及会话 diagnostics | 本机属性 opt-in；诊断明确 LOCAL／不存旧档 | GUI／OP 撤权待验；启动参数不替代设备页 |
| B09 范围／生命周期 | 适用；原操作 8 格、发现 16 格／退出 20 格、空席规则 | 异步退出报告真实完成；不扩大订阅范围 | 收尾合同测试；取消、断线、移除与旧回包待 MC 验证 |
| B10 配置归属 | 适用；全服／设备／客户端分层 | 仅本机 `piq.arcade.mediaBackend`，默认 jni；不从网络／存档读取 | 属性解析测试；不改世界配置 |
| B11 进度保护 | 适用；保存能力须如实说明 | 公共层有独立身份；本街机入口未绑定 persistence，不存进度 | 旧档隔离合同测试；持久化业务未完成 |
| B12 模型／UI | 适用；公共三种机柜和帧几何 | 模型、菜单布局不改；避免宽高比／转向二次转换 | 帧适配测试；四向、竖屏、Iris、听感待验 |
| B13 运行层 | 适用；该历史行“默认公共 JNI”的方向已由现行规范 1.8 更新 | 统一 RetroArch 是目标；首批 opt-in，JNI 暂留兼容 | 不能据首批宣布全模组迁移 |
| B14 分层测试／成品 | 适用；单元、核心、JAR、MC 分层 | FC／街机候选已构建 | 主包最终全量、街机测试及隔离核心探针通过；MC／长时未验 |
| B15 性能／资源 | 适用；后台 owner、内容／帧／输入有界 | 自主运行不同于逐帧；不挪用 JNI 四槽作进程预算 | 合同检查；CPU／内存／帧率／多会话实测待验 |
| B16 追踪交付 | 适用；固定核心、配套版本、许可与对应源码 | 本文与能力声明记录；旧证据不重认证 | 候选待验收；成品最终清单与人工验收另记 |
| C01 串流／旁观 | 实验；沿公共 MEDIA 授权与音画流 | 不是新 Netplay 旁观或混合多屏实现 | MC 主持、晚加入、远处主持及返回待验 |
| C02 本地同步 | 新 LOCAL 不支持；旧 snapshot 路线保留 | 不提供 STEP／状态同步 | 未迁移，不按 LOCAL 验证代证 |
| C03 Netplay／回滚 | 新 LOCAL 不支持；既有 FBNeo JNI Netplay 保留 | 不改房间／回滚／网络授权 | 未迁移，见 N10～N15 |
| C04 服务端托管 | 本批未接 LOCAL；原服务端工厂保留 | opt-in 仅客户端公共 MEDIA | 专服原路线回归待验 |
| C05 投币／维护／联柜 | 适用；CabinetCoinService／CabinetSeats／服务端维护权限 | 保留授权后单次扣币、投币释放与四口映射 | 合同测试；实际实体币、OP、主副柜待验 |
| C06 特殊能力 | 本批仅软件视频、数字手柄；其他逐项待迁移 | 无多盘／光枪／模拟轴／硬件渲染；无独立 RTC／NVRAM 业务承诺 | 不由核心元数据推定支持 |

### 1.2 从玩家入口到收尾

| 玩家动作 | 公共入口与授权 | 运行／保存／收尾边界 |
| --- | --- | --- |
| 选择／上传游戏 | 原公共游戏库 → CabinetSharedGames，保留原名及内容身份 | 本批不增下载授权；核心和 DLL 只能来自可信固定资源 |
| 实体电源开局 | ServerCabinets／CabinetRooms 原校验 → 客户端后端工厂 | 本机属性只选择执行方式；READY 不绕过席位和输入授权 |
| 入席／申请加入／投币 | 原席位、主持批准、端口与 CabinetCoinService | 最多四个数字输入端口；游戏及联柜容量另约束；授权成功才扣币 |
| 看屏幕／离远返回 | 原 MEDIA 音画服务与观看范围 | 不建立 Netplay 复制核心，不赋予输入或写档权限 |
| 保存／恢复／从头 | 本入口没有新增存档界面或 persistence 绑定 | 不自动读取、转换或写回任何旧档；检查点 API 不等于保存业务 |
| 配置／诊断 | 原螺丝刀／管理终端；OP 与普通操作分离 | 本机参数不是全服强制；错误报告不自动降级为 JNI |
| 退出操作／关机／失败 | 原空席规则、设备会话、代次检查 | 退席不等于关机；关闭先清输入，异步等待实际退出和目录回收 |

开局玩家、计算主持、操作席仍由公共机柜会话管理，1P 退出不自动变更主持或保存所有者；本入口没有新增保存所有者。准备取消、重复点击、超时、旧回复、断线及移除均须复核原会话代次，不能用核心已经启动代替服务器确认。主副柜和 OP Test／Service／DIP 权限不移入运行器。

### 1.3 Netplay 独立验收

统一 RetroArch 后端后，共享模拟路线优先复用 RetroArch Netplay；原 JNI Netplay 作为兼容路线保留。本批 LOCAL 是一端运行的 MEDIA 基础，不是共享模拟完成报告。

| 验收项 | 本批结论 |
| --- | --- |
| N10 后端与同步模式 | 固定 MAME＋RetroArch LOCAL＋公共 MEDIA 已分别声明；RetroArch Netplay 未接入此入口 |
| N11 确定性／回滚／内存 | 本入口无多端回滚；既有 JNI 结果不转记为 RetroArch 通过 |
| N12 开局／端口／授权 | 复用街机流程，不套家用 2P 选档流程；四口合同测试不替代 MC 双客户端 |
| N13 晚加入／旁观 | 仅沿用 MEDIA；Netplay 种子／追帧未迁移，MC 晚加入待验 |
| N14 保存／再次恢复 | 公共层身份分离；本街机未接持久化，旧档不迁移，不标闭环通过 |
| N15 失败／弱网／长测 | 自动测试与真实核心结果分列；隔离坏 ZIP 拒绝及回收通过，MC、弱网和长局未验 |

## 2. 选型、官方资料与影响面

调研日期：2026-10-09。已阅读公共层 README、技术总览、现有 FC/SFC/MD/GBA／街机调用及以下官方资料。Libretro 的 `retro_run()` 是单视频帧调用，前端负责调度；它不等同于现有 RetroArch 自主进程的 Java 控制接口，序列化也并非所有核心必备。依据：[Libretro 核心开发文档](https://docs.libretro.com/development/cores/developing-cores/)、[对应文档源码](https://github.com/libretro/docs/blob/master/docs/development/cores/developing-cores.md)。

RetroArch Netplay 另有确定性、同核心／同内容、回滚及只读旁观约束；本地运行成功不能代证。依据：[RetroArch Netplay 官方文档](https://docs.libretro.com/development/retroarch/netplay/)。街机内容还受核心版本、ROM 集及 BIOS／父集合依赖约束，不能失败后静默换核心。依据：[官方街机入门说明](https://docs.libretro.com/guides/arcade-getting-started/)。官方说明提供接口／内容约束，不认证本模组这一固定 MAME 二进制；实际补丁和依赖以仓库资源声明为准。

目标核心另核对了 [MAME 0.181-current 官方开发说明](https://docs.libretro.com/development/cores/core-specific/mame/)和[固定上游提交的 README](https://raw.githubusercontent.com/libretro/mame/4fc9a9312baaf34963847f884961ad9793fbbc1d/README.md)：Libretro 构建入口与独立 MAME 构建区分，固定版本不能由当前分支名称替代。此处只阅读资料，没有照抄上游清理命令或触发重编译；既有 lifecycle 补丁、共享运行库和完整许可仍按仓库清单追踪。

| 候选 | 版本／来源／许可 | 采用理由与缺口 |
| --- | --- | --- |
| 现有 NetplayProcess＋固定 RetroArch | 1.22.2／PNP7；[原生说明](../netplay-native/README.md)与[第三方声明](../THIRD_PARTY_NOTICES.md)，GPL-3.0-or-later | 复用认证 IPC、帧边界、资源提取和收尾；增加纯 LOCAL 分支，不新建每机型桥 |
| LibretroRuntime／LibretroRuntimes | 仓库公共逐帧 JNI／旧进程合同 | 保留兼容；自主运行不能同义替换 run、内存读取、RESET 和状态恢复 |
| 现有 MAME lifecycle1 | 固定 MAME 0.289 补丁制品及 libc++；[资源清单](../../piq-native-arcade/native/mame-lifecycle/runtime.json)和[许可](../../piq-native-arcade/docs/licenses/MAME-COPYING.txt) | 同核心做首次后端适配，避免同时改变内容／核心身份；固定内容隔离音画／输入首验通过，不认证所有 ROM |
| 直接替换为任意上游 RetroArch／另一核心 | 上游制品不含本仓固定 PNP7 IPC 合同；ROM 集／状态兼容亦未证 | 不采用；不向用户要求下载随机 EXE／DLL，不增加无依据核心补丁 |

本批没有新增原生补丁或升级核心，复用既有固定资源。必要适配限 Java LOCAL 会话、可信配置／文件入口、独立身份、进程真实退出和街机输入／帧转换。公共基础库不反向依赖主包，主包会话不倒灌成公共库的 Minecraft 业务。

源码核对基线为 `8257970574ab5fa143b74bef5fc7b23a2927436c`，新增实现以本记录注明的候选源码为准。环境目标 Minecraft 1.21.1、NeoForge 21.1.229 及兼容 21.1、Java 21；执行 LOCAL 的端为 Windows x64。主包和街机均在客户端／服务器安装，依据双方注册的机柜、网络与服务端逻辑，不仅依据依赖 `side`。本机属性不把专服改成 LOCAL。

## 3. 配置、数据与性能

唯一新增选择为 Java 本机启动参数 `-Dpiq.arcade.mediaBackend=retroarch`；缺省或显式 `jni` 保留旧路线，其他值报错。它不是聊天命令、网络字段或持久化到世界的设备配置。仅在新的客户端公共机柜 MEDIA 会话建立时读取；不切换正在运行的局，也不迁移存档。当前为显式测试候选，不因隔离首验通过改为正常游玩的默认后端。

内容、核心和固定运行库由后台 owner 检查、暂存至独占 RuntimeWorkspace；临时内容不回写原 ROM／BIOS。大 MAME 的预算绑定固定资源和 SHA，不提高任意下载／执行权限。LOCAL 身份包含运行器、核心、协议、端口／采样率、核心选项、固定依赖及内容／伴随文件摘要；不与旧 Netplay／JNI 状态互读。

输入供应者须即时返回，按原生 PNPI 请求取四口 12 位掩码，不按 Minecraft 渲染帧重复消费边沿；关闭清零输入。每次最多消费六个已接收音画帧，只保留最新图像并合并所消费音频。1024×1024 图像、协议 PCM 长度等沿公共边界；CPU、内存、网络带宽、音画同步、长局和多会话均待实测，JNI 四槽不作为进程并发承诺。

`close()` 发起异步收尾；`terminated()` 正常完成才表示子进程退出且目录移除。超时或清理失败不得显示成功，保留错误及安全回收信息。当前可能强制结束子进程，`forcedTermination()` 与诊断必须如实展示，不能写成正常 `retro_deinit` 已完成。进程隔离不等于操作系统沙箱。

## 4. 尚不能直接迁移的调用者

| 调用者 | 须保留的合同／身份／端口 | 本批状态 |
| --- | --- | --- |
| FC 普通／光枪 | 逐帧 runWithMemory、PLR1／电池内存、核心 variant namespace；普通两口或第二口光枪 | 未迁移；LOCAL 仅数字手柄不能覆盖光枪 |
| SFC 家用／机柜 | PSF1 的核心状态／画面／重采样状态、SRAM、各公开／私人身份；两口 257；Netplay 准备后激活 | 未迁移，不能丢弃状态封装后直接换工厂 |
| MD MEDIA／Netplay／私人 | GX 普通与专用 Netplay 核心不同；六键两口 513；公开／Netplay／私人 namespace、档主／写租约及最终保存确认 | 未迁移；不把旧档按新后端名搬动 |
| GBA 掌机／机柜 | 单口 1、mGBA 电池档、世界／服务器／玩家／后端／ROM scope、关闭保存；不是 GBA Link | 未迁移，原 JNI／专用进程仍保留 |
| Native 非客户端 MEDIA | 旧 snapshot 两口及内容门禁、FBNeo Netplay 四口及独立保存身份、服务端工厂／旧独立柜 | 均未迁移；不因本机属性改变 |

## 5. 外观与交互

本批不改模型、贴图、命中区、创造图标或界面布局。现有三种机柜的原始尺寸／宽高比／转向继续经公共 CabinetFrame 表达，PNP7 已处理的比例和旋转不再二次翻转。单元几何检查不替代四向、竖屏、贴邻、Iris、本人／旁观画面与实际听感。操作 8 格、发现 16 格、既有订阅退出 20 格、默认空席 60 秒及最后主动退出不立即关机的规则保持；不会为 LOCAL 新建冲突默认值。

## 6. 验证与交付边界

| 层次 | 本批结果 | 不代表什么 |
| --- | --- | --- |
| 主包自动测试 | 最终全量重跑通过，2879 项，0 失败、0 错误、11 跳过；构建通过 | 跳过不记覆盖；不代表各机型迁移或 MC 真人验收 |
| 街机自动测试 | 160 项，0 失败、0 错误、0 跳过；街机构建通过 | 不代表真实多人／长局通过 |
| 隔离真实 MAME 适配器 | 三次各 120 帧通过；检测到非零 PCM，输入诊断出现 24 种不同画面摘要；坏 ZIP 被原生拒绝，实际 PID 退出及目录清理确认；结果 `PASS checks=26377` | 只证明原创诊断与短程探针范围，不代表 MC 听感、四人、旁观或长期稳定 |
| 隔离真实 Mesen LOCAL | 使用最终生产 JAR 与 LOCAL `./原文件名` 路径，真实保存、关闭及重开恢复通过；结果 `PASS checks=182` | 只验证公共 LOCAL 保存通道；Native MEDIA 未绑定 persistence，仍无街机进度闭环，也不代表 FC 已迁移 |
| 最终 JAR 启动／MC 单机／专服多人 | 待验证 | 不用 Java 测试或探针代替安装与玩家流程 |
| 弱网／多会话／长时性能／所有 ROM | 待验证 | 不标稳定，不作通用兼容认证 |

自动测试证据在各组件 `build/test-results/test/`；关键新增测试为主包 `RetroArchLocalContractTest`、街机 `NativeMediaBackendTest`／`NativeRetroArchMediaSessionTest`。自动验证入口分别为 `./gradlew.bat test jar`（主包目录）及 `./gradlew.bat test build`（街机目录）；缓存完整时才使用 `--offline`，结果须对应本候选资源及配套版本。构建产物按各组件 `build/libs/` 及最终交付清单核对，本文不把旧公开包或未列出的其他附属记作本批成果。

真实核心验证使用[实际街机适配器探针](../../piq-native-arcade/tools/qa/NativeRetroArchMediaProbe.java)，不是只调公共会话。测试内容由[原创诊断生成器](../../piq-native-arcade/tools/make_diagnostic_rom.py)生成，配套[来源清单](../../piq-native-arcade/tools/native-diagnostic-original-manifest.json)；26 字节原创 8080 固件加零填充，不包含商业游戏代码。虽然 ZIP 使用 `invaders.zip` 驱动名，但不代表《Space Invaders》或其他商业游戏通过。固定 ZIP SHA-256 为 `10e62bbacbf6e79efb6db6f7a65ea1edfb65653eab60b41e80dbcb4673a5276c`。

复现须在隔离实例使用 Java 21、上述新建主包／街机 JAR 及全新输出目录：生成器以 `--output <新目录>/invaders.zip` 写入，按探针文件顶部 `javac`／`java` 参数编译执行，并设置独立的 420 秒外部 watchdog；不得使用现有世界或替换 DLL。它逐次核对真实 JAR 资源摘要、子进程身份、120 帧音画、坏 ZIP 失败及 `terminated()` 后实际目录移除。公共层另有 [RetroArchLocalProbe](../tools/qa/RetroArchLocalProbe.java)，其结果不替代实际适配器或 Minecraft 流程。

最初两次探针虽有帧却得到全零 PCM，原因是 LOCAL 传入裸文件名使 MAME 进入空目录 OSD；仅 LOCAL 的内容参数改为 `./原文件名` 后，第三次适配器探针取得上述结果。旧 Netplay 参数保持不变，不用早期失败掩盖或替代修复后的证据。

前期 `NetplayProfileTest` 参数行为测试曾因测试资源不符合 `LibretroProfile.Artifact` 约束而失败；修正测试夹具后，最终全量重跑通过，没有因此放宽生产核心资源合同。

后续用同一候选 JAR 验公共机柜内容授权、加入／拒绝、旁观、真人短按／投币／四端口、失焦释放、OP 撤权、空席／断线／取消、比例／竖屏及旧 JNI 不受影响；区分正常关闭和强制结束，再做重复启停与长局。持久化、其他机型、RetroArch Netplay、托管及并发迁移另列实现和证据。

当前结论是候选待验收，人工验收：未验收。未删除历史记录、ROM、BIOS、世界或旧档；没有由本文授予安装、部署、发布、重启或推送权限。
