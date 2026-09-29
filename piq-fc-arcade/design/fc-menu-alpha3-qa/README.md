# FC alpha.3 菜单布局与状态回归 QA

修改者：client_worker；日期：2026-09-08。仅本地界面、纯 Java 布局/helper 和定向测试，未运行或安装 Minecraft。

## 布局证据与边界

PNG/JPEG 由 `src/test/java/cn/piq/fcarcade/client/FcMenuLayoutQa.java` 直接调用实际 `FcMenuLayout`、`libraryActions` 和 `columns` 生成。每张图片保留其逻辑窗口像素尺寸；文字为 AWT 占位文字，不是 Minecraft 字体、实际翻译或游戏截图。此处仅检查实际面板、行、分栏、按钮几何位置和留白，不能代替 Minecraft 中的文字/交互验收。

- 面板目标约窗口宽 88%、高 86%，上限 760×460，下限 296×216；320×240 使用 12 像素边距。小于 320×240 显示明确缩窗提示和关闭按钮，不修改玩家 GUI scale。
- 512×278：面板 450×239、ROM 库紧凑左右栏，列表 242 像素宽/141 高可显示 6 条游戏（20 高行、24 步长、末行不额外留底间距），右侧 176 宽五操作按钮 18 高/20 步长，仍为正常字号；40 个游戏为 7 页而非初稿的 40 页。卡带页保持原方案（ROM 3 行/封面 2 行），没有因本次 ROM 库修订而变动。
- 640×360：ROM 库面板 563×312，保证五操作详情列需要的 192 像素高度，显示 8 条游戏；卡带 ROM 页 563×309 左右分栏，封面页按可用高度退为紧凑布局。1024×556：面板上限 760×460，ROM 库 12 行。320×240 仍保留下方操作区。宽而矮的 ROM 库使用紧凑侧栏，不溢出也不退化到单行。
- 长名称在按钮/正文上按实际 Minecraft 字体宽度裁切，悬浮显示完整名称；QA 图字体不同，因此不用于断言实际文字容量。
- ROM 选择、改名、存档/人数设置和删除确认沿用原服务端权限流程。新增文件夹按钮仅调用固定 `ClientFcDirectories` API，不从包或服务端读取任意路径。

## ModernUI 本机静态核验

只读核验文件：`客户端/versions/[PIQ]你好,新蒸程v1.7.1/mods/[现代化 UI] ModernUI-NeoForge-1.21.1-3.13.0.1-universal.jar`。

使用本机 JDK 21 的 `javap -p -c` 查看 `icyllis.modernui.mc.BlurHandler`、`icyllis.modernui.mc.mixin.MixinScreen`、`icyllis.modernui.mc.mixin.MixinGameRenderer`，另以 `javap -p -v` 查看混入注解：

- 运行时 `mBlacklist` 是 private volatile ArrayList，没有完整公开 getter/field；`loadBlacklist(List)` 在字节码偏移 125 替换整份名单，不是追加。
- `MixinScreen` 重定向 `renderTransparentBackground(GuiGraphics)` 内的 `fillGradient` 到 `BlurHandler.drawScreenBackground`，后者在偏移 199 调用 PostChain.process。
- `MixinGameRenderer.onProcessBlurEffect` 转发到 `BlurHandler.processBlurEffect`，后者在偏移 75 调用 PostChain.process。
- 这两张 FC 菜单已不调用 renderBackground / renderTransparentBackground / renderBlurredBackground，只用 fill 先绘局部底板再绘文字。这绕开了上述菜单背景触发入口；没有更改全局 blur 设置或其他模组按键。
- `CartridgeScreenCompat` 仅在可完整读取公开运行时名单时合并配置和运行时条目，并加入四个自有 FC 菜单类。当前所查版本无法读取，因此 warn 一次并放弃调用 loadBlacklist，绝不反射私有字段、覆盖其他 mod 的运行时名单或写配置磁盘。不能宣称该版本已成功追加黑名单。
- 以上为源代码与当前 JAR 字节码证据，不是已游戏实测；仍需装入候选后检查当前整合包 UI 字体/兼容效果。

## 21 项标准 JUnit 回归

`FcMenuLayoutTest` 的 21 个方法均有标准 `@Test`，常规 Gradle check 会自动发现；main 只是多代理开发期间的独立断言运行器，不替代 JUnit 注册。

本轮仅用本机 javac/java 和已有 JUnit 5.13.4 API、platform-commons、opentest4j、apiguardian 编译运行：FcMenuLayout.java、FcMenuState.java、FcMenuLayoutTest.java。最后一次实际结果为 `Passed 21 FC menu geometry/source-contract checks (standalone assertion runner).` 未由本代理运行 Gradle。

覆盖：72 种宽高 × 3 种布局的行/面板/详情/动作边界，指定截图尺寸比例留白，512×278 确保六行及完整五操作/40 条七页，中等窗口分栏，多尺寸两排底部入口不与正文相交，最小窗口两文件夹按钮与翻页，列表为空/变短，连续 resize 按 ROM SHA/封面文件名锚定，独立标签页锚点，32 项有界关闭 token 拒表，迟到旧 OPEN 不重开、同卡新 token 正常接受，主副手槽/UUID/alive 校验，后台 revision/connection/屏幕身份拒绝迟到覆盖，刷新 OPEN 不取消在途上传，ModernUI 公有名单保留/私有名单拒绝覆盖，以及渲染与固定路径源契约。

生成命令（已完成定向编译后）：

```powershell
javac -encoding UTF-8 -cp build/ui-layout-directed-tests -d build/ui-layout-directed-tests src/test/java/cn/piq/fcarcade/client/FcMenuLayoutQa.java
java -Djava.awt.headless=true -cp build/ui-layout-directed-tests cn.piq.fcarcade.client.FcMenuLayoutQa design/fc-menu-alpha3-qa
```

12 张原生尺寸 PNG、对应轻量 JPEG 与 `geometry.tsv` 可重复从相同实际 Layout 生成。它们仅为本轮自行生成的 QA 输出，不覆盖任何用户模型、贴图或旧版成品。
