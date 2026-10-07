# 原生街机首版：有界可行性评估

独立审查：alpha7_audit，2026-09-10。仅方案与只读盘点；未新建核心工程、下载商业 ROM、修改既有模拟器或部署。

## 结论

技术可行，优先现代 MAME 的有限驱动集，而不是把已有 NES/SFC 模拟器或街机机壳当作原生街机核心。目前尚无经过构建、核心运行和游戏验证的原生街机成品，不能称作已可玩。

工作区可复用的是 FC、SFC、J2ME 工程和显示/手柄/世界交互基础设施；本次盘点没有找到 MAME、FinalBurn Neo 原生核心工程或可执行产物。当前 PATH 未发现 emcc、make、clang、g++，不等于全磁盘不存在其他工具链。

## 最小路线

1. 固定一块 2D 基板/极小驱动集，使用原创诊断程序或用户有合法使用权的 ROM set；不下载或捆绑商业 ROM。
2. 用现代 MAME 的 SOURCES 子集构建独立 headless 核心，提供有界 C ABI：加载 ROM set、推进一帧、复制视频/立体声音频、两口按键/投币/开始、存档与释放。
3. 先独立验证动态帧尺寸、输入、存档回放、生命周期和确定性，再做独立附属 Mod。复用 FC 新显示接口，不替换 FC/SFC 内部核心与已交付 JAR。
4. Wasmtime 路线须替换浏览器相关宿主依赖并真正运行验证；若改成本地 JNI，也须独立线程/资源生命周期、目标平台构建和失败处理，不仅提供一个空壳 GUI。

## 核心选择与许可证

- 现代 MAME 整体 GPL-2.0，多数核心文件 BSD-3-Clause；按实际选用源文件审核依赖和交付源代码义务。官方支持 SOURCES 限定驱动。其现成 Emscripten 构建产物需要 HTML/JavaScript、SDL 和浏览器环境，因此推断不能直接当作当前零导入 Wasmtime 模块使用。[MAME 关于](https://www.mamedev.org/about.html)，[官方构建说明](https://docs.mamedev.org/initialsetup/compilingmame.html)。
- FinalBurn Neo 的 libretro 适配和 Emscripten bitcode 分支有复用价值，但官方许可证限制商业获利及为基于其代码的项目募捐，不能默认按 MIT/GPL 的条件用于未知商业/赞助服务器。因此本方案不将它作为默认交付核心。[官方许可证](https://github.com/finalburnneo/FBNeo/blob/master/src/license.txt)，[libretro 构建脚本](https://github.com/libretro/FBNeo/blob/master/src/burner/libretro/Makefile)。

## 首版必须实际解决的阻碍

- ROM set 不是一份任意 ROM 字节：需要分片名称/CRC、父集、BIOS 和缺件错误处理；不得自动联网补商业 ROM。
- 浏览器版 MAME 的文件系统、时钟、视频/音频回调、帧调度并非现有 Java/Wasmtime ABI；目前没有跑通的可复用成品。
- 多机器的核心全局状态隔离、每帧执行预算、内存上限、停机和线程退出需要实际验证，不能只用 Java future 超时冒充中断原生执行。
- 不承诺全部 MAME、增强芯片、所有输入设备、网络确定性或商业游戏兼容性；先通过一个明确驱动的真实核心 PoC。

下一验收点：选定有限驱动后，提供可重现构建和原创程序的实际帧/输入/音频/状态证据；未达成前维持“可行性已确认、核心尚未交付”。
