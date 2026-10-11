# 通用卡带封面 alpha.3：素材来源与处理

2026-09-08，像素匠。默认卡带改用通用封面，不再显示公路赛车。

模式：AI 图像生成（image_gen）；全新生成独立 2:1 标签，不重画完整 UV 图。原图保留为 `generic-cartridge-label-generated.png`，原外壳/触点/背面与自定义封面不变。

素材范围：通用像素风卡带标签，采用深灰、暖白和暗金配色，包含通用手柄图案及 `FC`、`GAME CARTRIDGE` 字样。不包含特定游戏角色或公司标志，也不表示附带游戏 ROM。

目视：横向通用FC、手柄图案与GAME CARTRIDGE字样，无赛车或游戏IP。

2026-09-08，标签规范尺寸后按原 UV 精确贴入，仅替换标签、不动外壳。执行 `tools/normalize_cartridge_label.py --apply`：原1774×887整幅最近邻缩至512×256，不裁切、不调色、不改字。仅组合到1024×1024旧卡带皮肤[32,32,544,288)及4px外扩[28,28,548,292)，边缘使用标签边缘像素钳制延展。所有其他RGBA像素（含透明RGB）与固定alpha.2原图一致；原图和旧皮肤均保留。

- `generic-cartridge-label-512x256.png` SHA256：`B14B8F632FF891487C133FD3974CB9BC7019C830E60E9E552FEF5B30DB4FDFB8`。
- `generic-cartridge-skin-1024.png`及模组默认皮肤 SHA256：`EBD76E0B5A56FC36C3F474CC0E4345377506E6AEA7B2EC14D8FECFECA246CCDA`。
- 原生成图 SHA256：`E0B67298F946D1F80830A48D77527B287C29C773E8838A675825847890D1D761`；旧皮肤 SHA256：`3F071A850BBD033F4311982B7510EA5C214DE4991B64130DC93E0F5A2616F14C`。
- `normalization-review.json`保留精确尺寸/区域/哈希。新默认通过原有卡带渲染入口用于GUI、手持、地面、物品框和插入主机；有效自定义封面完整覆盖同区域，所以合成结果不变，不删除用户封面或缓存。

该素材使用内置 image_gen 原图和确定性后处理，没有CLI/API再生成，也没有重新绘制卡带模型或其他UV岛。
