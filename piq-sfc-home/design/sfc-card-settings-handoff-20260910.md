# SFC 灰卡写卡设置交接 · 2026-09-10

修改者：`/root/fix_sfc_av`。本记录交由 root 合并进入 `Codex维护手册.md`，避免并行覆盖维护手册。

## 功能与约定

- 写卡屏幕保留 ROM 固定目录、列表、搜索、分页；新增 ROM / 封面列表切换、独立保存游戏名称、单人 / 双人人数切换并保存。
- 本地封面目录固定为游戏目录下 `piq-sfc-home/covers`，只列直接子文件 `.png`，由主模组公开的 `LocalRomLibrary` 后台安全扫描；不遍历其他目录、不联网、不自动带入任何商业游戏或封面。
- 选择 PNG 后，后台再次验证路径并限量读取，调用 FC 公开 `CartridgeCoverCodec.prepare` 规范为 512×256 不透明 PNG；服务端再次完整校验摘要、格式、尺寸、透明度后存储并引用。
- 源 PNG 最多 8 MiB / 2048×2048；上传封面最多 2 MiB，复用写卡上传预算和令牌；服务端 PNG 内容寻址存储在世界目录 `piq-sfc-home/covers`，复用 FC `CartridgeCoverRepository` 的 256 张 / 128 MiB 配额，并补父目录 reparse / junction 拒绝。
- 清空封面只清空引用。恢复封面恢复**本次打开电脑时**服务端捕获的 `originalCover`，不信任客户端自报原始摘要；不改 ROM、名称、人数，也不删除 PNG 文件。
- 手持、GUI 和插入主机的封面均通过现有卡带标签面绘制。卡壳网格、显示变换、所有模型 JSON、位图、核心资产零改动；512×256 标签在原凹框内按 2:1 居中。新增模型包装保留原 display 变换且仅应用一次。
- 卡带 `getName` 显示保存的游戏名称，tooltip 显示人数或旧卡兼容提示。

## 元数据与协议

现有 `piq_sfc_home_cartridge` 新增可选 `Cover`（64 位小写 SHA-256）和 `MaxPlayers`（NBT int，严格 1 / 2）。`Rom` / `Title` / `Id` 不改。旧卡仍可读取。旧 `write(stack, rom, title)` 保持原封面与人数是否显式设置；新 `write(stack, rom, title, cover, players)` 显式持久化人数。所有验证均在一次性更新 CustomData 前完成。

公开接口：

- `SfcCartridgeData.maxPlayers(ItemStack)`：仅 1 / 2；无人数元数据仍返回容量 2。
- `SfcCartridgeData.hasExplicitPlayerCount(ItemStack)`：区分旧卡与明确双人设置。**只有显式 2 人才应等待 P2。** 旧卡不能仅因为 `maxPlayers == 2` 就阻塞单人启动。
- 新空白卡第一次真正写入 ROM 时默认持久化单人；打开旧卡或仅改名 / 换封面不静默覆盖旧卡人数。

`SfcHomeNetwork` registrar 从 `1` 升为 `2`，要求客户端 / 服务端一起更新。`Editor` 回包新增 `coverSha / maxPlayers / explicitPlayers`；保留旧 Java 构造重载。`EditorAction` 原字段保持，新增操作 6–10；封面上传复用有类型标志的已有分片状态。新增 `CoverRequest / CoverChunk`。Session / Ready / Input / Frames / Leave / Stopped / RomRequest / RomChunk 的字段和编码不变。

封面客户端 hook 用纯 `Consumer<CoverChunk>` 注册，公共网络类不引用 client 类型；独立 client-only 初始化，不修改 `SfcHomeClient`。

## 权限与资源边界

写卡继续要求 OP2、同服务端 / 维度、已加载且同 UUID 的电脑、距离、世界边界、保护事件、同手同槽同物品引用、唯一卡带 UUID 和原组件快照。上传完成异步回调再次验证；取消、移走卡带或授权失效只丢弃提交，不消费 / 替换旧卡。所有同步设置动作有独立参数检查和异常收口。

