# 方块电玩 / Game Console 命名与交付规范

2026-09-29，维护者：像素匠（Codex root）。本轮用户要求：对外采用正式英文名，保留兼容标识，并将 GitHub 仓库改为 `game-console`。

## 名称分层

| 范围 | 规则 |
| --- | --- |
| 中文展示 | 方块电玩；附属加 SFC、街机、GBA、电脑等名称 |
| 英文展示 | Game Console；附属用 Game Console: SFC / Arcade / GBA / MD / Computer / PvZ / Flash Box / Java ME |
| 玩家交付 JAR | `game-console-<版本>.jar`；附属 `game-console-<机型>-<版本>.jar`，小写、连字符 |
| 源码和兼容标识 | 保留 `piq-*` 源码目录、`piq_*` 模组/资源/网络 ID、`cn.piq.*` 包名、原配置/存档路径 |
| 历史、署名、许可 | 保留原交付文件与历史记录，作者 PIQ / meilian 及上游署名不当作品牌删除 |

正式英文名为 Game Console，不再以早期临时名称 Block Arcade 作为当前品牌。GitHub 仓库现在为 [meilian258-dotcom/game-console](https://github.com/meilian258-dotcom/game-console)；旧地址仅是历史。其他克隆用 `git remote set-url origin https://github.com/meilian258-dotcom/game-console.git` 更新，不需要重新克隆或删除本地修改。

## 为什么保留 build/libs 的内部文件名

目前附属仍固定引用 FC76.22、FC76.24 等历史编译输入；部分打包器也固定版本和 SHA。直接改 `archivesName` 或全局替换 PIQ 会破坏这些依赖。**内部构建和面向玩家的交付文件名分离**：构建器继续产生原内部名称，交付阶段使用下面的统一工具，不更名、不覆盖原件。此决定不表示全附属构建依赖已标准化。

新机型必须在 `release_artifacts.py` 的明确映射中登记，不能从任意 JAR 文件名猜机型。现有映射覆盖主包、完整 SFC、街机、GBA、MD、电脑、PvZ、Flash 和 Java ME。SFC 单独 home/core 薄包默认拒绝，只有开发者显式 `--allow-development` 才能输出带 `-dev` 的名称；内部平台 JAR 不提供玩家交付入口。

## 统一交付入口

先按组件现有流程完成构建、素材筛选、配套验证和许可检查，再从仓库根运行（Python 3.11+）：

```powershell
python source-control/release_artifacts.py --jar "实际已审查的成品.jar" --output "outputs/本次命名候选"
```

`--jar` 可重复，但一次只放已确认可配套的文件；本工具**不验证依赖版本是否配套**。它根据包内 `neoforge.mods.toml` 的模组 ID 和实际版本命名，不更改 JAR 内容，生成含原文件名、正式文件名、版本、ID 和 SHA-256 的 `release-manifest.json`。输出目录必须不存在，防止覆盖历史。缺元数据、未知/重复 ID、危险版本字符、重复 ZIP 成员均拒绝。

只有重命名副本且 SHA 完全相同，不能写成重新构建或新版本；检查工具通过不是稳定性或发行许可认证。依赖、独立运行库、对应源码与许可证仍要按原安装说明配齐，**不能只把改名后的 JAR 当作完整发行包**。不要把旧名和新名的同一模组一起装入 mods。

## 本轮范围与验收

- R01：仓库首页和组件入口统一 Game Console；历史版本段落不批量改写。检查标题、相对链接和遗留可见品牌。
- R02：统一交付命名工具与真实 JAR 字节不变验证；不修改原成品、内部 ID、保存、协议、核心或游戏逻辑。
- R03：遗留 Java ME 当前展示名/资源说明去掉 PIQ 品牌；保留作者、ID、翻译键及占位符。源码修改需下一次构建才进入游戏，不冒充已更新玩家安装包。
- R04：GitHub 同一仓库更名后核对仓库 ID、私有性、默认分支及 origin；不公开、不邀请成员、不发布游戏版本。

本轮不批量重建/安装全部附属，不改测试服、客户端或管理系统实现。工作台保留人工验收为未验收；实际验证与提交信息登记在维护机记录，不以本文代替运行验收。
