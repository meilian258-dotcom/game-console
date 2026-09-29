# FBNeo 隔离同步研究（2026-09-12）

负责人：`/root/fix_sfc_av`。仅研究，不替换现有 MAME，不注册同步能力，不进入交付包。根维护手册由 root 汇总。

## 结论

此官方 FBNeo 构建在现有两份用户游戏上，独立启动的 2,800 帧完整视频和 PCM 一致，但**未通过完整快照恢复合同**。两个实例各自恢复以及 A 快照搬到 B 的恢复，视频相同、音频不同，完整状态也无法 load→save 原样复现。不能以官方标注支持 netplay，代替本项目完整状态与音频的真实检验。

| 游戏 | 显式官方状态上下文 | 独立启动 2,800 帧 | 三路恢复的各 600 帧视频 | 各 600 帧 PCM 不同帧数 | load→save 状态一致 |
|---|---|---|---|---|---|
| kof97 | NORMAL / ROLLBACK_NETPLAY | 视频、PCM 全一致 | 全一致 | 599 | 否 |
| mslug2 | NORMAL / ROLLBACK_NETPLAY | 视频、PCM 全一致 | 全一致 | 572 | 否 |

这里“三路”为 A 本地恢复、B 本地恢复、A 快照由 B 恢复；状态切点在第 2,200 帧，继续到第 2,800 帧。四项明确上下文实验各有两个独立 JVM；均完成原生 unload/deinit 后自然退出，未强行结束成功进程。报告 `ok=true` 只表示实验完整完成；`determinism_qualified=false`、`sync_qualified=false` 才是能力判断。

## 官方来源、固定身份与许可边界

