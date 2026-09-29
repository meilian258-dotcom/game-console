# Home FC alpha.8：同高双人宽屏、紧凑 SB926、圆滑 AV

2026-09-09，像素匠及 dual_lower / subor_slim / alpha7_audit。协议仍 26；没有注册、NBT、会话或网络载荷变化。

## 双人柜模型与生命周期

`build_dual_arcade_model.py` 从普通单人柜原尺度取形，X 扩展成双宽；模型比例为 1，总高 32 单位。前沿、纵深和操作台高度沿用单人柜；薄顶牌为 16:9 窗腾出垂直空间。屏幕宽 24、倾斜面内高 13.5，不把世界 Y 高误认为物理屏高。

两组操作器以单人柜为源平移，各底座对齐台面，保持与屏下楔形件的间隙；227 元素，碰撞面检查含故意穿模反例。`DualCabinetGeometry`、名字位置、动态屏面、物品居中与渲染器识别统一使用模型尺度常量。

`DualCabinetFootprint` 的实际碰撞与选择范围为 2×1×2；仍保留旧 12 格代理及 2×2×3 放置预留，超出实际体积的代理无实体碰撞。不要擅删结构归属或迁移旧 UUID。单人柜与其它设备不改。

## 屏幕比例

`ScreenAspectFit` 以屏幕面内基向量做 contain，保留完整 UV；`DualScreenPresentation` 缓存 3 比例×4 朝向的 12 份不可变坐标，渲染时不逐帧生成几何。

`LocalArcadePreferences.dualScreenAspect` 默认 FOUR_THREE；`ClientArcadeEvents` 提供 `/fc-client aspect` 及 `4:3`、`1:1`、`16:9` 子命令。显式设置即时生效并安全保留未知属性写盘；未知值回退 4:3。配置沿用 `piq-fc/config/piq-fc-arcade-client.properties`。

仅 `ArcadeBlockScreenRenderer` 的 DUAL 分支使用本地选项；其他玩家、普通机和电视不变。物理玻璃维持纯黑并排除自定义机身皮肤，形成真实黑边。默认是 FC 逻辑显示 4:3，不按 NES 帧缓冲 256×240 推断，也不宣称自动探测 ROM 比例。

## 紧凑小霸王

宽网格 XZ 围绕 (16,16) 缩至 80%，Y 保持 v4 薄底；外壳宽 24.54528 单位（1.53408 格）。完整边界 [3.2,0,6.5]–[28.8,6.3,23.8]。

卡带、槽口、铰链、外置手柄和 RCA 间距保留物理大小并平移，机壳重新挖出真实槽孔。卡带锚点 [16,1.52,22.164]/16、卡带缩放 .60；三 RCA x 为 24.32/22.72/21.12，y1.05、z23.632，均除16。静态手柄线重布；窄版与 PNG 不变，原四格归属保留。

## AV 曲线和有界几何

`HomeAvCableLayout` 三根支线用三次 Bézier，插头处沿轴离开，主线处共享切向。汇合点置于三头中部后方；路线 clearance .16，圆角 trim .34，步进 .05，最多256点。真实线管安全检测会减小圆角，仍不安全则隐藏外观，不取消连接。

同基底主线中心 .027、半径 .023，管底间隙 .004；异高仍保守悬桥，不探测任意世界支撑或障碍。`HomeAvCableMesh` 上限6000四边面；沿用现有缓存，曲线只在网格重建时计算。

## 构建和交付

版本 `0.31.0-alpha.8`；用 `tools/Package-HomeFcCandidate.ps1 -Review alpha8` 固定 beta3 外观基线与 `tools/home-fc-alpha8-final-reviewed-assets.json`。47项中45项保持alpha7字节不变，仅双人body与wide Subor改变；43项受保护旧外观及两张候选恢复贴图继续核验。不要交付 build/libs。

最终 JAR：29,219,717字节/1244条目，SHA256 `1A3C29126F4B1C06887367F9BB884D4A6188E9AEF40718EA78BCA29A8F3212A2`。

完整 clean check：455项JUnit中452通过3权限跳过；192项Python工具通过。最终包实际DLL/WASM启动、独立资源审计和AV网格审计均通过。屏幕三比例QA直接调用生产Java坐标，实际网格离线预览已目视检查，不是Minecraft截图。

本轮 computer-use 技能因 Windows helper deny-read ACL 初始化失败且重连无效，未绕过权限；用户实例只读确认干净退出，未安装、改存档/配置、上传、重启或关机。仍需用户在备份世界实测四朝向、视野、资源重载、连线及P2。
