# Mesen JNI Netplay r2 对应源码

仅供 FC76.25 的 JNI Netplay 试验；普通 FC 核心和 RetroArch 核心没有替换。

- 上游 https://github.com/libretro/Mesen ，提交 `0102910c39ad1a62bc3f784466f3f67ca9eae335`。
- 原源码ZIP SHA256 `375a885c397685a5aad82bd14f68e1b30d8e78405471344629a6f2a04c8a9e04`；交付的源码材料中保留原ZIP与本目录。
- 保留 r1 改动：`Core/ControlManager.cpp` 的 `Serialize` 将 `_isLagging` 追加进状态。原版遗漏该标志，会在恢复后的下一帧改变 lag counter，造成真实状态CRC差异。
- r2 修复 `Core/VRC7.h`：`InitMapper` 初始化控制寄存器后同步 PRG RAM 权限，避免首次恢复把 32 个 CPU 页的权限从读写改成无访问。
- r2 修复 `Core/BaseMapper.cpp`：恢复 PRG/CHR 映射时，有有效偏移但暂时 `NoAccess` 的页仍恢复原银行/类型/权限，不能当成未映射页去删除描述信息。禁用权限仍保留，未给游戏开放 RAM；MMC3 是实际触发案例。
- 没有屏蔽CRC、清零状态字节、跳过精确恢复检查或通过额外预跑帧隐藏问题。
- DLL `mesen_piq_jni_netplay_r2.dll` SHA256 `591976547fa49a29ad3c20acecd96ef46eed0a7376398295a9468cfaae55c05c`，3,606,016字节。LLVM-MinGW 20250910 UCRT x86_64 / clang21.1.1，105个上游编译单元，-O2/LIBRETRO/NDEBUG/C++11，静态链接运行库。
- GPL-3.0-or-later；原版权和许可在源ZIP。新核心摘要及 v2 试验存档命名空间隔离旧 r1 档，不读取/覆盖/自动迁移。不是上游正式发布版本。

在空输出目录重建：

```text
python build.py Mesen-source.zip LLVM-MINGW/bin OUTPUT_DIR
```

脚本验证源ZIP再解压到输出目录并精确替换字段，拒绝重复/不匹配补丁；不改原ZIP。重建DLL可能因PE时间戳/路径产生不同SHA；若更新二进制必须重做固定摘要与真核心验证，不能仅修改SHA绕过。

## 原生回归探针

只能在独立测试 JVM 运行，不放进 Minecraft。Java21、最终候选主 JAR：

```text
javac -encoding UTF-8 -cp game_console.jar -d probe-classes RestoreRegressionProbe.java
java -Xcheck:jni -cp "probe-classes;game_console.jar" cn.piq.fcarcade.netplay.RestoreRegressionProbe test-vrc7 fixture:85
java -Xcheck:jni -cp "probe-classes;game_console.jar" cn.piq.fcarcade.netplay.RestoreRegressionProbe test-mmc3 fixture:4
```

两个夹具为本项目原创的最小循环程序，不含商业 ROM。也可将 `fixture:85` 替换为用户有权使用的 `.nes` 路径，仅只读。探针复用生产 profile/JNI 桥和生产启动顺序，检查首次逐字节恢复、6轮30帧带双手柄输入的确定性重演、音画、SRAM与状态一起恢复、关闭释放；失败非零退出。不等于游戏可通关、光枪可用或 MC 真人联机验收。