- 文档：[Libretro FBNeo](https://docs.libretro.com/library/fbneo/) 列有 savestate/netplay 能力。
- 二进制仅来自 [Libretro 官方 Windows x86_64 构建站](https://buildbot.libretro.com/nightly/windows/x86_64/latest/fbneo_libretro.dll.zip)，下载响应 Last-Modified 为 `2026-09-11 23:02:07 GMT`。ZIP SHA-256 `934FF65DC9A3229E62F39A8F26630B54473995BE631FCF9D277996765B2643AB`。
- DLL SHA-256 `52343F65453C0E1FC06B3AA818B48CA4FED18361F7F336A684E9E3CFFA5F5F3C`，61,913,629 字节。核心实际报告 `FinalBurn Neo v1.0.0.03 260904 GITa251c76`。
- 固定源码：[libretro/FBNeo a251c76229f1637e433b93e29845039752771b6d](https://github.com/libretro/FBNeo/tree/a251c76229f1637e433b93e29845039752771b6d)。源码 ZIP SHA-256 `55CC0F5BF305D8953FA0A20C3598164D39EFC03EF3740C7B01E7EBF143CD4D7A`。核心版本中的 Git 前缀一致，但未做可复现构建，不把它宣称为完整二进制来源证明。
- 原始许可完整保存在 `vendor/license.txt`，SHA-256 `BB2369F1B75F42242968A78191B47EE90F85682224D3AD1EF63044E244B3E202`。许可明确包含非商业、修改公开、附完整许可及 ROM 分发限制，还包含第三方组件条件。不能当作可随意商用的 GPL 替换品；本次仅私有研究，不代表对未来服务器经营或分发方式作合规判断。

完整下载身份：`official-artifacts.json`。未下载任何游戏 ROM。

## 采样率与原生接口

仅覆盖官方选项 `fbneo-samplerate=48000`，其他选项原始有效值保存在各报告。实际两款均为 `59.18 fps`、`47994.98 Hz`，每次 run 恰好 **811 个双声道样本（1,622 个 signed short）**，所有 2,800 个基线帧的数量相同。没有重采样、没有将它贴成 48 kHz 播放。

固定源码 `src/burner/libretro/libretro.cpp:1575` 发布的采样率为帧率乘整数 `nAudSegLen`，与 `59.18 × 811 = 47994.98` 实测吻合。若今后接入项目固定 48 kHz 的 `CabinetFrame`，还需确定性重采样，其余数、历史样本和待消费 PCM 都要纳入快照。这个封装不能修复本实验已观察到的核心恢复音频差异。

显式查询 `RETRO_ENVIRONMENT_GET_SAVESTATE_CONTEXT`（`72 | 0x10000`），分别返回 NORMAL=0、ROLLBACK_NETPLAY=3；每个子 JVM 都断言核心实际查询了该接口。音视频使能返回 3，不跳过音频、不禁用音频合成。源码 `retro_memory.cpp:287-324` 显示两种上下文的扫描旗标分支，显式上下文的结果均失败，排除了只是没有提供这个官方 API 的解释。

首次 `retro_get_system_av_info` 令核心重新分配显示缓冲，第一帧回调是 NULL 重复帧（`libretro.cpp:1488-1496`）。探针对这一帧明确记录无前图像的空值摘要，之后必须出现真实图像；不会以空图像通过整个测试。所有后续视频按真实像素格式完整转换到 RGB24，再逐帧摘要；全部有符号立体声 PCM 原样逐样本摘要。还断言真实视频数量与非零音频帧数，避免静默空跑通过。

已只读定位 `BurnYM2610Scan` 及序列化入口，但没有证明具体哪项芯片/插值状态造成差异，因此本报告不把推测写成根因，也未修改或重建 FBNeo。

## 最终明确上下文证据

- `kof97-normal-v1.json`：`ACCA5A6D3223CB25B1972488E82645E858A3EAE4069BB2065D47391428A4642F`
- `kof97-netplay-v1.json`：`2DCFCDC21D57F952FF9EA2F74281D2841181720CCDF4EA2876D2AB29B9675DA3`
- `mslug2-normal-v1.json`：`10BD748AC5BBE6AD18DB5F675B3E03DB5B581E0959A57D2A781DB9DCDA8B50F9`
- `mslug2-netplay-v1.json`：`D3C61A85C41D2E4734F942B752F470E8CA00D21142A7A139A58042373442499C`

所有报告都含 DLL 身份、源 ROM/BIOS 前后 SHA/大小/mtime、核心版本、有效选项、各路状态 hash、每段视频/音频差异计数和自然退出证据。两个游戏状态各 415,155 字节；连续保存相同、独立实例同帧状态相同，但恢复后立即保存及继续运行末态均不相同。

保留早期 `kof97-v1.json`（48 kHz 精确断言停止）、`kof97-v2.json`（首帧重复图像断言停止），没有把这两份 QA 适配问题称为核心故障。`kof97-v3.json`、`mslug2-v1.json` 是未显式上下文的完整对照，不替代上述明确上下文报告。

## 安全边界及复现

只读取用户已提供的 kof97/mslug2/neogeo 文件；从原路径复制到本次独占 ASCII 临时目录、复制前后验 SHA。两个子 JVM 各自拥有独立目录、原生 DLL 实例与存档空间。正常结束后删除的只是工具创建的临时副本与快照；原始游戏文件未写入，未启动 Minecraft，未改现有 helper/DLL/src、版本或配置。快照内容和商业游戏画面/音频不进入报告或交付。

工具：`tools/acquire_official.py`（已执行，独占新文件，勿重复覆盖）；`tools/FbneoStudy.java`；`tools/run_study.py`。后者只编译 QA，不编译生产。复现需新报告路径，例如 `run_study.py --game kof97 --context netplay --report <新文件.json>`，单个子进程有 180 秒硬上限，只终止自己创建且超时的精确进程。

这是一组有限的同 Windows x86_64 构建验证，不证明跨操作系统、跨 CPU、其它 ROM 版本或真正四人游戏的全面兼容；输入脚本覆盖 4 个端口，但两份现有游戏仍是各自原生玩家能力。实验运行时间含 JVM/加载与逐帧 SHA，不作为实际客户端性能基准。

当前状态：**研究完成；不能据此开启 FBNeo 本地同步，不能自动替换 MAME。** 下一步若要继续，需独立的明确核心修复/替代方案及新的完整证明。
