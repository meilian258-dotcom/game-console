# 用户 SFC 模型接入交接（2026-09-11）

负责人：`/root/sfc_cabinet_provider`。根维护手册由 root 统一留痕。本工作只调整模型、客户端视觉、纯测试和离线预览；没有改存档、用户实例、ROM、服务器、协议、玩法、模拟核心或版本。

## 来源与重复生成

只读来源：`piq-fc-arcade/design/user-models-20260911/source/02_SFC双手柄` 和 `03_SFC独立卡带`。README/动画 JSON 仅作为模型数据；没有执行附件中任何工具。导入器 `tools/import_user_sfc_20260911.py` 从 BBModel 的 element/group/outliner/face UV 转换，沿用仓库已核对 Minecraft FaceInfo/BlockFaceUV/FaceBakery 规则的工具函数。

执行 `python tools/import_user_sfc_20260911.py --write --preview` 可重复生成生产网格、两张原字节复制的纹理与主机 GUI 包装。纯函数 `build()` 返回网格与审核数据，`console_item_bytes()` 无参数返回主机物品 JSON 字节。没有重画/压缩/编辑来源 PNG。

## 分组和空间合同

- 主机原模型 872 个 cube，旧装配卡带 38 个 cube 整体剔除。独立版 86 个 cube 整体导入，不借 UUID 假设其 PCB 等同旧装配卡带。
- 世界插入卡带仅平移 `[0, 2.18, 3.711]`，无缩放或旋转；独立物品平移 `[0, 6.55, 0]` 居中。
- P1/P2 各自的 controller 和 15 段 cable 放入独立 docked 层；还从原 console_ports 拆出每端 7 个已插入插头/出线护套。领取该柄时全部一同隐藏，空插口保留。
- 用户卡槽原本开放：`slot_cover.parts` 为空，不凭空添加静态盖板。不插卡时无旧卡残留。
- 手持柄来自 P1 原始组：先减中心 `[11.5, .375, 1.7225]`，等比乘 2，再加 `[8,8,8]`。仅手持/物品展示使用此变换，桌面原比例不变。现有双手姿势 Java 类和常量没有更改。
- 卡带正面标签范围原始 `[6.075,.845,9.925,2.58,7.5985]`。FC 2:1 封面等比例内接，不拉伸，沿北面外移 `.002` 模型单位防重叠。
- 动态 AV 主机端使用原单 MULTI OUT 中心 `[5.55,.695,15.5375]/16`，黑色矩形插头+护套；电视端沿用公共电视 API 的黄/白/红端点。主干半径 `.010`、支线 `.0045` 方块单位，旧路径防穿壳/范围限制保留。
- 现有 BE 只公开 leasedMask，没有远端持柄玩家手部坐标。没有伪造跨玩家长线：已领取柄及其原固定线隐藏，归还后恢复。没有增加网络消息。

## 按键视觉

`SfcHomeClient` 只增加 `visualInputMask()` 读取已发送本地 12bit mask，且受既有 acceptsInput 门禁约束；所有既有方法原样，不重新采键、不取得 owner、不发送包。

原分组 `button_b/y/select/start/a/x/shoulder_l/r` 对应位 `0,1,2,3,8,9,10,11`。各自下降用户建议 `.035` 模型单位（手持等比为 `.070`）；十字键按四方向倾斜 ±5°。动画不改变输入状态，不增加平滑等待。默认静态组、物品栏及第三人称不冒用本地输入；双手第一人称与非空副手时的普通第一人称均能显示本地按键。

## 精确生产范围

修改 Java，均位于 `src/main/java/cn/piq/sfchome/client/`：

- `SfcAvCableGeometry.java`：真实单插口、黑色插头和线宽。
- `SfcHardwareMeshData.java`：允许自有纹理 namespace，限定 slot_cover 空组，严格有界动画 binding。
- `SfcHardwareMesh.java`：Part 保留 binding，逐运动零件 PoseStack 变换。
- `SfcControllerPose.java`：draw 传只读 mask。
- `SfcHardwareItems.java`：普通第一人称手柄分支传只读 mask。
- `SfcHomeClient.java`：仅新增 getter。
- `SfcCoverGeometry.java`：用户标签/卡带平移坐标。

新增：`SfcButtonAnimation.java`，嵌套 record `Binding` / `Transform`。

其余渲染器、姿势布局、世界/BE/交互、网络、服务端、核心、输入算法未修改。编译器相关嵌套类型按最终 JAR 实际变更逐项审核，不以整个 client 包放行。

资源修改/新增（无删除）：

| 路径（`assets/piq_sfc_home/` 下） | 操作 | SHA-256 |
|---|---|---|
| `meshes/sfc_hardware.json` | 替换 | `4B944A5A98CF926A38092BCDF3B8FA5E8D1B07631B116E554E7DD551B4D80E64` |
| `textures/block/user_sfc_20260911.png` | 新增，源字节复制 | `4BBBA0F53697D69A919F5FC12750E608AA50A4D71D8281F23D6428A5D02BD920` |
| `textures/block/user_sfc_cartridge_20260911.png` | 新增，源字节复制 | `7608F10AD4205635C321AA423F348CA68515B16FDDC7BDF9F111811121D4892A` |
| `models/item/console.json` | 仅新增 `display.gui` | `466510B9D2073126A07593BB9DB5A4083A0EBDDBF6CFBDC2D73579D37E00F33D` |

GUI 覆盖为 rotation `[30,225,0]`、translation `[-.625,5.125,0]`、scale `[.81,.81,.81]`，避免新整套主机沿用旧继承 `.87` 时右边界 `.5067` 超过 `.5`。parent 和四个第一/第三人称 display 保持原样。

网格 2,974,181 字节。三角形：body 2402，P1/P2 各 3628，controller 3364，inserted/cartridge 各 1012，slot_cover 0。

## 验证与边界

`python tools/check_user_sfc_20260911.py` 独立编译实际纯 Java parser/pose/animation/cover/AV 类，不运行 Gradle 或 Minecraft。30 个 JUnit 测试全部通过：解析器拒绝异常输入、纹理 namespace、独立 UV、卡带精确平移、8 个独立按键遍历全部 4096 mask、快速按放/肩键、16 种 AV 相对朝向等。

实际 Java 姿势导出后验证 7 种摆动、60/70 FOV、4:3/16:9 的 28 个视野场景；standard/slim 与袖口体积共 252 条按键中心视线无遮挡。实际 Java AV 输出 893 个 quad。三物品 GUI 投影边界不裁切。

报告：`conversion-audit.json`、`java-pose-animation-audit.json`。预览：`overview.png`、`grip-0.png`、`grip-1296.png`（A+上+L）、对应局部图、`multi-out-av.png`、`item-gui.png`。

预览是实际网格/UV 和 Java 变换离线渲染，不是 Minecraft 游戏截图；手臂仅原版尺寸的诊断体积，不是玩家皮肤。不声称真机手柄、实际局域网或游戏内外观已实测。
