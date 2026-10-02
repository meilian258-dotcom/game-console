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

from interaction_models import BADGE_PREFIX, HANDS, TOP_NAME, prepare, relabel_console

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'piq-md-home/src/main/resources/assets/piq_md_home'
FC = ROOT / 'piq-fc-arcade/src/main/resources/assets/piq_fc_arcade/models/item/fc_controller.json'
STATES = ('empty', 'inserted', 'empty_borrowed', 'inserted_borrowed')
ORIGINAL_ELEMENT_SHA = {
    'block/md2_empty': '449809b919dbb0f7886628cf30bc8b7e188984a7a792b9945aff5c58434486e5',
    'block/md2_inserted': '9abbae957e1e9a83f997049b248a8a01bdd32942192775769a6d6c1b21ce9562',
    'block/md2_empty_borrowed': 'c23bcb38e6537a5f4a8d17a92ca08fe66cc2ac9f5ff64659400302deb4413c82',
    'block/md2_inserted_borrowed': '1542ef7d7a0b5c042306485002f46c3a753e42b1b29dadd26f9c961b6489c5cb',
    'item/md_controller': 'e23efe8a5c7bc6eb82932075acce5676d770f56f0b5fe57419c5f627fd6bfe81',
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


def atlas_rectangle(element, top):
    # Independent inverse of the production UV->world map, both axes reversed.
    uv = top['faces']['up']['uv']
    points = []
    for endpoint in ('from', 'to'):
        p = element[endpoint]
        u = uv[0] + (p[0] - top['from'][0]) / (top['to'][0] - top['from'][0]) * (uv[2] - uv[0])
        v = uv[1] + (p[2] - top['from'][2]) / (top['to'][2] - top['from'][2]) * (uv[3] - uv[1])
        points.append((u * 64, v * 64))
    return (min(p[0] for p in points), min(p[1] for p in points),
            max(p[0] for p in points), max(p[1] for p in points))


class InteractionModelsTest(unittest.TestCase):
    def assertVector(self, actual, expected, places=7):
        for a, b in zip(actual, expected):
            self.assertAlmostEqual(a, b, places=places)

    def test_four_hands_align_front_up_and_left_with_fc_rig(self):
        pad, fc = model('item/md_controller'), read(FC)
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
        pad, fc = model('item/md_controller'), read(FC)
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
            elements = [e for e in model(name)['elements'] if not e['name'].startswith(BADGE_PREFIX)]
            data = json.dumps(elements, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()
            self.assertEqual(expected, hashlib.sha256(data).hexdigest(), name)
        texture = ASSETS / 'textures/item/md2_cartridge_set.png'
        self.assertEqual('4c84293960060507108acb0fbc36e1170602731eddb04eadc6fbbbf83e9bd136',
                         hashlib.sha256(texture.read_bytes()).hexdigest())

    def test_logo_overlay_covers_only_g_and_leaves_neighboring_letters(self):
        overlays = []
        for state in STATES:
            elements = model('block/md2_' + state)['elements']
            top = next(e for e in elements if e['name'] == TOP_NAME)
            badge = [e for e in elements if e['name'].startswith(BADGE_PREFIX)]
            self.assertTrue(badge)
            overlays.append(badge)
            erase = next(e for e in badge if e['name'].endswith('erase_G'))
            # JSON coordinates round at 1e-8 model units (~6e-7 atlas pixels).
            self.assertVector(atlas_rectangle(erase, top), (262, 346, 280.5, 366), places=6)
            # Bounds of the actual old G plus 1px safety margin; neighboring E
            # ends at x260 and A starts at x281. Badge border is outside this.
            x0, y0, x1, y1 = atlas_rectangle(erase, top)
            self.assertLessEqual(x0, 262.000001)
            self.assertGreaterEqual(x1, 280)
            self.assertGreater(x0, 260)
            self.assertLess(x1, 281)
            self.assertLessEqual(y0, 346.000001)
            self.assertGreaterEqual(y1, 365)
            self.assertGreater(erase['from'][1], top['to'][1])
            for face in badge:
                self.assertEqual({'up'}, set(face['faces']))
                self.assertEqual(face['from'][1], face['to'][1])
                self.assertNotIn('cullface', face['faces']['up'])
                self.assertLess(face['to'][1] - top['to'][1], .01)
                if face is not erase:
                    self.assertGreater(face['from'][1], erase['to'][1])
                    rx0, ry0, rx1, ry1 = atlas_rectangle(face, top)
                    self.assertTrue(x0 <= rx0 < rx1 <= x1)
                    self.assertTrue(y0 <= ry0 < ry1 <= y1)
        self.assertTrue(all(o == overlays[0] for o in overlays))

    def test_logo_strokes_form_k_not_mirrored_g(self):
        elements = model('block/md2_empty')['elements']
        top = next(e for e in elements if e['name'] == TOP_NAME)
        rects = [atlas_rectangle(e, top) for e in elements if e['name'].startswith(BADGE_PREFIX + 'K_')]
        actual = []
        for row in range(9):
            cells = ''
            for col in range(7):
                x, y = 265 + col * 2, 348 + row * 2
                cells += '1' if any(x0 < x < x1 and y0 < y < y1 for x0, y0, x1, y1 in rects) else '0'
            actual.append(cells)
        self.assertEqual(['1100011', '1100110', '1101100', '1111000', '1110000',
                          '1111000', '1101100', '1100110', '1100011'], actual)

    def test_atlas_palette_tints_match_original_badge_colors(self):
        elements = model('block/md2_empty')['elements']
        for suffix, palette, target in (('erase_G', (48, 50, 56), (37, 39, 44)),
                                         ('K_0_0', (207, 204, 185), (182, 181, 172))):
            face = next(e for e in elements if e['name'] == BADGE_PREFIX + suffix)['faces']['up']
            color = int(face['neoforge_data']['color'], 16)
            self.assertEqual(color >> 24, 255)
            tint = ((color >> 16) & 255, (color >> 8) & 255, color & 255)
            for channel, component, expected in zip(palette, tint, target):
                self.assertLess(abs(channel * component / 255 - expected), 1)

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
                        self.assertTrue(any(e['name'] == BADGE_PREFIX + 'erase_G' for e in elements))
                        self.assertEqual(not borrowed, any(e['name'].lower().startswith('p1') for e in elements))
                        self.assertEqual(not second, any(e['name'].lower().startswith('p2') for e in elements))
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
                m['elements'] = [e for e in m['elements'] if not e['name'].startswith(BADGE_PREFIX)]
                path.write_text(json.dumps(m), encoding='utf-8')
            shutil.copyfile(assets / 'models/item/md_cartridge_mesh.json', assets / 'models/item/md_cartridge.json')
            for _ in range(2):
                prepare(assets)
                self.assertEqual(expected, {p.relative_to(assets): p.read_bytes() for p in paths})

    def test_relabel_rejects_changed_source_face_rotation(self):
        m = model('block/md2_empty')
        top = next(e for e in m['elements'] if e['name'] == TOP_NAME)
        top['faces']['up']['rotation'] = 90
        with self.assertRaises(ValueError):
            relabel_console(m)


if __name__ == '__main__':
    unittest.main()
