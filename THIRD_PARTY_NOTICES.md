# 第三方组件、源码与许可

方块电玩使用多种模拟核心、运行器及适配库。**它们不采用统一许可证；公开源码也不代表所有组件、模型或游戏内容都可以任意再分发、商用。** 本文是导航和已核对的技术事实，不替代各组件的完整许可，也不是法律合规保证。

更新日期：2026-10-15。本发行准备分支整合 FC76.54 候选 `adb1e37` 与主线 `6c535708` 的许可、隐私清理和快速回放源码；整合源码不等于修改或重新验证既有玩家 JAR。Release 的附件、对应源码与本分支可能不完全相同；再分发时以实际 JAR、运行库及其固定版本为单位核对，不能仅使用本表推断安装包内容。

2026-10-17 补充：FC76.58 开发候选新增独立 FCEUmm 双手柄资源，原核心和前端保留。固定二进制、参考源码及包内原文入口见下表；功能验收见[共存记录](piq-fc-arcade/design/FC双后端共存.md)，不由本许可导航推断候选已可采用或公开发行。

## 本项目代码与素材不是同一授权

- 主模组自有程序源码及公共 JNI 桥采用 [GPL-3.0-or-later](piq-fc-arcade/LICENSE.md)，完整文本见 [GPLv3](piq-fc-arcade/src/main/resources/META-INF/licenses/GPL-3.0.txt)。内部公共库随主包提供，不是要求玩家额外安装的模拟核心。
- 所列自有前端、公共层、SFC 集成和 JNI 实现另有 [Snes9x 有限链接许可](piq-fc-arcade/SNES9X-LINKING-EXCEPTION.txt)，仅覆盖固定 Snes9x 与有权授权的自有代码；GPL 保留，Snes9x 非商业条件保留，不替第三方授予例外。
- 所列自有 FC/公共层、MD/街机集成、JNI 实现和两个指定 JNA worker 文件另有 [固定 MD／街机有限链接许可](piq-fc-arcade/MD-ARCADE-LINKING-EXCEPTION.txt)，仅覆盖本批普通 GX `9ffa10…`、GX `piqnp1` 与 FBNeo `state-v3` 的指定源码和 DLL；自有 GPL、核心非商业及对应源码条件保留，不替 RetroArch 或其他第三方授予例外，也不扩大到普通 FBNeo、其他 nightly/修改版或后续修订。
- SFC、MD、街机、GBA、电脑、Flash、PvZ 的自有适配代码按各组件声明处理，不能把其自有代码许可套到第三方模拟核心。
- Java ME 原型的自身 metadata 当前声明 `All Rights Reserved`，不能由主模组 GPL 推导它已经改用 GPL。独立 Replay 兼容补丁的自有代码采用 MIT，见下文候选范围。
- 模型、贴图、声音、Logo 和宣传素材保留其权利人的声明；没有逐项许可时，不自动适用程序代码的 GPL。MD 模型已有随本仓库公开展示的授权说明，不等于通用素材的任意再分发或商用授权。电脑的黑白可组装主机、经典黑白键鼠两套原创素材已获作者允许随模组公开分发，范围见 [ASSETS.md](piq-computer/ASSETS.md)；不扩大为独立素材的任意复用或商用授权。
- 本项目不提供商业游戏 ROM、BIOS、SWF 游戏、`main.pak`、操作系统镜像或玩家存档的使用授权。使用者必须有权使用自己的内容。

## 已纳入源码的组件

“内置”表示相关构建/发行路线确实携带组件，不表示每个安装包均包含所有路线。旧运行方式仍有资源随包保留时，也需保留其许可。

