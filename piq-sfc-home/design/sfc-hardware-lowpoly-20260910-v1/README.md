# SFC 低多边形外形收敛 — 2026-09-10

修改者：sfc_cabinet_provider，按 root 分工。根维护手册由 root 统一记录。

本次只重做 SFC 原生网格：机壳采用单段 45° 斜角，手柄 24 点轮廓和机械分缝，四彩按钮/右侧深灰区域使用 12 边几何。铭文是小号细线平面矢量字，不再拼粗 3×5 像素块。外形由用户日版手柄参考图核对；不新增位图、不改现有 PNG 或 FC 外形。

材质复用原版/FC 既有纹理中的固定 texel。实际 RGB：浅灰壳 214/214/214，灰面板 176/176/176，右侧深灰区 104/104/104；X 蓝上、Y 绿左、A 红右、B 黄下。材质及 UV 由真实 parser 测试验证，不依赖运行时 tint 或新渲染逻辑。

## 精确生产白名单

- 唯一资源修改：`src/main/resources/assets/piq_sfc_home/meshes/sfc_hardware.json`
- SHA256：`65A70EB4C77F90B49DEAF76608BF3AF65D4694D50847BF553305484668D9C454`
- 新增生产资源 0，删除生产资源 0，Java 生产类修改/新增/删除 0。
- mesh JSON 格式和 7 个分组不变，26,418 → 18,196 个三角形。
- `body` 5,606；`controller` 2,350；`cartridge` 2,242；`slot_cover` 128；`p1_docked` / `p2_docked` 各 2,814；`inserted` 2,242。

## 源码与验证

修改的非生产 Java/工具：`tools/build_sfc_hardware_mesh.py`、`tools/check_sfc_hardware_mesh.py`、`tools/qa/SfcHardwareMeshTestRunner.java`、`src/test/java/cn/piq/sfchome/client/SfcHardwareMeshDataTest.java`。

- `geometry-audit.json`：有限顶点/单位法线/一格边界、低面数、真实材质 RGB 和源纹理 SHA。
- `pose/mesh-pose-audit.json`：真实 Java parser / 原持握矩阵，17 项独立 JUnit 全部通过；28 组 FOV/宽高比/摆动组合；196 条按钮中心射线无手臂遮挡。
- 全部 contract 字段与冻结 alpha5 完全相同：AV 中心、封面平面、插入变换、四按钮锚点。AV 和卡槽关键几何直接比较冻结三角形，不仅比较声明。
- 插入卡带仍避让真实开口；放置模型仍位于已有 AV 保守边界，物品 GUI 沿用原显示变换不裁边。
- 不修改核心、输入、网络、租约、方块碰撞或持握代码；不新增按钮动画；没有运行 Gradle / Minecraft / 原生模拟核心、安装实例或改历史成品。

`hardware-overview.png` 是实际三角形/UV 的离线前后斜视与手柄俯视；`pose/controller-grip.png` 使用实际 Java 持握矩阵，手臂只是原版尺寸的包围体示意。均不是 Minecraft 截图，不代替游戏内光照、字体小尺寸和真实玩家皮肤验收。
