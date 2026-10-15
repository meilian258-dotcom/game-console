# SFC54 的 Snes9x 源码与运行库通知

适用于完整 SFC `0.1.0-alpha.54` 中未修改的 Windows x64 Snes9x 核心。这里补充分发材料，不替换核心、不更改其保存身份；这些新材料尚未写入既有 SFC54 JAR，也不表示整套已经公开发行。

## 固定来源

| 对象 | 身份 |
| --- | --- |
| 原官方 DLL | `snes9x_libretro.dll`，3,840,000 字节；SHA-256 `5ce01616184f255623405793e192f79aef5089fa796b29bc11ddc5e052060124` |
| 报告版本 | Snes9x `1.63 fae2fea` |
| 完整上游源码 | [libretro/snes9x 固定提交](https://github.com/libretro/snes9x/tree/fae2fea08f74180759ef540ee94259213f503480) |
| 源码 ZIP | [固定完整树](https://codeload.github.com/libretro/snes9x/zip/fae2fea08f74180759ef540ee94259213f503480)，760,336 字节；SHA-256 `7f6dee7ab1a367da86bc86a87a204bf8fe0be389d62e7cfbd568dc2ccac32055` |

原核心于 2026-10-11 从 Libretro 官方 Windows x64 nightly 取得。该下载地址会变化，不能把今天的同名文件当作原件。官方取得记录、DLL 版本和固定源码相互吻合；历史 Buildbot 的完整 MXE recipe、命令行覆盖项与工具链补丁集仍未取得。这里不承诺字节一致重建，也不以无法字节复现本身认定禁止分发。

固定源码包含 `libretro/Makefile`、`Makefile.common`、全部编译单元及所需头文件。NTSC 滤镜 `filter/snes_ntsc.c` 和 DSP `apu/bapu/dsp/SPC_DSP.cpp` 保留 LGPL-2.1-or-later 声明，完整 LGPL 原文在 `filter/snes_ntsc-license.txt`。`libretro/libretro.h` 与 `libretro-common` 保留各自原始许可和署名。SFC54 内已有的 Snes9x、LGPL 原文与源树逐字节一致，不需要用通用模板替换。

固定源码及编译清单没有 JMA/xBRZ 编译单元；固定 DLL 另明确标识未启用 JMA。较早材料中的 reference-only 许可文本不是它们实际编入的证据。

## 补充的运行库原文

固定 DLL 含 GCC `16.2.0`、MinGW-w64 `v14.0.0`／winpthreads 版本信息；固定 Windows Makefile 使用 `-static-libgcc -static-libstdc++`。下列原文按这些版本保留，不把整套工具链的所有组件都声称为实际链接项。

| 原文副本 | 官方固定来源 |
| --- | --- |
| [GCC 运行库例外](GCC-16.2.0-COPYING.RUNTIME) | [GCC 16.2.0 COPYING.RUNTIME](https://raw.githubusercontent.com/gcc-mirror/gcc/releases/gcc-16.2.0/COPYING.RUNTIME) |
| [GCC GPLv3](GCC-16.2.0-COPYING3) | [GCC 16.2.0 COPYING3](https://raw.githubusercontent.com/gcc-mirror/gcc/releases/gcc-16.2.0/COPYING3) |
| [MinGW 顶层说明](MinGW-w64-v14.0.0-COPYING) | [MinGW-w64 v14.0.0 COPYING](https://raw.githubusercontent.com/mingw-w64/mingw-w64/v14.0.0/COPYING) |
| [MinGW 运行库通知](MinGW-w64-v14.0.0-runtime-notices.txt) | [固定运行库全文](https://raw.githubusercontent.com/mingw-w64/mingw-w64/v14.0.0/COPYING.MinGW-w64-runtime/COPYING.MinGW-w64-runtime.txt) |
| [MinGW 项目通知](MinGW-w64-v14.0.0-project-notices.txt) | [固定项目全文](https://raw.githubusercontent.com/mingw-w64/mingw-w64/v14.0.0/COPYING.MinGW-w64/COPYING.MinGW-w64.txt) |
| [winpthreads 通知](MinGW-w64-v14.0.0-winpthreads-COPYING) | [固定 winpthreads COPYING](https://raw.githubusercontent.com/mingw-w64/mingw-w64/v14.0.0/mingw-w64-libraries/winpthreads/COPYING) |

六份原文不改署名或条款；MinGW 顶层 COPYING 不能代替细分运行库通知。GCC 的运行库例外须与 GPL 文本一起保留，不能据此把整个 Snes9x 改称 GPL。

## 重建与分发范围

固定树的 Windows 构建入口为 `libretro/Makefile`。在具备 GNU make、POSIX shell 和面向 x86_64 的 MinGW-w64 GCC/G++ 的环境中，上游入口接受 `make -C libretro platform=win CC=gcc CXX=g++`；交叉编译时使用所安装工具链的实际 CC/CXX 名称。此命令来自固定构建脚本，未在本次执行，不冒称是原 Buildbot 的完整命令。源码归档无 `.git`，自动获取的版本后缀可能不同。

完整源树保留 NTSC/DSP 的可修改源、头文件与重新链接脚本。开发者可在独立开发副本中修改并重链接 LGPL 部件，再将该副本的 `source-control/sfc-stock-inputs.json` 和 `SfcNetplayProfile.java` 中的身份、大小改为自己的产物，按所供构建脚本重建适配层。适用的自用修改与调试权利不以项目生产审核为前提；正式发布和多人配套的审核另行处理。不要把新产物冒称原官方 DLL，不要求玩家关闭现有安装的校验或覆盖旧档。分发新修改组合须遵守适用许可，当前固定核心链接例外不自动扩展到进一步修改的核心。分发者须一并提供完整固定源、适用许可、运行库通知及修改说明，而不只给一个动态 nightly 链接。

[Snes9x 原许可](https://github.com/libretro/snes9x/blob/fae2fea08f74180759ef540ee94259213f503480/LICENSE)规定非商业及署名等条件；免费公开分发不等于商业授权。LGPL 部件、MIT 代码及运行库例外保持各自范围，不由前端的 GPL 覆盖。实际组合的再分发条件和最终安装包材料仍须在公开前完成核对；本目录补齐原文，不自动解除 SFC54 的发布门禁。

### 自有桥与核心的组合许可

SFC54 的 JNI 路线由 `SfcNetplayProfile` 选择核心，经 `JniNetplaySession`、`LibretroJniRuntime` 和 `NativeLibretroBridge` 调用自有 C++ 桥；桥通过 `LoadLibraryExW`/`GetProcAddress` 在同一进程中加载核心并调用 `retro_run` 等接口。这不是仅交换游戏文件的独立程序，`NetplayProcess` 这个类名也不证明进程隔离。

自有程序代码保留 GPL-3.0-or-later；自 2026-10-15 起，[Snes9x 有限链接许可](../../../piq-fc-arcade/SNES9X-LINKING-EXCEPTION.txt)为其中列明的 PIQ 自有前端、公共 Java 层、SFC 集成和 JNI 实现提供 GPLv3 第 7 条附加许可，允许与固定 `fae2fea` Snes9x 组合分发。它不授权其他核心或后续 Snes9x 修订，不替 RetroArch、jgenesis 等第三方版权人授权，也不把自有 GPL 代码整体改为非商业许可。Snes9x 原有非商业条件、其他组件许可和适用源码义务保留。参见 GNU 对[不相容库链接例外](https://www.gnu.org/licenses/gpl-faq.en.html#GPLIncompatibleLibs)的说明。

这项许可从生效日起同样覆盖指定基线中既有的自有代码副本，包括 FC76.54／完整 SFC54 的自有部分；不是追溯宣告历史分发全部合规。冻结 JAR 不重打包，发行时需把例外文本、GPL、完整对应源码和本目录通知一并作为配套提供。未来主包、SFC 两个内部组件和公共库构建均从同一原文打包，避免声明漂移。

冻结副本的对应关系如下；哈希只用于定位，例外不为这些文件中的第三方内容授予权利。

| 副本 | SHA-256 |
| --- | --- |
| `game-console-0.31.0-alpha.76.54.jar` | `3632de31cc6658f32f9e7c498c916c1680218fe58ae160f58e9fa0f0551d0549` |
| 完整 SFC54，含 home54 与 core11 | `35e7d740863be3e0e5e696defa03bfb4eea3186895210a379152374a541180c5` |
| 主包中的自有 `piq-libretro-jni.dll` | `fc731f3b83b0b23f473b48bb9f399582ace264c0646eec45fbffafaf0fb36923` |

这解决的是上述自有代码的组合授权，不能替代最终发行材料核验。公开 SFC 包仍需核对完整源码交付、LGPL 部件可重链接材料及运行库通知是否实际配齐；在该步骤完成前，`sfc_stock.PUBLICATION_BLOCK` 保持拒绝发布，不因存在例外文本而自动放行。
