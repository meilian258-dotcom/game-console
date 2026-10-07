# alpha28 光枪支架交接

仅本地源码/验证，不安装，不改原模型或已交付包。

## 用户操作

1. 放下空光枪支架；主手拿已有 `fc_zapper`，右键支架放入。放支架不会附送枪。
2. 主手拿 `zapper_stand_cable`，先右键支架，再右键关机的 FC 主机。连线同维度、端点距离不超过 16 格；交互期间分别复验两端权限、加载状态与 8 格使用范围。每台主机/支架只能有一根连接。
3. 开电视、给主机插卡、按主机实体电源。连接支架在开机时选择光枪核心，不在运行中偷换核心。
4. 空手右键支架取实际存入的枪。Host 可以领取；其他人按既有主机审批，待审批枪留手中，但不能输入。未成功授权也不复制或吞枪。
5. 持原枪右键原支架归还：撤销该枪输入/待审批，后台局继续。普通手柄与硬件第二口互斥由会话服务负责。
6. 潜行持连接线右键支架断线。断线/卸载撤输入；普通区块卸载保留 SavedData 连接。物理拆架只掉落架中实际枪一次，借出中的枪不会变出替身。

借出枪的 receipt 不是控制权：必须匹配活支架、当前 loan UUID/借用人，并且控制目标必须是该支架连接的精确主机。原支架确实已拆（源 chunk 已加载且旧身份不存在）的实物枪可存入另一空架；源 chunk 未加载不视作拆除。

## 生产范围

修改：`registry/ModItems`、`ModBlocks`、`ModBlockEntities`、`CreativeTabCatalog`、`FcArcadeMod`（只新增支架注册和客户端 renderer 注册）。

新增精确 stems：

- `home/ZapperStandLinks`（`$End`、`$Link`）
- `home/ZapperDock`（`$Loan`）
- `home/ZapperStandOrigin`（`$Receipt`）
- `home/ZapperStandBlockEntity`
- `home/ZapperStandBlock`（编译器 facing switch `$1`）
- `home/ZapperStandCableItem`
- `home/ZapperStandService`（`$Pending`、`$Data`）
- `layout/ZapperStandGeometry`
- `client/zapper/ZapperStandRenderer`（`$Cache`）

`HomeZapperService` / `HomeConsoleRuntime` 由 FC agent 维护；本组件调用 `take(player,console,stack)` / `returnGun(player,stack)`，提供 `connected(console)` 和 `validOrigin(player,stack,console)`。归还不依赖 ZapperBinding，因此审批中也可归还。

## 模型与资源

可信导出器 `tools/import_zapper_stand28.py` 调用既有审核后的 `prepare_zapper_model_parts.derive()`，不执行用户 ZIP 代码。保留原支架 85 / 线 51 / 插头 18 元素的所有坐标、旋转、面、UV；仅引用已存在 `item/zapper/skin`。枪身/扳机复用现有已导入模型。没有新 PNG。

库存连接线图标使用原盘线 + 插头共 69 元素，未缩改源几何；只改 GUI 显示变换，scale 3 符合真实 MC 最大 4 的解析限制。支架 GUI scale 1.25。两图标在真实顶点投影中居中并完全落入 16 像素物品槽范围。

七个新增资源 SHA256：

| 相对资源路径 | SHA256 |
|---|---|
| assets/piq_fc_arcade/models/block/zapper_stand/stand.json | B230833E8B2A7423FB61B8CAEB9FDF80FBC5718C07BE49A25F6706CC279CED8E |
| assets/piq_fc_arcade/models/block/zapper_stand/cable.json | EE3BC01026113DDB507909A703C2A291936D35C12DB1D01543806B82EF2228AF |
| assets/piq_fc_arcade/models/block/zapper_stand/connector.json | 5A92B1E62E2852F1ABC88AEADE009E3BB2ABE0B5D00464788D3440A0814231E4 |
| assets/piq_fc_arcade/models/item/zapper_stand.json | 1654F6EE8638424397A0F0C8B9871BAF102E1D0A3083C1EC356FE963ACA688BC |
| assets/piq_fc_arcade/models/item/zapper_stand_cable.json | 3F76659EC7EFDBDC3BDA6F861CA3E465E7EE83C35A762951E2AA78586B7C512B |
| assets/piq_fc_arcade/blockstates/zapper_stand.json | E59C837E7E30E3C455005616AE2E0DCF086ABB4375456A791F51B75B72261C95 |
| data/piq_fc_arcade/loot_table/blocks/zapper_stand.json | 0343F2ED72A2789DF1533DA87F98882CACA519E71B7158504AA1322C8B8D5210 |

语言 21 项由 `design/zapper-stand28-lang.json` 用于共享语言集成。

## 验证 / 边界

- `ZapperStandTest` 14 项：原对象转移、精确 receipt、旧 loan 拒绝、借出拆除不掉替身、存放拆除仅一次、持久化双所有权拒绝、连接一对一/16 格/128 对、BlockPos 有符号 12 位 Y 边界。
- `ZapperStandGeometryTest` 4 项：原尺寸、四朝向、动态线 8–64 段、端点、有限坐标/最大跨度。
- `tools/check_zapper_stand28.py --fc <jar> --report <new-path>`：只编译 tests/probes，真实成品类运行，逐字节检查七资产；`ZapperStandDataProbe` 真实 Minecraft SavedData/NBT 往返、畸形字段、512 行扫描与 128 对容量；`ZapperStandVisualProbe` 真实 MC ItemTransform 解析 + PoseStack 图标边界/原元素比较。最终候选报告待最终候选固定后运行。
- `design/zapper-stand28-preview-v2/stand-preview.png` 已实际查看：放回/取走没有重复枪，空架保留，原 UV 正常。是离线代码资产预览，不是 Minecraft 截图。

动态连线是有界视觉绳段，不做碰撞/实体绳物理；借出端用当前玩家主手位置近似，不宣称第三人称每个骨骼点精确贴合。未知/不在主手时不画悬空到错误物体的线。连接端按主机已有插口坐标放置原插头。

没有启动游戏世界、真实多人或实体设备，不能将纯状态/NBT/几何探针描述成真人完整操作验证。生产已收口，后续仅响应真实编译/审核缺陷与最终 JAR 绑定。

### 候选实包预检

`build/review-appliance28-preflight-v1` 的 FC28（SHA256 `91083F86A86ABA4E2BD02A306527FE553B3F022EAE5300F40EF60A9AF8386B40`）已通过：

- `design/zapper-stand28-preflight-v1.json`：18 测试，579 真实 SavedData/NBT 断言，3156 真实 MC ItemTransform/PoseStack 断言。
- `design/appliance-nbt28-preflight-v1.json`：419 断言；真实 NeoForge RegisterEvent 注册原 ModBlocks/Items/BE、真实构造电视与支架，验证新电视默认关闭、旧无字段默认开机、音量夹取、signal 仅 powered、ItemStack 与来源 receipt 往返。没有 Unsafe/假 holder/世界。

工具 `tools/check_appliance_nbt28.py --fc <jar> --report <new-path>` 已可在最终冻结包重跑。注册探针不是完整 FML discovery，也不是世界权限/玩家交互模拟。
