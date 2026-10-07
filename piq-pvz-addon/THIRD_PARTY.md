# PvZ 第三方来源与许可

适用于 `game-console-pvz` 0.1.0-prototype.12 的公开实验修订 `public-r1`。本修订只整理来源说明和补充许可资源，模组版本、Java 类、模型、原生 host、JNI 与核心均保持原件内容；不是新功能构建。它需要配套方块电玩主包，使用电脑入口时搭配 Computer prototype.12。

本附属不含 `main.pak`、原游戏素材、玩家存档或游戏授权。使用者自行提供合法取得的数据。本附属不是 PopCap/EA、Mojang/Microsoft 的官方产品，相关名称仅用于说明兼容对象。

## 适配层与原生核心

方块电玩适配代码采用 GPL-3.0-or-later；完整文本为仓库 `LICENSE` 和 JAR 内 `META-INF/LICENSE`。第三方代码分别适用自己的许可，不因随本附属打包而改授其他许可证。

核心来源是基于 [PvZ-Portable](https://github.com/wszqkzqk/PvZ-Portable) 的 libretro 移植。发行件内 `core/pvz/pvz_libretro.dll` 为 11564544 字节，SHA256：

```text
7E4CAA5F801CF9B0FDB03E3E46D704447A49AA2198DC20A1E8369D73C303AD08
```

原来源归档 `pvz_libretro.7z` 的 SHA256 为 `83F2D004668428B1002905FE2BF372C04D58BD2CA5349BB93C63E17BE0716982`，其 `dist/cores/pvz_libretro.dll` 与上述核心逐字节一致。随同一归档保存的移植源树、构建脚本和 libopenmpt 源码，以不含游戏数据、构建缓存及私人资料的 `game-console-pvz-core-source-prototype12-public-r1.zip` 配套提供。原来源归档本身不是发行附件。

源树以 `a0f676aeeda9e4b545e20ea54d0d78d41cd2bd6b` 为 Git 基底并包含 libretro 移植工作树文件；不能只用基底提交代替配套源树。构建配置为 MinGW GCC 15.2.0、Release、`BUILD_STATIC=ON`、`LIBRETRO=ON`、`LIBRETRO_OPENMPT=ON`。源码包包含原移植和构建所需文件，不承诺跨工具链重建后与原 DLL 字节一致。

PvZ-Portable 源码采用 LGPL-3.0-or-later。完整 LGPLv3 与框架许可保留于 JAR 的 `META-INF/pvz/LICENSE`、`META-INF/pvz/SexyAppFramework-LICENSE` 及配套源树。框架及其他第三方部分保留各自声明。

This product includes portions of the PopCap Games Framework, © 2005-2009 PopCap Games, Inc. All rights reserved. (http://popcapframework.sourceforge.net/).

This software is based in part on the work of the Independent JPEG Group.

## 核心静态依赖

依赖版本依据原配置、源版本头或 DLL 版本字符串。对应许可原文随 JAR 的 `META-INF/pvz/licenses/` 和核心源码包的 `licenses/` 提供。

| 组件 | 版本或来源 | 许可材料 |
| --- | --- | --- |
| SDL2 | 2.32.10 | 官方对应 tag 的 zlib 许可。 |
| SDL-Mixer-X | 2.7.0.0，原移植源树 | 源树 `COPYING.txt`；保留 codec 的原许可。 |
| libopenmpt | 0.8.9，原归档源码及自定义 CMake | BSD-3-Clause 及源文件所列许可。 |
| libpng | 1.6.50 | PNG Reference Library License。 |
| libjpeg-turbo | 3.1.2 | `LICENSE.md` 和 `README.ijg`，包括 IJG 条件与致谢。 |
| libVorbis / vorbisfile | 1.3.7 | BSD-3-Clause。 |
| zlib | 1.3.1 | zlib 许可。 |
| libogg | 原链接清单中的静态依赖，构建包版本未单列 | 官方 BSD-3-Clause 许可；附件采用 v1.3.5 的公开许可文本，不据此声明二进制版本。 |
| GCC runtime | 核心构建使用 GCC 15.2.0 | GPLv3 与 GCC Runtime Library Exception 3.1。 |
| LLVM runtime | host/JNI 的构建工具链为 LLVM-MinGW 20250910，Clang 21.1.1 | Apache-2.0 WITH LLVM-exception 及 libc++、libunwind、compiler-rt 的原许可。 |
| MinGW-w64 runtime / winpthreads | PE 中保留其 runtime/源文件标记 | 官方完整 runtime notices、ZPL 及 winpthreads MIT/BSD notices。许可文本取自固定 v13.0.0，不据此声明实际工具链包版本。 |

核心的 PE 导入表只列 Windows 系统 DLL；配套 host/JNI 使用 Windows 系统 DLL、UCRT 与 OpenGL。导入表不会列出已静态链接的库，不能据此省略其许可。没有随包分发 GCC 编译器、Windows 系统 DLL 或 SDK。GCC 运行库按其 [Runtime Library Exception](https://gcc.gnu.org/onlinedocs/libstdc++/manual/license.html) 处理；异常条款不替代 PvZ 核心及其他独立模块的许可。MinGW runtime 保留上游为二进制分发整理的完整 notices，winpthreads 另保留其 MIT/BSD 声明。

LLVM-MinGW 工具链附带的 MinGW runtime、winpthreads notices 与这里所附官方文本逐字节相同。LLVM 运行库另保留其包含 LLVM Exceptions 的完整许可；不把工具链自身的全部软件分发义务自动套到只含运行库目标代码的程序上。

本配置关闭 SDL-Mixer-X 的可选 GPL/LGPL 解码器、MIDI、modplug、GME、FLAC、XMP、Opus 等分支。第三方源码自带的通用示例清单不等于此核心实际链接清单；所有保留源码的原署名与许可仍须保留。

## 开发子模块与发行源树

`vendor/PvZ-Portable` 是开发子模块，来源为 [KLuoNuoYa/PvZ-Portable 的 libretro 分支](https://github.com/KLuoNuoYa/PvZ-Portable/tree/libretro)，固定提交 `6a3cbeee46679eaae2859d25a98207182388e149`。其 `Core.cpp`、`LibretroBackend.cpp` 与原核心配套源树不同，因此它不是旧核心的替代来源证明，也没有自动更新发行 DLL。

开发子模块的取得和升级规则见仓库 `design/PvZ源码子模块.md`。公开父仓库 ZIP 不自动包含子模块；原核心应使用本修订单独提供的核心源码包。不要将上游最新即时状态、时序或关闭流程能力宣传为当前模组已经接入。

## 实验功能边界

当前本机执行支持 Windows x64。电脑默认使用公共 JNI，也保留显式独立进程选择；旧测试盒仍走独立进程。不同运行方式使用各自保存目录，不自动迁移旧档。本附属没有即时状态存档或双人 Netplay；电脑共享画面和控制交接不等于双人游戏。JNI 原生故障可能使 Minecraft 一起退出，未知硬件、显卡和完整游戏流程仍需实测。
