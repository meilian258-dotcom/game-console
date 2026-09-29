# 当前桥接构建入口

## FC76.21 保存候选（2026-09-28）

当前开发及交付使用 `outputs/netplay-latency7620/native-save` 的 **PNP7 / 8 输入字** EXE，SHA256 `C84161B49D0ED02816454409F20E00FDC99D72C886ABFC0E230214F90D3A5CE6`。由该目录完整修改后的RetroArch1.22.2源码构建，命令、补丁、编译器见build.json；构建入口为 `outputs/netplay-latency7620/build_native.py save`。保留76.20 PRE_FRAME前最新输入采样，第8字为保存请求；不可混入PNP6运行器。MC netplay-4与netplay-save-1配合主包主持校验和服务器原子保存，SFC42/街机1.5.3提供对应归属授权。使用/验证见 `design/Netplay存档-FC76.21-SFC42-使用说明.md` 和 `outputs/netplay7621/VERIFICATION.md`；下方保存“未交付”描述仅是76.20当时历史。

## FC76.20 输入时序候选（2026-09-28）

本轮交付从已发行 FC76.18 隔离构建，源码在 `outputs/netplay-latency7620/isolated/piq-fc-arcade`，不是当前尚未完成的 FC76.19 保存支线。`outputs/netplay-latency7620/build_native.py` 默认以 `outputs/arcade766/native` 的已钉住源码为基线生成 `native`：PNP6 音画、PNPI 输入请求、7 个输入字。新增 `piq_input_poll.h` 在 `core_run` 的 Netplay PRE_FRAME 前取最新输入，音画回调不再提前冻结下一帧输入。沿用原生等待、回滚、CRC 和席位授权，不使用 Run-Ahead。

当前开发工作区的保存支线也合入相同时序，但使用独立的 PNP7 / 8 输入字，构建脚本加 `save` 参数输出 `native-save`；第 8 字仍是保存请求，不允许混用 PNP6 EXE。Gradle 路径已对应，原 FC76.19 保存改动和旧证据全部保留，尚未作为完整版本交付；后续保存交付版本应高于 FC76.20。

两种输出均保留完整修改后的 RetroArch 1.22.2 源树、补丁、编译命令、编译器与 EXE 哈希。MC `netplay-3` 和公开 Java 接入 API 不变；更改仅限已认证本地 IPC。最终验证/未验证边界见 `outputs/netplay-latency7620/VERIFICATION.md`。下面内容是历史入口，不是本轮构建命令。

## FC76.1 历史入口

FC 0.31.0-alpha.76.1 使用 `outputs/arcade-four76/build_native.py`，输入为保留的 RetroArch 1.22.2 / PNP3 已验证源码树 `outputs/netplay72/native-final`。校验其 build.json 源码指纹后复制到独立目录，输出完整修改源码、两份最小补丁、命令/编译器记录及 EXE SHA256。

本目录原 build.py/piq_bridge.h 为旧 FC70 / PNP2 构建历史，不能拿来重新生成当前 EXE。当前 Gradle 从 outputs/arcade-four76/native 取运行器。没有改 FBNeo、Mesen、SFC DLL 或 RetroArch 回滚/时序/存档算法。

当前私有前导仍32字节随机令牌+1字节，但最后字节由布尔值变为设备掩码：0旁观、2=P2、4=P3、8=P4。它仅由主持Java使用服务端赋予的固定座位写入，不从客户端透传。原生PLAY请求必须恰好等于该掩码；拒绝共享端口、自动抢空位、冒充P1或观众升级。MC netplay-3 阻止与旧主包混联。
