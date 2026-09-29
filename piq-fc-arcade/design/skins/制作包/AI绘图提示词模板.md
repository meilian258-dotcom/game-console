# AI 绘图提示词模板

## 使用原则

AI 负责创作原画和纹理，不负责最终 UV 排列。最终 UV 必须由读取真实 OBJ 的脚本或已验证蒙版生成，不能让 AI 猜测面片方向，也不要让 AI 在旧的 512×512 成品图上继续切割和修补。

推荐给 AI：

- 一张连续侧板原画。
- 一块正面灯箱背景。
- 一块控制台表面。
- 无缝金属、蜂窝或喷漆纹理。

不要要求 AI：

- 重画 512×512 UV 图集。
- 保持几十个不规则 UV 岛的像素级位置。
- 直接生成可上传的最终皮肤。
- 生成精确可读的标题文字。
- 修补旧的 512×512 最终图集并自行推断哪里该拼接。
- 自行判断 `B18`、`B21` 后盖面的真实上下方向。

## 1. 连续侧板原画——通用提示词

将方括号内容替换成你的需求：

```text
为一台直立式复古街机设计一张完整、连续、从顶部延伸到底部的竖向侧板原画。

主题：[主题名称]
核心视觉：[主角、装置或标志]
主色：[主色1]、[主色2]
辅助色：[辅助色]
美术方向：[卡通 / 科幻 / 医疗实验室 / 像素复古 / 工业]

画面必须是一整个连续场景，不是贴纸拼贴，不是多个独立方框，不是 UV 展开图。
用一条贯穿全高的视觉动线连接顶部、中部和底部，例如管线、能量轨迹、藤蔓、闪电或道路。
主体集中在中上部与中部；背景图案自然延伸到四边，满版出血，不留白边。
顶部保留一个干净的标题牌区域，但不要生成任何文字、字母或 Logo。
重要角色和标志远离四周边缘，允许街机模型的屏幕凹口、操作台凸起和底部切口遮挡少量背景。
材质需要有适量细颗粒、面板高光和低对比纹理，远看清晰，近看有细节。
正视图构图，平面贴图，不要透视展示街机，不要画街机机体。

输出纵向海报，建议比例接近 864×1821。
```

## 2. PIQ 医疗实验室范例提示词

```text
Create one original, continuous, full-height vertical side-panel illustration for a retro upright arcade cabinet.

Theme: a cheerful futuristic capsule research laboratory.
Central motif: one large original red-and-blue energy capsule inside transparent medical tubing.
Supporting characters: three small original germ mascots in blue, red, and yellow, expressive but not based on existing game characters.
Palette: cyan, clean white, deep navy, capsule red, electric blue, small golden accents.

The entire composition must read as one connected scene from top to bottom. Use glowing tubes, ECG lines, bubbles, molecular diagrams, and energy trails as a continuous visual flow. Do not create separate stickers, boxes, panels, or a collage.
Reserve a clean integrated title plate near the top, but generate no words, letters, logos, or typography.
Keep important faces and the capsule away from the outer edges. Extend background artwork fully to all four edges.
Add restrained honeycomb texture, brushed metal hints, soft rim lighting, tiny rivets, and fine paint grain without visual noise.
Front-facing flat artwork only. Do not draw an arcade cabinet, mockup, UV map, perspective scene, watermark, signature, or copyrighted character.
Vertical poster, approximately 864×1821.
```

## 3. 负面提示词

```text
no UV map, no UV islands, no texture atlas, no collage, no split panels,
no sticker sheet, no mockup, no arcade cabinet body, no perspective,
no cropped title, no generated text, no letters, no watermark, no signature,
no black empty triangles, no white borders, no transparent gaps,
no copyrighted characters, no existing game logo, no copied artwork,
no photorealistic humans, no excessive noise, no tiny unreadable details
```

如参考图中已有标题，再追加：

```text
Do not reproduce any text from the reference. Leave every title plate blank for deterministic post-typesetting.
```

## 4. 无缝面板纹理提示词

```text
制作一张可无缝平铺的[青蓝色 / 白色 / 红色]街机金属面板纹理。
包含低对比度的[蜂窝 / 拉丝 / 细颗粒喷漆 / 电路]细节。
光照均匀，正交平面，不要透视，不要边框，不要文字，不要 Logo，
不要明显中心构图，不要大面积阴影，不要生成面板外形。
纹理在缩小到 512×512 图集后仍然干净，不产生摩尔纹。
```

