# 可组装电脑 prototype.4 固定视角验证

2026-09-26：电脑4配套FC76/PvZ8/Flash0.1.2。48项全过，真实MouseHandler签名、位移累计/边界及原射线/释放测试保留。实际PvZ/Flash适配器在隔离迁移后出图和正常退出，PvZ七点截图可达四角附近；真实Minecraft锁定右键/Mixin与视角手感待验，不标稳定。所有数据是隔离测试，未安装/发布或迁移玩家存档。完整证据和已知长路径限制见 `../outputs/storage76/VERIFICATION.md` 及 FC76当前指南。

## 历史：prototype.3

日期2026-09-26，像素匠（Codex root）。仅电脑附属改动，FC75/PvZ7/Flash0.1.2冻结依赖保持；不安装/发布/重启，不改实际游戏或存档。Minecraft实机仍待验。

- 成品：`game_console_computer-0.1.0-prototype.3.jar`，374943字节，SHA256 `830a3e49b151e39a7447b2bf437776a37e81f0d615500d11b194c3edb9531ff7`。
- 根因：ComputerWorldInput用ArcadeStructure.resolve判断遮挡命中是否属于电视；该接口不解析家用电视代理，故只允许主方块（用户图中右下）区域。不是PvZ负坐标被截断；旧版本真实运行器七点输入探针也可到达四角。
- 修复：新增ComputerScreenRay，通过HomeTvStructure.resolveAnchor仅跳过本电视精确归属的壳体；复用ZapperAimGeometry有界遍历，逐格检查其它真实碰撞体与已加载状态，保持8格限制。不会因先撞到自己电视而漏掉后方的其它障碍。输入、游戏/存档格式、注册资源、协议computer-hardware-2不变。PvZ/Flash/硬件页共用此路径。
- 最终`check jar --rerun-tasks --offline --max-workers=2`通过：42测试、零失败/错误/跳过，源码指纹前后一致。证据`outputs/computer-pointer3/build-final/`。
- 5个新增测试含：主方块豁免旧逻辑的左上失败复现；2/3格LCD×桌面/壁挂×4朝向的784个画面网格采样均可见且UV正确；外来/未获归属壳体、墙壁及未加载区块仍阻挡；忽略自己壳体后继续检查；画外和超过8格拒绝；生产入口调用精确归属校验的接线检查。测试世界为回调提供的电视碰撞场景，不是启动Minecraft，也未将源码接线检查描述为真实NBT世界验收。
- 真实PvZ原生进程：旧电脑2和最终电脑3分别加载相同PvZ7，隔离测试目录、静音，输入中心/四角附近/内侧共7点，图像输出正常并正常退出；核心与鼠标转换未改。不进入用户实例，不使用用户实际存档。帧图见`outputs/computer-pointer3/{before,final-runtime}`。
- 本补丁ZIP仅电脑JAR、文档与对应源代码；已有prototype.2完整包的依赖沿用，不重复包含PvZ/Flash运行器或游戏数据。旧版完整交付保留。
- 待用户确认：真实Minecraft屏幕四角、不同视角、墙壁遮挡、Esc退出，以及Flash的同路径点击。旁观/鼠标交接/DOS没有新增，不标稳定。

## 以下为 prototype.2 历史记录（不是本次新验证）

# 可组装电脑 prototype.2 验证记录

日期：2026-09-26；修改者：像素匠（Codex root）。**本地测试候选；自动测试及独立运行器通过，Minecraft 实机待验。** 没有安装用户实例、发布、部署、重启服务器或改动玩家世界/原存档。

## 成品

- `game_console_computer-0.1.0-prototype.2.jar`，372893字节。
- SHA256：`e6f4632fa71244579143291a00677d7c9b239dea6a8878ef91351123d0c08503`。
- MC1.21.1 / NeoForge21.1.236 / Java21 / 冻结FC75。FC JAR保持 `6F0D0C41F2A922BC108D0CDFC329D4FACA18BC4B287030B9D1CBAC68C6E7305A`，不重建主包或其它附属。
- PvZ可选复用原prototype.7；Flash复用0.1.2原运行器，不安装不兼容FC75的Flash8。协议 `computer-hardware-2`，双方电脑附属同版。
- 交付：`制作Mod/03-街机模拟/方块电玩-可组装电脑-prototype2-20260926/`及同名ZIP。说明见README；原prototype.1保留。

## 本轮实现

机箱及部件60%缩放、单格占位/旧上半部迁移；带电可开侧板但禁止带电拆装；同格黑白键鼠套装；校正显卡HDMI端与LCD HDMI端，CRT沿用AV兼容；静止框架＋通电旋转风扇叶片；取消键鼠全屏GUI、直接瞄准实体电视操作、独占输入及退出松键；管理页使用不产生背景模糊的DeviceScreen；保留硬件测试。

新增通用ProgramBackend、PvZ/Flash本机选择、分开记忆路径、异步目录浏览与启动/退出；切换须先正常结束当前程序。PvZ沿用公开PvzRuntime与原个人存档布局，Flash移植三个IPC类并隔离命名空间、补退出查询/等待。没有改旧播放盒内部实现、核心DLL、原始模型ZIP或贴图。

