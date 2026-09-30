# MD3 · Genesis Plus GX 适配与验证

2026-09-30，像素匠 / Codex root。源码基线 5c22134，FC76.28 + MD2。

## 需求与范围

- 按群讨论使用 Genesis Plus GX；用户已明确接受其非商业许可限制，保留原许可与署名。
- 新默认仍使用公共 JNI，不自建原生桥或重写模拟器。
- 保留实体拷卡、AV、电源、手柄租约；旧 BlastEm 核心与旧档不能被新核心覆写。
- 验证新核心音画、按键映射、保存恢复及回滚能力；配置两个六键端口，实体仍只开放1P。官方 Netplay 支持不是 Minecraft 双人房间已完成；后者仍需独立接入/验收。
- 将“每个新附属开工必须读文档、检索复用和现成方案”写成规范与验收项。
- 本次不部署服务器/客户端、不推送、不发群消息，不扩展 CD/32X/光枪/额外机型，不迁移旧即时状态。

## 开工检索与选型（2026-09-30）

| 候选 / 来源 | 已核对内容 | 结论 |
| --- | --- | --- |
| [Genesis Plus GX 官方说明](https://docs.libretro.com/library/genesis_plus_gx/) | 官方标示 Netplay、保存、状态支持；44.1kHz；非商业许可；32X 不支持 | 用户选定新默认；能力仍需本模组实测 |
| [Libretro 上游源码](https://github.com/libretro/Genesis-Plus-GX)、[许可](https://github.com/libretro/Genesis-Plus-GX/blob/master/LICENSE.txt) | 标准 Libretro 软件画面、核心选项、六键手柄；许可禁止商业使用，含多个组件许可 | 优先采用官方未修改二进制，附许可及来源；不将核心改标 GPL |
| [官方 Windows x64 制品](https://buildbot.libretro.com/nightly/windows/x86_64/latest/) | 有现成 genesis_plus_gx_libretro.dll.zip | 只用 latest 发现，锁定下载文件 SHA，核验核心版本后使用；无需先自行编译 |
| 现有 BlastEm profile / MdEngine | 固定 SHA、私人保存、约53kHz输出，拷卡/开机/AV已实现 | 作为显式旧档兼容选择保留；新默认不共用旧状态 |
| 公共 LibretroRuntimes / PrivateSaveStore / ContentCards / PrivateHomeClient | JNI/进程、保存原子写/隔离、内容校验分片、租约生命周期 | 原样复用，只补核心声明/音画合同/选择与验证 |
| 公共 JniNetplaySession / NetplayProfile / RollbackTimeline | 双端口 JNI 回滚、核心与内容身份、严格状态往返 | 复用验证，先排除核心不兼容；不复制 SFC 房间网络业务来假装通用 |

### 制品身份

官方 ZIP SHA256：`36b60039012d38d99e651cfdf06da4ffa3e75f1aa7e50907389771d00564dbcb`。
DLL SHA256：`9ffa10a115b20e1b49e9caf0b53f287c640ed4e5bb93f7ed9a23b416a4ccfdf7`。
下载于2026-09-30，目录标示2026-09-29构建。最终JAR真实报告 `v1.7.4 c2838c7`，与下载的固定源码提交 `c2838c7dc4236fc2fe94e5dbd08b41486067918e` 一致；未自行修改/编译官方DLL。固定源码ZIP SHA256：`dd4f5ef7ad3bae410854da8d0d7b99c99f13caa1df77f4ef1f2b8f59be3b6977`。版本戳核对不等于第三方制品可复现构建证明。

核心源码与完整许可随测试包的材料目录交付，不放 `mods`。原BlastEm DLL及COPYING/NOTICE保持原字节；模型公开分发授权仍待核对。核心的非商业限制不能因适配代码采用GPL而取消。

## 安全与兼容设计

按核心 SHA、固定选项/端口合同、后端、个人/服务器、ROM SHA 隔离新档。保持旧 BlastEm 保存身份不变，禁止自动读入或转换其即时状态/SRAM。回退必须显式选择，不在失败时静默切核心。

沿用现有单人距离6格、内容8MiB上限、客户端工作线程和输入/失焦/关闭清理。不新增全服配置；新增核心选择是本机偏好。两端安装依据：共同注册物品/方块及服务器卡带授权，客户端执行核心；不从依赖 side 推断。

## 安装与使用

版本：`game-console-md-0.1.0-alpha.3.jar`，实测搭配**已有FC76.28**。MC1.21.1 / NeoForge21.1.236+的21.1系列 / Java21；执行核心的客户端限Windows x64。两端安装依据是 `MdMod/MdConsole` 共同注册与服务端卡带/租约授权、客户端 `MdClient/MdEngine` 执行，不是根据依赖side猜测。

1. 正常关机、退出，备份世界、mods及game-console。在两端把旧MD移出mods保留，只放入新的MD3；不能两个MD版本共存。FC已是76.28则不替换；其他GBA13/完整SFC43-core9/街机1.5.4/电脑11/PvZ11沿用，不另装platform或SFC薄包。
2. 持MD卡右键老式电脑：服务器条目写卡或明确选择本地文件上传；合法内容自行提供，沿用终端权限。AV线连电视并开电视、插卡、空手借1P手柄、点顶面米白电源；本轮不改模型和按钮位置。
3. 下次启动默认Genesis Plus GX/JNI。按键沿原SFC公共设置：WASD、J/K/P=ABC、L/I/O=XYZ、退格Mode、回车Start。六键设备类型与核心映射在profile适配，不改玩家绑定。
4. 旧档：关机保存后执行 `/gameconsole-md core blastem`，回到原核心与原档目录；`/gameconsole-md core genesis`恢复GX，`/gameconsole-md`查询。只影响本机本次连接，登出复位GX；不上传全服选择，不运行中热切换。兼容进程仍使用 `/gameconsole-private cartridge-runtime process`，`jni/default`恢复默认。进程/JNI档继续分开。
5. 新GX的保存命名空间额外包含核心SHA/固定选项/端口合同，个人/服务器/ROM隔离沿公共保存。GX启动前暴露65536字节SRAM，运行后按非FF尾部裁剪；只在首帧前按FF补齐恢复，不放宽公共桥严格尺寸校验。空电池与无SRAM游戏均测试了重开。

本次只提供MD补丁，不含ROM/BIOS；ZIP、源码、许可和说明不要放进mods。没有安装到正式客户端/远程服务器，没有发布或重启线上实例。

## 验证与已知缺口

证据根：维护机 `outputs/md-gx-20260930/`，早期失败尝试保留，不被新通过记录覆盖。

| 检查 | 结果与边界 |
| --- | --- |
| Gradle check/jar | 12项单元测试，0失败/错误/跳过；包括4096种映射组合、两采样率分块、旧档身份、新SRAM边界及既有控制几何。沿用既有NeoForge注解弃用警告 |
| 最终JAR GX JNI / PROCESS | `gx-jni-v3`、`gx-process-v3`各28断言通过：真实音画/A输入、状态往返、SRAM写入/重开、空电池/无SRAM重开、启动取消及槽释放。诊断为原创68000程序，无商业游戏 |
| 旧BlastEm JNI / PROCESS | `legacy-jni-v3`、`legacy-process-v3`各19断言通过，原保存命名空间不变；不是旧玩家所有档案实测，旧档仍应备份 |
| 7包隔离专服 | `server-check` Done、正常stop与保存、退出0；原QA世界/文件/EULA恢复一致，仅localhost与新临时世界，未连玩家 |
| MC真人 | 未验：实际游戏兼容性、六键逐键手感、AV/拷卡/电源/核心选择命令、满背包、旧世界及长时间体验。1P诊断不是2P实体输入验收 |

### Netplay 门禁未通过，不开放入口

复用公共 `RollbackTimeline`，24帧预测、19帧重放的原生探针 `gx-rollback-v3` 明确失败：回滚后状态不一致（首差异偏移16）、画面一致、音频不一致，且核心状态不包含SRAM回退。不能把这一诊断直接推论成所有游戏必然不同步，也不能据官方支持表跳过本模组验收。旧BlastEm严格状态字节往返同样不满足公共JNI Netplay现有门禁；私人恢复无需宣称这种强一致性。

私人功能验收与Netplay门禁分别运行，后者失败不会被前者通过掩盖，脚本返回非零。后续应先继续核对上游状态/输入时序与RetroArch既有处理，再补最小共享适配及受控保存合同；不得复制房间/下载/保存/原生桥来绕开失败。目前仍无MD公共房间、2P租约、旁观、Netplay保存授权；这轮不改FC/SFC现有模式，不提升稳定标记。

## 复现

原核心输入准备沿历史README，GX使用 `python tools/prepare_genesis.py --archive <已下载官方ZIP> --source <上述固定源码ZIP>`：先校验ZIP/DLL/源码SHA，再仅提取DLL与完整许可，拒绝覆盖不同内容，无自动升级。

在仓库根，Java21：

```powershell
.\piq-fc-arcade\gradlew.bat -p piq-md-home --offline --max-workers=2 '-PgameConsoleJar=../piq-fc-arcade/build/libs/piq_fc_arcade-0.31.0-alpha.76.28.jar' check jar
python -X utf8 piq-md-home/tools/run_native_probe.py <新输出名> JNI_TRIAL piq-fc-arcade/build/libs/piq_fc_arcade-0.31.0-alpha.76.28.jar piq-md-home/build/libs/game_console_md-0.1.0-alpha.3.jar GENESIS_PLUS_GX
```

PROCESS/BLASTEM为另一组合；末尾追加`rollback`执行目前**预期不通过**的联机门禁。现成制品锁与官方源码/许可已记录；这不代表所有附属空缓存可构建。

## 规范记录

已补共同AGENTS、制作规范2.1、功能说明模板2.1、验收清单R05。每个新附属开工都要先读官方文档、公共API及相近附属，再检索现成方案；记录来源日期、采用/不采用理由、能力缺口与许可。不能因先前做过另一个机型跳过；接口够用时只改自身。本次即未改主包和公共JNI。
