# 第三方来源（本地试验）

## 源码依赖登记（2026-10-01）

`vendor/PvZ-Portable` 改为 Git submodule，来源 [KLuoNuoYa/PvZ-Portable 的 libretro 分支](https://github.com/KLuoNuoYa/PvZ-Portable/tree/libretro)，首次 pin 为 `6a3cbeee46679eaae2859d25a98207182388e149`。上游自己的许可、版权声明和依赖文件原样保留；不将第三方代码改授本附属许可。

旧两份许可逐字节一致，旧 libretro 头文件与此提交仅换行差异，无本地代码补丁。本轮只调整源码获取方式：**下面记录的原核心 DLL 未更新，也未证明它可由这个新提交重现**。上游源码文档新增的即时状态等能力，不代表现有 DLL 或模组已经接入。上游完整树还保留其平台图标与框架示例资料（包括一个 VB6 示例压缩包），不自动进入模组 JAR；此登记不是对全部第三方素材的公开分发许可审查。详见[核对记录](design/PvZ源码子模块.md)。

## 原始二进制来源（保留历史事实）

历史核心制品：`pvz_libretro.7z` 中的 `dist/cores/pvz_libretro.dll`，11564544 字节，SHA256 `7E4CAA5F801CF9B0FDB03E3E46D704447A49AA2198DC20A1E8369D73C303AD08`。

来自另一位开发者基于 PvZ-Portable 的 libretro 移植；其 README 指向 https://github.com/wszqkzqk/PvZ-Portable 。随包源码声明 LGPL-3.0-or-later，框架等第三方部分另有许可。DLL 没有被修改，也未核验原始二进制和源码重现一致性。原包中的 `main.pak`、图片/游戏资源没有进入本附属发行件。

保留核心 LGPL 文本和 PopCap Games Framework 许可在 JAR 的 META-INF/pvz。完整依赖和对应源码分发清单仍须与提供者核对；本版只供用户本地验证，不作为公开上架成品。

This product includes portions of the PopCap Games Framework, © 2005-2009 PopCap Games, Inc. All rights reserved. (http://popcapframework.sourceforge.net/).

libretro API 头文件取自同一来源源码包，许可声明保留于 vendor 中原头文件。本附属不是 PopCap/EA、Mojang/Microsoft 的官方产品，相关名称仅用于说明兼容对象。