| 使用位置 | 组件和固定版本 | 使用/打包方式 | 许可与材料 |
| --- | --- | --- | --- |
| 公共桥 | libretro API 头文件 | JNI/JNA 适配接口；API 不是模拟核心 | MIT，保留头文件原署名。桥实现仍按本项目 GPL；见[主包第三方说明](piq-fc-arcade/THIRD_PARTY_NOTICES.md) |
| FC | Mesen，源参考 `0102910c39ad1a62bc3f784466f3f67ca9eae335` | 内置固定 Windows/Linux libretro 二进制；另有历史 JNI r2 独立修改版 | [固定 GPLv3 原文](https://github.com/libretro/Mesen/blob/0102910c39ad1a62bc3f784466f3f67ca9eae335/LICENSE)；修改版必须对应自己的完整源包，不使用普通源包冒充 |
| FC 显式 RetroArch 双手柄候选 | FCEUmm `(SVN) ac0d00a`，参考源码 `ac0d00a7fc01166f84c7bac01d1084ebfd2b865e` | 未修改的官方 Windows x64 DLL，SHA `a04f7fa28bdd46f602bb62750ab9b5e360d8e8d8ece97de8ad3ef1b669257ffd`；独立资源和保存身份 | [来源、完整源包摘要及许可入口](piq-fc-arcade/src/main/resources/core/fc-retroarch/README.md)；原 GPLv2 与 nes_ntsc 许可随新 JAR 保留，完整参考源包另附。运行短提交一致不等于字节可复现构建证明，也不取代其他源文件的声明 |
| FC | RetroArch 1.22.2 / PNP7 | 内置经过音画、输入及进程通信适配的 Windows 前端；不是仅引用 RetroArch 名称 | [GPLv3](https://github.com/libretro/RetroArch/blob/v1.22.2/COPYING)，项目按 GPL-3.0-or-later 分发；完整修改源码为 `retroarch-piq-pnp7-corresponding-source.zip`，入口见对应 Release 源码附件 |
| FC 旧 WASM | nes-rust 0.1.2 及适配 | 普通 NES、光枪和 mapper19 等保留的 WASM 资源 | [MIT 原文](piq-fc-arcade/src/main/resources/META-INF/licenses/nes-rust-LICENSE.txt)；源码在 `piq-fc-arcade/native/nes-rust` 及相应适配目录，修改和打包资源须一一对应 |
| 公共独立 worker / 部分附属 helper | JNA 5.14.0 | 独立 Java/native 调用库；主包 worker 不将它合并到 Minecraft classpath | 上游 LGPL / Apache 双许可，本发行选择 Apache-2.0；[原双许可说明](https://github.com/java-native-access/jna/blob/5.14.0/LICENSE)、[本包 Apache 全文](piq-fc-arcade/src/main/resources/META-INF/licenses/wasmtime4j-LICENSE.txt)。JNA 所含 libffi 仍须保留自己的 MIT 声明 |
| FC WASM 运行器 | Wasmtime4j 46.0.1-1.2.0，`a377267cc70f891bb7c9618259251380ba26e57b` | 三份 Maven 库扁平合并进入主 JAR，不是 NeoForge Jar-in-Jar | [Apache-2.0 原文](piq-fc-arcade/src/main/resources/META-INF/licenses/wasmtime4j-LICENSE.txt)，[固定上游](https://github.com/tegmentum/wasmtime4j/blob/a377267cc70f891bb7c9618259251380ba26e57b/LICENSE) |
| FC WASM 运行器 | Wasmtime 46.0.1，`823d1b8f251494a06288194d0df746191f535ff7` | Wasmtime4j 内部的 Windows/Linux 原生运行库 | [Apache-2.0 WITH LLVM-exception 原文](piq-fc-arcade/src/main/resources/META-INF/licenses/wasmtime-LICENSE.txt)，[固定上游](https://github.com/bytecodealliance/wasmtime/blob/823d1b8f251494a06288194d0df746191f535ff7/LICENSE)；新增 [121 个已确认 crate 版本及 Rust 标准库的原文通知](piq-fc-arcade/third-party/wasmtime4j-native/README.md)，不是完整 SBOM |
| SFC 家用 | Mesen-S，`9e4fdeb9b336470bc96beb8765b2e79c86a2da1e` | SFC native 核心路线 | [本库原文/来源](piq-sfc-home/src/main/resources/core/sfc-libretro/)、[固定 GPLv3 原文](https://github.com/libretro/Mesen-S/blob/9e4fdeb9b336470bc96beb8765b2e79c86a2da1e/LICENSE) |
| SFC 旧核心模块 | jgenesis，`25fc9e55ff065284c7b5f5855e2fc6d0f26e8270` | 内部 SFC 核心/WASM 路线，不另当成玩家必装主模组 | [固定 GPL 原文](https://github.com/jsgroth/jgenesis/blob/25fc9e55ff065284c7b5f5855e2fc6d0f26e8270/LICENSE)；源树中另行标为 MIT 的文件保留各自声明 |
| MD | Genesis Plus GX 1.7.4，`c2838c7dc4236fc2fe94e5dbd08b41486067918e` | 普通核心及历史 PIQ Netplay `piqnp1` 独立修改版 | [本库原始许可](piq-md-home/src/main/resources/licenses/genesis-plus-gx/LICENSE.txt)、[修改版来源与源包](piq-md-home/src/main/resources/licenses/genesis-plus-gx-piq-netplay/NOTICE.txt)。GX 整体保留非商业和对应源码条件，不是 GPL/MIT 核心；[PIQ 有限链接许可](piq-fc-arcade/MD-ARCADE-LINKING-EXCEPTION.txt)只适用于指定普通 GX `9ffa10…` 与 `piqnp1` 的自有 JNI/JNA 组合，不覆盖其他 GX 身份或 RetroArch |
| 街机 | MAME 0.289，libretro 源参考 `4fc9a9312baaf34963847f884961ad9793fbbc1d` | MAME helper/native 路线 | [固定 COPYING](https://github.com/libretro/mame/blob/4fc9a9312baaf34963847f884961ad9793fbbc1d/COPYING)：整体按 GPL version 2，单文件按各自声明；不得省略源树中其他组件 notices |
| 街机 | FBNeo，`a251c76229f1637e433b93e29845039752771b6d` | 历史 state-v3 修改版；普通上游与旧 NeoGeo snapshot 路线按实际包区分 | [固定原始许可](https://github.com/libretro/FBNeo/blob/a251c76229f1637e433b93e29845039752771b6d/src/license.txt)：独立非商业条件，含牟利、募捐和公开修改源码等限制；不能统称 GPL。[PIQ 有限链接许可](piq-fc-arcade/MD-ARCADE-LINKING-EXCEPTION.txt)只适用于指定 `state-v3` 组合，不覆盖普通 FBNeo 或 RetroArch |
| GBA | mGBA 0.11-219-e31759b，`e31759b24e7a4e3899285ff720d7b573ac328ae7` | 附属内置原生核心/helper | [固定 MPL-2.0 原文](https://github.com/libretro/mgba/blob/e31759b24e7a4e3899285ff720d7b573ac328ae7/LICENSE)；保留其适用源文件、许可证和对应源入口；不是改用适配层 GPL |
| 可组装电脑 | 自有 Java 适配与复用的 Flash 接口 | Computer JAR 本身不含下列外置 Flash 运行器，也不因存在入口而包含 DOSBox | [代码/素材来源](piq-computer/ASSETS.md)；接口代码 GPL-3.0-or-later，素材权利分开核对 |
| 外置 Flash 运行器 | Ruffle 0.7.0-nightly.2026.9.16 | 外置 web/WASM 运行资源；旧 Flash Box 入口与电脑复用区分 | 顶级 MIT OR Apache-2.0；[完整该版本 web 第三方许可](piq-flash-box/runtime/vendor/LICENSE-RUFFLE-WEB.md)，不能只列顶级两种许可证 |
| 外置 Flash 运行器 | Microsoft.Web.WebView2 SDK 1.0.4078.44 | WinForms SDK/Loader；Edge WebView2 Runtime 另装；.NET6 框架依赖不随 SelfContained=false 构建整体打包 | [该 SDK 包原文](piq-flash-box/runtime/vendor/LICENSE-WebView2.txt)，Microsoft 版权三条款 BSD 式条件；不要将其与另装浏览器运行时条款混淆 |
| Java ME 原型 | MicroEmulator 2.0.4：cldc、midp、javase、javase-swing | 四个 Maven 库扁平合并，不是独立嵌套 JAR；不随包带用户游戏 JAR/JAD | [本库说明与待核验项](piq-j2me-arcade/src/main/resources/META-INF/licenses/microemu-NOTICE.txt)；现声明 LGPL-2.1，精确 2.0.4 源/原文/替换材料需进一步核对 |
| PvZ 程序附属 | PvZ-Portable libretro 移植，源基底 `a0f676aeeda9e4b545e20ea54d0d78d41cd2bd6b` 加实际移植工作树 | 内置 native 核心、host/JNI；不含商业游戏素材 | [详细来源与许可](piq-pvz-addon/THIRD_PARTY.md)：核心 LGPL-3.0-or-later、框架及依赖独立许可。裸 Git 基底或当前开发子模块不能代替发行件完整移植源树 |

### GX、FBNeo 与原生依赖的特殊条件

Genesis Plus GX 的整体非商业条件和子组件许可应分开：**Nuked OPN2 与 blip_buf 各自是 LGPL-2.1-or-later，Nuked OPN2 本身不是非商业许可**；这不解除 GX 整体限制。[固定 GX LICENSE](https://github.com/libretro/Genesis-Plus-GX/blob/c2838c7dc4236fc2fe94e5dbd08b41486067918e/LICENSE.txt) 还列出 Tremor、minimp3、libchdr、NTSC 和压缩库等原声明。实际启用哪些平台分支应依固定构建配置判断。

FBNeo 使用自定义许可，不能只用“免费、开源”概括。保留完整 [FBNeo 许可](https://github.com/libretro/FBNeo/blob/a251c76229f1637e433b93e29845039752771b6d/src/license.txt)，并在再分发前核对其中的非商业、募捐和公开源码修改等条件。仅把进程隔离、使用 JNI 或换包名不会重新授权核心。

本批固定普通 GX `9ffa10…`、GX `piqnp1`、FBNeo `state-v3` 与有权授权的自有模组/JNI 及指定 JNA worker 代码的组合，由上述独立有限链接许可明确；该许可自生效日起也适用于所列旧成品中的自有代码，不表示整包或第三方被重新许可。普通 GX 与 `piqnp1` 分别对应 MD16 的不同真实运行路径，不是互相替换的二进制。最终发行仍须实际提供许可文本、GPL、普通 GX 完整原源、两份修改核心完整对应源码、构建/链接资料、适用 LGPL 重链接材料及原通知。旧 JAR 未因新增此文稿而改变，不将“已有授权文稿”写成“已完成配套或发布”。普通 FBNeo／RetroArch 路线不由这份自有代码许可放行。

PvZ 的实际静态依赖说明见 [THIRD_PARTY.md](piq-pvz-addon/THIRD_PARTY.md)：SDL2 2.32.10（zlib）、SDL-Mixer-X 2.7.0.0、libopenmpt 0.8.9、libpng 1.6.50、libjpeg-turbo 3.1.2、libVorbis 1.3.7、zlib 1.3.1，以及需要保留的框架和 IJG 致谢。libogg 的实际构建版本尚未由该记录确认，参考许可版本不当成实际链接版本。

编译运行库亦需按实际链接组成保留原文：例如 GCC Runtime Library Exception、LLVM Exceptions、MinGW-w64 runtime 和 winpthreads。**PE 导入表只有 Windows 系统 DLL，不证明没有静态第三方代码。** 工具链整体清单也不等于二进制实际链接清单，不能把 PvZ 的记录照搬成其他核心的已核验结论。

## 独立候选与历史资源

仓库中的 [快速回放兼容补丁 test.3](piq-replay-quickmode-compat/README.md)自有源码和测试工具采用 [MIT](piq-replay-quickmode-compat/LICENSE)。Minecraft、NeoForge、ReForgedPlay、Sable、Sponge Mixin 等由开发者或玩家另行提供，不打包进补丁，也不因适配代码采用 MIT 而改变各自许可。构建输出、测试中解出的依赖、私人录像和运行报告不纳入源码。

以下候选源码已纳入本发行准备分支；原发行件仍对应 `adb1e37`，新增通知未写入旧 JAR。可从[原对应源码快照](https://github.com/meilian258-dotcom/game-console/tree/adb1e37ef5326bff7b8f5b22f1f074260b017e8c)核对；下载与完整材料见[项目 Releases](https://github.com/meilian258-dotcom/game-console/releases)。

| 范围 | 许可和边界 |
| --- | --- |
| Replay 独立补丁 test7 | 自有代码/API为 MIT；[原文](https://github.com/meilian258-dotcom/game-console/blob/adb1e37ef5326bff7b8f5b22f1f074260b017e8c/piq-replay-compat/LICENSE)。不重新许可 ReplayMod、Iris、Minecraft 或其资产 |
| SFC Snes9x 候选 | 1.63 fae2fea，固定 `fae2fea08f74180759ef540ee94259213f503480`；[上游原文](https://github.com/libretro/snes9x/blob/fae2fea08f74180759ef540ee94259213f503480/LICENSE)。有非商业及独立子组件条件，**公开分发审查未完成**；引用原文不是分发批准 |
| Snes9x 组成审查 | 固定完整源、NTSC/SPC DSP 的 LGPL-2.1+ 原文及 API MIT 声明已核对；新增 [SFC54 源码与运行库通知](piq-sfc-home/third-party/snes9x-runtime/NOTICE.md)，保留六份 GCC/MinGW 原文。原 nightly 完整构建记录与组合分发核对仍未完成，材料尚未进入旧 JAR；参考文本中的 JMA/xBRZ 不当作实际编入证据 |
| MAME 生命周期历史候选 | 沿用同一 0.289 源码版本的已有补丁；核心许可不变，构建辅助脚本另有 MIT 许可。修改后的完整源包、补丁和构建说明不能由普通 upstream ZIP 替代 |
| LLVM 配套库 | libc++ 22.1.8 / MSYS2 CLANG64 22.1.8-1；候选材料附 libc++、libc++abi、libunwind 完整原文。保留 LLVM 例外及原历史 notices；其他静态运行库仍按实际链接收据核对 |
| 旧 MD BlastEm | 当前 MD 构建已显式排除，不是现在默认打包的核心；历史许可、旧成品及其对应源码仍按各自分发保留 |
| 历史 ArcadeMod 模型 | 当前 FC 资源已退役该来源；[来源与退役说明](piq-fc-arcade/ARCADEMOD_ASSET_NOTICE.md)保留真实上游署名。退役不抹去历史分发的许可义务 |

## 新补的外置运行器许可原文

这次只补文档与原文，不改运行器、核心或已发布 JAR。外置运行器的 vendor 资源按现有构建规则一起复制；新资料实际进入某个发行 ZIP 前仍须验包。

| 原文 | 固定来源 | 本库文件 SHA-256 |
| --- | --- | --- |
| [Ruffle web/LICENSE.md](piq-flash-box/runtime/vendor/LICENSE-RUFFLE-WEB.md) | [nightly-2026-09-16 的原文件](https://github.com/ruffle-rs/ruffle/blob/nightly-2026-09-16/web/LICENSE.md)，与 vendor package.json 的版本对应 | `83e0a3ea1690281ca2c70f92c17dff9a05f110c0100242478a89bb0a06e815a3` |
| [WebView2 SDK LICENSE.txt](piq-flash-box/runtime/vendor/LICENSE-WebView2.txt) | [Microsoft.Web.WebView2 1.0.4078.44 官方 NuGet 包](https://www.nuget.org/packages/Microsoft.Web.WebView2/1.0.4078.44)内 LICENSE.txt，包 nuspec 指定的许可证；条款与署名保留，仅规范 LF 换行及行尾空格 | `9995174528dba139ca753d02d8667dbec49f65ab17e65c643a914f2b58cfb4a2` |

Ruffle 的顶级 [MIT](piq-flash-box/runtime/vendor/LICENSE_MIT) / [Apache](piq-flash-box/runtime/vendor/LICENSE_APACHE) 文本继续保留，不以新归属清单替换或删除。第三方许可证中的合法作者、版权联系地址和上游引用不是项目的私人运维资料，隐私清理不会删除这些必要声明。

## 对应源码与仍需补齐的事项

发行材料应提供可访问的完整对应源码、必要构建/打包脚本、修改说明、原始许可和特殊通知。GitHub 自动生成的 Source code ZIP 不含完整子模块或未入库的固定核心源，因此不一定够用。按版本保留已有源包，不为精简下载列表删除唯一的对应源码入口。

现有材料入口：[整套测试版源码附件说明](piq-fc-arcade/docs/整套测试版-20261007.md)、[主包来源说明](piq-fc-arcade/THIRD_PARTY_NOTICES.md)、[MD 修改版源包说明](piq-md-home/src/main/resources/licenses/genesis-plus-gx-piq-netplay/NOTICE.txt)、[PvZ 完整移植源树说明](piq-pvz-addon/THIRD_PARTY.md)。部分新成品复用前一版本的未变源码附件，必须记录对应关系而非要求玩家猜测。

尚未闭合的事项不以本次文档补齐冒充完成：

1. **SFC Snes9x**：官方取得记录、固定源提交、DLL 版本与摘要已绑定，完整源和 LGPL 原文已在材料中。自有代码与固定 Snes9x 的组合授权现由上述有限链接许可明确，不能据此给 RetroArch 或其他第三方 GPL 代码授予例外。最终发行仍需实际携带例外、GPL、完整对应源码、适用 LGPL 重链接材料和运行库通知；旧 JAR 未重打包。原 Buildbot 构建细节仍有精度限制，但不将字节一致重编当作一律必需。详见 [SFC 通知](piq-sfc-home/third-party/snes9x-runtime/NOTICE.md)。最终材料核验完成前保持现有公开分发门禁。
2. **Wasmtime/Wasmtime4j**：已补 121 个可确证 crate/version 的 250 份原文和 Rust 1.96.1 标准库通知，来源及摘要见[独立材料目录](piq-fc-arcade/third-party/wasmtime4j-native/README.md)。旧 JAR 未重打包，分发时须携带相应补充材料。历史发布未提供锁/SBOM且原生日志已过期，剩余未显示在二进制版本字符串中的成员仍不能宣称全部核清；这不直接等于缺少必须交付的 GPL 源码，也不把完整 SBOM 本身当作普遍发行条件。未用今天重新解析的依赖冒充历史构建事实。
3. **MicroEmulator 2.0.4** 四个固定 Maven 制品的完整源/许可清单及扁平合并后的替换/重链接材料；上游当前分支的双许可概述不自动适用于每个旧制品文件。该 Java ME 原型不属于当前七件快照配套。
4. **核心来源**：固定 SHA、版本字符串、官方取得记录、完整源树与构建脚本共同说明来源；若缺原构建记录，明确对应关系的证据范围。字节完全一致的可重复构建是另一个验证目标，不能仅因尚未做到它就断言不可分发，也不能用同名版本代替完整对应源码。
5. **JNA、PvZ 与编译运行库**：逐发行包核对完整 Apache/libffi、public-r1 补充材料和静态链接库 notices；修改说明不能证明每个旧 JAR 都已携带它们。
6. **素材权利**：电脑上述两套原创素材的随模组公开分发授权已确认；其他素材继续逐项核对提供者、来源和获准范围，不从程序 GPL、文件公开或去掉 Logo 推断素材可以自由使用。
7. **RetroArch 与普通 FBNeo 试验组合**：街机 `retroarch-stock-v1` 通过 RetroArch 的 `-L` 参数加载固定 `52343f…` 普通 FBNeo，区别于自有 JNI 的 `state-v3`。本项目不能为第三方 RetroArch 代码授予链接例外，但也不能仅凭“GPL 前端＋非商业核心”就断言该组合禁止分发。[本批固定 FBNeo 源码的 libretro README](https://github.com/libretro/FBNeo/blob/a251c76229f1637e433b93e29845039752771b6d/src/burner/libretro/README.md#license-and-changelog)已说明上游对这一组合的立场：该 port 不含 GPL 代码，并引用欧盟下独立程序互操作的解释，同时明确非欧盟适用尚不清楚。其引用的[欧盟官方门户分析](https://interoperable-europe.ec.europa.eu/collection/eupl/licence-compatibility-permissivity-reciprocity-and-interoperability)是关于独立作品链接的分析，不是本模组的裁判或 RetroArch 的额外许可。分发仍须保留双方原文、完整对应源码及本项目修改，按实际组合和适用地区核对；这份 PIQ 例外不改变上游立场，也不构成全部后端的法律保证。

完整 SFC54 保留的 jgenesis/WASM 另有[实际运行依赖通知与历史构建锁](piq-sfc-arcade/third-party/wasm-runtime/README.md)：25 个 crate 的 52 份原通知、Rust 1.97.1 标准库原文及实际 Cargo.lock。它与 Wasmtime 的 Rust 1.96.1 材料分开，不以旧的项目锁冒充该 WASM 的精确构建锁。完整源、隐私路径变换脚本和这些通知须配套提供。公共 JNI 也补充了[compiler-rt 与 MinGW CRT 原文](piq-fc-arcade/native/libretro-jni/licenses/NOTICE.md)，保留运行库例外，不把整个工具链清单当作实际链接清单。

升级依赖时，应同步记录：组件名和用途、实际版本/commit与平台、制品摘要、内置或外部依赖、打包/链接方式、许可原文、版权/NOTICE、源码/补丁与构建入口、未核实限制。没有闭合证据的项目明确标注，不能统一写为“全部授权通过”。

## 外部平台

[NeoForge](https://github.com/NeoForged/NeoForge/blob/1.21.1/LICENSE.txt)、Minecraft、Java 及玩家另外安装的模组保留自己的许可和使用条款。测试依赖、编译工具、运行环境和随本项目一起再分发的代码不是同一清单。[Minecraft EULA](https://www.minecraft.net/en-us/eula) 不因本项目代码开源而被替代。本项目不是 Mojang/Microsoft、Nintendo、Sega、PopCap/EA 或 libretro/RetroArch 项目的官方产品；相关名称用于说明兼容对象，不表示背书。
