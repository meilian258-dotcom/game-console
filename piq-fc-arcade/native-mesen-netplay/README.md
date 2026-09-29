# Mesen JNI Netplay r1 对应源码

仅供 FC76.24 的 JNI Netplay 试验；普通 FC 核心和 RetroArch 核心没有替换。

- 上游 https://github.com/libretro/Mesen ，提交 `0102910c39ad1a62bc3f784466f3f67ca9eae335`。
- 原源码ZIP SHA256 `375a885c397685a5aad82bd14f68e1b30d8e78405471344629a6f2a04c8a9e04`；交付的源码材料中保留原ZIP与本目录。
- 唯一核心改动：`Core/ControlManager.cpp` 的 `Serialize` 将 `_isLagging` 追加进状态。原版遗漏该标志，会在恢复后的下一帧改变 lag counter，造成真实状态CRC差异。没有屏蔽CRC或给特定偏移清零。
- DLL `mesen_piq_jni_netplay_r1.dll` SHA256 `81989a6d9932c928a9a75b63ae7100381b99529ddba3064d0d92d66c2162aabe`，3,606,016字节。LLVM-MinGW 20250910 UCRT x86_64 / clang21.1.1，105个上游编译单元，-O2/LIBRETRO/NDEBUG/C++11，静态链接运行库。
- GPL-3.0-or-later；原版权和许可在源ZIP。源码格式变化因此使用新的 profile 和存档命名空间，不读取旧状态。不是上游正式发布版本。

在空输出目录重建：

```text
python build.py Mesen-source.zip LLVM-MINGW/bin OUTPUT_DIR
```

脚本验证源ZIP再解压到输出目录并精确替换字段，拒绝重复/不匹配补丁；不改原ZIP。重建DLL可能因PE时间戳/路径产生不同SHA；若更新二进制必须重做固定摘要与真核心验证，不能仅修改SHA绕过。
