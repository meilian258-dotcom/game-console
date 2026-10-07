# Git 工作流历史记录

以下记录早期版本的技术状态与构建边界，命令和版本仅用于历史追溯。当前入口请看[现行说明](../GIT_WORKFLOW.md)。

# 方块电玩源码与 Git 工作流

适用日期：2026-09-29。仓库维护者 Meilian。本文件是源码入口，不是游戏发行说明。

## 现在用什么管理

方块电玩采用一个标准 Git 单仓库，默认分支 `main`。原工作区中的 11 个组件保持同级，不搬目录：主包 `piq-fc-arcade`、公共层 `piq-retro-platform`、`piq-sfc-home`、`piq-sfc-arcade`、`piq-native-arcade`、`piq-gba`、`piq-j2me-arcade`、`piq-computer`、`piq-flash-box`、`piq-pvz-addon`、`piq-md-home`。

首个提交是真实的当前源码基线，**不是稳定版，也不保证等于任何已发布 JAR**。原来没有有效 Git 历史，因此不从旧 ZIP 伪造提交或版本标签。旧文件、成品、历史证据及素材草稿保留在原位置，今后的源码修改以提交及 diff 为准，不以“哪个备份目录较新”判断。新建其他组件必须先审核并更新根忽略白名单和 `source-control/audit.py`。

2026-09-29，仓库接入 GitHub，当时为私有仓库；同日从 `block-arcade` 更名为 `game-console`，提交历史保留。此处记录历史状态，当前仓库入口与访问方式见[现行工作流](../GIT_WORKFLOW.md)。

每位贡献者使用自己的 Git 提交署名，可选用 GitHub 提供的隐私邮箱；不要借用他人身份或为修改署名擅自改写历史。

## 获取源码与启用检查

在仓库根运行（Git、Python 3.11+）：

```powershell
git status --short
python source-control/setup.py --name "你的署名" --email "你的提交邮箱"
python -B -m unittest discover -s source-control -p "test_*.py" -v
python source-control/audit.py
```

新克隆不会自动启用 Git hooks，必须执行 setup。它只配置当前仓库的 hooks/Python/显示及换行策略；不改全局配置，有其他 hooks 时会停止。已有署名可省略两个参数，不能借用他人署名。它不会下载或执行模拟器。

