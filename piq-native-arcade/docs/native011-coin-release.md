# Native 0.1.1 / FC48：投币后松键保持正确

日期：2026-09-17。维护者：像素匠。

适用 Minecraft 1.21.1 / NeoForge 21.1.236 / Java 21；Native 0.1.1 最低需要 FC 0.31.0-alpha.48。本地开发验证版，未安装实例、发布或运行游戏。

## 语义

- 普通菜单、失焦或控制输入 Reset 使用可选 `supportsCoinPreservingRelease()` / `releaseGameplayPortKeepingCoin(port)`。它在既有 FIFO 中仅保留 bit 2 的投币边沿，立即去掉其它位和冗余重复状态；不是往旧队列末尾排一个零输入。
- Java 父队列保留其它口的输入及先前硬释放；新 helper 在下一原生帧取样时只留下该口的投币历史，不再重放最多 128 帧方向。已经执行的 coin-down 不会因软释放再次合成；未执行 down/up 顺序与次数保持。
- 席位离开、会话停止仍使用原 `releasePort` / `clearInput` 硬清语义。异常退出不能被宣称为跨进程原子退款或一定执行游戏内积分。
- 可选公共接口默认“不支持”，调用会明确异常；绝不默认用硬清或普通零输入冒充支持。旧 Native 没有此能力，FC 收费输入必须在能力确认前拒绝，不扣币。FC/SFC 普通输入不因此改变。

## 固定 helper 与运行库

新增 `piq-native-arcade/runtime/piq-native-helper-v4.jar`，18858 字节，SHA-256：

`51A1A6A0A326E5E855647A414DBB78894CA01E9D766627EF272CE59BC809A304`

仅通过新 `tools/build_native_helper48.py` 离线编译。私有管道 v4 新增指令 6；JDK21 与已校验 SHA 的 JNA 仅作编译依赖，没有执行 MAME/NeoGeo DLL 或读取 ROM。旧 helper 文件与旧生成器不覆盖、不删除。普通 Java 桥只绑定新的文件名与固定 SHA。

新 `tools/prepare_embedded_runtime011.py` 校验原 runtime37 完整九项 ZIP、原许可、新 helper 和四份 helper 源码 SHA。旧 `prepare_embedded_runtime17.py` 与其原十二项输出原字节保留；新生成目录只增加三条：

- `native-runtime/win-x64-v1/piq-native-arcade/runtime/piq-native-helper-v4.jar`
- `native-runtime/win-x64-v1/manifest-native011.json`
- `META-INF/licenses/native-runtime17/NOTICE-native011.md`

新 manifest 列出原六 payload + 新 helper 和来源 SHA。原 manifest/NOTICE 是原运行库来源记录，继续保留。MAME DLL、NeoGeo DLL/helper、两个 JNA、GBA 引用不变，不把运行库 JAR 放到 mods。

## 安装约束

现有固定清单/流式校验/链接保护/原子无覆盖安装器只向新文件名补缺，已有旧 helper 与未知邻居文件不动；新文件名若已有不匹配内容会阻断，绝不自动替换。

原 runtime37 九项离线包继续按原清单检查，GBA 的原离线流程不失效；新 helper 必须来自配套 Native 0.1.1 内置资源，不能通过旧 ZIP 补齐。运行环境页和诊断明确提示此限制，不联网下载。

## 已完成与待验证

- 生产队列 9 个纯 Java 测试通过，涵盖全部 65536 masks、四端口、128 边沿、硬/软释放交错、重复释放和控制槽上限。
- 实际新 helper JAR 的真实命令解析/输入回调与父 FIFO 无核心探针 416 项断言通过。
- 公共默认拒绝/适配委托 3 项及实际安装器惰性共存/不覆盖 2 项通过。
- 新运行库打包工具 8 项小样本测试通过；`--plan` 只读完整原件与新 helper 身份核验通过。旧生成器的 21 项测试也保留，其中旧构建依赖断言从历史 FC42 更新为 FC48。
- 完整 FC/SFC/Native Gradle 回归与最终三包冻结由主任务统一执行，不能以本记录代替。Minecraft 内失焦/菜单/离席和真实联机投币验收仍待进行。
