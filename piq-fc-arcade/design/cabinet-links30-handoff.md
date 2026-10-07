# alpha30 单柜通讯线交接

2026-09-11。实现与离线验证记录；未进行游戏内测试。

## 行为与边界

允许两台物理机柜组合：单+单 2 席、单+双 3 席、双+单 3 席、双+双 4 席。主柜物理座位从 P1 开始，副柜从主柜物理座位数之后开始；单柜不显示或分配第二个物理席位。原未连线模式仍保留既有两席兼容行为。

容量来自 `CabinetBackends.maxPlayers` 的显式网络能力注册，必须足够承载两台席位总和。旧 NES 路径没有网络后端能力，不伪造支持。当前 SFC 2 口只支持两台单柜；Native 4 口可承载全部组合。

保留 OP 2、双端保护检查及回调后对象/UUID/权限复验、两端各 8 格操作距离、16 格连线距离、忙碌拒绝、128 对上限、64 个选择/60 秒过期。原 Host 邀请审批继续决定入席，不因连线放开权限。存档仍是同一 SavedData，既有双柜记录可读；端点 `Dual` 字段现在明确要求存在，避免缺失字段被默认当单柜。

协议从 `cabinet-room-1` 升级为必选 `cabinet-room-2`，需客户端/服务器成套版本。原消息字段顺序未变；只有 Assignment 对合法容量及物理端点归属的校验改变。Ready/Input/Reset/Seat/Buttons/Media/Stream codec 与输入/音画运行算法不改。

可视线从双方 BE update tag 的 pair UUID + 对端完整身份互相验证，不能授予权限。视觉字段不写入持久存档；加载由服务端原 SavedData 重建，每秒清理/刷新已加载端点，不主动加载区块。真正拆机删除原 UUID 对应配对；卸载仅停止房间，持久配对保留。客户端只画一次，缓存最多 128 对、弱引用世界、距离 48 格；两端未加载/身份不符不画。纯线路绕过两柜 AABB，含相邻背靠背单柜的有限短线头回退；不扫描任意地形或改方块，因此不保证避开第三方障碍。

## 精确生产范围

修改：

- `cabinet/CabinetLinkLedger.java`（兼容类型和容量校验；End/Pair 字段不变）
- `cabinet/CabinetLinks.java`（泛化连接、真实拆放、视觉恢复；既有 Pending/Data）
- `cabinet/CabinetRooms.java`（容量与端点物理座位范围）
- `cabinet/CabinetRoomNetwork.java`（registrar 与 Assignment 构造校验；CODEC 字段顺序不变）
- `world/LegacyFcArcadeBlockEntity.java`（只读视觉 getter、server setter、updateTag/load、onLoad）
- `client/cabinet/CabinetClientBackends.java`（仅 visualInputs）
- `client/cabinet/CabinetPeerInputs.java`（新增 count-aware visualPair；旧 overload 仍只接受 0/2）
- `client/LegacyArcadeSkinRenderer.java`、`client/DualCabinetRenderer.java`（BER render/bounds 调用线缆；默认单柜无 skin 也显示线）

新增精确 class：

- `cn/piq/fcarcade/cabinet/CabinetSeats.class`
- `cn/piq/fcarcade/layout/CabinetDataCableGeometry.class`
- `cn/piq/fcarcade/layout/CabinetDataCableGeometry$Quad.class`
- `cn/piq/fcarcade/client/CabinetDataCableRenderer.class`
- `cn/piq/fcarcade/client/CabinetDataCableRenderer$Key.class`
- `cn/piq/fcarcade/client/CabinetDataCableRenderer$Cached.class`

没有新增/更改模型、贴图或其它资产；使用原版 gray_concrete 纹理。未改 helper、核心、ROM、音画/输入网络执行算法。

## 验证

`tools/check_cabinet_links30.py` source 模式显式编译所列生产类，对本轮源进行独立真实 MC API 校验；final 模式只编 tests/probes，不编任何生产类，并逐类 CodeSource 验证最终 JAR。

已有 `design/cabinet-links30-source-v2.json`：63 项测试，3367 个实际断言；`source-v3.json` 在相同生产字节上追加真实必选协议 2 注册检查、拆放/替换身份/保存重载及视觉旧包拒绝，3403 个断言。之后工具补完整 14 类 CodeSource 检查，最终以 final 报告为准。

测试覆盖：所有容量矩阵/反序混接、邀请 Gate、真实 RoomLedger 座位、距离/忙碌/权限/上限/旧 UUID、混接视觉端口与 guest 隔离、几何四朝向/三角绕序/有限预算/近距回退。实际 probe 使用已注册 NeoForge 外层 packet codec，逐字节截断拒绝、SavedData NBT、真实 BE update/save/load；未启动世界/服务器/网络连接，不冒充实际多人游戏或保护插件实测。

最终调用：

```text
python tools/check_cabinet_links30.py --fc <final-fc30.jar> --report <exclusive-new-path.json>
```
