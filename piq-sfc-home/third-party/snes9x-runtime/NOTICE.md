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

完整源树保留 NTSC/DSP 的可修改源、头文件与重新链接脚本。开发者构建的新核心仍须单独审核身份和适配，不指导玩家关闭生产哈希校验或覆盖旧存档。分发者须一并提供完整固定源、适用许可、运行库通知及修改说明，而不只给一个动态 nightly 链接。

[Snes9x 原许可](https://github.com/libretro/snes9x/blob/fae2fea08f74180759ef540ee94259213f503480/LICENSE)规定非商业及署名等条件；免费公开分发不等于商业授权。LGPL 部件、MIT 代码及运行库例外保持各自范围，不由前端的 GPL 覆盖。实际组合的再分发条件和最终安装包材料仍须在公开前完成核对；本目录补齐原文，不自动解除 SFC54 的发布门禁。
