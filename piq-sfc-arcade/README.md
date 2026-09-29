# 方块电玩：SFC 核心 / Game Console: SFC Core

当前作为 SFC 完整附属的历史核心组件；对外统一[Game Console 命名](../source-control/BRANDING.md)，内部 `piq_sfc_arcade` 标识不改。单独构建不当完整玩家包交付。下方保留早期原型的能力和使用记录。

面向 Minecraft 1.21.1、NeoForge 21.1.x、Java 21 的独立 SFC/SNES 街机模组。SFC 街机是新增方块，不会替换现有 FC 街机；运行时复用 PIQ FC Arcade 已提供的 Wasmtime，避免重复打包大体积原生库。

## alpha.3 已实现

- 使用 jgenesis 0.13.1 的无窗口 WASM 核心，支持常规 `.sfc` 以及带 512 字节 copier header 的 `.smc`；
- 方块正面直接显示游戏画面，独立模拟线程输出动态分辨率、NTSC/PAL 帧率和 48 kHz 双声道声音；
- 每台 SFC 街机通过方块实体独立保存所选 ROM 的文件名和 SHA-256；
- OP Shift+右键打开服务端游戏库，为当前机器选择游戏；普通玩家右键启动，再次右键同一台机器停止；
- 客户端缺少所选 ROM 时自动从服务端同步；已有相同 SHA-256 的本地文件会显示“已同步”，无需重复下载；
- ROM 采用 128 KiB 分块传输，服务端每名玩家每 tick 最多发送 2 块，避免一次性发送大文件阻塞服务器主线程；
- 方向键控制方向，`Z=B`、`X=A`、`A=Y`、`S=X`、`Q=L`、`E=R`、`Enter=Start`、`右 Shift=Select`。本模组不注册或拦截 `R`，避免影响 TaCZ 换弹。

alpha.2 的单人画面流畅度和声音已经由服主实际测试通过；alpha.3 新增的管理菜单、机器持久化和 ROM 网络同步目前只完成了编译及自动测试，仍需要进游戏验证。

## 使用方法

1. 将自己拥有合法使用权的 `.sfc` / `.smc` 放进服务端运行目录的 `sfc-roms`。单人模式中，服务端运行目录就是 Minecraft 实例目录。
2. 使用 `/give @s piq_sfc_arcade:sfc_arcade` 获得机器并放置。
3. OP 对机器 Shift+右键，在游戏库中选择该机器要运行的游戏。
4. 玩家普通右键机器。若本地缺少 ROM，会先自动下载并校验 SHA-256，随后启动游戏。
5. 再次普通右键同一台机器停止本地会话。

多台机器可以各自保存不同游戏。当前仍是客户端本地单人输入：旁观、双人输入、确定性多人同步、存档槽、客户端上传 ROM 和运行中换卡尚未接入。

## 构建与验证

```powershell
.\native\build-wasm.ps1
.\gradlew.bat clean check jar --no-daemon
```

`check` 会执行 ABI 自测、合法自生成 LoROM 的 Wasmtime 烟雾测试、ROM 仓库/校验自测、成品 JAR 烟雾测试，并检查成品未重复打包 Wasmtime。

## ROM、固件与许可

项目、构建产物及测试代码均不包含商业 ROM。只应使用玩家合法备份或明确允许再分发的 homebrew；DSP/ST01x 等协处理器固件同样不会随模组分发。

本项目采用 GPLv3。嵌入的 jgenesis 代码也是 GPLv3，发布二进制时必须同时提供对应源代码、许可证及修改说明。
