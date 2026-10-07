# 方块电玩开发协作

这份指南面向参与源码开发的人。玩家安装和使用请看[玩家指南](piq-fc-arcade/docs/玩家指南.md)，准备编译请看[构建指南](source-control/BUILDING.md)。

源码采用一个公开 Git 仓库：[meilian258-dotcom/game-console](https://github.com/meilian258-dotcom/game-console)，默认分支为 `main`。任何人都能阅读或克隆；写入仍需权限，也可以通过 Pull Request 提交贡献。公开源码不等于已经发布稳定版或全部素材可任意再分发。

## 获取代码

```powershell
git clone https://github.com/meilian258-dotcom/game-console.git
cd game-console
git submodule update --init --checkout -- piq-pvz-addon/vendor/PvZ-Portable
python source-control/setup.py
git status --short
```

使用 Git 和 Python 3.11 或更新版本。setup 为当前仓库配置检查，不修改全局设置；使用你自己的 Git 姓名和邮箱，不借用维护者署名。提交中的姓名、邮箱和提交内容会公开。

PvZ 子模块来源是 `KLuoNuoYa/PvZ-Portable` 的 `libretro` 分支，但日常获取的是父仓库记录的固定提交。不要用 `--remote` 悄悄升级。GitHub 源码 ZIP 不包含子模块完整代码，详见[子模块说明](piq-pvz-addon/design/PvZ源码子模块.md)。

## 先读哪些资料

- [协作规范](piq-fc-arcade/AGENTS.md)：组件边界、安全、验证与交付要求。
- [制作规范](piq-fc-arcade/design/机器制作与交互标准.md)：新设备应有的玩家体验。
- [实际流程和接口](piq-fc-arcade/design/全组件运行流程与复用接口总览.md)：现有代码入口及未完成部分。
- 目标组件自己的 README、构建脚本和源码。

以上仓库内资料是公开协作入口；无需额外的私人工作区资料。

## 日常修改流程

1. 查看 `git status --short`、当前分支和已有改动，确认哪些属于本任务。不要自动清理别人的未提交文件。
2. 从合适基线创建任务分支，例如 `codex/docs-player-guide`；多人不要在同一目录互相切换分支。
3. 列清改动目标和不改范围，先用现有公共接口。只改文档就不需要重建全部模组。
4. 逐项暂存本任务文件，审核 `git diff` 和 `git diff --cached`，不要盲目 `git add .`。
5. 运行源码检查及与改动相关的测试，再提交。
6. 推送自己的任务分支并提交 Pull Request，说明影响、配套版本和未验证项。不要因为编译成功就写“已稳定”。

常用检查：

```powershell
python -B -m unittest discover -s source-control -p "test_*.py" -v
python source-control/audit.py --staged
git diff --check
git diff --cached --stat
```

`--staged` 检查的是实际索引。工作树扫描可能包含未跟踪资料；不能把这些扫描结果当作“应该上传全部文件”。检查不通过要定位原因，不使用 `--no-verify` 绕过。

## 提交范围与凭据

源码、测试、构建声明、设计说明及获准公开的素材可以进入 Git。运行库缓存、DLL/WASM 等构建输入、ROM/BIOS、玩家保存、世界、日志、服务凭据和私钥不提交；已登记的引导工具与子模块按现有检查规则处理。

根白名单只覆盖方块电玩及其共用工具。新增组件需同步审核白名单与检查器，不能为方便而扩大到无关目录。

登录交给 Git Credential Manager 或 GitHub 的标准工具，不把令牌写进 URL、脚本或说明。自动扫描能减少误提交，但不能保证绝无秘密或代替素材许可审查。

## 构建和发布不是同一步

`main` 保存开发源码，不等同于稳定发行。构建依赖、固定输入和附属顺序见[构建指南](source-control/BUILDING.md)；产物命名见[交付规范](source-control/BRANDING.md)。

更改源码、推送 Git、合并 PR、发布安装包、部署服务器是不同动作。只获准其中一项时，不自动执行其他项。回退已提交代码优先使用新的 `git revert` 提交，不自动强推、重写历史或删除旧成品。

## 备份与历史

GitHub 只保存已提交内容。迁移开发电脑还要单独保管合法的固定输入、必要工具链和本地资料；迁移游戏实例则另需备份世界、`game-console` 和配置。

早期源码导入和构建迁移的技术记录见[Git 工作流历史](source-control/GIT_WORKFLOW-history.md)，当前操作以本指南为准。
