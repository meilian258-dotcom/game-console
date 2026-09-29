"""Re-run the original UV typesetter with keyboard hints suppressed; geometry is untouched.

This is a deterministic source-asset build, not a repaint of the user's screenshot.
All legacy font metrics, atlas allocation, letter placement, mesh UVs and non-key
labels stay identical. Historical released archives and draft models are read-only.
"""
from pathlib import Path
import hashlib
import io
import json
import numpy as np
from PIL import Image, ImageDraw
from build_subor_reference_model import Model, font

ROOT = Path(__file__).resolve().parents[2]
TARGET = ROOT / 'piq-fc-arcade/src/main/resources/assets/piq_fc_arcade/textures/block/home_subor_sb926.png'
OUT = ROOT / 'outputs/admin58/subor-keycaps'
OLD_SHA = '39A7F6DE2FE4CB669A0CE4E88BFDAE23C234CE7F9A7314A150D13B6EB2A89C4C'

def digest(raw): return hashlib.sha256(raw).hexdigest().upper()

def build():
    original = Model(key_hints=True).build()
    revised = Model(key_hints=False).build()
    assert original.elements == revised.elements and original.groups == revised.groups
    assert original.atlas.tiles == revised.atlas.tiles
    before, after = np.asarray(original.atlas.image), np.asarray(revised.atlas.image)
    changed = np.any(before != after, axis=2)
    allowed = np.zeros(changed.shape, dtype=bool)
    keys = ['键_浅键_' + key + '_拼音' for key in 'QWERTYASDF']
    for name in keys:
        x, y, x1, y1 = original.atlas.tiles[name]
        # Only the sub-label band may change. The original letter ink is exact.
        allowed[y + int((y1-y)*.6):y1, x:x1] = True
    assert changed.any() and not np.any(changed & ~allowed)
    raw = io.BytesIO(); revised.atlas.image.save(raw, format='PNG')
    return original, revised, raw.getvalue(), int(changed.sum()), keys

def main():
    original, revised, raw, pixels, keys = build()
    prior = TARGET.read_bytes()
    assert digest(prior) == OLD_SHA, 'Source texture changed; preserve it and re-review'
    assert np.array_equal(np.asarray(Image.open(io.BytesIO(prior)).convert('RGBA')), np.asarray(original.atlas.image))
    assert not OUT.exists(), 'Preserve previous evidence; do not overwrite it'
    OUT.mkdir(parents=True)
    (OUT / 'original.png').write_bytes(prior)
    preview = Image.new('RGB', (1000, 286), '#ededdf')
    draw = ImageDraw.Draw(preview)
    draw.text((12,8),'键帽 UV 源码生成校验 · 上：原图 / 下：去掉拼音小字',font=font(22),fill='#30372f')
    for row, model in enumerate((original, revised)):
        for column, name in enumerate(keys):
            tile = model.atlas.image.crop(tuple(model.atlas.tiles[name]))
            preview.paste(tile.resize((96,96),Image.Resampling.NEAREST),(12+column*108,54+row*112))
    preview.save(OUT / 'comparison.png')
    TARGET.write_bytes(raw)
    assert TARGET.read_bytes() == raw
    report = dict(ok=True, old_sha256=OLD_SHA, new_sha256=digest(raw), changed_pixels=pixels,
                  changed_keycaps=len(keys), geometry_and_uv_unchanged=True,
                  english_letters_and_other_pixels_exact=True, source='original procedural UV typesetter')
    (OUT / 'verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(report,ensure_ascii=False))

if __name__ == '__main__': main()