封面下载仅允许当前玩家物品栏中的卡、32 格内玩家手中卡，或请求坐标处 32 格内已加载 SFC 主机内实际插入的卡；每次发送分片重新检查，禁止按任意摘要枚举库。最多 4 个并行封面下载、每个 ≤2 MiB、有速率 / 超时 / 离线清理；不加载区块。客户端最多 16 张 GPU 纹理、32 排队、128 重试记录、单个在途分片，退出连接销毁纹理和取消旧回调。

## 精确生产变更清单

修改：

- `data/SfcCartridgeData.java`
- `net/SfcHomeNetwork.java`（新增 `CoverRequest / CoverChunk` 嵌套 record）
- `server/SfcCartridgeEditorService.java`（`Edit` 增加原封面和上传类型）
- `client/SfcCardEditorScreen.java`
- `client/SfcCardEditorLayout.java`
- `client/SfcHardwareRenderer.java`
- `item/SfcCartridgeItem.java`

新增：

- `server/SfcCoverStore.java`
- `server/SfcCoverService.java`（`State / Transfer`）
- `client/SfcCartridgeCovers.java`
- `client/SfcCoverGeometry.java`（`Face`）
- `client/SfcCartridgeRenderer.java`（`ItemModel / 匿名 IClientItemExtensions`）

不修改 FC 生产源码、旧 SFC 核心、SFC 会话三文件、已有游戏 ROM、世界、已装 JAR。版本 / 构建 / 安装由 root 统一负责。资源文件零改动。

## 验证

- root 已确认 `compileJava` 和整套 `check / jar` 成功；后续增加两条标签几何 JUnit 测试后由 root 再执行最终 check。
- `tools/check_sfc_card_metadata.py --report <新报告路径>`：独立 javac 编译 12 个实际生产源码对接真实 Minecraft / NeoForge API，不运行 Gradle。真实 ItemStack / NBT 保存重载、元数据原子拒绝、五种新操作 codec、Editor / 封面 codec、PNG 校验与安全存储、标签几何、模型包装变换共 **66 项通过**。报告：`design/sfc-card-metadata-real-20260910.json`。
- `tools/check_sfc_card_editor.py --report <新报告路径>`：现有 **25 项通过**，包含真实草稿 / 列表 / 搜索 / 分页 / 最小布局 / 异步回调取消状态，界面接线部分属于源码契约检查。报告：`design/sfc-card-editor-settings-20260910.json`。
- 原工具报告中的 `unchanged_boundary_sha256` 是该工具旧字段名，只是当前源码摘要快照，**本轮 Network / EditorService 已获授权修改，不代表相对上一版本未变**；最终严格差异审计由独立验包代理负责。
- 新增 `SfcCoverGeometryTest` 两条 JUnit 测试覆盖 item 标签凹框、2:1 和插入模型精确变换。
- 最终 alpha4 JAR 直接复验：工具增加 `--jar` 模式，只编译测试探针、不编译生产源码；**66 项再次通过**。报告 `design/sfc-card-metadata-jar4-20260910.json`；受测包 SHA-256 `EA95B35F946F8576FEDFB668DEACA1A7FC86F8111A015F0C962E7B1C80A74E4F`。
- 应独立验包代理要求补充 `piq-fc-arcade/tools/probes/Alpha17SfcCoverProbe.java`，单独验证最终 JAR 的代码来源及两种标签实际几何，由总验包器调用；不修改 FC 生产代码。

## 未宣称完成的验证

本代理未启动 Minecraft、未安装包、未做 GUI 截图 / 真实网络上传 / 保护插件现场 / 游戏内插卡封面观感验证。实际文件夹打开与完整手持 / 主机视觉仍需最终客户端实机确认，不能用纯 Java 探针替代。包中不附游戏与封面。
