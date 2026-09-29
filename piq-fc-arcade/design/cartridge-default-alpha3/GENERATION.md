# 通用卡带封面 alpha.3：原始生成记录

2026-09-08，Codex（像素匠）。用户要求默认卡带使用通用封面，不再显示公路赛车。

模式：内置 image_gen；全新生成独立 2:1 标签，不让AI重画完整UV图。原图保留为 `generic-cartridge-label-generated.png`，用户原外壳/触点/背面与自定义封面不变。

提示词如下（原文）：

```text
Use case: stylized-concept
Asset type: production-ready flat default label texture for an FC game cartridge in a Minecraft mod, not a mockup.
Primary request: design a clean generic all-games cartridge label, replacing a specific racing-game label.
Canvas: wide 2:1 rectangle, opaque edge-to-edge artwork; intended final label is 512 by 256 pixels.
Style: crisp retro 8-bit pixel graphic, restrained charcoal and warm ivory with muted gold accents to suit an existing yellow cartridge shell. Strong shapes readable at very small in-game size, subtle pixel texture, not photographic.
Composition: one simple generic retro gamepad emblem and a very large clean pixel-letter "FC", with smaller "GAME CARTRIDGE" underneath. Generous 8 percent safe margin all around, modest thin rectangular printed border.
Text (verbatim): "FC", "GAME CARTRIDGE". Render each once, no other text.
Constraints: only the flat rectangular label fills the image. No cartridge shell, no 3D perspective, no scene, no product photograph, no background outside the label, no transparency. No racing car, no specific video game characters, no ROAD FIGHTER wording, no Nintendo or other company logo, no watermark. Do not imply any bundled game ROM. Keep details simple enough for nearest-neighbor downsampling.
```

原始工具输出：`C:/Users/13498/.codex/generated_images/019fa457-9736-7420-b0dd-b1885755e744/exec-7f52ebac-2ca8-4215-8008-0d534909a4be.png`。选定输出复制到本设计目录；不删除工具原图。

目视：横向通用FC、手柄图案与GAME CARTRIDGE字样，无赛车或游戏IP。

2026-09-08后续用户明确回复“可以”，批准程序规范尺寸并按原UV精确贴入，只替换标签、不动外壳。执行 `tools/normalize_cartridge_label.py --apply`：原1774×887整幅最近邻缩至512×256，不裁切、不调色、不改字。仅组合到1024×1024旧卡带皮肤[32,32,544,288)及4px外扩[28,28,548,292)，边缘使用标签边缘像素钳制延展。所有其他RGBA像素（含透明RGB）与固定alpha.2原图一致；原图和旧皮肤均保留。

- `generic-cartridge-label-512x256.png` SHA256：`B14B8F632FF891487C133FD3974CB9BC7019C830E60E9E552FEF5B30DB4FDFB8`。
- `generic-cartridge-skin-1024.png`及模组默认皮肤 SHA256：`EBD76E0B5A56FC36C3F474CC0E4345377506E6AEA7B2EC14D8FECFECA246CCDA`。
- 原生成图 SHA256：`E0B67298F946D1F80830A48D77527B287C29C773E8838A675825847890D1D761`；旧皮肤 SHA256：`3F071A850BBD033F4311982B7510EA5C214DE4991B64130DC93E0F5A2616F14C`。
- `normalization-review.json`保留精确尺寸/区域/哈希。新默认通过原有卡带渲染入口用于GUI、手持、地面、物品框和插入主机；有效自定义封面完整覆盖同区域，所以合成结果不变，不删除用户封面或缓存。

这是一轮内置image_gen原图加用户许可的确定性后处理，没有CLI/API再生成，也没有重新绘制卡带模型或其他UV岛。
