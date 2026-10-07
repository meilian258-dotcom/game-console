# GBA 掌机可视接线（2026-09-12）

范围：GBA 可视组件，不修改 FC／SFC／Native。

## 已实现

- `GbaHandheldLayout`：独立纯几何，保持原模型比例；屏面 `+Y`、顶部 `-Z`，第一/第三人称仅以 `Rx90` 归正，不镜像。屏幕为 3.6 × 2.4 原模型单位，对应 240 × 160。
- `GbaHandheldRenderer`：原版模型烘焙缓存、9 分件、第一人称双手；另一手有物品时只绘持握手、不取消另一件物品。第三人称复用 FC 公开 `ControllerArmPoseParameters`，不修改 SFC 姿势。事件和 ClientExtensions 均由客户端订阅类注册。
- 动态画面只在第一人称、`GbaHandheldClient.visualMatches(actualStack)` 与 `running()` 同时成立时显示；相同物品的副手/远端/背包/掉落不会复制玩家的游戏。真实屏面满亮、无 CRT 过滤；失去本地匹配则回原图。客户端会话实现负责纹理最近邻过滤及生命周期。
- 按键采用 GBA/libretro 位：B0、Select2、Start3、上下左右4–7、A8、L10、R11。只改外观位移/倾斜，不执行输入或核心。
- 中英各 3 条掌机物品/用法/本机限制文本。

## 原件与资源

原 ZIP 保持 SHA256 `8E4B502BA203892D7580D9B3A0DB4308737C70AA7543905AD42AFB8DA62C3FC7`。
原 2048 × 2048 PNG 保持 SHA256 `376FB935DEB9D6F5F4682A24FC4DF94D5EF9A5793D14B4255F573FE6FF921BCC`。
735 元素、4370 面、451 元素旋转全部恰好分配一次；body 533、screen 1、dpad 5、A/B 各 16、Select/Start 各 9、L/R 各 73。元素几何、合法旋转、UV 不改，只改纹理命名空间与分组文件。

资源为 `assets/piq_gba/models/item/handheld.json`、`models/item/handheld/{body,screen,dpad,button_a,button_b,button_select,button_start,shoulder_l,shoulder_r}.json`、`textures/item/handheld.png` 和 `lang/{zh_cn,en_us}.json`，共 13 个新文件。前 11 项精确 SHA 与安全提取原件见 `import-audit.json`；语言交付由根源围栏记录。

## 验证与冻结

- 两个可视生产源已在真实 FC33 + MC/NeoForge API 下 javac 成功；仅旧式事件总线弃用警告，无 Minecraft、GL 窗口或原生核心启动。
- `qa-v2/report.json`：纯生产布局/按键/四角视锥 5,104 断言；实际 Minecraft `BlockModel` 解析原资源 22,598 断言。通过不等于已真人进游戏测试。
- 已实际目视 `model-reference-v2.png` 与 `qa-v2/poses.png`；后者使用实际 Java 布局，手臂是原版宽臂尺寸的简化着色预览，不是 Minecraft 截图。
- 最终 JAR 复验命令：`python tools/check_gba_handheld_visual.py --jar <最终GBA3.jar> --output <不存在的新报告目录>`。该模式只编探针，不编生产类；逐字节核对原件导出资源，并绑定 JAR SHA。不会运行 ROM 或启动游戏。
- 新工具为 `tools/import_gba_handheld.py`、`tools/check_gba_handheld_visual.py`，探针为 `tools/qa/GbaHandheldLayoutProbe.java`、`GbaHandheldModelProbe.java`。原历史工具与预览未覆盖。

当前范围已完成，等待根最终 JAR 复验。未注册手持观众流、掌机通讯线、原生多人；远端只看静态模型。