## 自动检查

最终 `check jar --rerun-tasks --offline --max-workers=2` 退出0，构建前后源码指纹一致；原始日志/XML：`outputs/computer-prototype2/build-final/`。

**37测试，0失败/错误/跳过**：原装配7、租约8、真实MC Codec3、注册/资源5；新增输入捕获5、共享几何4、程序选择/隔离与Flash边界5。

- 全256装配位图、内存槽及拆装依赖；独占票据、过期/重复/限速和旧票据释放保护；真实消息round-trip和截断拒绝。
- 8方块/16物品/16配方注册及模型解析，四向布局、0.825高度、缩放反变换、GPU端点、HDMI16种双向组合有限数值。
- 输入进入须松键；退出只隔离此前按住的键/鼠标；失焦后清除；闲置不扫描全键盘。
- 显式程序选择、后缀过滤、未知配置回退硬件测试、两组Flash按键不串组、原IPC长度/坐标约束。
- 资源审计：90 JSON，派生键鼠所有元素在单格内、模型纹理引用/范围有效；Flash27项运行器文件与JAR内固定SHA一致。实际KeyboardHandler签名匹配两个Mixin目标。见 `audit.json`、`keyboard-signatures.txt`。
- 去掉可选PvZ JAR，仍可加载ComputerPrograms并调用空闲状态接口；这是独立JVM链接检查，不冒充完整FML启动。

过程保留：build-1曾因误用只接受ServerLevel的HomeSystems.connection而编译失败，改客户端用现有HomeHardware连接校验；build-2的32项通过后追加Flash，build-3与最终37项全通过。一次补丁格式错误在应用前被工具拒绝，没有部分写入。没有降低测试门槛或覆盖失败证据。

## 最终JAR独立真实运行验证

证据：`outputs/computer-prototype2/runtime-final/receipt.json`绑定上述最终SHA。运行生产ProgramBackend适配器、真实子进程，隔离game目录；不是模拟返回值，也不是Minecraft窗口测试。

- Flash：已有森林冰火人 `the-forest-temple.swf`（SHA `83c59cd2e3ba63d86f1ddf6dd22bdeefa8d5964985fdd0e92d4c5161d99236e9`），收到520帧、436种帧内容；鼠标进入关卡、发送两组按键/松键；暂停窗口帧哈希保持一致，恢复后继续，退出确认。已查看真实帧图。这里只测工作区定位到的一个旧样本，不称所有Flash游戏可运行。
- PvZ：已有main.pak（SHA `5878326408285cb01f83b4fa4edcc66d65e727f6d6ee88563b5b3b287dd259fc`），收到1228帧、1121种内容；进入主菜单/新用户页，发送坐标/按键/暂停恢复并正常退出。没有做关卡通关、植物操作全覆盖或存档重进持久性验收。暂停期间不发送画面，因此记录pauseStable=false表示未采样到静态心跳，不冒充已验证音画同步。
- 用户样本文件SHA、大小来源与mtime保持；新进度仅在隔离测试目录生成，没有触碰实际实例存档。旧运行器和三个IPC源文件有独立哈希留档。检查完成后无本次helper/host进程残留。
- 未做声音听感/延迟、MC多端、真实网络或长时间性能测试；帧计数不等同Minecraft FPS。Flash依旧有JPEG损耗和系统声音、旧.NET6依赖、仅两组五键/左键、不保存进度等限制。

## 待实机验收与未实现

1. 客户端及专服实际组合加载；世界副本旧机箱迁移、四朝向按钮与线头、模型缩放/内部件/风扇观感，领地取消、拆除/掉落无重复遗失。
2. 键鼠套装连接、独立外设兼容、准星映射与点击位置、MC按键隔离/松键、Esc/失焦/菜单/断线/断线材/区块卸载；真实存档保存与重新进入。
3. 文件浏览与程序切换在实际GUI尺寸下操作；不可读文件、缺系统运行环境、启动中取消及其它模组快捷键兼容。
4. **PvZ游戏旁观、远端鼠标交接、多客户端音画都未接入**。当前别人只看到硬件页。可行性与下一项设计见 `PvZ旁观与交接方案.md`；不把本轮称为Netplay支持。
5. DOS、任意Flash键盘输入、Flash持久存档、跨平台运行器、航空载具适配未完成。公开发行前仍需资产许可、运行时升级及完整Minecraft验收。本版不标稳定。
# 2026-09-26 电脑5串流候选

74项电脑测试＋28项PvZ测试通过，最终源码围栏一致；真实固定Flash/PvZ运行器的JPEG封包/音频旁路/退出探针通过。细节、精确版本、未验收项见 `outputs/computer-stream5/VERIFICATION.md` 与当前 `design/电脑5-串流使用说明.md`。Minecraft真实多人、交接/迟到旁观/光影仍待验，不标稳定。下面保留历史验证。
