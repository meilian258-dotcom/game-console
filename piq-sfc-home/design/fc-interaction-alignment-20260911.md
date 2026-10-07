# SFC 家用交互对齐 FC（本地制作）

日期：2026-09-11。范围：SFC 与 FC 交互对齐。

## 本轮范围

- 空气中右键手柄只提示；普通/Shift 右键绑定主机或其电视（含合法代理格）才归还。
- P1 归还结束整局；P2 归还只释放自己的原端口。投掷/转交手柄仍禁止，本轮不复制 FC 的投掷租约系统。
- 领取前先验证卡带、AV 连接和两端交互权限；不再先发柄、缺线时后台自动开局。FC 的 `FcCartridgeItem.useOn -> HomeHardware.insertCartridge` 在正常插卡后会调用 `startHomeConsole`，本轮 SFC 依照同一规则；插入事务先提交，启动失败不复制或退还第二张卡。
- 主机空手 Shift 仍取卡，持卡 Shift 不插卡；电脑写卡和双人确认窗口不改。
- 个人物品栏、2×2 制作区、光标暂存保留原租约和本局；未手持时只清该端口输入。空手点原主机可从个人库存重新拿起原柄，不生成新物品；光标/制作区需先手动放回库存。
- 持柄支持主/副手，距离采用有效已连接主机/电视较近者 8 格；未连接或其他电视不放宽距离。
- 开 GUI 立即清键，复焦/重新持柄后要求所有按键松开再接管；正常快速按下/松开仍走原事件和序号链。

## 安全边界

`SfcControllerInventory.unique` 按对象身份去重同一槽位的重复视图，但拒绝两个不同对象的同租约副本、错误数量。服务端只在唯一且玩家/租约/物品类型/端口/数量均有效时重新绑定因光标搬动产生的新 ItemStack；外部容器中的同租约物品不算个人持有。

成员有效性、Ready/Leave、旁观来源使用个人保管授权；输入另走 `SfcControllerAuthority.input`。非手持包仍消耗原序号和有界心跳，但强制零掩码并清队列，不能通过背包里的手柄注入动作。非法掩码/非法 force-release 不会被“修正”为合法心跳。

领取前后的保护回调会重新检查当前 State、会话不存在、原租约、插卡快照、ROM、BE/AV 身份和权限，防止同步回调期间变更后覆盖新会话。归还按实际点击格、电视代理锚点、两端权限、距离和手中物品对象逐次复验，同 tick useOn/use 保留去重。

## 修改清单

旧生产类：

- `server/SfcHomeServer`（及 `Lease` 的可重新绑定 stack 字段）；
- `server/SfcControllerAuthority`（新增不可变 `Input` 返回值与纯输入授权函数）；
- `item/SfcControllerItem`；
- `world/SfcHomeConsoleBlock`（只 `useItemOn`，形状/模型方法不变）；
- `client/SfcHomeClient`。

新增纯生产类：`server/SfcControllerInventory`、`client/SfcInputFocus`。

未改本轮资源、模型、版本、网络包、SfcJoinClient、安全同步 gate、SfcPlayback、模拟核心、帧算法、写卡或存档流程。FC M 静音/别名键以及 SFC 服务端存档能力差异未在本轮扩展；现有 SFC 本地恢复备份不能称为 FC 同等级“保存继续”。

## 验证

新增 21 项测试：10 库存/授权/4096 掩码/双端口与心跳，4 焦点重臂/快速边沿，7 源码接线/权限回调重验。旧 startup/input/join 源码合同按新交互语义更新，继续保护角色、精确租约、输入序号和原核心。

验证为实际 Java 编译、纯领域测试及源码接线合同，不是 Minecraft 真人联机/保护插件/实体手柄实机验收。完整 check 与最终成品仍须分别验证。
