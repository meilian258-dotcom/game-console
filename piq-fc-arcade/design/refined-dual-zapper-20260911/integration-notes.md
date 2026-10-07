# 新版双人柜与光枪几何准备

仅本地源码/模型/离线验证，不安装、不启动 Minecraft、不更改版本或联机输入。

## 源与视觉替换

`original.zip` 保留共享原件，SHA256 `8E4B502BA203892D7580D9B3A0DB4308737C70AA7543905AD42AFB8DA62C3FC7`。
仅展开双人柜、光枪和合集说明/校验文件；附件 tools 内脚本没有执行。GBA 仍在原 ZIP 内，本次不接入。

新版双人柜仍为 24×32×17.6 模型单位（1.5×2×1.1 方块），模型 X 加 4、Y 不偏移，旋转中心仍为旧 anchor 的 (0.5,0,0.5)。239 元素中 117 主体、122 动画元素；16 个动画分件与上一版逐字节相同。仅 `models/block/user_dual/body.json` 与 `textures/block/user_dual/skin.png` 改变。原 12 个 part/UUID/占地/机柜连线/网络/碰撞代码不变。

屏幕源 UUID `808ee99f-a604-41e7-928d-ff7286a7d1fd`，北面从 [3,14.55,4.6] 到 [21,28.05,4.6]；绕 [12,14,4.8] 的 X 轴 22.5°，不 rescale。四比三玻璃由 16×12 增至 18×13.5，长宽增加 12.5%、面积增加 26.5625%；UV 区域仍 [9.375,2.1875,14.625,6.125]。渲染面沿法线外推 .0015 方块。默认新 PNG 原字节保留；旧 skinHash 仍不套用新 UV，未改皮肤存储。

新工具 `tools/import_refined_dual_model.py` 独立固定新源 SHA，`--apply` 仅接受已知旧派生资源或本版资源，`--check` 逐字节复验；alpha22 历史工具/报告不改。`tools/check_refined_dual_model.py` 直接编译实际纯生产类、执行41项测试，并比较1424面原几何/UV、四向bounds/屏幕/动画pivot与物品GUI实际投影；输出明确标为非游戏截图的离线预览。

## 实际屏幕与射线 API

`ScreenSurfaceGeometry.frame(style, quarterTurns, width, height, centered, dualAspect)` 返回 `Surface(image, translation)`：image 是实际完整游戏 UV 的四角，不是整个玻璃。宽屏 LCD 先按现有 4:3 presentation 留黑边；双人柜使用既有本机比例；大 CRT 的 centered 仅提供单独 ±0.5 平移。

`ArcadeBlockScreenRenderer.drawFace` 统一绘制 image；两个家用入口仅用共同 `translation` 一次，保留旧 PoseStack 平移再发 float 顶点的运算顺序。测试逐 float bits 检查旧平面四向全部顶点/UV/法线，旧斜面复用实际原quad。光枪使用同一个 Surface，不另写电视尺寸公式。

`ScreenRayMapping.hit(surface, anchorLocalEye, direction, maxDistance)` 返回 `Optional<Pixel>`；默认 NES 256×240。眼位先减实际锚点，helper 内仅减 centered translation 一次；direction 不必单位化。UV 原点沿现有绘制为 upperMaxX=(0,0)、upperMinX=(1,0)，不是按世界 X 轴猜左右。拒绝黑边外、背面、近平行、负距离、超距与无效输入。Pixel 只是瞄准坐标，不能当作已命中鸭子或亮度传感器结果。

后续真正光枪功能仍需：授权光枪租约/当前连接与设备身份、遮挡射线校验、输入协议和确定性帧记录、NES $4017 光感/扳机实现及回放/快照兼容；本工具不从 Minecraft 世界光照判断命中，不绕过旧控制权。

## 光枪静态分件准备（未注册）

`raw-zapper-parts` 仅是中间资产，不是已安装资源包。`tools/prepare_zapper_model_parts.py` 固定4源SHA，不执行附件脚本，输出 body130/cable51/connector18/trigger2/stand85，共286元素，原顶点/面/UV/旋转、2048 PNG保持。

枪口沿原坐标 -X；所有坐标仍为原展示模型单位，没有自动缩放、回正或手持矩阵。握姿实现需独立选 gun-only body+trigger，不能把支架/盘线一起拿在手中。桌面展示才组合全部部件。

扳机组 `850c61a5-210d-4747-a4f1-5b85881e94c8`，pivot [9.52327672,4.03390858,8.204]，两个元素，源建议绕本地 Z 轴 +8°/0.07s；不可沿旧光枪 Y 轴。支架为 display_stand 全子树，细线 cable、接头 connector 单独保留，可分别隐藏/接运行时线。原 `zapper_stand` 纹理命名保留于中间JSON，正式接入时需显式注册本模组资源路径，不能直接把中间目录当完整Mod交付。

没有物品注册、手持姿势、模拟核心、世界结构或联机协议变更；完整项目构建/最终包统一执行，离线验证不能替代真人游戏验收。
