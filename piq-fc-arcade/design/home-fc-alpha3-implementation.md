# 家用 FC alpha.3：本地候选交付完成，待游戏实测

2026-09-08。用户提出实体P1/P2手柄、卡带编辑器打开ROM/封面目录、统一分类存储、选择页布局和文字模糊，并追加通用默认卡带封面。随后明确采用“两位玩家各自领取”的手柄方式。仅本地源码和指定分类成品，不安装、上传、发布、重启或关机。

## 分工与边界

- root：新手柄物品注册、协议23/alpha.3、原手柄模型机械拆分和渲染接线、语言、通用标签图、集成验证和最终资源白名单。
- fix_server：Home/ServerArcadeSessions、实体手柄唯一借用、P1/P2审批与转交、清理和纯逻辑测试；四个服务器存储入口调用统一API。
- client_worker：ROM库/卡带菜单自适应居中布局、文件夹按钮、仅FC界面无模糊路径、删除确认/编辑身份回归。
- spectator_settings：piq-fc统一分类路径、安全一次复制迁移、旧数据保留/新路径权威、后台IO与目录消费者测试。

## 手柄约定

用户选择每个玩家领取自己的端口，单玩家不能占两只。P1沿现有插卡/存档选择真正启动后取得Ⅰ柄；P2本人请求并由P1确认后取得Ⅱ柄，失败/手位无空间不先发物或占端口。输入端口继续由服务端成员角色决定，不相信物品NBT自己指定权限。

新增 `fc_controller` 单个不可堆叠物品，元数据区分0/1端口，不提供自由领取控制权限。运行期借用标识绑定主机硬件UUID、会话、端口和唯一持有人；同NBT副本不能授予重复控制权。丢弃/失去有效手持清输入；真正P2转交仍需P1批准；P1离开结束家用本局、不晋升P2，传统街机的晋升不动。正常保存退出优先，强制丢失控制权仍只尽力保存最新已收到快照，不能承诺最后瞬间数据。

模型从已审alpha2完整208元素机械分为body101/P1 dock47/P2 dock60；取走时隐藏相应40或53个柄零件及各7段原线，两收纳底槽保留。手持模型仅均匀放大回原比例、居中和平面方向旋转，原UV不动。原完整模型仍供主机物品显示，不覆写。渲染缓存四个手柄网格，F3+T随baked身份更换。

## 目录与界面约定

统一为实例 `piq-fc/`：roms、covers、skins、saves、scores、cache/covers、cache/skins、shared/covers、shared/skins、config和reports/calibration。世界SavedData和BE NBT仍在世界内；历史suppressed-keys恢复日志保留原config位置，避免迁移影响按键恢复。

只在显式prepare入口一次复制旧FC文件，原文件不删、不搬、不覆盖；新位置同名冲突保留双方并记录，新位置为权威。每类完成标记防删除新存档后旧数据复活，失败不开启空权威库；拒绝链接/越界，跨类别父目录并发按安全创建处理。不在render/getter扫描或迁移。服务端基础目录仍server.getServerDirectory，不改变数据作用域。

ROM库/卡带菜单max760×460逻辑像素，居中、随窗口宽高分栏或上下排版，不修改全局GUI scale；长名称省略+悬浮，分页按列表实际高度。卡带用固定目录按钮，不能由网络传任意路径。自绘无模糊；ModernUI仅在可取得完整公开runtime名单时合并自身FC类，否则不替换他人黑名单、不读写私有字段。

## 默认标签

内置image_gen已生成通用FC/手柄图案，完整原图和prompt在 `design/cartridge-default-alpha3/`。原图SHA `E0B67298F946D1F80830A48D77527B287C29C773E8838A675825847890D1D761`。2026-09-08后续用户明确批准程序后处理；normalize_cartridge_label.py已完成整幅NEAREST512×256及原UV标签/4px边缘组合。新skin SHA `EBD76E0B5A56FC36C3F474CC0E4345377506E6AEA7B2EC14D8FECFECA246CCDA`，区域外RGBA逐像素不变、其他UV岛不相交、有效自定义合成结果不变。原图/旧皮肤备份保留；新标签、完整皮肤、话术和实际原模型预览已复制到指定成品分类的通用卡带封面-alpha3。