源码入口为 [game-console](https://github.com/meilian258-dotcom/game-console)。独立克隆以 `piq-fc-arcade/AGENTS.md` 和各组件规范为入口，不需要任何维护机路径、凭据或其他项目数据。

已克隆旧地址的协作者可执行 `git remote set-url origin https://github.com/meilian258-dotcom/game-console.git`。分享源码需所有者在 Settings → Collaborators → Add people 邀请并由对方接受；个人私有仓库的协作者拥有读写权限，不是只读分享。参考 [GitHub 权限说明](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/repository-access-and-collaboration/permission-levels-for-a-personal-account-repository)。对外正式名与交付文件名见[命名规范](../source-control/BRANDING.md)，内部兼容 ID 不改。

### PvZ 上游源码子模块

自 2026-10-01 起，`piq-pvz-addon/vendor/PvZ-Portable` 通过 Git submodule 管理；来源为 `https://github.com/KLuoNuoYa/PvZ-Portable.git` / `libretro`。父仓库保存固定 gitlink 提交，不在父仓库重复保存上游全量代码。

克隆或拉取含此变更的父仓库提交后，在仓库根执行：

```powershell
git submodule update --init --checkout -- piq-pvz-addon/vendor/PvZ-Portable
git submodule status -- piq-pvz-addon/vendor/PvZ-Portable
```

只取父仓库 ZIP、运行 `git archive` 或只备份父库 bundle，不会包含子模块的代码对象；完整离线恢复还须单独保留对应子模块提交。`branch = libretro` 不代表日常使用 `--remote` 自动升级。更新上游需另外审查差异、许可和构建/运行影响，再提交新的 gitlink；未提交的子库修改不能靠父库提交保存。见 [PvZ 核对及恢复说明](../piq-pvz-addon/design/PvZ源码子模块.md)。

源码检查只允许已登记的这一处子模块及精确来源/分支；`.gitmodules` 的未知字段、额外来源和冲突索引会报错。`--staged` 校验实际索引的 `.gitmodules` 和 gitlink；工作树检查另核实已初始化子库的提交及脏改动，未初始化会明确标注。**父库检查不递归认证第三方子库的全部内容**，报告将其与父库普通文件分开列出；新 pin 仍须人工审查，不执行上游脚本来“验证安全”。其余原有文件/秘密/二进制检查保留。

## 私有远端与备份边界

- 首次推送只包含经过检查的 `main` 及其祖先提交，不使用 `--all`、`--mirror` 或 `--force`；旧本地任务分支保留，不自动推送其他引用。
- 凭据交给 Git Credential Manager 等标准凭据管理器。设备登录由用户在 GitHub 官方页面确认；不把密码、令牌或一次性验证码写进脚本、remote URL、文档或提交。
- 每次推送前检查 `git remote -v`、工作区、待推提交与文件范围；首次接入核实仓库属于正确账号且为私有，推送后比对远端分支提交。不要以“网页上能打开”代替私有性和提交校验。
- 独立克隆/对象及文件校验用于验证源码恢复。源码仓库**不包含**被忽略的运行库缓存、ROM/BIOS、玩家存档、世界、服务器凭据或其他未纳入版本管理的项目资料；这些需按访问权限单独备份。
- 本阶段未配置 GitHub Actions、强制 PR/分支保护或自动发布。后续功能分支可按评审流程合并，但不能把流程建议写成已经启用的服务端强制规则。
- 当前附属构建仍有历史 JAR/工具链依赖。源码仓库可恢复源码，不等于任意新电脑已经能完整构建全部附属。对外分发还需核对各组件许可、第三方资源来源与隐私边界。

## 一次修改的标准流程

1. 先读共同 AGENTS、组件 README 和行为规范，检查 `git status`、当前分支与变更范围。别人的未提交修改不是可清理的缓存；同一目录多人工作不得随意切换分支，需各自克隆或经确认使用独立工作树。
2. 干净工作区从 `main` 创建短分支，例如 `git switch -c fix/GC-123-fc-input`；已有工作区有改动时先核对归属，不强制切换、覆盖或自动 stash。
3. 写明确需求/不改范围/验收清单，按现有脚本构建测试。公共接口变化需检查依赖附属；只改文档也要校验链接、JSON 等。
4. 只暂存本次负责的具体路径，例如 `git add piq-fc-arcade/src/main/java/某文件.java`。用 `git diff`、`git diff --cached --stat`、`git diff --cached` 逐项审查，不盲目提交整个工作区。
5. 执行 `python source-control/audit.py --staged`，提交例如 `git commit -m "fix(fc): correct input sampling (GC-123)"`。hook 检查的是实际暂存内容；遇拦截查明原因，不用 `--no-verify` 或强制纳入文件绕过。
6. 在工作台/维护记录写提交号、版本、测试证据及待验项。审核和相应验证后合入 `main`，再制作候选；有远端时走 PR/评审。不要把 main、通过编译或最大版本号等同于稳定发行。

查询使用 `git log --oneline --decorate -15`、`git show <提交号>`、`git diff main...HEAD`。需要撤销已提交修改时，评估依赖和未提交内容后用新的 `git revert` 提交保留历史；不要自动 `reset --hard`、`clean -fdx`、强推或删除旧制品。大型变更拆成可审查、能说明验证范围的提交。

## 哪些内容进入 Git

纳入源码、测试、脚本、模型/贴图、许可证、构建声明和设计说明。忽略构建输出/缓存、原生运行库二进制、ROM/BIOS、存档/世界/玩家数据、日志、私密凭据及游戏测试截图。忽略不是删除，文件仍在磁盘。Gradle wrapper JAR 是明确的引导工具例外。

根 `.gitattributes` 保持首批文件原始字节，不在接管时批量转换 LF/CRLF；今后如统一换行，单独提交，不夹带功能改动。pre-commit 检查白名单、文件类型/魔数、10 MiB 上限和常见密钥字面量；它是防误提交措施，不是“绝无秘密”的安全证明。公开开源前还需完整的隐私、商用素材/模型授权和第三方许可证复核。

## 构建边界与外部依赖

**源码已纳入 Git，不等于从干净克隆即可完整打包所有 MOD。** 首次 Git 接管未改构建；随后 FC 构建一期已完成，入口为 [固定输入及构建说明](../source-control/BUILDING.md)：

- FC 主包的 JNA/Mesen、JNI 桥、RetroArch、三个 WASM 和 JNI Netplay 核心，现按 `source-control/build-inputs.json` 从显式离线缓存读取，校验大小/SHA；不再回退旧 `outputs`、默认 JNI dist 或原资源目录的未提交二进制。必须先导入合法固定输入，不是自动从公网下载核心。
- 多个附属依赖主包 `build/libs` 的 FC76.22；Flash 仍有历史 FC61 路径；MD alpha.2 默认 FC76.27（共享内容卡服务），可通过 `-PgameConsoleJar=` 指定兼容包。SFC 家用还依赖 SFC core9 的历史 JAR。
- 街机还使用旧 `piq-runtime-pack-v1.zip` 与固定 FBNeo DLL；GBA、Flash、PvZ 和各原生构建器另有自己的运行库/工具链。第三方 vendored 源码和许可保留，商业游戏内容不入库。

旧路径中的文件仍保留，不能因被忽略就随意删除。`source-control/external-artifacts.json` 是首次接管时的历史盘点，FC 当前构建锁为 `build-inputs.json`；两者都不是全部平台依赖已闭合的证明。缺项应按组件说明重新构建或由维护者提供合法、固定哈希的制品，禁止找一个同名 DLL/JAR 冒充。

后续继续其他附属的源码依赖、原生工具链与对应来源/许可、空缓存 CI；本期 FC 独立源码目录加显式固定缓存已完成 `check jar`，仍使用本机既有 Maven/NeoForge 缓存，不是所有核心从零重编。不要借构建整理改变游戏实现或旧存档规则。制作候选时记录源码 commit、工作区是否干净、所有外部输入及成品 SHA，只有完整回归和用户验收后才标稳定；版本发布、安装或重启仍需授权。

## 本次基线与恢复

可使用 `git bundle verify <文件>` 检查源码备份，再用 `git clone <文件> <新的空目录>` 恢复已提交源码。bundle 不包含忽略的制品与玩家数据；同盘 bundle 也不是异地备份。

首次接管不生成新 MOD。构建一期生成的 FC76.24 同版本开发验证 JAR 作为独立开发制品保留，不替换原交付，不提升稳定标记。此前报告的 FC JNI Netplay 精确恢复启动失败，以及新 FC 默认能力值检查问题，未在源码/构建迁移修复；Git 基线也不能覆盖这些待处理反馈。历史 README 中的测试/候选结论只对其注明版本及环境有效。

实际克隆编译发现首次基线 `665b12c` 的 `**/world/` 忽略规则误排了 Java `world` 包。本期收窄为组件根运行世界目录，原样补纳 64 个既有 Java 源文件并增加回归测试；没有删除或重写历史。首版 bundle 保留作历史证据，完整恢复应使用包含此次补齐提交的新 bundle。首次源码哈希比对通过只证明当时选中文件一致，不证明源码清单完整。
