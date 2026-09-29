# 紧凑原版 GUI 独立离线预览

修改者：sfc_cabinet_provider，2026-09-10。按 root 分配只新增预览工具和本目录，不修改生产、旧预览或任何资源 PNG。

`tools/preview_compact_vanilla_ui.py` 直接编译执行当前 `DeviceLayout`、`CartridgeWorkbenchLayout` 与独立 `tools/qa/CompactVanillaUiLayoutDump.java`。Java 输出实际 Browser 矩形、列表行、primary 和全部 14 个工作台控件矩形，Python 不重复计算这些布局。

原版按钮及其 nine-slice 元数据读取自 Minecraft 1.21.1 客户端 JAR 内 `assets/minecraft/textures/gui/sprites/widget/button{,_disabled,_highlighted}.png`，只读并记录 SHA。灰面板色、边框和按钮文字规则遵循当前 `DeviceUi`；FC/SFC 标签及显示条件来自本轮工作台源码。源码前后 SHA 一致才生成通过报告。

## 查看

- `comparison-320x240.png`：最小支持尺寸，2 行列表。
- `comparison-512x278.png`：常见缩放尺寸，3 行列表。
- `comparison-1024x556.png`：较大视口，4 行列表；面板保持紧凑，不充满页面。
- 每张图依次为 FC 游戏、SFC 游戏、FC 封面、SFC 封面。
- `index.html`：12 张原 GUI 分辨率图片；`geometry.json` 包含实际矩形、控件状态、字体省略结果和所有输入 SHA。

三组实际面板为 280×208、410×225、420×260，居中且所有显示控件无相互重叠。320 宽时目录按钮采用正常省略号，完整名称由生产 hover tooltip 提供；封面详情压缩为一行，清空/恢复和主要行动分开。

限制：这是代码布局预览，不是 Minecraft 截图。字体使用 9px 微软雅黑近似，不能代替 Minecraft 的真实字符宽度、玩家资源包或其他 UI 模组实测。背景与示例文件为诊断用虚构内容；未读取用户 ROM，没有启动游戏、模拟核心或服务器。
