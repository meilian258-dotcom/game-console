# GBA A 档试玩交接（2026-09-11）

负责人：cabinet_reuse_review；独立复核：sfc_cabinet_provider。只修改新 `piq-gba`，另曾新增 FC 项目设计文件 `design/gba-feasibility-20260911.md`；FC / Native / SFC 既有生产代码、版本、JAR、模型、ROM 与存档均未修改。根维护手册由 root 统一留痕。

## 最终候选

目录：`G:/服务器/服务器Codex/piq-gba/build/preview-v4`

| 文件 | SHA256 |
|---|---|
| piq_gba-0.1.0-alpha.1.jar | 331202E4DEA6BF0EB4C2C1BA8752A2F62886BF2007F7F3BB99902BE0D70B0A3E |
| piq-gba/runtime/piq-gba-helper.jar | AF687B20AFD470992F9C02C80173356E20E9D9E98FABDDCF3800979F11A4B28C |
| piq-gba/runtime/jna-5.14.0.jar | 34ED1E1F27FA896BCA50DBC4E99CF3732967CEC387A7A0D5E3486C09673FE8C6 |
| piq-gba/runtime/mgba_libretro.dll | D1BA96BC1AF23997D5C8003A6F6F8BE7ACBA9D770D4D42D14557AAEB469FA16B |

最终证据：`preview-v4/final-independent-audit.json`，schema `piq-gba-final-bundle-1`，`ok=true`、`production_compiled=false`、`compiled_only_probes=true`。绑定实际 addon、helper、核心、JNA 和本次 FC29（80FD1512…B833C2）；若交付搭配新的 FC30，请以新 FC JAR 重新运行 `check_gba_final.py` 到新的报告名，不覆盖旧报告。

核心真实最终 JAR 探针 98,434 个断言（大部分是逐字节存档保持，非9万种功能）；私有子进程/存档/目录安全 27 断言；真实 FML 读取、注解扫描、Maven 依赖及实际 FC 注册器 28 断言。没有启动 Minecraft 世界、用户实例、网络 socket 或使用用户 ROM/存档。

## 打包需要保留的文件

1. 仅 addon 放 `mods/piq_gba-0.1.0-alpha.1.jar`。
2. 整个 `piq-gba/runtime` 放游戏实例根目录的同名路径。helper/JNA 不放 `mods`，DLL 仅被自有子 JVM 加载；不覆盖原 Native helper 或其运行库。
3. 随附 `README.md`、`licenses-and-source` 全目录、构建报告与最终审计报告。MPL 对应完整源码约16MB，不能仅附下载链接而漏掉已准备的源/许可。PIQ 源 ZIP 含 Java、资源、所有构建/测试工具及说明。
4. 上游完整源码 archive 的 `cinema/` 保留原有开源诊断测试 GB/GBA 二进制；这些没有被我们当游戏运行或安装。对用户说“不附商业游戏/BIOS”，不要说“源码里绝无 ROM 后缀”。运行文件夹本身没有游戏或 BIOS。
5. `preview-v1/v2/v3` 是保留的历史候选；只打包 v4，不把 incoming/vendor/build 整个工作目录一并装进实例。

## 实现接缝与限制

- common `GbaMod.registerBackend()` 实际注册 `piq_gba:gba`，localOnly=true、没有网络能力；沿用现 FC 权限、lease、连接、配置记忆和拥有者守卫。仅未公开LAN的本地单人世界。
- client `GbaCabinetBackend` 注册旧兼容接口，实际被 FC 的 `RetroFactoryRegistry` 薄适配消费。ROM `.gba`、240×160、3:2、无旋转；只接受十个GBA按键，未另增GBA共享输入profile，当前复用 ARCADE 配置。
- `GbaProcessSession` 主JVM只持有有界管道、latest frame/PCM队列；异步 owner 独占子进程、128输入边沿、10秒IO进度超时、退出清零且等待真实子进程结束后释放单实例预算。原生 DLL/JNA 不在 MC addon 内。
- 官方 mGBA 实际版本 `0.11-219-e31759b`（不是稳定版0.10.5），源码完整提交 e31759b24e7a4e3899285ff720d7b573ac328ae7，DLL与source对应为内嵌commit核对、非可重现构建证明。实际音频65536Hz→流式48000Hz。
- 电池存档按ROM SHA+核心版本位于新目录，同实例跨世界共享，每300帧/正常退出原子保存；不是 FC 个人三槽或即时状态UI。不会读写用户原ROM旁 `.sav`。
- 真实 junction 拒绝/目录祖先复验、损坏主档→好备份→保留原坏档→重新保存/重开，以及构造失败不能占住全局进程槽已覆盖。文件检查不声称对同权限恶意并发目录替换提供OS沙箱。
- 用户GBA模型只评估未接物品：原比例/UV/PNG未改。当前没有手持屏幕、GBA线联机、多人、传感器/特殊卡带兼容保证；没有商业游戏实机或帧率验收。

## 再现入口

工作区内（Java21、现有真实NeoForge/JNA依赖）：

```text
python piq-gba/tools/build_gba_preview.py --fc <主FC.jar> --output <piq-gba内新目录>
python piq-gba/tools/check_gba_final.py --fc <相同主FC.jar> --bundle <新目录> --report <不存在的新报告.json>
```

`build` 编译新GBA；`check` 只编探针、禁止替代生产类。所有输出拒绝覆盖。未安装、未自动关机，交付由 root 决定。
