# FC33 / Native11 工作笔记（2026-09-12 13:55）

## 当前状态

现有街机的双人实验本地同步已实现并交本地测试包；未安装、发布或操作用户游戏/服务器。不要执行历史关机授权。完整记录在根 `Codex维护手册.md` 本时刻条目。

- 冻结目录：`piq-fc-arcade/build/review-sync33-v1/`。FC33：F36169E46B13CF46868851447B8518BC38C10967A03994AF26E89CE7B1E4FE00；Native11：F582314F64A2DC556FAC1704719C04EE9E5A05154C146FA1C49FCAD9615E2C5E。
- 测试ZIP：`制作Mod/03-街机模拟/街机本地同步-FC33-Native11-测试包-20260912-v1.zip`，46,423,677字节，7D7A3E35D3333967206D8EF71A624D1316D86C191A521C885424444F156DD653。
- 可选源码ZIP：同名加`-核心源码.zip`，240,120,669字节，D52DB4D4A76532E7C82CF7BED9804E34688673498F38C96E552AFB06D38153C1。
- 4个MOD：FC33、Native11、原SFC19合包、原GBA2。实验运行库是实例目录`piq-native-arcade/runtime-snapshot-v1/`的固定DLL/helper/JNA，绝不是mods里的文件；保留原`runtime/`给传画面模式。没有ROM/BIOS或用户存档。

## 接口与约束

现有机台管理员Shift＋空手右键 → 同步/网络，空闲切模式，下局生效；只有snapshot运行库时先选本地模式再配置游戏。没有新机台。不改变旧FC/NES和SFC家用操作。

`CabinetBackends.registerSnapshotSync` 明确注册最多两席和固定compat；SFC原`registerSync`保持初态全等要求。新`cabinet-sync-2`需客户端/服务器FC33成套。Native仅确切kof97/mslug2/BIOS集；完整身份见使用说明。两台单人柜可以联成两席；双人柜相连形成3/4席拒绝本地实验，原媒体容量不变。

Native原版两进程冷启state不等，因此先校ROM/BIOS/profile/帧率，再载Host完整快照并立即重存核完整SHA；逻辑帧与封装native帧（+32）同时验证。Digest300帧，Host快照1800帧。Guest追赶输入队列<=6才发ACK，server核精确token及head差0–120。Host不恢复、不停顿等待Guest；Host退出仍关整局，没有迁移。

取消用非阻塞Factory/Core.requestClose；只有拥有core的worker执行常规close，Native仅销毁精确自有child并等待退出后归还父进程单租约。私有ASCII暂存、路径和源文件SHA围栏。不是OS安全沙箱。

## 验证与下一步

全量Gradle：FC1416项，7既有skip；Native80项全部通过。最终六报告均在冻结目录，明确final-jar-only。两款真核boundary各600帧全画面/PCM/state零差；实际通用worker组合各600帧音画和周期全state零差，旧SFC实际WASM恢复也通过。真实FML发现、服务端公共类无Native/client加载、外层codec/分片和有界策略通过。源、旧包、所有模型纹理与运行库保持。

下一步只需用户两台Minecraft实测：加入/退出再加入、按键释放、P2取消不影响P1、旁观、单人柜连线、切回媒体。不要将离线多进程报告称作已测公网网络、UI或权限插件。新修复必须新stage/新证据/新ZIP，不覆盖v1；历史冻结源码草案资产不能直接用build/libs替代本包。

构建入口`tools/build_sync33.py`；最终公共验证`check_sync33_final.py`、server`check_snapshot_sync33.py`、Native真worker`check_native_cabinet_workers.py`、Native工程boundary/unit工具、SFC工程`check_sfc_repair_workers.py`。包装`tools/package_sync33.py`绑定最终SHA和全部证据，并把完整对应MAME源码单独封装。不同电脑重建需要配置工具链/缓存，详见源码说明。
