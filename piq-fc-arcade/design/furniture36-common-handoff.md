# 家具公共端交接 — 2026-09-13

维护者：像素匠。

## 范围与公共接口

- 仅新 `cn.piq.fcarcade.furniture` 包、家具新配方/loot、两个语言文件新增键，以及 `FcArcadeMod` 一行 `FurnitureRegistry.register(modBus)`。
- `FurnitureRegistry.BENCHES / STOOLS` 按 `WoodSpecies` 查找；22 个同名 block/item ID 为 `piq_fc_arcade:furniture/<wood>_bench`、`<wood>_stool`。
- 唯一家具 BE `FURNITURE_ENTITY` 支持22块；`SEAT` 是无碰撞、不保存、不可命令召唤的短期单玩家坐骑；独立 `furniture` 创造栏，不改原FC栏目目录。
- `FurnitureBlock.wood()`、`isBench()`、静态 `isAnchor(state)`、`turns(state)` 供独立 renderer 使用。Bench `PART=LEFT/RIGHT`，NORTH 基准第二格沿 EAST；整模型仅 LEFT 渲染。Stool `FOLDED` 加 `FACING`。

## 行为与数据

- 主/副手都为空，普通右键坐下；凳子对应模型约半格高度，马扎保持原模型高度。玩家脚位并非凳面：1.21.1 原版骑乘会减去 Player 的0.6格 VEHICLE attachment，本实体返回凳面作为髋部挂点。
- 长凳2人，优先所点半边；单席已用时尝试另一席。马扎1人；Shift＋双空手右键折叠/展开，有人坐着拒绝折叠。Shift离开座位。拿起模拟器手柄的旧输入逻辑不修改，坐下后可切物品。
- 一对bench两个真实方块、共同新UUID，SavedData `piq_furniture_benches` 管理一次物品掉落实权；同坐标替换的不同UUID不会被旧半凳删掉。玩家拆两格都过当前权限及另一半break事件，另一半未加载时保守拒绝人工拆卸，不强加载；自然破坏仍先关闭代际实权，已加载部分清理，另一半加载后清理无第二个掉落。
- 原ItemStack放置快照事务包含两个方块，捕获快照期间flags=UPDATE_ALL使NeoForge成功后的正常邻居通知保留；第二格失败只回滚本次确切BE/state，不替换异物、不扣额外物品。取消多格放置事件时只删除本次ledger记录，由NeoForge恢复方块和持有物。
- Stool同木种block/item ID不变，folded通过物品 `DataComponents.BLOCK_STATE` 的唯一合法 `folded` bool与loot `minecraft:copy_state` 保存；不接受物品带BE资料、bench part/其他state字段。
- seat由原版 `startRiding` 触发可取消mount事件，创建/骑乘钩子后复查玩家/车辆/BE/state/身份/两端权限；逐tick核对有效结构与权限。死亡/掉线/空位/破坏/卸载均清自建seat，不调用模拟器session，不保存乘员。

## 合成

11种：oak、spruce、birch、jungle、acacia、dark_oak、mangrove、cherry、bamboo、crimson、warped。

- 长凳：同种木板3 + 木棍2 → 1长凳。
- 马扎：蓝色羊毛1 + 铁粒2 + 对应木板1 + 木棍2 → 1马扎。
- 木板用每种具体item，不接受混木；木棍作为通用材料不代表木种；蓝布和铁件不跟木种染色。

## 验证与边界

- 定向新增10个JUnit全部通过；真实Java21/NeoForge21.1.236 `test --tests cn.piq.fcarcade.furniture.* jar --offline` 成功。
- `tools/check_furniture36_common.py --fc <最终jar> --report <新路径>` 只编 QA `tools/qa/FurnitureCommon36Probe.java`，生产类必须来自给定JAR。
- dev-v2：2833断言、实际注册22块/22物品/BE/座位、22实际MC配方、176实际blockstate codec / loot context、22 ItemStack NBT往返、每状态BE UUID NBT与碰撞尺寸。木种严格ingredient拒混木，折叠物品掉落状态保持。
- Probe独立注册环境没有NeoForge item回调，显式调用实际BlockItem.registerBlocks完成Item.BY_BLOCK映射；实际NeoForgeRegistryCallbacks.ItemCallbacks.onAdd亦调用此方法。并非自行mock生产block/item。
- loot上下文没有ServerLevel，使用真实构造器、真实表/函数与固定随机；不包含全局loot modifiers或真实爆炸事件。两格破坏/取消/跨区块/保护/骑乘做ledger和编译调用边界检查，不冒称真实玩家/实际世界回归。
- 实机尚需验收：双人同时坐、保护模组、位置姿态和安全离座、跨chunk拆凳、折叠/存读档、资源包表现。未启动Minecraft、安装/上传/重启/改用户世界。
- 不支持以WorldEdit/结构模板复制内部BE UUID来产生可用长凳；测试版按正常合成/物品放置流程使用。没有向旧模拟器加入任何输入/session钩子。
