# SFC alpha3 本地目录写卡界面

修改者：sfc_cabinet_provider（Codex 子代理），2026-09-10。主代理负责版本、完整构建、最终验包与维护手册汇总；本记录不是可安装包。

## 用户流程

手持灰卡右键已有写卡电脑，仍由原服务端授权打开。

1. 点击“打开本地 ROM 文件夹”，将自己持有的 `.sfc` / `.smc` 放在当前游戏实例 `piq-sfc-home/roms`。
2. 返回点击“刷新列表”，可按文件名搜索，列表明确区分“本地”和“服务器已同步”，长文件名/路径/字节数可悬停查看。
3. 点击文件行选择，再点“上传并写入所选 ROM”或“写入所选服务器 ROM”。本地操作自动上传并写卡，无须输入路径或再次手动写卡。

与统一 SFC 街机共用这一本机目录。服务器世界内已有 `piq-sfc-home/roms` 是原公共库，来源不同；UI 不将其误说成本地文件。扫描仅一层元数据，不扫描磁盘、子目录或读取 ROM 内容，不复制/删除 ROM。

## 保留边界

- 未改 `SfcHomeNetwork` / `SfcCartridgeEditorService` / `SfcClientFiles` / 原模拟核心、播放或网络玩法，服务端电脑/手位/卡片/权限持续复验沿用。
- 原 `UPLOAD_FINISH` 自带写卡事务，以文件名写入。只有确认同 token 当前界面/连接、同上传 hash、服务端“写入完成”后，才自动用既有 `WRITE` 追加自定义名称；失败明确提示“ROM 已写入；名称未完成”，不是原子双事务。空白或已有相同标题不追加。
- 标题草稿、搜索和稳定选项标识保留到本界面关闭；服务器回复不覆盖用户草稿。初始已有卡名不标记为用户草稿；未亲手改文字时切换 ROM 自动使用新文件名，亲手修改过则跨选项保留。程序同步标题输入框不标脏，当前已写入 ROM 初始选中保留原卡名。GUI 缩放保留分页位置，不重启扫描或上传；查询改变回到第一页，隐藏选项不可误写。
- 320×240 GUI 可用，最少两行；更小空间提示调小 GUI 缩放并允许 Esc 关闭。不调用原版模糊背景。
- 扫描、目录准备、文件读取、ROM 规范化及 SHA256 在 `LocalRomLibrary.submit` 有界后台执行。读取前后拒绝链接/非普通文件；实际内容仍经原 SFC 解析和大小限制。
- 回调在 Minecraft 线程复验 connection / screen / closed / 请求 revision；退出清除上传字节并失效 pending callback，通过原 CANCEL 释放权限。没有协议扩展或重新打开关闭界面的路径。
- 原服务端编辑权限在没有请求的约 60 秒后会失效，不增加周期性全库刷新保活。收到原服务端明确取消/超时消息时提示放好 ROM 后重开电脑并关闭界面，避免无效 token 继续显示可写按钮。

## 生产文件

修改 `client/SfcCardEditorScreen`、`client/cabinet/SfcCabinetProvider`；新增纯 Java `SfcCardLibrary`（Row）、`SfcEditorWork`、`SfcCardEditorLayout`、`SfcUploadTitle`。GUI 新内部类为 Phase、Imported。资源/模型/旧类不改。

## 验证

`tools/check_sfc_card_editor.py --report <新报告路径>` 不运行 Gradle。独立 javac/JUnit 25 项通过，0 跳过、0 失败；最终报告 `sfc-card-editor-alpha3-qa-v3.json`，初次 22 项 v1 和标题修复 v2 留存。

覆盖：同名本地/服务器条目、当前 hash 选项与原名保留、换 ROM 自动名称、手改名称跨选择保留、程序同步不标脏与超长名称、Unicode/大小写搜索、分页/缩放/刷新、移除条目、过期任务/关闭任务、上传成功同 hash 单次标题写入、30 组 GUI 尺寸及 API 使用源码合同。

仍需主代理完整 Minecraft API 编译/全量 check/最终冻结 JAR 审计；本代理没有执行 Minecraft 实机、系统文件夹窗口、远程服务器上传、安装或关机，不把源码合同当作游戏实测。