## 当前状态

2026-09-08封面落盘后22:34完整 `gradlew.bat clean check --offline` 成功（31秒）：325项JUnit/70套，322成功、3项因Windows符号链接权限跳过、0失败/错误；Windows junction拒绝和跨Area并发测试实际通过。最终52项Python discover+12项家用资源合同共64项通过（含6项新标签处理、8项最终审计器自测）。22:40最终交付JAR直接DLL/WASM smoke成功，独立最终报告errors为空；未运行Minecraft，不当作多人验收。

手柄补查：原版菜单搬槽可能copy/split，已用登记持有人的库存+鼠标栈唯一token重定位，普通换槽不误停；Q和GUI窗口外投掷均验证真实事务，P2拾取有歧义不旋转授权。`ControllerDepartureInputs.clear`供生产和测试共用，家用P2离开仅清P2，传统仍清两端。清理使用原sessionId，不能因AV重绑误关新会话。

最终布局：512×278逻辑窗口的ROM面板450×239，6条20高ROM行、5项详情操作；40条ROM为7页。小窗口320×240保留下方操作，正常/大窗口分栏。21项菜单回归与12组实际几何QA已完成，图片是离线布局、不是Minecraft截图。ModernUI当前本机版本无公开完整runtime名单，因此安全跳过黑名单替换，通过自绘入口避开其背景模糊链。

旧alpha.2 JAR SHA `A928C4FBC984E756CFA939FEA8A60DB6000CCBB73C6AD19DE3B7413148F89D00`、旧最终清单SHA `1D928AB5673508728FDBBCB15BE145A35796982994ED0CDF9ECCFA029A6EF33A` 及默认卡带原图SHA `3F071A850BBD033F4311982B7510EA5C214DE4991B64130DC93E0F5A2616F14C` 复核未变。原始build/libs仍含未发布草稿，不得交付。

## 交付与后续复现

1. 最终候选：指定分类 `piq_fc_arcade-0.31.0-alpha.3.jar`，28,808,034字节/1155条目，SHA `1758B328069F2E68001AE10CF06E04A7704BC4A1C2200308CA3ABA1DC78F22B9`；协议23，双端更新。使用说明和校验清单已交付，无安装上传发布授权，不启停游戏/服务器。
2. 已冻结独立32项 `tools/home-fc-alpha3-final-reviewed-assets.json`，SHA `79546C62BB8D3A0282DF9AF16D870AE190CDA4DFD3D420916B8909EE6011C5F5`。旧清单不改；Package脚本新增明确alpha3文件名/hash/数量分支，43项其他外观必须等固定beta3，仍仅在候选恢复两张旧草稿。原始build/libs不可交付。
3. `tools/verify_home_fc_final_jar.py`独立审核最终JAR，报告 `家用FC-0.31.0-alpha.3-模型预览/final-jar-validation.json` SHA `AFD303418D854846D1E2C299108E13CC9B77BEB324FF8B14265F5A4005712EFE`；检查器只做静态资源/字节码/格式检查，root另执行最终JAR原生加载，二者不要混称。
4. 封面复现用 `tools/normalize_cartridge_label.py --apply`，严格校验原生成图、alpha2JAR/旧皮肤哈希；不覆盖任何独立修改的皮肤，只允许已批准的同一变换。原图、归档PNG、模型不改；6项处理测试和家用资源合同含新hash与区域外逐像素不变式。
5. 后续需Minecraft手测：P1/P2领取和审批、正常整理背包、P2 Q/GUI丢弃转交、P1退出停本机、两台机器互不影响、复制/过期手柄无权、拆机/拔线/重启清理、受保护区域拒绝操作，以及当前整合包文字清晰和目录第一次迁移。自动测试不能代替这些场景。
