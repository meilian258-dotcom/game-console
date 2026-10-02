"""Idempotent production JSON adaptations; original models/atlas stay editable and intact.

NeoForge 21.1's ItemTransform applies rotation * scale * right_rotation.
Keep FC's outer hand rig and align the native MD axes in that inner rotation.
The top badge changes only G to K, using native model faces and atlas swatches.
"""
from pathlib import Path
import json
import copy


HANDS = ('firstperson_righthand', 'firstperson_lefthand',
         'thirdperson_righthand', 'thirdperson_lefthand')
BADGE_PREFIX = 'SEKA_top_badge_'
TOP_NAME = '上盖窄收边_横芯'
# Pixel coordinates on the untouched 1024px atlas, not Minecraft model units.
# The G is x=263..279, y=347..364; S/E/A and the badge border remain original.
BADGE_ERASE = (262, 346, 280.5, 366)
K_ROWS = ('1100011', '1100110', '1101100', '1111000', '1110000',
          '1111000', '1101100', '1100110', '1100011')


def write_model(path, model):
    path.write_text(json.dumps(model, ensure_ascii=False, separators=(',', ':')),
                    encoding='utf-8', newline='\n')


def badge_face(top, name, bounds, lift, ink=False):
    """Map a rectangle in the top artwork to its actual reversed UP-face UV.

    The checked source face has no rotation. FaceBakery's UP vertices map
    min-X/min-Z to UV[0:2] and max-X/max-Z to UV[2:4]. Do not guess its center.
    Large palette swatches plus a baked vertex color match the old badge's
    RGB (37,39,44) / ink (182,181,172) within one level, including mipmaps.
    """
    face = top['faces']['up']
    if 'rotation' in top or face.get('rotation', 0) != 0:
        raise ValueError('top badge needs its unrotated source face')
    u0, v0, u1, v1 = face['uv']
    x0, _, z0 = top['from']
    x1, y1, z1 = top['to']
    x = [x0 + (u / 64 - u0) / (u1 - u0) * (x1 - x0) for u in bounds[::2]]
    z = [z0 + (v / 64 - v0) / (v1 - v0) * (z1 - z0) for v in bounds[1::2]]
    # Palette interiors: dark grey (48,50,56), cream (207,204,185).
    uv, color = (([10.0625, 1, 10.1875, 1.125], 'ffe0e2ed') if ink else
                 ([9.375, .3125, 9.5, .4375], 'ffc5c7c8'))
    y = round(y1 + lift, 8)
    return {'name': BADGE_PREFIX + name,
            'from': [round(min(x), 8), y, round(min(z), 8)],
            'to': [round(max(x), 8), y, round(max(z), 8)],
            'faces': {'up': {'texture': '#0', 'uv': uv,
                             'neoforge_data': {'color': color}}}}


def relabel_console(model):
    # Remove only our own derived faces, so prepare() and import+prepare agree.
    model['elements'] = [e for e in model['elements']
                         if not e.get('name', '').startswith(BADGE_PREFIX)]
    top = next(e for e in model['elements'] if e.get('name') == TOP_NAME)
    model['elements'].append(badge_face(top, 'erase_G', BADGE_ERASE, .003))
    # Native 2px-wide strokes; the retained S/E/A preserve the original logo.
    for row, cells in enumerate(K_ROWS):
        col = 0
        while col < len(cells):
            if cells[col] == '0':
                col += 1
                continue
            start = col
            while col < len(cells) and cells[col] == '1':
                col += 1
            bounds = (264 + start * 2, 347 + row * 2, 264 + col * 2, 349 + row * 2)
            model['elements'].append(badge_face(top, f'K_{row}_{start}', bounds, .006, True))


def prepare(assets):
    items = assets / 'models/item'
    card = json.loads((items / 'md_cartridge.json').read_text(encoding='utf-8'))
    if 'elements' in card:
        write_model(items / 'md_cartridge_mesh.json', card)
        stub = {'parent': 'builtin/entity', 'gui_light': 'front', 'textures': {'particle': 'piq_md_home:item/md2_cartridge_set'}, 'display': card['display']}
        (items / 'md_cartridge.json').write_text(json.dumps(stub, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    pad = json.loads((items / 'md_controller.json').read_text(encoding='utf-8'))
    root = Path(__file__).resolve().parents[2]
    fc = json.loads((root / 'piq-fc-arcade/src/main/resources/assets/piq_fc_arcade/models/item/fc_controller.json').read_text(encoding='utf-8'))
    for hand in HANDS:
        pose = json.loads(json.dumps(fc['display'][hand]))
        # MD: front +Y, cable/top +Z, D-pad +X. FC rig: +Z, +Y, -X.
        # Rx(90) Ry(180) maps (x,y,z) -> (-x,z,y), with determinant +1:
        # no mirrored lettering, and it works with ItemTransform's left-hand
        # Y/Z sign inversion. Adding 90 to an Euler X angle instead only
        # fixes the face normal while reversing up/left and miscomposing third-person.
        pose['right_rotation'] = [90, 180, 0]
        pose['scale'] = [round(v * 2.8, 8) for v in pose['scale']]
        pad['display'][hand] = pose
    write_model(items / 'md_controller.json', pad)
    for state in ('empty', 'inserted', 'empty_borrowed', 'inserted_borrowed'):
        path = assets / f'models/block/md2_{state}.json'
        model = json.loads(path.read_text(encoding='utf-8'))
        relabel_console(model)
        write_model(path, model)
    # Derive the second controller independently; never duplicate/hide the other port.
    for state in ('empty', 'inserted'):
        for first in (False, True):
            stem = state + ('_borrowed' if first else '')
            model = json.loads((assets / f'models/block/md2_{stem}.json').read_text(encoding='utf-8'))
            model['elements'] = [e for e in model['elements'] if not e.get('name', '').lower().startswith('p2')]
            write_model(assets / f'models/block/md2_{stem}_borrowed_two.json', model)
    variants = {}
    for facing, turn in (('north', 0), ('east', 90), ('south', 180), ('west', 270)):
        for inserted in (False, True):
            for first in (False, True):
                for second in (False, True):
                    key = f'facing={facing},inserted={str(inserted).lower()},borrowed={str(first).lower()},borrowed_two={str(second).lower()}'
                    stem = ('inserted' if inserted else 'empty') + ('_borrowed' if first else '') + ('_borrowed_two' if second else '')
                    variants[key] = {'model': 'piq_md_home:block/md2_' + stem, 'y': turn}
    (assets / 'blockstates').mkdir(parents=True, exist_ok=True)
    write_model(assets / 'blockstates/md2.json', {'variants': variants})


if __name__ == '__main__':
    prepare(Path(__file__).resolve().parents[1] / 'src/main/resources/assets/piq_md_home')
