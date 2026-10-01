# PvZ 上游源码子模块：核对与维护

日期：2026-10-01。维护者：像素匠（Codex）。范围：源码依赖管理，不是 PvZ 功能升级或新发行。

## 1. 需求、范围与依据

用户确认：将 `piq-pvz-addon/vendor/PvZ-Portable` 改成 Git submodule，来源为 `KLuoNuoYa/PvZ-Portable` 的 `libretro` 分支，先核对并保留现有补丁。

沿用 Git 原生子模块，不自建源码下载器。阅读 [Git submodule 官方说明](https://git-scm.com/docs/git-submodule)，核对父库 `build.gradle`、原生构建器及历史打包脚本，并只读检查上游固定提交的许可、文件树、根 README 与 libretro 说明中的内容/保存/构建范围。本轮不是制作新机器，不改 JNI、游戏交互或公共 API。

| 项目 | 固定值或边界 |
| --- | --- |
| 父库基线 | `f7418ba0b7444f48c84b261b49e731e40715f9aa` |
| 子模块路径 | `piq-pvz-addon/vendor/PvZ-Portable` |
| 上游 URL | `https://github.com/KLuoNuoYa/PvZ-Portable.git` |
| 来源分支 | `libretro` |
| 首次固定提交 | `6a3cbeee46679eaae2859d25a98207182388e149` |
| 本轮不改 | Java/原生运行代码、固定 DLL/host/JNI 二进制、协议、配置、存档、成品与安装要求 |
| 未授权操作 | 推送、发布、通知群聊、安装、部署、重启 |

父库 GitHub 链接中的 `fix/md-cartridge-power` 是用户指向的历史位置。本次不自动切换共享工作区分支或覆盖别人的改动；实际在原工作分支完成本地变更。

## 2. 原目录与补丁核对

迁移前父库只跟踪以下三个文件；工作区该目录也仅这三个文件，没有隐藏的源码补丁或额外游戏文件。对比固定上游提交：

| 文件（相对子模块根） | 旧/新字节数 | 结果 |
| --- | --- | --- |
| `LICENSE` | 7652 / 7652 | 逐字节相同 |
| `src/SexyAppFramework/LICENSE` | 2130 / 2130 | 逐字节相同 |
| `src/SexyAppFramework/platform/libretro/libretro.h` | 322233 / 313828 | 仅 8405 处 CRLF → LF，规范化后逐字相同 |

旧头文件 SHA256：`bf272d81ce94e604751203fc70bfa9c7564bb3cf42776049669a55a284171df4`；上游头文件 SHA256：`5875414c47d8af4facf118c184b40b0e311285333a46bbfe8221a853efe5ba7a`。没有需重放的功能补丁，不人为制造空补丁；原件仍可从父库旧提交恢复，维护机另外保留完整目录备份。

`tools/build_native.py` / `tools/build_jni.py` 仍从同一路径包含 `libretro.h`，没有改 include、重跑工具或改固定二进制摘要。`build.gradle` 不编译整个上游仓库。历史 `package_local.py` / `package_compat3.py` 的旧源码包仍只是本附属加头文件，不追溯覆盖旧交付。

固定上游树无嵌套 `.gitmodules` / gitlink / 符号链接；本次不引入递归子模块下载。没有 `main.pak` 或核心 DLL 入父库。上游原树自带平台图标和框架示例，其中有一个 SDL-Mixer-X VB6 示例 `.7z`；不把第三方源码树整体复制进 JAR，也不将父库审计通过解释为已全面审查这些资料。

## 3. 获取、升级与恢复

在父仓库根执行以下命令，取得**父仓库记录的提交**：

```powershell
git submodule update --init --checkout -- piq-pvz-addon/vendor/PvZ-Portable
git submodule status -- piq-pvz-addon/vendor/PvZ-Portable
git -C piq-pvz-addon/vendor/PvZ-Portable rev-parse HEAD
git -C piq-pvz-addon/vendor/PvZ-Portable status --short
```

正常结果：status 前无 `-`（未初始化）、`+`（不是父库记录的提交）或 `U`（冲突）；HEAD 等于父库 gitlink，子库工作树干净。网络失败保留现有目录和修改，不用强制选项覆盖。

- `branch = libretro` 记录升级来源，不自动跟最新版本；不要把 `update --remote` 写进日常构建。
- 要升级时先单独检查该分支变更和许可，明确是否重建/换核心及是否涉及存档；通过相关验证后再更新父库 gitlink。新增功能应参考上游现成能力，但不能因更新源码就宣布 DLL 功能更新。
- 需要本地补丁时，将补丁保存为父库可审查文件，或使用已授权且协作者可访问的固定上游/分叉提交；不能仅在子库留下脏修改，然后声称父库已保存补丁。
- GitHub 父库 ZIP、`git archive`、父库 bundle 不自动携带子库内容。离线备份需另保留该提交的子库对象与许可；不得捆入玩家游戏资源或保存文件。
- 回退父库版本前检查子库修改并备份。旧父库版本仍将这三个文件作为普通文件跟踪，切换形式时不能用 `reset --hard` / `clean` 粗暴清目录。

## 4. 审计与验证边界

根 `.gitmodules` + gitlink 是权威依赖登记。源码 guard 仅接受上述批准路径/URL/分支，区分普通 blob 和 `160000` gitlink；拒绝未知子模块、未合并阶段、配置注入或路径类型替换。索引审计不依赖父库持有子库 commit 对象；工作树检查已初始化子库 HEAD 与脏状态，未初始化时标明状态但不自动联网。

父库 guard 的二进制、秘密、大小和路径检查仍用于父库普通文件；子库作为外部依赖单列，不假报其所有源码已递归审查。实际运行上游代码、构建核心、Minecraft 游戏验证均不属于本次验证。

本轮核验项目：原件备份与三文件差异、gitlink/上游 pin、子库干净状态、现有原生二进制哈希不变、从独立父库索引快照初始化同一子模块、源码 guard 回归、暂存范围与 hook。具体运行结果由维护记录记载，不能用文档中的清单代替执行结果。

本次无新 JAR，不把旧成品重新标为本次构建，不改管理台的稳定/人工验收标记。当前交付 DLL 仍是 [THIRD_PARTY.md](../THIRD_PARTY.md) 中原始固定版本，**未证明该 DLL 与此新 pin 可重复构建一致**；上游最新即时状态、关闭流程等改进尚不能视为已集成。
