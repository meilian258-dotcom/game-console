# 固定 ROM 文件夹与 Native 列表接入交接

修改者：Codex `/root/fix_sfc_av`；日期：2026-09-10。
范围：只新增 FC 公用纯 Java `client.rom.LocalRomLibrary` 与测试，修改 Native `NativeArcadeSetupScreen` / `NativeCabinetBackend`；未改核心、ROM 暂存、协议、运行库、helper、已安装冻结包或版本。根代理统一维护手册、版本、构建及安装。

## 公共契约

- `sfcDirectory(gameDir)`：`piq-sfc-home/roms`；`arcadeDirectory(gameDir)`：`piq-native-arcade/roms`。getter 无 I/O。
- `prepare(dir)`：显式逐级建目录；NOFOLLOW 属性与真实路径逐父复核，拒绝文件占位/符号链接/重解析跳转，不覆盖既有文件、不迁移旧数据。
- `scan(dir, extensions, excludedNames)`：不创建目录、不读 ROM 内容、不解析 ZIP，只枚举指定目录的第一层文件元数据；大小写无关扩展名/排除名单，非普通文件和链接跳过。最多返回 512 条、最多检查 2048 条，约两秒协作式扫描预算，响应线程中断。单次操作系统文件调用无法保证硬截止，不声称硬实时超时。
- `Scan(List<Entry> entries,int skipped,boolean limited)` 与 `Entry(Path path,String fileName,long bytes)`；结果列表不可修改，按名字稳定排序。
- `validateFile(path)`：选择后读前复核路径、父目录、文件类型；不是 ROM 内容兼容性检查，最终核心加载器仍负责长度/格式和它原有的安全检查。
- `submit(Runnable)`：单个 daemon worker、队列上限 8、满队列返回 false，没有 CallerRunsPolicy，不把文件操作挤到渲染线程。

## Native 入口

历史 `NativeArcadeSetupScreen` 类型保留，改继承 root 提供的 `LocalRomPickerScreen`。固定 arcade ROM 目录，只显示 `.zip`，排除 `neogeo.zip` / `qsound_hle.zip`。选择成功回调 `NativeArcadeClient.start`，取消回调 `NativeArcadeClient.stop(null)`；切换到游玩界面不会调用取消逻辑。Native 后端新增目录/扩展/排除项元数据，保留原 `defaultRom` 诊断路径但不自动把诊断当真实游戏启动。

## 验证

`tools/check_local_rom_library.py` 直接用 Java21 编译实际新增生产类、JUnit 与 `tools/qa/LocalRomLibraryProbe.java`，未运行 Gradle 或 Minecraft。13 项：11 成功、2 因 Windows 未授予创建符号链接权限而显式跳过。覆盖 getter 无写入、只创建指定目录、不覆盖文件、scan 不创建缺失目录、中文/大小写/非递归/metadata-only、BIOS 过滤、真实 512/2048 截断、错误扩展/文件名、选择后文件移除/变目录、线程取消，以及实际 daemon/有限队列/拒绝内联执行。

初次报告 `design/local-rom-library-20260910.json` 保留；加强上限为精确数量后的报告为 `design/local-rom-library-20260910-v2.json`。纯夹具证明不等于 Minecraft 界面目视或实际游戏验收。Native 完整编译由根代理在公共界面与后台契约落盘后统一执行。
