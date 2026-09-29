# SFC 家用6：实体手柄接缝与联机保护交接

修改者：fix_sfc_av；日期：2026-09-10。本记录对应用户新授权“可以，制作吧。记得保障好联机功能”，与上一轮停止制作的工具记录分开。由 root 合并到维护手册。

## 已修改

- `gradle.properties`：家用版本 `0.1.0-alpha.6`。
- `build.gradle`：编译依赖 FC19，仍读取原冻结 SFC6；普通 `jar` 是内部 thin 中间产物，最终使用独立合包工具。不添加 platform 模组依赖或嵌入公共运行库。
- `src/main/templates/META-INF/neoforge.mods.toml`：FC 最低版本 alpha19；旧 `piq_sfc_arcade` 依赖保留，最终合包内含两个旧 modId。
- `client/SfcHomeClient.java`：仍使用原键盘 poll、活跃/失焦/维度/手持真实租约门禁。只在 active 且非强制释放时，以实际 `playback` 对象身份调用 `GamepadInput.mix(... ProfileKind.SFC ...)`；失焦/GUI/强制释放调用 pause，关闭会话先 release 再关闭 worker。渲染阶段通过原 `sendInput(false)` 捕获手柄边沿，没有新网络路径。
- 新 `client/SfcInputSendPolicy.java`：原发送判定提取成纯函数；force、mask 变化立即发送，稳定输入每10个客户端tick心跳。keepalive 只有 tick 递增，render/key 回调不能制造额外心跳。

本代理未改 SFC 网络、服务器、租约、存档/联机算法、SfcPlayback、SfcHomeKeys、模型/物品外形或GUI。root另负责GUI画序，公共 `cn.piq.retro.client.GamepadInput` 由 sfc_cabinet_provider 提供并由 FC 主模组打包。

## 联机保持的路径

P1 仍通过原 `SfcHomeNetwork.Input`，P2 仍通过原 `SfcJoinNetwork.ControllerInput(controllerLease, input)`；12位顺序 B/Y/Select/Start/Up/Down/Left/Right/A/X/L/R 和 forceRelease/sequence 含义不变。实体手柄只增加当前本地玩家的输入源，不分配另一玩家或绕过 P1 批准。

只读复审确认：P2批准前/加载中仍是候选，不公布运行端口；提交要求当前双方身份、具体卡/AV链路、lease、transaction token、权限与状态摘要。错误分片/失效候选/超时只取消候选，不重置P1；30秒暂停/120秒申请期限保持。P2非法输入或100tick断流仅释放P2，P1继续；P1归还结束本局并归还P2。旧会话/epoch和旧P2租约不会通过原输入成员门禁。本轮未找到必须改变这些生产算法的阻塞问题。

## 验证

- 新 `tools/check_sfc_gamepad_network.py`：41项通过，0失败/跳过，报告 `design/sfc-gamepad-network-regression-20260910.json`。执行真实纯 gate、timeline、health 和新增发送策略，另1项源码接线契约。覆盖所有4096位组合、快速点按和组合键顺序、P2失焦立即清队列且不动P1、旧序号拒绝、候选错误摘要、超时/旧nonce、P1反重放序号保留、P2重新加入队列、P2速率/断流隔离、10tick心跳和高频render不增心跳。
- 既有 `tools/check_sfc_join.py`：24项通过，报告 `design/sfc-home6-legacy-join-regression-20260910.json`。有部分测试与前述41项重复，不能相加当作65项独立测试。
- `python -m unittest -v test_merge_sfc_addon.py`：20项再次全部通过，最终2.519秒，哈希固定的核心/home5旧原件仍未改变。这里只用临时合成元数据夹具，不是home6成品。

未运行全Gradle、最终JAR验证、Minecraft、实体GLFW手柄或真实双客户端联机；未安装/出包。41项测试不冒充完整服务端权限插件或实机验收。公共GamepadInput落盘后，root需统一编译 FC19→家用6，再用 merge_sfc_addon.py 合成一个SFC交付JAR，并做最终包审计/旧核心hash保护。旧冻结JAR和历史报告不覆盖。

## 最终审计允许差异（本代理部分）

`cn/piq/sfchome/client/SfcHomeClient.class`（其Setup若重编仅允许确证的调试表差异）；新增 `cn/piq/sfchome/client/SfcInputSendPolicy.class`。除此之外本代理未改任何生产类。配置元数据版本/依赖变化如上。root GUI部分另列白名单。
