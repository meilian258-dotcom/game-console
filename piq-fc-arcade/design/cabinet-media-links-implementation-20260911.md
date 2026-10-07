# 公共四口、媒体编解码与双机通讯线交接

日期：2026-09-10～11。

## 生产边界

- 内部公共库：`RetroEmulator` 新增兼容 default `maxPlayers/offerInputs/releasePort`。
- FC：`CabinetEmulator` 同步桥接上述接口；新增 `CabinetMediaCodec`、`CabinetLinks`、`CabinetLinkLedger`。
- SFC 通用机柜：`SfcCabinetInputs`、`SfcCabinetSession`、`SfcCabinetProvider`；`SfcHomeMod` 只将通用后台声明为可联网并注册最大 2 口。
- 未修改 SFC 家用 Playback、服务器、租约、写卡界面、核心；未修改模型、PNG、ROM、版本、已交付包、用户游戏实例或远程服务器。

## 四口与媒体

旧两口接口仍可调用，第三/四口非零不被旧实现静默吞掉；旧默认独立释放明确拒绝，不会错误清空所有玩家。`asRetro()` 完整转发新增能力。SFC 仍只有两个口；退出某个口会同时清除该口当前状态、目标状态及所有待处理输入边沿，但保留其他口的顺序与按下/松开。

视频是 RGB565 little-endian + Deflate level 1，下采样上限 384×288，无上采样，保留显示比例与旋转。压缩输出上限 131072 字节；高熵超限返回 null 丢弃视频，不能连带丢弃音频。解压严格验证精确长度、结束标志、无尾随字节/字典/截断，并限制解压体积。音频为独立 48kHz 双声道 PCM16LE，每块最多 19200 字节，序号/样本时钟由上层管理。

`design/cabinet-media-ports-source-v1.json`：42 个 JUnit 测试通过；真实冻结 WASM SFC 核心的原创 65816 测试 ROM 探针通过。合成视频压缩实测仅代表这台开发机的纯 codec 成本，不能代表商业游戏/真实网络表现：384×288 渐变压缩约 28841 字节，编码中位数约 0.93ms；同尺寸随机高熵样本全部受限丢帧。

## 通讯线 API 与席位

- `CabinetLinks.register()`：根模组初始化注册 pending 生命周期清理。
- `master(server,target)`：未连接返回自身；存在持久连接就返回主柜，即使另一端暂时卸载。
- `hasLink(server,target)`：是否有精确身份的持久链接。
- `peer(server,target)`：仅在双方完整实体、身份与后台均有效时返回另一柜，否则 null。房间层必须拒绝 `hasLink && peer == null`，不得将副柜降级为独立 P1。
- `use(player,clicked,hit,disconnect)`：通讯线物品调用；首次选择主柜 P1/P2，第二次选择副柜 P3/P4；Shift 为拆线。
- `removed(level,anchor,identity)`：只能从真实方块 onRemove 调用。不能在 BE `onChunkUnloaded()` 或共用的“拆除或卸载”回调中调用。

两柜必须双人机、同维度、相距不超过 16 格，主后台支持至少 4 口，两端均空闲且没有已有连接，玩家必须 OP2。副柜同步主柜模拟器，外观及旧卡/存档保持各自不变。只有同位置还不够，UUID 和原 BE/代理 BE 对象均须匹配。

每端使用原 `ServerCabinets.validatedTarget` 权限路径。所有保护事件执行完后，再用保存的双方 `Binding` 调用 `valid(false)`，防止第二端事件替换第一端、撤销权限、改变玩家身份或启动会话。连接和拆线都保留原 8 格玩家距离检查：较远两柜需要站在中间，使两端分别在 8 格内，不放宽保护范围。

链接保存于 overworld SavedData `piq_cabinet_links`，最多 128 对；加载最多检查 512 行，拒绝重复身份/坐标、同柜、跨维度、超距、循环、无效 endpoint。pending 最多 64 人，60 秒，绑定真实玩家对象、维度、精确第一柜对象，登录/退出/服务器停止清除。提示只用物品栏上方 actionbar。

## 验证界限

`design/cabinet-links-source-v1.json`：16 个测试通过，包含实际纯生产 Ledger 的三维 16 格边界穷举、整数溢出、权限/忙碌/四口限制、重复/循环、陈旧身份不能拆线、128 对容量、恢复和不可变快照。权限事件顺序、pending 生命周期与 SavedData 调用目前另有源码契约检查；这些不能冒称真实 Minecraft 服务端、保护插件或四名真人联机实测。

后续实际 API 验证：`tools/qa/CabinetLinksDataProbe.java` 直接使用真实 MC `CompoundTag`、`RegistryAccess.EMPTY` 与生产 `CabinetLinks.Data.save/load`，920 条断言通过，结果在 `design/cabinet-room-actual-api-qa-v3.json`。探针实际找到缺失 Pair UUID 时 MC NBT 抛 NPE 的路径；现已在读取前显式验证 UUID 与两个 Compound 类型，而非扩大异常吞掉范围。覆盖 128 对往返、512 行扫描上限、损坏/缺失/错类型/重复反向/超距/跨维度和可变 NBT 隔离。此项验证没有启动真实世界或区块；未加载的主副机仍不能降级席位由源码与房间权限复核确认。

独立连线入口复核：三种机柜 `useWithoutItem` 对主手通讯线先返回 PASS，由 `Item.useOn` 执行；保护事件重入受原 `CHECKING` 与 Links `USING` 双重约束。已加载部件拆除经结构 cleanup 移除 anchor，再触发精确 UUID 删线；环境破坏部件而 anchor 未加载时不强制加载世界，持久线不可授权，待 assembly 重载清理 anchor 时删除。普通 BE 区块卸载只关闭房间，不删除线。

负责完整构建、协议/房间与实体物品整合和后续成品验证；该项没有启动游戏、修改用户存档或发布/安装。
