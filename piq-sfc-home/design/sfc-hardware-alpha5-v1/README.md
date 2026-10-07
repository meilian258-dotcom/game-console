# SFC 自由网格重制 / alpha5 候选

日期：2026-09-10。范围：SFC 硬件模型；未执行 Gradle 构建或 Minecraft 实机验证。

## 参考与实现

核对任天堂官方 [ニンテンドークラシックミニ スーパーファミコン](https://www.nintendo.co.jp/clvs/index.html) 及其 [控制器图片](https://www.nintendo.co.jp/clvs/img/top/block_01-image.jpg)，并与日版原机照片对照。官方页面说明控制器尺寸与按钮布局复刻原版；迷你主机尺寸不是原机尺寸，本实现没有将两者混同。

主机使用连续倒角壳、分层浅灰外壳、中灰顶板、实体卡槽、EJECT/POWER/RESET、前面双七孔端口、后面 AV 三孔。手柄使用连续曲线轮廓而非逐行方块拼接，SELECT/START 为正确的斜条，X 蓝上 / Y 绿左 / A 红右 / B 黄下，顶部只有 L/R。灰卡使用同一倒角语言和原封面坐标。所有字样由几何面组成，没有照片贴脸，没有新增或编辑 PNG。

自由三角网格的原始 UV/缓存渲染方式与既有 FC Subor 精细模型一致。`build_sfc_hardware_mesh.py` 是确定性源，生产资源只新增 `assets/piq_sfc_home/meshes/sfc_hardware.json`。原 8 个模型 JSON 保持字节不变，其物品 display 变换、资源路径仍然保留，实际物品/方块几何改由本轮 mesh 绘制。资源包若要更改新外形，需要覆盖新 mesh，而不是只更改历史方块模型。

## 不变的功能边界

- 没有修改服务端、联网、租约、输入、写卡或模拟器核心代码。
- 放置/碰撞和 `SfcModelPresentation` 8 种显隐、4 个朝向保持不变。
- AV 中心仍为 `[9.335,1.955,15.2] / [8.675,1.955,15.2] / [8.015,1.955,15.2]`；后孔面向外多出 0.002 原生单位用于避免共面闪烁，锚点不动。
- 插卡变换仍是 `scale=0.645`，偏移 `[8-8*0.645, 2.81-4.6*0.645, 10.40-8*0.645]`。
- 卡面贴图仍使用未修改的 `SfcCoverGeometry`，面位 `z=7.241`，上下 `y=6.43..9.55`；新静态标签在其后方，保留旧动态封面显示。
- 第一人称仅在 SFC 手柄和另一只实际空手时接管；游泳、滑翔、瞄准、隐形、死亡以及另一手持物时走原物品渲染。使用玩家自身标准/slim 皮肤与双手，轻微倾斜，不改原 FC 的双手姿势。
- 本次没有新增按键动画，不能把静态彩键或持握动作描述为按键动画。

## 生产源码白名单

修改：`client/SfcHardwareRenderer.java`、`client/SfcCartridgeRenderer.java`。

新增：`client/SfcHardwareMeshData.java`、`client/SfcHardwareMesh.java`、`client/SfcHardwareItems.java`、`client/SfcControllerPoseLayout.java`、`client/SfcControllerPose.java`。

新增内部类型：MeshData.Part、Mesh.Part、HardwareItems 的匿名 extension / HardwareItemRenderer / ItemModel、PoseLayout.Rig / Arm。旧 HardwareRenderer.CachedModel 被删除，原 facing switch 的编译辅助类可以保留。

唯一新增生产资源：`meshes/sfc_hardware.json`，4,647,478 bytes，SHA256 `F6EBE22F24F84D4383AB409876BC3C6A43E20DB81E1B2A98869A2340ADDF0AAC`。

## 已执行验证

`tools/check_sfc_hardware_mesh.py --output design/sfc-hardware-alpha5-v1/pose`：

- 直接 javac 编译和执行实际生产 CPU 解析/姿势类（不是重写算法替身），13 JUnit 通过，含坏版本、缺组、异常资源来源、越界、NaN、无效法线/UV/材质/顶点形状等负例。
- 7 个挥手时间 × 2 种视角 FOV × 2 种屏幕比例，共 28 个实际矩阵投影构图未越界。
- 标准/slim、带袖口/不带袖口的真实原版尺寸包围体，共 196 条关键按键中心射线没有被手臂挡住。
- 主机、手柄和灰卡的实际旧 JSON GUI 变换均不裁切；插卡落入真实槽洞并有间隙；模型与生成器逐字节一致。
- 客户端专属订阅、按重载原子替换缓存、失败清空旧缓存、无逐帧资源读入、物品只替换几何、原封面和 AV 调用等静态源码接线断言通过。

`tools/test_sfc_model_pipeline.py`：6 项历史兼容测试通过，8 个旧 JSON 字节冻结、实际原 selector 和旋转、负例仍覆盖。该检查已明确标注历史 JSON/transform 兼容，不再把旧 voxel 几何当作新的可见外形；本轮 mesh 使用独立实际网格检查。

最终模型报告：`pose/mesh-pose-audit.json`，SHA256 `BF66D72539A45F33A4E6923960ACAB41B5B281A77BD3CAF19642256F9586C422`。

## 目视资料与限制

- `hardware-overview.png`、`console-front.png`、`console-rear.png`、`controller-top.png`、`cartridge.png`：从实际三角形、UV、既有材质采样渲染。
- `pose/controller-grip.png` 和 `pose/controller-grip-detail.png`：实际 Java 持握矩阵与生产资源；手臂为原版尺寸/袖口包围体的诊断颜色示意，游戏内使用玩家真实皮肤。
- `pose/item-gui.png`：实际物品 display 变换下的三物品预览。

这些是离线真实网格预览，不是 Minecraft 游戏截图；没有启动客户端或原生模拟器。射线验证按键中心而非按钮的全部边缘像素，只覆盖所列 FOV 和比例。完整 MC 渲染、资源重载、动画/第三方模型 MOD 兼容尚需父任务实机/构建验收，不能假称已实机验证。
