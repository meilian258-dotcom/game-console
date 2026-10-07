# Game Console / 方块电玩：FC41 与 SFC25 发布预览交接

时间：2026-09-14 14:39 +08:00。负责人：像素匠。

## 请求与状态

英文名为 Game Console。该版本提供 FC/SFC 新玩家文件的发布形式预览，仍未完成全部平台与实机验收；不等于正式公开发行。

中文名称保留方块电玩；内部 `piq_*` ID、技术路径、作者与第三方署名不删。英文 source 14文件精确替换：4英文语言7值、3pack、2metadata description、4manifest title与1 Java导出报告字面量。本次只制作 FC/SFC 新JAR；Native/retro 的相关英文源改动已落盘，但没有新 Native/GBA JAR，不能把旧brand40附属说成已改新版英文名。

版本：FC `0.31.0-alpha.41`；合并 SFC home `0.1.0-alpha.25` / core `0.2.0-alpha.9`。SFC 两部分最低 FC41，home最低内含core9。FC首装一个JAR；SFC首装加合并附属共两个，不需 Native/GBA 运行库包或独立 retro/SFC core。

## 冻结与测试

新冻结器 `tools/build_public41.py` 使用固定 brand40 两JAR与旧见证。实际 Gradle `check jar --offline --max-workers=2`：FC5m30、SFCcore1m05、home1m02成功。FC1640项含8既有跳过，home338全部通过，合计1970通过/0失败/0错误/8跳过。SFC ABI、ROM仓库、source+packaged WASM smoke及无重复Wasmtime检查通过。只重编Java，不重编Rust，也没有真实Minecraft/GPU/控制器/两客户端/专服首装验收。

v1生产捕获在Gradle前，SHA `F4EDD8E22663D557D5D76C65175515DE4B47A187DC0E46E307507B7CD316DF0C`。独立审查后冻结器补强唯一常量池变化验证和写包后输入复核；v2仅此冻结器一文件改变，SHA `E0FFA4393444E71FAB1772A43C95B31F6E1839F561242736BE154347AFCCED54`。v2 captured_ns为新时刻，tests_not_before_ns明确沿用v1，不说测试发生在v2后；生产输入完全一致。

最终1834类仅FC ScoreCalibrationSession一个UTF8常量变化，新class与旧class唯一等长英文替换精确相同，源码逆替换也回到brand40源SHA。两JAR各5条变化，其余2150条原字节；核心、原生库、模型、PNG、协议与业务不动。FC两张历史controller源草稿在冻结包中保留已验证旧资源，在源码候选内按最终JAR字节替换，未覆盖本机草稿。

## 最终文件（均在 build/review-public41-v1）

| 文件 | 字节 | SHA256 |
| --- | ---: | --- |
| game_console-0.31.0-alpha.41.jar | 30750439 | 27DA245957D64D88BE105F8AC3911C059A01FC60CAA2548776AC2A376390674D |
| game_console_sfc-0.1.0-alpha.25.jar | 1349951 | C0B15FEE1A05C914296DFC04E8B37B7FC45881F7102F6F6BAE3E82FDE3C49C78 |
| Game-Console-FC-first-install-alpha41.zip | 30398033 | EADC9530D5A720F20628790D51056623303F780AD65AD4AF559B2F88B9B9AA07 |
| Game-Console-FC+SFC-first-install-alpha41.zip | 31701784 | 5F7438D5E26AF83B61E6CB6A43114054744F671EF5035FCA281A75B9D12FA8DB |
| Game-Console-corresponding-source-candidate-alpha41.zip | 5695735 | 54A636349BB54E521B3AA053EAD9FD1488DE755C94FEAF3F537ACBBB53E9D2DC |
| Game-Console-publisher-preview-alpha41-v2.zip | 37066183 | E353515936D1D45ABE38D24B6E8DD74D2963F8BB69CC90F0DFDC8985C10E3973 |

首装ZIP只含mods下一个/两个JAR、安装/边界说明与SHA清单。作者v2含两JAR、源码候选、中英页面文案/安装/构建/许可待办/变更说明与构建路径补充，共13条。作者v1保留为历史：37,065,151B SHA7478E50F10549ADBF96089649EA4D002BA706EC0629B5A548B0B9EF2C2FD9622；v2只增补充说明、README指引和相应SHA清单，JAR/source原字节。

独立冻结检查核对1278项输入与301份XML。独立标准库最终审计 `final-deliverable-audit.json`、`final-addendum-audit.json`，六基础文件及作者v2全部CRC/SHA/成员清单通过。首装打包纯测试6项通过；源码包内通用SFC合并工具14项纯测试通过。

## 对应源纠错与待办

源码候选1879条：四工程Java/测试/资源/Gradle/native，jgenesis587/590文件（排除不在10crate SFC路径闭包中的3个32X独立固件bin），不包含原整份ZIP以免带回排除项。项目旧adapter lock64包不是原WASM准确锁；原ASCII staging锁51包/41registry，SHA `F376ED49DE856C11F2321B1495C06F6B294EEC1C3FC0363733FADFF0D5491219`，其旁WASM与最终SFC核心相同。只在源码ZIP内换成准确锁，原项目锁仍保留且随包support另存历史；本机文件不改。源见证 SHA4852F5693843841CABC86952F2BD8DD87D14700E6DC7E76ED746CCA2AEC9F77E，审计staging绝对路径不入ZIP。

仍不能标为直接公开上架：

1. SFC内嵌64B SPC700启动固件专项权利依据未确认；不通过删字节/改编码绕过。
2. 预构建Wasmtime原生库的准确传递依赖/许可/构建资料未齐；顶层已给许可不等于完整清单。源码候选不是法律履约或字节可复现认证。
3. 模型/贴图/保留品牌的公开再分发依据未确认；不擅自改用户批准恢复的素材。
4. 旧WASM有编译路径字符串：FC光枪工作区7处（首53322）；SFC用户目录57处（首1811912）及工作区2处（首1814667）。二进制检查确认；不认定都是可剥离debug区，未编辑核心。需可复核路径重映射重编和兼容性验证，或作者知情决定。作者v2已含补充说明。
5. 干净新机、真实联机/专服/存档重连、平台发布素材与审核未做。不以自动测试或测试版标签替代这些要求。
