"""Offline model geometry/UV regression, not Minecraft visual acceptance.

Run: python -B -m unittest discover -s piq-md-home/tools -p test_interaction_models.py -v
Item rotation order and left-hand signs match NeoForge 21.1.236 ItemTransform.apply;
UP face mapping matches Minecraft 1.21.1 FaceInfo/BlockFaceUV/FaceBakery.
"""
import copy
import hashlib
import json
import math
from pathlib import Path
import shutil
import tempfile
import unittest

from interaction_models import (BADGE_PREFIX, BADGES, HANDS, TOP_NAME, CONTROLLER_PARTS,
                                controller_part, derived, prepare, relabel_console)

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'piq-md-home/src/main/resources/assets/piq_md_home'
FC = ROOT / 'piq-fc-arcade/src/main/resources/assets/piq_fc_arcade/models/item/fc_controller.json'
STATES = ('empty', 'inserted', 'empty_borrowed', 'inserted_borrowed')
ORIGINAL_ELEMENT_SHA = {
    'block/md2_empty': '449809b919dbb0f7886628cf30bc8b7e188984a7a792b9945aff5c58434486e5',
    'block/md2_inserted': '9abbae957e1e9a83f997049b248a8a01bdd32942192775769a6d6c1b21ce9562',
    'block/md2_empty_borrowed': 'c23bcb38e6537a5f4a8d17a92ca08fe66cc2ac9f5ff64659400302deb4413c82',
    'block/md2_inserted_borrowed': '1542ef7d7a0b5c042306485002f46c3a753e42b1b29dadd26f9c961b6489c5cb',
    'item/md_controller_mesh': 'e23efe8a5c7bc6eb82932075acce5676d770f56f0b5fe57419c5f627fd6bfe81',
    'item/md_cartridge_mesh': '69f2d366e662f06f57a816c0fcd925500794e172895dd5943a39887494018ee0',
}


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def model(name):
    return read(ASSETS / 'models' / (name + '.json'))


def rotate(v, degrees, left=False):
    # Quaternionf.rotationXYZ means Rx * Ry * Rz: Z acts first on a vector.
    x, y, z = map(math.radians, degrees)
    if left:
        y, z = -y, -z
    a, b, c = v
    a, b = math.cos(z) * a - math.sin(z) * b, math.sin(z) * a + math.cos(z) * b
    a, c = math.cos(y) * a + math.sin(y) * c, -math.sin(y) * a + math.cos(y) * c
    return a, math.cos(x) * b - math.sin(x) * c, math.sin(x) * b + math.cos(x) * c


def transform(v, pose, left=False, direction=False):
    v = rotate(v, pose.get('right_rotation', [0, 0, 0]), left)
    v = tuple(a * b for a, b in zip(v, pose.get('scale', [1, 1, 1])))
    v = rotate(v, pose.get('rotation', [0, 0, 0]), left)
    if not direction:
        t = pose.get('translation', [0, 0, 0])
        v = (v[0] + t[0] * (-1 if left else 1), v[1] + t[1], v[2] + t[2])
    return v


def unit(v):
    size = math.sqrt(sum(a * a for a in v))
    return tuple(a / size for a in v)


def dot(a, b):
    return sum(x * y for x, y in zip(a, b))


def center(element):
    return tuple((a + b) / 2 - 8 for a, b in zip(element['from'], element['to']))


def atlas_rectangle(element, top, direction='up'):
    # Independent inverse of the production UV->world map, both axes reversed.
    uv = top['faces'][direction]['uv']
    points = []
    for endpoint in ('from', 'to'):
        p = element[endpoint]
        u = uv[0] + (p[0] - top['from'][0]) / (top['to'][0] - top['from'][0]) * (uv[2] - uv[0])
        depth = (p[2] - top['from'][2]) / (top['to'][2] - top['from'][2])
        if direction == 'down':
            depth = 1 - depth
        v = uv[1] + depth * (uv[3] - uv[1])
        points.append((u * 64, v * 64))
    return (min(p[0] for p in points), min(p[1] for p in points),
            max(p[0] for p in points), max(p[1] for p in points))


