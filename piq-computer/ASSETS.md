# 模型来源

prototype.6：`tools/wireless6_assets.py` 对黑白组合键鼠模型做右手布局，原单件模型/PNG保留；USB接收器复用主机atlas的USB金属/暗色UV，生成代码原生立方体，不新增生成贴图。移除四个单件合成入口但保留旧ID，直接套装配方与旧物品转换并存。

本测试版模型和贴图来自 meilian 本轮提供的两个模型套件：

- 黑白可组装主机_模型套件.zip
- 经典黑白键鼠_模型套件.zip

原始模型不执行任何代码。`tools/import_models.py` 导入 Java 模型/贴图并生成命名空间、物品和资源注册；保留原始元素坐标与 UV，黑白外观分别使用套件贴图。主机内部零件采用装配坐标，物品采用套件独立居中坐标。

prototype.2：渲染机箱及内部件统一缩放为60%，生成同格键鼠派生模型（原贴图/UV不变）；静态模型剔除风扇面，运行时绘制转动叶片和固定框架。HDMI插头/线材由代码生成。未改两个原始ZIP或原贴图。

Flash 接口代码来自本工作区 piq-flash-box 的 FlashRuntime、FlashProtocol、FrameImageDecoder（GPL-3.0-or-later）；构建时隔离为 cn.piq.computer.flash，资源清单独立命名，并补只读进程结束状态/等待接口。原项目文件不改。对应原始源码、生成规则和冻结 SHA 清单随 source 包提供。配套 Flash0.1.2 运行器原样复用，Ruffle（固定nightly-2026-09-16）的 MIT/Apache 与 WebView2 许可见运行器 vendor/及 LICENSE-WebView2.txt；不因本代码GPL而改授第三方许可。

模型用于本地功能验证，不将代码 GPL 许可自动套用到这些资产；公开发行前由提供者确定资产许可。本包不含游戏、BIOS、商业操作系统或 PvZ 游戏资源。
