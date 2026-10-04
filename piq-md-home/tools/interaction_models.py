"""Idempotent production JSON adaptations; original models/atlas stay editable and intact.

NeoForge 21.1's ItemTransform applies rotation * scale * right_rotation.
Keep FC's outer hand rig and align the native MD axes in that inner rotation.
SOKA badges use native model faces and atlas swatches; source PNG stays intact.
"""
from pathlib import Path
import json
import copy


HANDS = ('firstperson_righthand', 'firstperson_lefthand',
         'thirdperson_righthand', 'thirdperson_lefthand')
CONTROLLER_PARTS = ('body', 'dpad', 'a', 'b', 'c', 'x', 'y', 'z', 'start', 'mode')
BADGE_PREFIX = 'SOKA_'
LEGACY_BADGE_PREFIX = 'SEKA_top_badge_'
TOP_NAME = '上盖窄收边_横芯'
# Pixel coordinates on the untouched 1024px atlas, not Minecraft model units.
# The top G is x=263..279, y=347..364; retain S/A and the badge border.
K_ROWS = ('1100011', '1100110', '1101100', '1111000', '1110000',
          '1111000', '1101100', '1100110', '1100011')
O_ROWS = ('0111110', '1100011', '1100011', '1100011', '1100011',
          '1100011', '1100011', '1100011', '0111110')
# Only E/G are masked: the original S/A, borders and nearby inscriptions stay.
# (surface, direction, E/G masks, O/K origins and pixel sizes, background, ink)
BADGES = {
    'top': (TOP_NAME, 'up', ((246, 346, 262, 366), (262, 346, 280.5, 366)),
            ((247, 347, 14, 18), (264, 347, 14, 18)), (37, 39, 44), (182, 181, 172)),
    'pad': ('上盖连续中桥_纵芯', 'up', ((241, 598, 253.5, 614), (253.5, 598, 267.5, 614)),
            ((242, 599, 10.5, 14), (254, 599, 12.5, 14)), (48, 50, 56), (153, 155, 160)),
    'bottom': ('底面产品铭牌', 'down', ((662, 268, 677, 288), (678, 268, 695.5, 288)),
               ((663, 269, 13, 18), (679, 269, 14, 18)), (23, 25, 30), (174, 176, 180)),
}


def derived(element):
    # Docked overlays retain p1/p2 prefixes so existing borrowed-state filters
    # remove the complete controller, including its label.
    name = element.get('name', '')
    return any(name.startswith(p) for p in (BADGE_PREFIX, LEGACY_BADGE_PREFIX,
                                            'p1' + BADGE_PREFIX, 'p2' + BADGE_PREFIX))


def write_model(path, model):
    path.write_text(json.dumps(model, ensure_ascii=False, separators=(',', ':')),
                    encoding='utf-8', newline='\n')


def badge_face(top, name, bounds, lift, ink=False, direction='up', rgb=None):
    """Map an artwork rectangle to its actual UP/DOWN-face UV.

    The checked source face has no rotation. FaceBakery's UP vertices map
    min-X/min-Z to UV[0:2] and max-X/max-Z to UV[2:4]. Do not guess its center.
    Large palette swatches plus a baked vertex color match the old badge's
    RGB within one level; broad uniform swatches avoid neighboring glyph bleed.
    """
    face = top['faces'][direction]
    if 'rotation' in top or face.get('rotation', 0) != 0:
        raise ValueError('top badge needs its unrotated source face')
    u0, v0, u1, v1 = face['uv']
    x0, y0, z0 = top['from']
    x1, y1, z1 = top['to']
    x = [x0 + (u / 64 - u0) / (u1 - u0) * (x1 - x0) for u in bounds[::2]]
    # DOWN's V=0 vertex is max-Z, unlike UP's min-Z (FaceInfo/FaceBakery).
    za, zb = (z0, z1) if direction == 'up' else (z1, z0)
    z = [za + (v / 64 - v0) / (v1 - v0) * (zb - za) for v in bounds[1::2]]
    # Palette interiors: dark grey (48,50,56), cream (207,204,185).
    uv, palette = (([10.0625, 1, 10.1875, 1.125], (207, 204, 185)) if ink else
                   ([9.375, .3125, 9.5, .4375], (48, 50, 56)))
    rgb = rgb or ((182, 181, 172) if ink else (37, 39, 44))
    color = 'ff' + ''.join(f'{round(wanted * 255 / source):02x}' for wanted, source in zip(rgb, palette))
    y = round(y1 + lift if direction == 'up' else y0 - lift, 8)
    return {'name': name,
            'from': [round(min(x), 8), y, round(min(z), 8)],
            'to': [round(max(x), 8), y, round(max(z), 8)],
            'faces': {direction: {'texture': '#0', 'uv': uv,
                             'neoforge_data': {'color': color}}}}


