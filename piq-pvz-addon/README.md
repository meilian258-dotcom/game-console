# 方块电玩 PvZ 测试附属

用于验证植物大战僵尸程序如何接入方块电玩的显示、输入和运行器。可以通过[电脑附属](../piq-computer/README.md)运行，也保留一个本机测试播放盒。

**目前是开发验证机型，暂不按正式机型推进完整功能。** 玩 FC、SFC、MD 或街机不需要安装它。模组不含 `main.pak`、原游戏素材或游戏授权。

`0.1.0-prototype.12 public-r1` 是公开发行资料修订，只补充来源、许可和配套核心源码，运行代码、模型与固定原生组件不变。JAR 总哈希与原 prototype.12 不同，但模组版本仍为 prototype.12；同一实例只安装一个修订。通过电脑启动时搭配 **Computer prototype.12**，不要混用旧电脑原型。

## 安装条件

- Minecraft 1.21.1、Java 21、NeoForge 21.1.229 及以上的 21.1 系列，以及配套的方块电玩主包。
- 多人服务器和参与客户端都装 `game-console-pvz-版本.jar`；只有要用电脑启动 PvZ 时才另装电脑附属。单人世界装在客户端实例即可。
- 本机执行目前支持 Windows x64，需要可用的 OpenGL 2.1 兼容上下文。服务器只管理设备和授权，不运行 PvZ 原生核心。
- 使用配套候选包内的固定运行组件，不必另装 RetroArch，也不接受用户或服务器指定任意 DLL。游戏数据由使用者合法自备。

两端安装用于共同的播放盒方块、物品、实体和授权消息，不代表服务器要保存或分发你的游戏文件。安装总则见[玩家指南](../piq-fc-arcade/docs/玩家指南.md)。

## 两个入口怎么选

**电脑程序入口**：装好电脑、电视和键鼠，在“本机程序”选择 PvZ，指定本机 `main.pak`，再右键键鼠操作。电脑提供可选的共享音画和轮流交接键鼠；详见[电脑操作说明](../piq-computer/README.md)。这不是 PvZ 双人游戏或 Netplay。

**测试播放盒**：仅创造模式、具有 OP 权限的玩家使用。放置“PvZ 测试播放盒”，用视频线连接已开机电视，双手空着右键盒子，指定 `main.pak` 后开始。在预览里用鼠标操作；“返回看电视”只关闭操作界面，要结束请点“结束并保存”并等待完成。盒子不会把游戏音画共享给其他玩家。

文件选择必须指向真正的 `main.pak`，不是压缩包、文件夹或 DLL；其旁边的 `properties` 按游戏数据配套保留。不要把素材放进 `mods`。

## 运行与存档

- 电脑入口在受支持平台默认使用公共 JNI，并保留显式的独立进程选择；旧测试播放盒仍走独立进程。JNI 原生故障可能让 Minecraft 一起退出。
- PvZ 使用游戏自身的文件保存机制，**不提供即时状态存档或 Netplay**。不同菜单／关卡能否继续由游戏决定，不能把“进程停止”直接当作保存成功。
- 独立进程档在客户端 `game-console/piq-pvz/saves/`；公共 JNI 档在 `game-console/piq-pvz/jni-common-v1-saves/`。按玩家和游戏资源身份隔离，不自动覆盖或合并旧档。
- 正常结束后再备份保存目录。突然退出可能丢失最近进度；同一档案不可由两个运行器同时写入。
- 日志位于客户端 `game-console/piq-pvz/logs/`。路径过长、显卡能力不足或组件校验失败，应按具体报错处理，不随意替换核心。

电脑串流、测试播放盒、JNI 与独立进程的能力不同。已有独立核心测试不代表 Minecraft 多人、输入延迟、所有显卡或光影环境已通过验收。

## 获取上游源码

`vendor/PvZ-Portable` 是固定提交的 Git 子模块，来源为 [KLuoNuoYa/PvZ-Portable 的 libretro 分支](https://github.com/KLuoNuoYa/PvZ-Portable/tree/libretro)。在本仓库根目录执行：

```powershell
git submodule update --init --checkout -- piq-pvz-addon/vendor/PvZ-Portable
```

父仓库保存的是具体提交，不自动跟随上游最新版。GitHub 父仓库源码 ZIP 不包含子模块内容；不要用 `--remote` 代替固定版本恢复，也不要强制覆盖自己的修改。

子模块接入没有自动更新当前交付 DLL；不能把上游最新能力当作这个候选已经支持。来源、升级与核对规则见[子模块说明](design/PvZ源码子模块.md)。

公开实验修订另提供 `game-console-pvz-core-source-prototype12-public-r1.zip`，包含与原核心同一来源归档保存的移植源树、构建脚本、libopenmpt 源码和许可，不含游戏数据。该源树与上述较新的开发子模块不同，详见[第三方来源](THIRD_PARTY.md)。

## 开发与许可

先读[统一构建说明](../source-control/BUILDING.md)与[公共 JNI 接入记录](../piq-fc-arcade/design/通用JNI一期-FC76.22-使用与附属接入.md)。当前 JNI 实现使用主包的公共桥；旧专用桥是历史实现，不作为新附属模板。

适配代码与第三方核心分别遵守各自许可证，见[THIRD_PARTY.md](THIRD_PARTY.md)。公开源码不提供原游戏素材的分发授权，也不表示现有 DLL 已完成独立可重现构建证明。

[历史 README](README-history.md)保留旧候选、按键和验证记录；更多导航见[文档索引](../piq-fc-arcade/docs/README.md)。
