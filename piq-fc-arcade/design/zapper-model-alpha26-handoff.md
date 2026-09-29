# 光枪物品、姿势与瞄准交接（alpha26）

修改者：cabinet_reuse_review / 像素匠协作子任务。日期：2026-09-11。
状态：生产冻结；最终 v2 成品验证通过。未启动 Minecraft、安装或修改用户 ROM/存档。

## 生产范围

修改 `registry/ModItems`、`registry/CreativeTabCatalog`，新增 `fc_zapper` 主手物品。

新增 class stems（均为 `cn/piq/fcarcade/` 下；无删除）：

- `home/HomeZapperItem`、`home/HomeZapperAim`。
- `layout/ZapperAimGeometry`、`ZapperAimGeometry$Cell`。
- `layout/ZapperPoseLayout`、`ZapperPoseLayout$ArmPose`、`$FirstPose`、`$ItemPose`、`$View`。
- `client/zapper/ZapperInputState`、`ZapperArmPoseParameters`。
- `client/zapper/ZapperClient`、`ZapperClient$1`、`$RemoteAnimation`。
- `client/zapper/ZapperItemRenderer`、`ZapperItemRenderer$1`、`$2`、`$Cached`。

`HomeZapperService` / `ZapperBinding` / `ZapperData` / network / worker 由会话代理负责，`ClientArcadeEvents` facade 和注册由 root 负责，不混入此清单。

## 资源与原模型保护

可信本地导入器 `tools/import_zapper_item26.py` 仅导入原 body 130 + trigger 2 元素及原 PNG；原几何、单元素旋转和 UV 保留。支架、卷线、插头保留在 raw 文件，本轮不作为手持部分或新增物品。无执行用户 ZIP 代码。以下路径相对 `src/main/resources/`：

| 路径 | SHA256 |
| --- | --- |
| assets/piq_fc_arcade/models/item/zapper/body.json | 57877F01B43BE62C548097856052A0B833DB9740929A850C22017942BDDDDF1E |
| assets/piq_fc_arcade/models/item/zapper/trigger.json | 3A3AF4EA4290D68BB178BCF02AB47A088F0638F535E626F7C64C74617F25AF3F |
| assets/piq_fc_arcade/textures/item/zapper/skin.png | AB5C925B7BD21AD2CBBFCC96A0F38CC4FE6C4E9013BCE57DF8F866E9EA16167F |
| assets/piq_fc_arcade/models/item/fc_zapper.json | DF645D82F64276D6EB3511B33CA8C8D5373DE7D0D5AC8C1C31B98B490E07DED3 |
| assets/piq_fc_arcade/lang/zh_cn.json | C8B72873F4A8843A5636C96747B5CA63BFE4A0075A587E06355380659C4A8D7C |
| assets/piq_fc_arcade/lang/en_us.json | 5ABE2604EFAFAAC56292826540B5BD90E4A6EEE5B2E6CDB28B4BC1F9D23DD605 |
| META-INF/piq-fc-controller-enumextensions.json | D5F080C6D24EC77E583AD79158AC46033F1ED3595D47DD9D0EAC2721863972C0 |

前三模型/纹理和 wrapper 为新增；语言只新增三项，枚举保留旧两项并追加 `PIQ_FC_ARCADE_ZAPPER`。没有将旧模型替换为重新绘制图片。

## 输入与瞄准

`HomeZapperAim.sample(Player,ZapperBinding)` 为两端共用解析器：绑定维度、主机/TV 身份、双向 AV 链、完整结构与已加载区块复验后，使用与 renderer 相同的 `ScreenSurfaceGeometry` 游戏 quad 映射 256×240；黑边、背面、平行射线、越界、方块/实体遮挡无效。忽略自身电视的精确活跃占位外壳，不忽略任意区域；不读取世界亮度判游戏命中。

`ZapperClient` 只接受实际当前 Connection 的网络授权，主手真枪 + 当前租约 + session facade 才可发输入。左键快速按下/松开分别发送；GUI、失焦、失去权限强制清 trigger，回到操作需先松开。epoch 变化重置序列，中断不创建另一核心。远端扳机仅消费已完成权威帧，无新增视觉包。

独立 QA 发现并修复 DDA 在负方向整数终点 tie 时走过目标 cell 的缺陷，保留16米限制，并加入 `(-8,8,0)` 精确回归；不是删除或放松边界测试。

## 最终 v2 证据

- 输入成品：`build/review-controls26-v2/piq_fc_arcade-0.31.0-alpha.26.jar`。
- SHA256：`74E16FEF0F69C88191C0A64DA4FCCDEFD2F3B18B231FC56C97839E60B885FBE5`。
- 报告：`design/zapper26-visual-final-v2/verification.json`，SHA256 `F2963B089BA13184192D4689964434BF7494C1CDF02DFA084B9D66E76C70B3B1`。
- `schema=piq-zapper-visual26-1`、`mode=final-jar-only`、`production_compiled=false`、`ok=true`。
- 17 项纯几何/输入测试；真实 FML parser + `RuntimeEnumExtender` 8 项检查，保留原枚举常量、准确添加旧两项和枪第三项；真实 `PoseStack` 3027 项检查，其中3项核验生产类 CodeSource 来自同一最终 JAR。
- 原 UV 离线预览：`design/zapper26-visual-final-v2/zapper-preview.png`。此图不是游戏截图；第一视角枪在画面下侧、准星未被遮挡。
- 真实 MC ArmPose 变换结果 SHA256：`E238E0422256BBD8514CE36CA64B83F0B1B0449A20667F8C308E00A3796DEFFA`。

执行器：`tools/check_zapper_visual26.py --fc <最终JAR> --output <新目录>`；只编 tests/probes，不编生产类，使用 SHA 一致的 ASCII 临时副本并前后复核原件。v1/source 报告仅留作历史，不代替此 v2 报告。

验证边界：没有初始化 MC ArmPose、ModLauncher 游戏启动、OpenGL/真实玩家 renderer、真实鼠标/设备或实际世界运行。姿势矩阵、FML 变换、纯射线规则通过，不冒称已做实机体验验收。会话/实际 WASM/网络回归见独立会话代理报告。
