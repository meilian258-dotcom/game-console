"""User-approved, exact-UV brand replacements; never resample an atlas or change geometry.

The approved FC37 archives are read-only provenance. Current release PNGs are the
editable asset sources for ordinary Gradle builds; no old ROM/model ZIP is needed.
Re-running this historical derivation requires the pinned private FC37 baseline.
Fonts are rendered locally, not embedded or redistributed as font files.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
from pathlib import Path
import zipfile

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / 'piq-fc-arcade/build/review-runtime37-v1'
ARCHIVES = {
    'fc': ('piq_fc_arcade-0.31.0-alpha.37.jar', '809A4FEE17B8805C642C4DF1A0272B5C369D02B179A98E41253262E268ACD406'),
    'sfc': ('piq_sfc-0.1.0-alpha.21.jar', '62A53BEF68D9D181DFEC74E2926F6D431F61D6CD8B989C62DF36593771C94774'),
    'gba': ('piq_gba-0.1.0-alpha.5.jar', 'DCB0F05BF4CD3880430DB9985B6E37DA1B116509A4B7A0E84F59CCCAF679BDB1'),
}
PROJECTS = {'fc': 'piq-fc-arcade', 'sfc': 'piq-sfc-home', 'gba': 'piq-gba'}


def region(box, background, lines, accent=False):
    return dict(box=box, background=background, lines=lines, accent=accent)


# UV rectangles come from actual model faces / supplied atlas region metadata.
# Text boxes are local to each rectangle and must remain inside it.
# (text, local x/y/right/bottom, maximum point size, colour)
SPECS = [
    dict(kind='fc', entry='assets/piq_fc_arcade/textures/block/home_famicom_console.png',
         before='5EA8EE7C97F54BC0FC83949FDC7F196C4F053A798EC98B1AEF9D18579D852935', size=(1024, 1024),
         regions=[region((16, 897, 1008, 1021), '#aa1c2b', [
             ('PIQ', (38, 22, 200, 88), 60, '#e1d38c'),
             ('8-BIT COMPUTER', (224, 24, 944, 89), 48, '#e1d38c'),
         ], True)]),
    dict(kind='fc', entry='assets/piq_fc_arcade/textures/block/home_subor_sb926.png',
         before='39A7F6DE2FE4CB669A0CE4E88BFDAE23C234CE7F9A7314A150D13B6EB2A89C4C', size=(2048, 2048),
         regions=[
             region((8, 528, 520, 656), '#d7d3ad', [('PIQ', (20, 12, 492, 116), 88, '#a13935')]),
             region((528, 528, 1168, 608), '#d7d3ad', [('PIQ LEARNING COMPUTER', (10, 10, 630, 70), 36, '#414339')]),
             region((1176, 528, 1496, 592), '#d7d3ad', [('PIQ 8-BIT', (10, 8, 310, 56), 36, '#414339')]),
         ]),
    dict(kind='fc', entry='assets/piq_fc_arcade/textures/item/zapper/skin.png',
         before='AB5C925B7BD21AD2CBBFCC96A0F38CC4FE6C4E9013BCE57DF8F866E9EA16167F', size=(2048, 2048),
         regions=[
             region((32, 32, 662, 203), '#abb0b0', [
                 ('PIQ', (20, 14, 610, 60), 30, '#4e585b'),
                 ('LIGHT GUN', (20, 65, 610, 153), 64, '#4e585b')]),
             region((708, 32, 1344, 189), '#969c9f', [
                 ('PIQ LIGHT GUN', (20, 12, 616, 77), 40, '#566166'),
                 ('8-BIT LIGHT CONTROLLER', (20, 86, 616, 117), 24, '#566166'),
                 ('STYLIZED DISPLAY MODEL', (20, 122, 616, 146), 18, '#566166')]),
             region((32, 1064, 654, 1190), '#343d45', [
                 ('PIQ LIGHT GUN', (40, 16, 604, 77), 43, '#c8d0d3'),
                 ('LIGHT CONTROLLER / 8-BIT', (40, 86, 604, 113), 20, '#aebabe')], True),
         ]),
    dict(kind='sfc', entry='assets/piq_sfc_home/textures/block/user_sfc_20260911.png',
         before='4BBBA0F53697D69A919F5FC12750E608AA50A4D71D8281F23D6428A5D02BD920', size=(2048, 2048),
         regions=[
             region((566, 563, 1072, 611), '#e0e1db', [('PIQ 16-BIT CONSOLE', (8, 4, 498, 44), 28, '#474e52')]),
             region((1080, 563, 1366, 611), '#c9cbc7', [('PIQ 16-BIT', (8, 4, 278, 44), 28, '#474e52')]),
         ]),
    dict(kind='gba', entry='assets/piq_gba/textures/item/handheld.png',
         before='376FB935DEB9D6F5F4682A24FC4DF94D5EF9A5793D14B4255F573FE6FF921BCC', size=(2048, 2048),
         regions=[
             region((1640, 32, 1910, 108), '#22232e', [('PIQ', (10, 8, 260, 68), 46, '#abaebc')]),
             region((880, 534, 1580, 629), '#22232e', [('PIQ POCKET', (18, 12, 682, 83), 50, '#c9cad4')]),
             # Only the two branded lines; power/safety lines and rounded border stay untouched.
             region((44, 527, 720, 613), '#c7c8c3', [
                 ('PIQ POCKET', (12, 0, 664, 41), 29, '#555d61'),
                 ('MODEL NO. PIQ-32', (12, 44, 664, 83), 25, '#555d61')]),
         ]),
]


def sha(raw):
    return hashlib.sha256(raw).hexdigest().upper()


def fit_text(draw, text, box, size, colour, font_path):
    x0, y0, x1, y1 = box
    for point in range(size, 5, -1):
        font = ImageFont.truetype(str(font_path), point)
        left, top, right, bottom = draw.textbbox((0, 0), text, font=font)
        if right-left <= x1-x0 and bottom-top <= y1-y0:
            draw.text((x0 + (x1-x0-right+left)/2-left,
                       y0 + (y1-y0-bottom+top)/2-top), text, font=font, fill=colour)
            return
    raise ValueError('Text does not fit approved UV region: ' + text)


def changed_atlas(raw, spec, font_path):
    original = Image.open(io.BytesIO(raw))
    assert original.mode == 'RGBA' and original.size == spec['size']
    image = original.copy()
    mask = np.zeros((image.height, image.width), dtype=bool)
    for patch in spec['regions']:
        x0, y0, x1, y1 = patch['box']
        assert 0 <= x0 < x1 <= image.width and 0 <= y0 < y1 <= image.height
        assert not mask[y0:y1, x0:x1].any(), 'Overlapping approved patches'
        layer = Image.new('RGBA', (x1-x0, y1-y0), patch['background'])
        draw = ImageDraw.Draw(layer)
        for text, box, size, colour in patch['lines']:
            assert 0 <= box[0] < box[2] <= layer.width and 0 <= box[1] < box[3] <= layer.height
            fit_text(draw, text, box, size, colour, font_path)
        if patch['accent']:
            if spec['entry'].endswith('home_famicom_console.png'):
                draw.line((0, 91, layer.width-1, 91), fill='#e1d38c', width=3)
            else:
                draw.rectangle((20, 16, 24, layer.height-20), fill='#f07826')
        layer.putalpha(original.crop((x0, y0, x1, y1)).getchannel('A'))
        image.paste(layer, (x0, y0))
        mask[y0:y1, x0:x1] = True
    before, after = np.asarray(original), np.asarray(image)
    assert np.array_equal(before[~mask], after[~mask]), 'Unexpected pixel outside approved region'
    assert np.array_equal(before[:, :, 3], after[:, :, 3]), 'Alpha changed'
    result = io.BytesIO()
    image.save(result, format='PNG', compress_level=9)
    return result.getvalue(), image, int(np.any(before != after, axis=2).sum()), original


def exclusive(path, raw):
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        assert path.read_bytes() == raw, 'Refusing conflicting artifact ' + str(path)
    else:
        with path.open('xb') as stream:
            stream.write(raw)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--font', type=Path, default=Path('C:/Windows/Fonts/arialbd.ttf'))
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    out = args.output.resolve()
    assert out.is_relative_to(ROOT / 'outputs/release38'), 'Bounded output required'
    assert args.font.is_file(), 'Explicit local font required'
    entries = {}
    for kind, (name, pin) in ARCHIVES.items():
        path = BASE / name
        assert sha(path.read_bytes()) == pin, 'Frozen baseline drift'
        with zipfile.ZipFile(path) as jar:
            entries[kind] = {s['entry']: jar.read(s['entry']) for s in SPECS if s['kind'] == kind}
    operations, assets, staged = [], [], []
    previews = []
    for spec in SPECS:
        raw = entries[spec['kind']][spec['entry']]
        assert sha(raw) == spec['before']
        new, image, changed, original = changed_atlas(raw, spec, args.font)
        production = ROOT / PROJECTS[spec['kind']] / 'src/main/resources' / spec['entry']
        assert production.read_bytes() in (raw, new), 'Unreviewed user edit ' + str(production)
        relative = production.relative_to(ROOT).as_posix()
        exclusive(out / 'assets' / spec['kind'] / spec['entry'], new)
        staged.append((production, new))
        operations.append(dict(kind=spec['kind'], entry=spec['entry'], action='replace', category='texture',
                               source=relative, before_sha256=sha(raw), after_sha256=sha(new),
                               reason='User-approved exact UV replacement of external brand marks by PIQ labels'))
        assets.append(dict(source=relative, before_sha256=sha(raw), after_sha256=sha(new),
                           size=spec['size'], regions=spec['regions'], changed_pixels=changed,
                           outside_regions_identical=True, alpha_identical=True))
        for i, patch in enumerate(spec['regions']):
            previews.append((spec['kind'] + ' / ' + production.stem + ' / ' + str(i+1),
                             image.crop(patch['box'])))
    sheet = Image.new('RGB', (1100, 95 + len(previews) * 175), '#e8ecef')
    draw = ImageDraw.Draw(sheet)
    title = ImageFont.truetype(str(args.font), 27)
    caption = ImageFont.truetype(str(args.font), 16)
    draw.text((28, 22), 'PIQ / ORIGINAL LABELS / EXACT UV PATCHES', font=title, fill='#243039')
    for index, (name, patch) in enumerate(previews):
        y = 82 + index * 175
        draw.text((28, y), name, font=caption, fill='#354149')
        # Preview only: production images are never resized.
        patch.thumbnail((1000, 130), Image.Resampling.LANCZOS)
        sheet.paste(patch, (28, y+30), patch)
    buffer = io.BytesIO(); sheet.save(buffer, format='PNG')
    exclusive(out / 'label-preview.png', buffer.getvalue())
    if args.apply:
        for path, new in staged:
            path.write_bytes(new)  # Explicitly user-authorized raster replacement; source code uses apply_patch.
    report = dict(schema='piq-release38-textures-1', ok=True, applied=args.apply,
                  font_name=args.font.name, font_sha256=sha(args.font.read_bytes()), font_distributed=False,
                  asset_count=len(assets), assets=assets, operations=operations,
                  geometry_changed=False, protocol_changed=False, rom_sharing_changed=False,
                  imagegen_used=False, exact_script_authorized=True,
                  legal_clearance=False, minecraft_rendered=False)
    exclusive(out / 'texture-report.json', (json.dumps(report, ensure_ascii=False, indent=2)+'\n').encode())
    print(json.dumps(dict(ok=True, applied=args.apply, assets=len(assets), output=str(out)), ensure_ascii=False))


if __name__ == '__main__':
    main()
