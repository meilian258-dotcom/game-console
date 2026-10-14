# PIQ 快速回放兼容补丁

`0.1.0-test.3` 是客户端测试候选，处理部分录像因缺失 Sable 位置标记而自动退出快速模式的问题，并保留此前的玩家位置和 NeoForge 握手修复。适用 Minecraft 1.21.1、Java 21、NeoForge 21.1.250、ReForgedPlay 0.3、Sable 2.0.5。

## 安装

1. 完全退出 Minecraft，备份现有补丁。
2. 把旧 `piq-replay-quickmode-compat-…-test.1.jar` 或 `test.2.jar` 移出 `mods`，保留在别处。
3. 将本包的 `piq-replay-quickmode-compat-1.21.1-0.1.0-test.3.jar` 放进客户端 `mods`，不要同时保留两个版本。
4. 重启后打开原来的录像，测试快速模式中的玩家显示和 B 列表跟随。

原 ReForgedPlay、Sable 不替换。**保留与当前主模组配套的方块电玩屏幕/声音补丁 `game-console-replay-compat-….jar`**，它与本补丁不是同一个模组。服务端无需安装本补丁；现有录像可直接测试，不要求重新录制。

卸载 test.3、恢复备份的旧版并重启即可撤回；不要删除录像或回放缓存。本补丁只读取原录像，不改写其内容和快速索引缓存。

## 本版修复什么

Sable 为位置数据增加了标记，但快速模式重建的玩家瞬移包不包含该标记，可能出现玩家仍在 B 列表中、却停在旧位置，跟随时跳到错误位置的情况。

本补丁优先从原录像读取标记，在快速模式发送合成玩家位置包时补齐。标记按目标时间、换世界和实体生命周期查找，不从未来帧借值，不沿用已销毁实体的状态。普通回放、真实服务器连接和非玩家实体包不做这项改写。

部分经过处理的录像保留了移动坐标，却没有位置标记。test.2 会直接退回普通模式；test.3 会进一步核验接收该位置包的当前世界与实际 Sable 空间边界：只有目标明确在所有子世界之外、且两种标记在已核验处理器中执行结果相同时，才补齐这一包。不会把缺失的历史标记改成已知，也不会硬编码默认空间范围。

载具空间内、世界或接收连接不一致、边界无法确认、数据格式不支持时，仍会提示并返回普通回放、保持暂停；可以继续普通播放。日志区分位置空间无法确认、包格式不支持与损坏的标识编码。未核验的 Sable 实现会拒绝启用本项快速适配。

## 范围与验证

这是玩家位置兼容修复，**不是完整的 Sable 载具/子世界快速回放支持**。快速模式遗漏的载具跟踪和姿态数据、非玩家实体扩展、DH、光影以及视频导出不在本次修复范围内。需要完整载具效果时仍使用普通回放。

自动验证与待测项目见 [TESTING.md](TESTING.md)。Minecraft 中的当前世界绑定、图形显示、B 跟随及多次 Q 切换仍需实机验收，不能将隔离测试候选视为稳定版。

## 构建

准备 Python 3.11 或更新版本、Java 21、NeoForge 21.1.250 客户端依赖库及 ReForgedPlay 0.3 JAR。在本目录运行：

```text
python build.py --java-home <JDK目录> --libraries <启动器libraries目录> --replay <ReForgedPlay.jar>
```

`libraries` 需包含 `net/neoforged/neoforge/21.1.250` 下的 client/universal JAR、唯一的 Minecraft 1.21.1 映射客户端 `client-1.21.1-*-srg.jar`，以及客户端所需依赖。可以使用已准备好的开发/启动器依赖目录；本脚本不下载或生成这些外部输入，不要求私有录像、历史报告或方块电玩构建缓存。

只读依赖，成品输出至本目录 `build/libs`；构建与测试中间文件也位于 `build`，请将源码检出到有足够空间的工作盘。源码采用 [MIT](LICENSE) 许可；不内含 Minecraft、Sable、ReForgedPlay 或模拟器核心。不随本仓库七件快照自动发行，也不自动安装到游戏。

构建后可运行以下检查；`tools/run-tests.py` 需提供原版 Sable 2.0.5 JAR。测试工具只在本项目输出目录生成文件，不启动游戏：

```text
python verify.py --java-home <JDK目录>
python tools/run-transform.py --java-home <JDK目录>
python tools/run-tests.py --java-home <JDK目录> --sable <Sable.jar>
```
