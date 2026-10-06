# SFC 核心 README 历史存档

以下完整保留 2026-10-07 整理前的 README，供查找旧版本行为、兼容边界和验证记录。文中的“当前”“本轮”仅指各段当时的版本，不作为现行安装指南。请从[当前入口](README.md)开始阅读。

# 方块电玩：SFC 核心 / Game Console: SFC Core

当前作为 SFC 完整附属的历史核心组件；对外统一[Game Console 命名](../source-control/BRANDING.md)，内部 `piq_sfc_arcade` 标识不改。单独构建不当完整玩家包交付。下方保留早期原型的能力和使用记录。

## 当前 core10：旧柜服务端内容目录收尾（2026-10-04）

配套FC76.40和完整SFC47。正式服务端库存统一放实例根 `game-console/piq-sfc-arcade/roms`；此前实例根 `sfc-roms` 在首次使用时由后台worker校验复制，原文件不删。同名同字节复用、同名冲突拒绝覆盖、无效旧ROM留源并记录；最多512目录项、256个ROM、合计2GiB，不递归扫描。失败后检查服务端日志/冲突文件再重试，不建议把新旧目录同时当写入入口。

客户端本地 `sfc-roms` 和“打开ROM文件夹”按钮保持不变；它是本机导入/下载缓存，不再等于远端服务器库存。旧柜服务器已有游戏现在可直接选择，修正过去必须先在客户端存在该ROM的错误前置条件。库准备、列库、哈希查询与上传落盘均不在服务器tick执行：独立单IO worker、总4请求/每玩家1请求，明确准备中/处理中/失败提示；请求完成回主线程复核同连接、机器实体、ROM快照、距离与OP权限。取消后的实际IO尚未退出前不释放上传内存配额；停服中断后台工作，后台任务不持有玩家/世界回调。若文件已经成功入库才失去会话，允许保留经校验内容，但不再修改机器选择。

公共路径来自主包 `ServerContentPaths.instanceArea`；只负责纯路径，不授予内容权限或迁移保存。原有下载仍绑定授权会话/连接/文件与SHA，注册ID、协议3、WASM核心、模型和存档不变。**这不是把旧单人WASM街机迁入公共多席/Watch/JNI Netplay，也不是SFC家用开局流程已统一。**

core10发布依赖FC≥76.40，默认NeoForge21.1.236编译、最低21.1.229、MC1.21.1/Java21。源码构建用 `-PgameConsoleJar=../piq-fc-arcade/build/libs/piq_fc_arcade-0.31.0-alpha.76.40.jar`（默认即此路径）；正式玩家只安装经审核的完整SFC47，不另装core10/旧core9/家用薄包。

先前未改源码构建与冻结core9的52个class全部逐字节一致，WASM也一致；本轮不覆盖core9或重编WASM。core10 `check jar` 通过ABI、实际仓库、复制迁移/冲突/限额/取消自测及源码与成品两项真实jgenesis WASM smoke。Windows符号链接创建探针无法执行，明确跳过；Minecraft菜单、上传、断线和多人仍待实测。完整包边界和证据见[构建说明](../source-control/BUILDING.md)、[本轮指南](../piq-fc-arcade/design/内容库收尾与核心退役-20261004.md)，不标稳定、不代表已部署。

## 历史原型说明（alpha.2～3）

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

## 历史使用方法（新服务端路径见上方 core10）

1. 将自己拥有合法使用权的 `.sfc` / `.smc` 放进服务端运行目录的 `sfc-roms`。单人模式中，服务端运行目录就是 Minecraft 实例目录。
2. 使用 `/give @s piq_sfc_arcade:sfc_arcade` 获得机器并放置。
3. OP 对机器 Shift+右键，在游戏库中选择该机器要运行的游戏。
4. 玩家普通右键机器。若本地缺少 ROM，会先自动下载并校验 SHA-256，随后启动游戏。
5. 再次普通右键同一台机器停止本地会话。

多台机器可以各自保存不同游戏。当前仍是客户端本地单人输入：旁观、双人输入、确定性多人同步、存档槽、客户端上传 ROM 和运行中换卡尚未接入。

## 历史原生核心构建与验证

```powershell
.\native\build-wasm.ps1
.\gradlew.bat clean check jar --no-daemon
```

`check` 会执行 ABI 自测、合法自生成 LoROM 的 Wasmtime 烟雾测试、ROM 仓库/校验自测、成品 JAR 烟雾测试，并检查成品未重复打包 Wasmtime。

上面的 `native/build-wasm.ps1` 仅用于明确批准的历史核心重建；当前内容库改动不需要运行它，也不修改已审核WASM。普通Java构建直接 `gradlew.bat check jar --no-daemon`，前置主包/完整合包要求见上方。

## ROM、固件与许可

项目、构建产物及测试代码均不包含商业 ROM。只应使用玩家合法备份或明确允许再分发的 homebrew；DSP/ST01x 等协处理器固件同样不会随模组分发。

本项目采用 GPLv3。嵌入的 jgenesis 代码也是 GPLv3，发布二进制时必须同时提供对应源代码、许可证及修改说明。
