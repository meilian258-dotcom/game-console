# FC33 / Native11：现有街机的实验本地同步

2026-09-12，像素匠 root。用户已确认制作；只交测试文件，不安装、发布、重启或关机。不改原 ROM、BIOS、存档、模型或历史交付包。

## 接入范围

- 现有机台 Shift＋空手右键 → 同步/网络；管理员、全部相连机柜空闲时修改，下局生效。保留媒体模式；不新增机器。
- Native 实验仅固定 KOF97 / MSlug2 ROM、neogeo BIOS、E8F4 DLL、F175 helper、JNA 5.14.0 与已测启动 profile。最多双人；超过两席的物理串联明确拒绝，不静默截断。媒体仍支持原四席。
- 两端独立冷启动的完整状态不同，不能假称已通过初态确定性。仅显式注册的固定实验策略 HOST_SNAPSHOT 允许先校核心/内容/帧率，再加载主持快照并立即重新保存核完整 SHA，追帧后才激活。SFC 原 STRICT_INITIAL 不变。
- 固定32帧 bootstrap 对应逻辑0；快照封装完整核心状态和内部帧/ROM/BIOS/profile，不能只以日志SHA代替状态。每帧精确执行一次，48k完整PCM；追帧只不播放历史音频，不能跳过核心混音。
- 普通模式不替换原 runtime。新增 `piq-native-arcade/runtime-snapshot-v1/`，只允许固定文件身份，Minecraft 进程不加载 JNA/DLL，专服只加载公共协议。所有 Native 会话共享唯一父端租约，终止仅精确自有子进程。

## 网络与边界

- `cabinet-sync-2` 成套 FC33；SFC19/GBA2 保留二进制与默认接口行为。ROM共享仍走既有服务端授权/内容清单，不传 DLL，不把分享游戏等同于分享个人存档。
- 完整状态摘要每300帧；Native主持快照每1800帧（约30.4秒），约0.88Mbps持续状态payload，另有加入/纠偏及旁观媒体。不能宣传纯按键或零带宽。保留最新待上传快照，有界重试临时拒绝，不因一次拒绝等下个周期。
- 16MiB状态上限、24KiB分片、实际Connection背压、7200帧历史、45秒名义客端恢复期限、权限/租约/epoch/token复验不放宽。A正常继续，失败只隔离B；主持退出仍结束整局，不做无缝主持迁移。
- 不支持任意驱动、非Windows x64实验核心、未经验证的四人游戏。合金弹头2原硬件减速不由网络模式自动修复，不做超频。

## 分工与验证

- root：合同/版本/依赖、源码与资产围栏、构建冻结、最终JAR核验、成套与源码包、手册。
- cabinet_reuse_review：Native fixed profile/session/workspace/core、provider/common注册，真实逻辑0冷恢复/长序列音画状态检查。
- fix_sfc_av：公共同步策略、服务端Hello/席位/设置/协议及授权测试。
- sfc_cabinet_provider：兼容客户端API、worker取消/快照周期/有界上传重试、实验UI及生命周期测试。
- 必须真实两个独立原生进程验证；最终JAR再测，不把fake协议或离线测试当Minecraft公网真人联机通过。全套回归、旧SFC/GBA依赖、专服无client/native加载、所有模型资产不变后再交付。源码冻结期间停止编辑，变更后重新构建核验。

状态（2026-09-12 13:56）：生产已冻结于 build/review-sync33-v1；FC33 SHA F36169E46B13CF46868851447B8518BC38C10967A03994AF26E89CE7B1E4FE00，Native11 SHA F582314F64A2DC556FAC1704719C04EE9E5A05154C146FA1C49FCAD9615E2C5E。全量构建和最终 JAR 的 Native 真核/实际通用 worker/SFC worker/公共注册/协议验证通过，成套测试包封装中。真实两台 Minecraft 联机仍待玩家验收。
