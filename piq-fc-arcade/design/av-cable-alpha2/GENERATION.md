# AV 线像素物品草案（alpha.2）

- 日期：2026-09-08
- 制作者：client_worker（imagegen 技能）
- 模式：内置 image_gen；全新生成；无参考图片；未使用 CLI/API 备用路径。
- 原始工具文件：C:/Users/13498/.codex/generated_images/01a0778d-9f8e-7410-b609-235e37a71d29/exec-2b5ac538-6894-4863-a09b-f6c42505c4c7.png
- 工作区原样副本：av-cable-generated-original.png
- 尺寸 / 模式：1254 × 1254 / RGBA
- 文件字节：440417
- SHA-256：0898A906507FC7D2296B7973D87F9117FCFD54B9093DF4E1F0A2699E0F77B66F
- Alpha 范围：0–255；全透明像素 1201569；全不透明像素 4241；部分透明像素 366706。
- Alpha 非零包围盒（右、下不含）：[49, 51, 1194, 1228]。

## 视觉与接入状态

已检查生成结果：单根黑色盘绕线，两组末端各为黄、白、红 RCA 插头，共六头；无文字、场景或绘制的棋盘格；主体完整并有透明留白。

这是生成原稿，不是合格的最终 64 × 64 游戏资源。工具未遵从实际 64 × 64 尺寸，输出还存在大量部分透明像素，不能声称已达到严格二值 Alpha / 硬像素规范。后续规范化须获得主代理转达的用户许可，并复核六头在 64 × 64 下的辨识度。当前没有程序缩放、阈值化、重绘、覆盖原贴图、修改资源引用或写入 JAR；原始生成文件和现有游戏文件均保留。

## 2026-09-08 后处理许可与最终接入

用户随后明确回复“可以”，授权程序规范为 64 × 64 透明 PNG，并一同打包。此前“未规范化”的描述为生成阶段历史状态，本节为最终状态。

- 处理方式：`tools/normalize_av_cable_sprite.py`；Pillow 最近邻完整画布缩放；alpha ≥ 128 设为 255、其余设为 0；仅将全透明像素 RGB 清零。没有裁切、重绘、调色或重新调用 imagegen/API/CLI。
- 原稿 SHA-256 仍为 `0898A906507FC7D2296B7973D87F9117FCFD54B9093DF4E1F0A2699E0F77B66F`，原文件未变。
- 最终资源：`src/main/resources/assets/piq_fc_arcade/textures/item/av_cable.png`；64 × 64 RGBA，3215 字节；SHA-256 `8F0F976A6EC675A3A501549FDFC543C74C28CE7D70359FC9F38759CC57CD594E`。
- `models/item/av_cable.json` 只将 layer0 从原版 lead 改为 `piq_fc_arcade:item/av_cable`，parent 未变。
- 原生图、512 最近邻预览及机器可读报告保存在 [AV线像素图-alpha2](G:/服务器/服务器Codex/制作Mod/03-街机模拟/PIQ-FC街机/AV线像素图-alpha2/GENERATION.md)。最终原提示词保留在下节，未改写。
- 目视检查：六枚黄白红插头与盘线仍可辨，无裁切、无附加背景；全透明 3201 像素、不透明 895 像素、部分透明 0；左/上/右/下留白分别 4/14/3/11 像素。

## 最终提交提示词

```text
Use case: stylized-concept
Asset type: Minecraft inventory item sprite, transparent PNG.
Primary request: One black coiled analog AV/RCA cable, with exactly TWO ends. Each end splits into exactly THREE male RCA plugs: one yellow, one white, and one red. There must be exactly SIX visible plug heads total, in two clearly separated groups of yellow-white-red. This is one complete cable, not a leash, rope, adapter, pair of cables, or disconnected plugs.
Scene/backdrop: Genuinely transparent background with real PNG alpha, including the open spaces inside the coil. Do not paint a checkerboard, solid backdrop, ground, or shadow.
Style/medium: Authentic coarse Minecraft pixel-art item texture, designed on a strict 64 by 64 logical square-pixel grid. Very clear large blocky pixels and hard stepped pixel edges. Limited flat palette: black and a few charcoal shades for the insulated coil, bright yellow/red/off-white plug sleeves, simple flat gray metal tips. No smoothing, antialiasing, blur, gradients, photorealism, painterly strokes, microtexture, or glossy 3D render.
Composition/framing: A compact readable loose black cable coil with the two triple-plug ends both visible outside the coil and easily countable. Entire cable and all six tips fit within the square canvas. Centered with generous transparent margin on every side; no cropping.
Constraints: Only the single cable item. No text, letters, numbers, logos, watermark, border, UI, scene, checkerboard pattern, or decorative elements. Preserve genuine transparency. Request an actual 64x64 PNG if supported; otherwise retain the strict coarse 64x64 logical pixel-grid design in the generated square output without adding detail.
```
