# 方块电玩源码与 Git 工作流

适用日期：2026-09-29。仓库维护者 Meilian。本文件是源码入口，不是游戏发行说明。

## 现在用什么管理

方块电玩采用一个标准 Git 单仓库，默认分支 `main`。原工作区中的 11 个组件保持同级，不搬目录：主包 `piq-fc-arcade`、公共层 `piq-retro-platform`、`piq-sfc-home`、`piq-sfc-arcade`、`piq-native-arcade`、`piq-gba`、`piq-j2me-arcade`、`piq-computer`、`piq-flash-box`、`piq-pvz-addon`、`piq-md-home`。

首个提交是真实的当前源码基线，**不是稳定版，也不保证等于任何已发布 JAR**。原来没有有效 Git 历史，因此不从旧 ZIP 伪造提交或版本标签。旧文件、成品、历史证据及素材草稿保留在原位置，今后的源码修改以提交及 diff 为准，不以“哪个备份目录较新”判断。新建其他组件必须先审核并更新根忽略白名单和 `source-control/audit.py`。

本次只建本地仓库，没有 GitHub/Gitee 远端，没有上传或推送，也没有改服务器、客户端及模拟器功能。Git 提交署名使用用户确认的 `Meilian <meilian258@gmail.com>`，仅仓库级配置；以后公开推送会连同提交中的这个邮箱公开。

## 获取源码与启用检查

在仓库根运行（Git、Python 3.11+）：

```powershell
git status --short
python source-control/setup.py --name "你的署名" --email "你的提交邮箱"
python -B -m unittest discover -s source-control -p "test_*.py" -v
python source-control/audit.py
```

新克隆不会自动启用 Git hooks，必须执行 setup。它只配置当前仓库的 hooks/Python/显示及换行策略；不改全局配置，有其他 hooks 时会停止。已有署名可省略两个参数，不能借用他人署名。它不会下载或执行模拟器。

日常可用 `git clone <经维护者确认的仓库地址>` 获取代码；当前没有远端地址。原维护机仓库在 `G:\服务器\服务器Codex`。工作区专用的根 AGENTS/维护手册、服务凭据和其他项目没有纳入这个仓库；独立克隆以本文件、`piq-fc-arcade/AGENTS.md` 及各组件规范为入口，不需要复制维护机私有数据。

## 一次修改的标准流程

1. 先读共同 AGENTS、组件 README 和行为规范，检查 `git status`、当前分支与工作台编号。别人的未提交修改不是可清理的缓存；同一目录多人工作不得随意切换分支，需各自克隆或经确认使用独立工作树。
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

**源码已纳入 Git，不等于从干净克隆即可完整打包所有 MOD。** 当前构建脚本有历史依赖，迁移时没有改写它们：

- 主包读取 `native/libretro` 的 JNA/Mesen、`native/libretro-jni/dist` 的桥 DLL，以及根 `outputs/jni-unified-20260929/crc-audit/fixed-native/piq-retroarch.exe`。
- 多个附属依赖主包 `build/libs` 的 FC76.22；Flash 仍有历史 FC61 路径；MD 默认 FC76.24，可通过 `-PgameConsoleJar=` 指定。SFC 家用还依赖 SFC core9 的历史 JAR。
- 街机还使用旧 `piq-runtime-pack-v1.zip` 与固定 FBNeo DLL；GBA、Flash、PvZ 和各原生构建器另有自己的运行库/工具链。第三方 vendored 源码和许可保留，商业游戏内容不入库。

这些路径不能因被忽略就随意删除。`source-control/external-artifacts.json` 是已核对的关键输入文件名/大小/SHA清单，不是下载器，也不是全部平台依赖已闭合的证明。缺项应按组件说明重新构建或由维护者提供合法、固定哈希的制品，禁止找一个同名 DLL/JAR 冒充。

后续另做构建标准化：统一依赖声明/固定工具链与原生核心来源、减少历史 outputs 输入、完成干净克隆 CI；不要为接管 Git 顺手改变游戏实现或旧存档规则。制作候选时记录源码 commit、工作区是否干净、所有外部输入及成品 SHA，只有完整回归和用户验收后才标稳定；版本发布、安装或重启仍需授权。

## 本次基线与恢复

迁移证据在维护机 `outputs/git-migration-20260929/`，交付后包含审查报告、提交/独立克隆校验和 Git bundle。bundle 可使用 `git bundle verify <文件>` 检查，然后 `git clone <文件> <新的空目录>` 恢复已提交源码。它不包含忽略的制品和用户数据；同盘 bundle 也不是异地备份。

本次不生成新 MOD，不提升稳定标记。此前报告的 FC JNI Netplay 精确恢复启动失败，以及新 FC 默认能力值检查问题，未在本次源码迁移修复；Git基线也不能覆盖这些待处理反馈。历史README中的测试/候选结论只对其注明版本及环境有效。
