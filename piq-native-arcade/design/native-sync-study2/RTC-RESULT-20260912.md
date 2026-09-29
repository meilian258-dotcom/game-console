# 固定 RTC 的最后一组有界复验

记录者 cabinet_reuse_review；2026-09-12 10:39（UTC+8）。这是在 `README-20260912.md` 之后，经根代理明确批准的最后一次扩展；原失败报告和原说明保留，不覆盖。

## 结论：固定初态重放通过，不等于任意快照恢复通过

仅在独立 QA 临时目录，由 libretro 自身解析固定参数文本：

```text
mame -rtc 20000101000000 -verbose -rp "<本次临时目录>" <kof97或mslug2>
```

没有调用 Windows shell 执行 `.cmd`，没有读取用户提供的命令或配置，也没有改 DLL/helper。生产 `read_config` 仍为 disabled。双方原生日志均确认 RTC 解析成功，并显示 `base_time=946656000`、`2000-01-01 00:00:00`。参数入口依据固定版本 [retro_init.cpp](https://github.com/libretro/mame/blob/4fc9a9312baaf34963847f884961ad9793fbbc1d/src/osd/libretro/libretro-internal/retro_init.cpp) 的 cmd 内容解析和 [machine.cpp](https://github.com/libretro/mame/blob/4fc9a9312baaf34963847f884961ad9793fbbc1d/src/emu/machine.cpp#L157) 的 RTC 参数处理。

每游戏仅一对依次新建、互相独立的 QA JVM：先运行 32 次 `retro_run`，双方加载同一份第 32 帧初态，然后执行同一份 12,000 帧输入。每帧比较全部 RGB、PCM 及输出头；初态和每 600 帧比较全部 1,029,356 字节状态。

| 游戏 | RGB/PCM | 21 个完整状态检查点 | 重建耗时 | 游戏时长 |
|---|---|---|---:|---:|
| KOF97 | 12,000/12,000 完全一致 | 21/21 完全一致 | 22.73 秒 | 202.75 秒 |
| mslug2 | 12,000/12,000 完全一致 | 21/21 完全一致 | 23.68 秒 | 202.75 秒 |

先前 159001、159004、1029342–1029343 的差异全部消失。没有忽略字段、修改状态内容、静音或只校验画面。两报告均 `ok:true, initial_replay_qualified:true`。这支持 RTC 初始时间是前一组完整重放差异的实际来源；但没有对 MAME 每个原始状态偏移取得独立 registry 名称映射。

## 可实现范围与必须保留的限制

可据此做严格白名单的“固定 RTC + 同一初态 + 有界输入重放”实验适配；不是直接使用任意当前 MAME snapshot 冷恢复。之前的不同历史重载音频问题仍成立，本次没有重新宣布它已修好。

- 固定且校验 core、ROM、BIOS、运行 profile/RTC、原生架构；首版只覆盖下述两份实际 ROM，不由同名文件自动视为合格。
- 初态约 1.03 MB；12,000 帧四端口输入按每帧 8 字节占 96 KB，合计 1,125,356 字节。恢复必须重放所有输入/控制事件，不得漏 reset、改选项或额外 core 步进。
- 已实测范围只有 202.75 秒历史。按本机速度线性估算一小时需 404/420 秒重播，不是已验证的一小时恢复；低性能机器可能无法边运行边追赶。应明确历史上限、超时与媒体回退，不能称无限时长/即时加入。
- 同一 Windows x64 机器和时区下完成；未验证跨架构/时区/不同编译器浮点差异。生产兼容标识不能省掉关键 profile 条件。
- 仍需真实 step IPC、取消/回收/连接生命周期和最终 JAR 验证；本报告没有覆盖 Minecraft 世界、网络或生产 free-running loop。

## 固定输入与证据

| 项目 | SHA256 |
|---|---|
| mame_libretro.dll | 6172A988AB67FE68F4177A6FC8FBB82619EB2044C330930F0F572F7B1EDC2301 |
| 已交付 piq-native-helper.jar | 20F6F3028D76DAEB01212D1808BE90E35BFB5429D1E06153B7D8B32DD73E943C |
| kof97.zip | 804F892924D4650545D3EA2D19FB85670094DC46DB882FECAF3E03009E2C4B9F |
| mslug2.zip | 1A82D65E88050FDC75DBCEA180E48802D54C67A4748558FC4BD51C0103D56E4A |
| neogeo.zip | E1FFD4AB180E2F6AA4A3AA4D2C6F991E19D8EF762BEC8B5A6283704A2EDC3BBC |
| kof97-fixed-rtc-replay-v1.json | E1C78A1D57509407B9011B1B53430B9219B68E5594D6DC2BF8507F8FDFA22C7F |
| mslug2-fixed-rtc-replay-v1.json | EC3CC5B0F7915222E9F66DB5C71B540B17CAC39494922B34C2C2560C76892570 |
| tools/check_native_fixed_rtc_replay.py | B8C24468229BAD03228F805AB28E55903AD1020E57D17AF0D3C0335D93CF284F |

唯一新增工具 `tools/check_native_fixed_rtc_replay.py --rom <已有游戏.zip> --bios <已有neogeo.zip> --report <新报告.json>`，复用未修改的 `NativeInitialReplayProbe` 和 `NativeDeterminismProbe`。原文件 SHA/大小/mtime 前后均一致；临时 ROM/命令/state 被清理，所有自有 QA 进程已退出。没有安装、打包、修改生产或操作活跃游戏实例。