def relabel(model, console=True):
    model['elements'] = [e for e in model['elements'] if not derived(e)]
    for label, (surface, direction, masks, glyphs, bg, ink) in BADGES.items():
        names = [surface] if label != 'pad' else ['p1' + surface, 'p2' + surface]
        if not console and label != 'pad':
            continue
        for source_name in names:
            top = next((e for e in model['elements'] if e.get('name') == source_name), None)
            if top is None:
                if label == 'pad':  # Borrowed controllers have no remaining surface.
                    continue
                raise ValueError('missing SOKA label surface: ' + source_name)
            prefix = (source_name[:2] if label == 'pad' else '') + BADGE_PREFIX + label + '_'
            for letter, mask in zip(('E', 'G'), masks):
                model['elements'].append(badge_face(top, prefix + 'erase_' + letter, mask, .003,
                                                  direction=direction, rgb=bg))
            for letter, rows, (x, y, width, height) in zip(('O', 'K'), (O_ROWS, K_ROWS), glyphs):
                for row, cells in enumerate(rows):
                    col = 0
                    while col < len(cells):
                        if cells[col] == '0':
                            col += 1
                            continue
                        start = col
                        while col < len(cells) and cells[col] == '1':
                            col += 1
                        bounds = (x + start * width / 7, y + row * height / 9,
                                  x + col * width / 7, y + (row + 1) * height / 9)
                        model['elements'].append(badge_face(top, prefix + f'{letter}_{row}_{start}', bounds,
                                                          .006, True, direction, ink))


def relabel_console(model):
    relabel(model)


def face_cartridge_forward(card):
    # Its real cover is on NORTH (-Z), not SOUTH; rotate only handheld contexts.
    # A proper rotation keeps glyphs upright/not mirrored and preserves GUI,
    # ground/fixed transforms, the actual cover plane and inserted world mesh.
    for hand in HANDS:
        right = hand.replace('lefthand', 'righthand')
        pose = copy.deepcopy(card['display'][right])
        pose['right_rotation'] = [0, 180, 0]
        card['display'][hand] = pose


def controller_part(element):
    """Partition by the exporter's explicit cap names, never by nearby shell bounds."""
    name = element.get('name', '')
    if name.startswith('p1十字键'):
        return 'dpad'
    for key in 'ABCXYZ':
        if name.startswith('p1' + key + '键_'):
            return key.lower()
    if name.startswith('p1开始键_'):
        return 'start'
    if name == 'p1MODE键':
        return 'mode'
    return 'body'


def split_controller(items, pad):
    # Keep a full neutral mesh for icons/ground/fixed and deterministic reimport.
    # Animated models contain disjoint original elements, including original UVs,
    # lettering and rotation origins; static SOKA stays with the body.
    write_model(items / 'md_controller_mesh.json', pad)
    for part in CONTROLLER_PARTS:
        model = {key: copy.deepcopy(value) for key, value in pad.items()
                 if key not in ('elements', 'display')}
        model['elements'] = [copy.deepcopy(e) for e in pad['elements'] if controller_part(e) == part]
        expected = 12 if part == 'dpad' else 1 if part == 'mode' else 6
        if part != 'body' and len(model['elements']) != expected:
            raise ValueError('MD controller cap group changed: ' + part)
        write_model(items / ('md_controller_' + part + '.json'), model)
    stub = {'parent': 'builtin/entity', 'gui_light': 'side',
            'textures': {'particle': 'piq_md_home:item/md2_cartridge_set'},
            'display': copy.deepcopy(pad['display'])}
    (items / 'md_controller.json').write_text(json.dumps(stub, ensure_ascii=False, indent=2) + '\n',
                                             encoding='utf-8', newline='\n')


def prepare(assets):
    items = assets / 'models/item'
    card = json.loads((items / 'md_cartridge.json').read_text(encoding='utf-8'))
    if 'elements' in card:
        face_cartridge_forward(card)
        write_model(items / 'md_cartridge_mesh.json', card)
        stub = {'parent': 'builtin/entity', 'gui_light': 'front', 'textures': {'particle': 'piq_md_home:item/md2_cartridge_set'}, 'display': card['display']}
        (items / 'md_cartridge.json').write_text(json.dumps(stub, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    else:
        face_cartridge_forward(card)
        (items / 'md_cartridge.json').write_text(json.dumps(card, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
        mesh = json.loads((items / 'md_cartridge_mesh.json').read_text(encoding='utf-8'))
        face_cartridge_forward(mesh)
        write_model(items / 'md_cartridge_mesh.json', mesh)
    pad = json.loads((items / 'md_controller.json').read_text(encoding='utf-8'))
    if 'elements' not in pad:
        pad = json.loads((items / 'md_controller_mesh.json').read_text(encoding='utf-8'))
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
    relabel(pad, console=False)
    split_controller(items, pad)
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