class InteractionModelsTest(unittest.TestCase):
    def assertVector(self, actual, expected, places=7):
        for a, b in zip(actual, expected):
            self.assertAlmostEqual(a, b, places=places)

    def test_four_hands_align_front_up_and_left_with_fc_rig(self):
        pad, fc = model('item/md_controller_mesh'), read(FC)
        for hand in HANDS:
            with self.subTest(hand=hand):
                left = hand.endswith('lefthand')
                pose, target = pad['display'][hand], fc['display'][hand]
                self.assertEqual(pose['rotation'], target['rotation'])
                self.assertEqual(pose['translation'], target['translation'])
                self.assertEqual(pose['scale'], [round(v * 2.8, 8) for v in target['scale']])
                # Actual MD model axes -> the FC rig's front, up, and left.
                for source, desired in (((0, 1, 0), (0, 0, 1)),
                                        ((0, 0, 1), (0, 1, 0)),
                                        ((1, 0, 0), (-1, 0, 0))):
                    self.assertVector(unit(transform(source, pose, left, True)),
                                      unit(transform(desired, target, left, True)))

    def test_actual_buttons_and_cable_are_upright_and_not_swapped(self):
        pad, fc = model('item/md_controller_mesh'), read(FC)
        elements = {e['name']: e for e in pad['elements']}
        dpad = center(elements['p1十字键横臂_横芯'])
        a, b, c = (center(elements[f'p1{key}键_横芯']) for key in 'ABC')
        x, y, z = (center(elements[f'p1{key}键_横芯']) for key in 'XYZ')
        cable = center(elements['p1手柄应力套'])
        self.assertGreater(dpad[0], 0)  # Verify the source assumption, not its name.
        self.assertGreater(cable[2], 0)
        for hand in HANDS:
            with self.subTest(hand=hand):
                left = hand.endswith('lefthand')
                pose, rig = pad['display'][hand], fc['display'][hand]
                right = unit(transform((1, 0, 0), rig, left, True))
                up = unit(transform((0, 1, 0), rig, left, True))
                pts = [transform(p, pose, left) for p in (dpad, a, b, c, x, y, z, cable)]
                self.assertLess(dot(pts[0], right), dot(pts[1], right))
                self.assertLess(dot(pts[1], right), dot(pts[2], right))
                self.assertLess(dot(pts[2], right), dot(pts[3], right))
                for lower, upper in zip(pts[1:4], pts[4:7]):
                    self.assertGreater(dot(upper, up), dot(lower, up))
                self.assertGreater(dot(pts[7], up), max(dot(p, up) for p in pts[:7]))

    def test_old_x_plus_90_bug_is_detected_in_both_person_views(self):
        fc = read(FC)
        for hand in HANDS:
            pose = copy.deepcopy(fc['display'][hand])
            pose['rotation'][0] += 90
            left = hand.endswith('lefthand')
            expected_up = unit(transform((0, 1, 0), fc['display'][hand], left, True))
            expected_left = unit(transform((-1, 0, 0), fc['display'][hand], left, True))
            self.assertLess(dot(unit(transform((0, 0, 1), pose, left, True)), expected_up), 0)
            self.assertLess(dot(unit(transform((1, 0, 0), pose, left, True)), expected_left), 0)

    def test_original_geometry_and_raster_are_unchanged(self):
        for name, expected in ORIGINAL_ELEMENT_SHA.items():
            elements = [e for e in model(name)['elements'] if not derived(e)]
            data = json.dumps(elements, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()
            self.assertEqual(expected, hashlib.sha256(data).hexdigest(), name)
        texture = ASSETS / 'textures/item/md2_cartridge_set.png'
        self.assertEqual('4c84293960060507108acb0fbc36e1170602731eddb04eadc6fbbbf83e9bd136',
                         hashlib.sha256(texture.read_bytes()).hexdigest())

    def badge_cases(self):
        for name in ['block/md2_' + state for state in STATES] + ['item/md_controller_mesh']:
            elements = model(name)['elements']
            for label, (surface, direction, masks, glyphs, bg, ink) in BADGES.items():
                for top in elements:
                    if top['name'] == surface or (label == 'pad' and top['name'] in ('p1' + surface, 'p2' + surface)):
                        prefix = (top['name'][:2] if label == 'pad' else '') + BADGE_PREFIX + label + '_'
                        yield name, elements, top, prefix, direction, masks, glyphs, bg, ink

    def test_logo_masks_cover_e_g_not_s_a_or_neighboring_controls(self):
        for name, elements, top, prefix, direction, masks, *_ in self.badge_cases():
            with self.subTest(model=name, badge=prefix):
                badge = [e for e in elements if e['name'].startswith(prefix)]
                self.assertEqual(33, len(badge))  # 2 covers + 16 O runs + 15 K runs.
                for old, new, rectangle in zip(('E', 'G'), ('O', 'K'), masks):
                    erase = next(e for e in badge if e['name'] == prefix + 'erase_' + old)
                    self.assertVector(atlas_rectangle(erase, top, direction), rectangle, places=5)
                    for face in [e for e in badge if e['name'].startswith(prefix + new + '_')]:
                        x0, y0, x1, y1 = atlas_rectangle(face, top, direction)
                        self.assertTrue(rectangle[0] - 1e-5 <= x0 < x1 <= rectangle[2] + 1e-5)
                        self.assertTrue(rectangle[1] - 1e-5 <= y0 < y1 <= rectangle[3] + 1e-5)
                        self.assertEqual({direction}, set(face['faces']))
                        self.assertNotIn('cullface', face['faces'][direction])
                        self.assertEqual(face['from'][1], face['to'][1])
                        sign = 1 if direction == 'up' else -1
                        self.assertGreater((face['from'][1] - erase['from'][1]) * sign, 0)
                        self.assertLess(abs(face['from'][1] - erase['from'][1]), .01)

    def test_all_console_and_controller_letters_form_o_k_not_mirrored(self):
        expected = [('0111110', '1100011', '1100011', '1100011', '1100011',
                     '1100011', '1100011', '1100011', '0111110'),
                    ('1100011', '1100110', '1101100', '1111000', '1110000',
                     '1111000', '1101100', '1100110', '1100011')]
        for name, elements, top, prefix, direction, _, glyphs, *_ in self.badge_cases():
            for letter, rows, (x, y, width, height) in zip(('O', 'K'), expected, glyphs):
                rects = [atlas_rectangle(e, top, direction) for e in elements if e['name'].startswith(prefix + letter + '_')]
                actual = []
                for row in range(9):
                    cells = ''
                    for col in range(7):
                        px, py = x + (col + .5) * width / 7, y + (row + .5) * height / 9
                        cells += '1' if any(x0 < px < x1 and y0 < py < y1 for x0, y0, x1, y1 in rects) else '0'
                    actual.append(cells)
                self.assertEqual(list(rows), actual, (name, prefix, letter))

    def test_atlas_palette_tints_match_each_original_badge_colors(self):
        for name, elements, _, prefix, direction, _, _, bg, ink in self.badge_cases():
            for suffix, palette, target in (('erase_G', (48, 50, 56), bg), ('K_0_0', (207, 204, 185), ink)):
                face = next(e for e in elements if e['name'] == prefix + suffix)['faces'][direction]
                color = int(face['neoforge_data']['color'], 16)
                self.assertEqual(color >> 24, 255)
                tint = ((color >> 16) & 255, (color >> 8) & 255, color & 255)
                for channel, component, expected in zip(palette, tint, target):
                    self.assertLess(abs(channel * component / 255 - expected), 1, (name, prefix))

    def test_cartridge_actual_cover_faces_player_in_four_hands_without_mirroring(self):
        stub, mesh = model('item/md_cartridge'), model('item/md_cartridge_mesh')
        label = next(e for e in mesh['elements'] if e['name'] == '游戏标签正面')
        self.assertEqual({'north'}, set(label['faces']))
        self.assertEqual(stub['display'], mesh['display'])
        for hand in HANDS:
            left = hand.endswith('lefthand')
            pose = stub['display'][hand]
            original = dict(pose, right_rotation=[0, 0, 0])
            # Real front -Z and text-right -X must match old back +Z / right +X.
            for source, expected in (((0, 0, -1), (0, 0, 1)), ((-1, 0, 0), (1, 0, 0)), ((0, 1, 0), (0, 1, 0))):
                self.assertVector(unit(transform(source, pose, left, True)), unit(transform(expected, original, left, True)))
            # Mutation control: the old pose points the real cover away.
            self.assertLess(dot(unit(transform((0, 0, -1), original, left, True)),
                                unit(transform((0, 0, -1), pose, left, True))), -.99)
            self.assertEqual([0, 2, 0], pose['translation'])
            self.assertEqual([1.2] * 3 if hand.startswith('first') else [.6] * 3, pose['scale'])
        self.assertEqual({'rotation': [25, -35, 0], 'translation': [0, 0, 0],
                          'scale': [2.5] * 3, 'right_rotation': [0, 180, 0]}, stub['display']['gui'])

    def test_cartridge_gui_front_label_is_upright_unmirrored_and_faces_viewer(self):
        stub, mesh = model('item/md_cartridge'), model('item/md_cartridge_mesh')
        pose = stub['display']['gui']
        label = next(e for e in mesh['elements'] if e['name'] == '游戏标签正面')
        self.assertEqual({'north'}, set(label['faces']))
        self.assertEqual(mesh['display']['gui'], pose)
        front = unit(transform((0, 0, -1), pose, direction=True))
        right = unit(transform((-1, 0, 0), pose, direction=True))
        up = unit(transform((0, 1, 0), pose, direction=True))
        self.assertGreater(front[2], .7)
        self.assertGreater(right[0], .8)
        self.assertGreater(up[1], .9)
        self.assertGreater(right[0] * up[1] - right[1] * up[0], .7)
        old = dict(pose, right_rotation=[0, 0, 0])
        self.assertLess(transform((0, 0, -1), old, direction=True)[2], 0)

    def test_all_world_states_and_console_item_resolve_to_patched_models(self):
        variants = read(ASSETS / 'blockstates/md2.json')['variants']
        self.assertEqual(32, len(variants))
        for facing, rotation in (('north', 0), ('east', 90), ('south', 180), ('west', 270)):
            for inserted in (False, True):
                for borrowed in (False, True):
                    for second in (False, True):
                        key = f'facing={facing},inserted={str(inserted).lower()},borrowed={str(borrowed).lower()},borrowed_two={str(second).lower()}'
                        ref = variants[key]
                        self.assertEqual(rotation, ref['y'])
                        elements = model(ref['model'].split(':')[1])['elements']
                        self.assertTrue(any(e['name'] == BADGE_PREFIX + 'top_erase_G' for e in elements))
                        self.assertEqual(not borrowed, any(e['name'].lower().startswith('p1') for e in elements))
                        self.assertEqual(not second, any(e['name'].lower().startswith('p2') for e in elements))
                        for port, present in ((1, not borrowed), (2, not second)):
                            self.assertEqual(33 if present else 0, sum(e['name'].startswith(f'p{port}SOKA_') for e in elements))
                        self.assertFalse(any('SEKA_top_badge_' in e['name'] for e in elements))
        self.assertEqual('piq_md_home:block/md2_empty', model('item/md2')['parent'])

    def test_regeneration_is_byte_idempotent_and_import_postprocess_compatible(self):
        with tempfile.TemporaryDirectory(prefix='md-model-test-') as temporary:
            assets = Path(temporary) / 'assets'
            shutil.copytree(ASSETS / 'models', assets / 'models')
            paths = sorted((assets / 'models').rglob('*.json'))
            expected = {p.relative_to(assets): p.read_bytes() for p in paths}
            # Simulate freshly imported models: no overlay and a normal cartridge.
            for state in STATES:
                path = assets / f'models/block/md2_{state}.json'
                m = read(path)
                m['elements'] = [e for e in m['elements'] if not derived(e)]
                path.write_text(json.dumps(m), encoding='utf-8')
            shutil.copyfile(assets / 'models/item/md_cartridge_mesh.json', assets / 'models/item/md_cartridge.json')
            shutil.copyfile(assets / 'models/item/md_controller_mesh.json', assets / 'models/item/md_controller.json')
            write_console = assets / 'models/item/md2.json'
            write_console.write_text(json.dumps({'parent': 'piq_md_home:block/md2_empty'}), encoding='utf-8')
            for _ in range(2):
                prepare(assets)
                self.assertEqual(expected, {p.relative_to(assets): p.read_bytes() for p in paths})

    def test_relabel_rejects_changed_source_face_rotation(self):
        m = model('block/md2_empty')
        top = next(e for e in m['elements'] if e['name'] == TOP_NAME)
        top['faces']['up']['rotation'] = 90
        with self.assertRaises(ValueError):
            relabel_console(m)

    def test_controller_parts_are_complete_disjoint_and_preserve_exact_geometry(self):
        mesh = model('item/md_controller_mesh')
        original = {e['name']: e for e in mesh['elements']}
        self.assertEqual(len(original), len(mesh['elements']))
        seen = {}
        for part in CONTROLLER_PARTS:
            split = model('item/md_controller_' + part)
            self.assertEqual(mesh['textures'], split['textures'])
            self.assertNotIn('display', split)
            if part != 'body':
                self.assertEqual(12 if part == 'dpad' else 1 if part == 'mode' else 6,
                                 len(split['elements']), part)
            for element in split['elements']:
                self.assertEqual(part, controller_part(element))
                self.assertNotIn(element['name'], seen)
                self.assertEqual(original[element['name']], element)
                seen[element['name']] = element
        self.assertEqual(original, seen)
        body = model('item/md_controller_body')['elements']
        self.assertEqual(33, sum(derived(e) for e in body))
        self.assertTrue(all(not derived(e) for p in CONTROLLER_PARTS[1:]
                            for e in model('item/md_controller_' + p)['elements']))

    def test_controller_stub_keeps_every_display_and_mode_is_real(self):
        stub, mesh = model('item/md_controller'), model('item/md_controller_mesh')
        self.assertEqual('builtin/entity', stub['parent'])
        self.assertEqual('side', stub['gui_light'])
        self.assertNotIn('elements', stub)
        self.assertEqual(mesh['display'], stub['display'])
        self.assertEqual({'rotation': [25, -35, 0], 'translation': [0, 0, 0],
                          'scale': [2.5] * 3}, stub['display']['gui'])
        mode = model('item/md_controller_mode')['elements']
        self.assertEqual('p1MODE键', mode[0]['name'])
        self.assertGreater(mode[0]['from'][2], 8.6)
        self.assertLess(mode[0]['to'][1], 8.2)  # Rear edge, not a fabricated face key.


if __name__ == '__main__':
    unittest.main()
