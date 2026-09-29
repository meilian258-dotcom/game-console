"""Offline asset-level regression checks for the FC59 Subor typesetting build.

Run against an immutable generator evidence directory, optionally comparing the
approved candidates with production assets after --apply. No game launch needed.
"""
import argparse
import copy
import io
import json
from pathlib import Path
import unittest

import numpy as np
from PIL import Image

import rebuild_subor_keycaps59 as build59

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--evidence', type=Path, required=True)
parser.add_argument('--production', action='store_true')
parser.add_argument('--output', type=Path)
OPTIONS, TEST_ARGS = parser.parse_known_args()


class SuborKeycaps59Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.report = json.loads((OPTIONS.evidence/'verification.json').read_text(encoding='utf-8'))
        cls.before = {n:(OPTIONS.evidence/'before'/n).read_bytes() for n in build59.EXPECTED}
        cls.after = {n:(OPTIONS.evidence/'candidate'/n).read_bytes() for n in build59.EXPECTED}
        cls.old = np.asarray(Image.open(io.BytesIO(cls.before[build59.TEXTURE])).convert('RGBA'))
        cls.new = np.asarray(Image.open(io.BytesIO(cls.after[build59.TEXTURE])).convert('RGBA'))

    def test_frozen_input_and_report_hashes(self):
        for row in self.report['assets']:
            name = row['name']
            self.assertEqual(build59.EXPECTED[name], build59.sha(self.before[name]))
            self.assertEqual(row['before'], build59.sha(self.before[name]))
            self.assertEqual(row['after'], build59.sha(self.after[name]))

    def test_only_202_key_face_uvs_change_in_each_actual_mesh(self):
        for name, faces in self.report['meshes'].items():
            old, new = json.loads(self.before[name]), json.loads(self.after[name])
            expected = {index for face in faces for index in (face['start'], face['start']+1)}
            actual = {i for i, (a,b) in enumerate(zip(old['groups']['body']['triangles'],
                         new['groups']['body']['triangles'])) if a != b}
            self.assertEqual(101, len(faces), name)
            self.assertEqual(202, len(expected), name)
            self.assertEqual(expected, actual, name)
            restored = copy.deepcopy(new)
            for index in expected:
                restored['groups']['body']['triangles'][index]['uv'] = old['groups']['body']['triangles'][index]['uv']
            self.assertEqual(old, restored, name)

    def test_old_atlas_branding_all_alpha_and_size_are_exact(self):
        self.assertEqual((2048,2048,4), self.old.shape)
        self.assertEqual(self.old.shape, self.new.shape)
        self.assertTrue(np.array_equal(self.old[:build59.START_Y-3], self.new[:build59.START_Y-3]))
        self.assertTrue(np.array_equal(self.old[:,:,3], self.new[:,:,3]))
        permitted = np.zeros(self.old.shape[:2], dtype=bool)
        for row in self.report['tiles']:
            x,y,x1,y1 = row['rect']
            permitted[y-3:y1+4,x-3:x1+4] = True
        self.assertTrue(np.all(permitted[np.any(self.old != self.new, axis=2)]))

    def test_tiles_do_not_overlap_and_all_new_uvs_use_the_exact_tile(self):
        rects = [row['rect'] for row in self.report['tiles']]
        for i,(x,y,x1,y1) in enumerate(rects):
            self.assertGreaterEqual(y-3, build59.START_Y-3)
            self.assertLess(x1+3, 2048)
            self.assertLess(y1+3, 2048)
            for ox,oy,ox1,oy1 in rects[:i]:
                self.assertTrue(x1+3 < ox-3 or ox1+3 < x-3 or y1+3 < oy-3 or oy1+3 < y-3)
        for name, faces in self.report['meshes'].items():
            mesh = json.loads(self.after[name])['groups']['body']['triangles']
            for face in faces:
                self.assertIn(face['new'], rects)
                for index in (face['start'], face['start']+1):
                    self.assertEqual(tuple(face['new']), build59.rect_of(mesh[index]))

    def test_same_font_weight_and_category_sizes_are_used_without_scaling(self):
        self.assertEqual(build59.sha(build59.FONT.read_bytes()), self.report['fontSha256'])
        repeated = {}
        for row in self.report['tiles']:
            text = row['display']
            expected = 32 if len(text) == 1 else 26 if text.startswith('F') and text[1:].isdigit() else 18
            self.assertEqual(expected, row['fontSize'])
            if not text:
                self.assertIsNone(row['inkBounds'])
                continue
            left,top,right,bottom = row['inkBounds']
            dimensions = (right-left,bottom-top)
            # A tall or wide key carrying the same caption has identical ink.
            self.assertEqual(dimensions, repeated.setdefault(text,dimensions))
            x,y,x1,y1 = row['rect']
            self.assertLessEqual(abs((left+right)-(x1-x)), 1)
            self.assertLessEqual(abs((top+bottom)-(y1-y)), 1)

    def test_previously_smaller_letters_are_full_size_and_centered(self):
        letters = [r for r in self.report['tiles'] if len(r['display']) == 1 and r['display'].isupper()]
        self.assertEqual(set('ABCDEFGHIJKLMNOPQRSTUVWXYZ'), {r['display'] for r in letters})
        self.assertEqual({32}, {r['fontSize'] for r in letters})
        heights = {r['inkBounds'][3]-r['inkBounds'][1] for r in letters}
        self.assertLessEqual(max(heights)-min(heights), 3)  # Q has its natural tail.
        self.assertGreaterEqual(min(heights), 22)

    def test_texture_and_actual_surface_have_isotropic_pixel_density(self):
        for faces in self.report['meshes'].values():
            for row in faces:
                x,y,x1,y1 = row['new']
                ratio = ((x1-x)/row['width']) / ((y1-y)/row['height'])
                self.assertAlmostEqual(1, ratio, delta=.021, msg=str(row))

    def test_long_and_tall_repeated_keys_never_alias_different_aspects(self):
        for name, faces in self.report['meshes'].items():
            enters = [row for row in faces if row['label'] == 'Enter']
            zeros = [row for row in faces if row['label'] == '0']
            shifts = [row for row in faces if row['label'] == 'Shift']
            self.assertEqual(2, len(enters), name)
            self.assertEqual(2, len(zeros), name)
            self.assertEqual(2, len(shifts), name)
            self.assertNotEqual(enters[0]['new'], enters[1]['new'])
            self.assertNotEqual(zeros[0]['new'], zeros[1]['new'])
            for rows in (enters, zeros, shifts):
                for a in rows:
                    for b in rows:
                        if (a['relativeWidth'],a['relativeHeight']) != (b['relativeWidth'],b['relativeHeight']):
                            self.assertNotEqual(a['new'],b['new'])

    def test_generator_reproduces_all_candidate_bytes(self):
        original = Image.fromarray(self.old, 'RGBA')
        documents = {n:json.loads(raw) for n,raw in self.before.items() if n.endswith('.json')}
        atlas,revised,details = build59.build(original,documents)
        output = io.BytesIO()
        atlas.image.save(output,format='PNG')
        self.assertEqual(self.after[build59.TEXTURE], output.getvalue())
        for name,doc in revised.items(): self.assertEqual(self.after[name], build59.encode(doc))
        self.assertEqual(self.report['meshes'], details)

    def test_production_equals_approved_candidate_when_requested(self):
        if OPTIONS.production:
            for name,raw in self.after.items(): self.assertEqual(raw,build59.asset_path(name).read_bytes())
        else:
            for name,raw in self.before.items(): self.assertEqual(raw,build59.asset_path(name).read_bytes())


if __name__ == '__main__':
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(SuborKeycaps59Tests)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    summary = dict(ok=result.wasSuccessful(),tests=result.testsRun,failures=len(result.failures),
                   errors=len(result.errors),skipped=len(result.skipped),production=OPTIONS.production,
                   evidence=str(OPTIONS.evidence),limitation='Offline asset checks; no actual Minecraft/shader/mipmap validation')
    if OPTIONS.output:
        assert not OPTIONS.output.exists(), 'Preserve earlier test evidence'
        OPTIONS.output.write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    raise SystemExit(0 if result.wasSuccessful() else 1)