## 5. 灯箱背景提示词

```text
设计一块横向复古街机灯箱背景，比例约 4:1。
主题为[主题]，使用[主色]到[辅助色]的柔和渐变，
加入低对比心电线、能量轨迹或微型六边形纹理。
中央必须保持干净，后期将放置标题。
不要生成文字、字母、Logo、边框、街机机体或透视。
```

## 6. 修改 AI 输出的话术

### 画面被做成拼贴

```text
请彻底重构，不要在现图上继续局部修补。
我需要一张从顶部到底部完全连续的单幅原画，而不是多个贴纸、方框或面板拼成的画面。
让背景、管线和能量轨迹跨越整个画布，所有元素处于同一个场景和同一个光照环境中。
```

### 主体会被模型切口挡住

```text
保持整体风格不变，把主要角色、标题牌和核心装置向画面中央移动。
四周约 12% 只保留可被裁切的背景纹理与次要装饰。
不要把脸、文字区域或关键轮廓放在边缘。
```

### 纹理太空

```text
保持现有构图和主体位置完全不变，只增加低对比表面细节：
细颗粒喷漆、轻微拉丝金属、稀疏蜂窝暗纹、面板高光和少量铆钉。
纹理必须克制，不能盖过主体，不能新增分区框或贴纸感。
```

### 纹理太乱

```text
保持主体和配色不变，将背景纹理强度降低约 50%。
删除高频噪点、密集小字、重复图标和多余边框。
远看先看到主体和大色块，近看才看到细节。
```

### AI 生成了错误文字

```text
删除画面中的全部文字、字母、数字和伪 Logo。
顶部只保留一块干净、完整、可供后期排版的标题底板。
不要尝试重新生成标题。
```

### 风格方向不对，需要整版重做

```text
不要沿用上一版的构图、裁片或局部素材，请从空白画布重新设计整张图。
只保留以下设计目标：[列出主题、配色、主体、氛围]。
画面必须是一体化的完整视觉系统，并保持原创。
```

## 7. 给 AI 附参考图时的说明

```text
参考图 1 只用于理解配色、材质密度和整体气氛，不得复制其中的角色、Logo、文字或具体构图。
参考图 2 是技术定位图，只用于说明哪些区域最终会被裁切；不得把定位线、编号、彩色面片或 UV 岛画进成品。
请只输出干净的连续原画，不输出模型预览或最终 UV 图集。
```

## 8. AI 输出后的人工处理

1. 删除 AI 生成的所有文字和伪 Logo。
2. 检查是否包含受版权保护的角色或标志。
3. 修复四周不连续和透明边。
4. 保留高分辨率原画。
5. 通过脚本或蒙版映射到真实 UV。
6. 由确定性字体工具加入最终标题。
7. 实际进入游戏检查，不能只依赖 AI 预览。

## 9. 交给代码型 AI 生成最终 UV 的话术

下列话术用于能读取 OBJ、运行脚本或编写图像处理代码的 AI，不用于普通绘图模型：

```text
请从空白输出重新生成最终 512×512 PNG，不要继续裁切或拼补旧的最终 UV 图集。

必须读取 legacy_generic_machine.obj 的真实顶点与 UV 坐标，使用模型空间投影把连续侧板原画映射到 RightSide 和 LeftSide；UV 图集中的相邻关系不能代替模型空间关系。动态屏幕区域保持黑色或极深色。标题由确定性字体工具后期排版，不允许沿用 AI 图像中的伪文字。

特别校正 Back 组的 B18 与 B21：这两个面上“模型向上”对应“图集向右”。希望在游戏里水平显示的灯条、刻度和格栅，必须在图集中画成竖直；有上下方向的图案写入图集前顺时针旋转 90°。后盖不要放可读文字。

输出后必须报告：输出路径、尺寸、像素格式、文件大小、哈希、是否实际进入游戏验证。没有进入游戏时只能写“已生成并检查图集”，不得写“游戏内测试通过”。
```

如果 AI 又开始按矩形缩放旧图，直接回复：

```text
停止使用旧成品图作为切片来源。请回到连续原画和真实 OBJ 坐标，以模型空间重新投影；不要通过肉眼猜测 UV 岛方向。
```
