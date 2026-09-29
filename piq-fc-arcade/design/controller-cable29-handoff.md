# alpha29 线材接缝（cabinet_reuse_review）

仅本地源码与独立测试；根任务负责完整构建、最终包与根手册。无游戏/服务器启动、安装、ROM或存档操作。

## 动态手柄线

- 新FC生产：`layout.ControllerCableGeometry`（Style）和 `client.ControllerCableRenderer`（Lease/Receipt）；新SFC生产：`client.SfcControllerCableRenderer`。
- 两个现有 BER 在 facing pose 已 pop 后调用 `renderFc` / SFC `render`。根任务修改调用点和旧 bounds.minmax(new bounds)。新 margin9，view32；服务端玩家脚点至主机锚点中心6格内才显示。
- 每帧最多两次按UUID找玩家。BE player/lease、实际持有物品、端口全部匹配且只在一只手中；空、重复、归还、不可见、失效、跨level、断线不画，不缓存人或会话。视觉信息无授权作用。
- FC端口来自实际旧docked模型末尾第6段环；小霸王两版来自mesh metadata.controller_sockets；SFC来自当前SfcConsoleScale.console的1.5主机缩放，手柄不额外放大。全部只绕(.5,0,.5)四向转一次。
- 线段9–65点、每段8面、长度上限12，无强制加载，无物理绳索碰撞。原停放线/模型/PNG/UV保持；新线只在对应dock隐藏时显示。
- 第一人称使用现有ControllerPoseLayout rig/摆动，装备进度因BER阶段无公开值取idle；第三人称为按身体朝向的手部近似区域，不读取可能属于其它玩家的共享PlayerRenderer模型。不是逐骨骼精准绑定；自定义手部FOV/其它姿势MOD可能有偏移，需要实际游戏观感验收。
- 独立 `tools/check_controller_cable29.py`：源码模式15项真实pure JUnit +47,558次真实MC Camera/PoseStack断言；`design/controller-cable29-source-v1.json`。最终可传 `--fc JAR --sfc JAR --report NEW`，只编探针/测试并绑定SHA，读取包内模型；不把源码模式当最终JAR结果。
- 原光枪线 `ZapperStandRenderer` 主机端改为同一几何helper的物理P2口，不再用早期临时占位坐标。枪/支架外形与UV不改。

## 统一数据线

- 保留 `zapper_stand_cable` 为主数据线，`cabinet_link_cable` 旧ID/旧public类为继承兼容别名；两者走同一useOn/tooltip。creative仅显示前者。旧alias模型只改parent指向原数据线模型，未重做几何/贴图。
- 真实机柜入口仍调用原CabinetLinks.use，OP2/双端8格权限/16格拓扑/4口backend/空闲检查未减少。新增public cancelSelection只调用原forget；点击另一类设备时清本玩家未完成选择，不清installed link。FcArcadeBlock/DualCabinetBlock/DualCabinetPartBlock各改1行识别共用父类，在普通会话互动之前PASS给物品；新线与旧alias均不意外启动游戏。
- 支架→FC连接仍用原ZapperStandService.cable。Shift点支架保留原断线；新增Shift点FC端按已保存pair找到真实已加载支架，依次双端保护事件、后置身份/对象/held/pair复核才断线。
- AV仍独立原HomeHardware.useCable；原本已可持AV线Shift点主机或TV断开，仅追加tooltip。
- 新 `loanPlayer(console)` 仅查询已加载精确连接、在线同维度6格内、主手唯一实物receipt，绝不授予输入。关机取枪 `interact` 中两running门禁由FC会话代理修改，此文件不同段协作。
- 语言增改见 `data-cable29-lang.json`，由根任务合入共享lang。旧机柜服务提示“街机通讯线”表示用途，不另设物品。

## 当前修改范围

除上述新类，既有改动为 `ZapperStandCableItem`、`CabinetLinkCableItem`、`CabinetLinks`（仅cancelSelection）、`ZapperStandService`（只读loanPlayer/取消选择/FC端断开；interact由另一代理）、`AvCableItem`（仅tooltip）、`CreativeTabCatalog`、`FcArcadeMod`（移除旧alias的重复creative事件）、上述3个world类守卫和`ZapperStandRenderer`物理P2口。资源仅 `models/item/cabinet_link_cable.json` parent和根任务合并的lang。前者SHA256 `EEA3C8D435F5B5654D297A215848C376F92513306EE7558036AF4B5F06190BD4`。

`tools/check_data_cable29.py`源码模式6项窄合同已通过，报告`design/data-cable29-source-v2.json`；新增实际NeoForge RegisterEvent/旧ID ItemStack NBT/useOn继承/tooltip调用探针，等待最终JAR运行。合同检查不是实际保护插件或世界交互测试，不混为最终通过。两个服务端代理实现的idle物理租约均保留BE视觉player/lease；本renderer没有开机/核心门禁，因此关机手持仍有线。

## TV音光只读审查

根任务实现暂未发现阻塞。实际MC SoundBuffer成功上传后data置null，不memFree，故TelevisionTone.allocateDirect的JVM所有权匹配；没有把memAlloc误用成GC buffer。测试音8源、read上限1MiB，基于精确loaded BE/当前level清理；BLOCKS声音分类保留引擎音量/暂停。TV LIT仅服务端已加载精确BE同步，新off与旧无字段on保持。PowerIndicator一像素纹理退出释放。此为源码/字节码核对，非实际OpenAL/世界听感验收。
