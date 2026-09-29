import copy
import unittest
import numpy as np
from check_dual_render_pipeline import analyze,probe_geometry,PATHS,screen_matches,world_matrix,item_matrix
from render_rocket_arcade_preview import collect_quads


class DualRenderPipelineTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.probe=probe_geometry();cls.report,cls.model,cls.item,_=analyze(cls.probe)

    def test_all_actual_source_contracts_and_math_pass(self):
        self.assertTrue(self.report['ok'],[c for c in self.report['checks'] if not c['ok']]);self.assertGreaterEqual(self.report['check_count'],20)

    def test_all_four_dynamic_screen_offsets_are_normal_only(self):
        self.assertEqual(4,len(self.report['turns']));self.assertTrue(all(t['screen_offset_error']<1e-9 for t in self.report['turns']))

    def test_float32_static_screen_identity_unique(self):
        quads=collect_quads(self.model);matches=screen_matches(quads,self.probe)
        self.assertEqual(1,len(matches));self.assertEqual(47,quads[matches[0]].element_index)

    def test_duplicate_static_glass_detected(self):
        model=copy.deepcopy(self.model);model['elements'].append(copy.deepcopy(model['elements'][47]))
        self.assertEqual(2,len(screen_matches(collect_quads(model),self.probe)))

    def test_wrong_world_pivot_source_contract_fails(self):
        altered=PATHS['renderer'].read_text(encoding='utf-8').replace('poses.translate(.5,0,.5);','poses.translate(1,0,1);')
        report,*_=analyze(self.probe,renderer_override=altered)
        self.assertFalse(next(c for c in report['checks'] if c['name']=='world transform order and anchor pivot')['ok'])

    def test_wrong_custom_item_scale_source_contract_fails(self):
        altered=PATHS['renderer'].read_text(encoding='utf-8').replace('poses.scale(.40F,.40F,.40F);','poses.scale(.6F,.6F,.6F);')
        report,*_=analyze(self.probe,renderer_override=altered)
        self.assertFalse(next(c for c in report['checks'] if c['name']=='item custom transform and strict JSON')['ok'])

    def test_old_three_block_item_center_fails(self):
        altered=PATHS['renderer'].read_text(encoding='utf-8').replace('poses.translate(-1,-1.175,-.5);','poses.translate(-1,-1,-.5);')
        report,*_=analyze(self.probe,renderer_override=altered)
        self.assertFalse(next(c for c in report['checks'] if c['name']=='item custom transform and strict JSON')['ok'])

    def test_uv_mirror_source_contract_fails(self):
        altered=PATHS['screen_renderer'].read_text(encoding='utf-8').replace('quad.lowerMaxX(), quad.normal(), 0, 1','quad.lowerMaxX(), quad.normal(), 1, 1')
        report,*_=analyze(self.probe,screen_override=altered)
        self.assertFalse(next(c for c in report['checks'] if c['name']=='dynamic screen UV order')['ok'])

    def test_missing_y_rebase_detects_misaligned_black_glass(self):
        probe=copy.deepcopy(self.probe);probe['y_offset']=0
        self.assertEqual([],screen_matches(collect_quads(self.model),probe))
        altered=PATHS['renderer'].read_text(encoding='utf-8').replace('poses.translate(0,DualCabinetGeometry.MODEL_Y_OFFSET,0);','')
        report,*_=analyze(self.probe,renderer_override=altered)
        self.assertFalse(next(c for c in report['checks'] if c['name']=='world transform order and anchor pivot')['ok'])

    def test_missing_dual_branch_cannot_pass_using_legacy_uv_code(self):
        altered=PATHS['screen_renderer'].read_text(encoding='utf-8').replace('ArcadeDisplayStyle.DUAL_CABINET','ArcadeDisplayStyle.WRONG_STYLE')
        report,*_=analyze(self.probe,screen_override=altered)
        self.assertFalse(next(c for c in report['checks'] if c['name']=='dynamic screen UV order')['ok'])

    def test_extra_known_tv_styles_preserve_strict_dual_validation(self):
        source=PATHS['screen_renderer'].read_text(encoding='utf-8')
        altered=source.replace('ArcadeDisplayStyle.DUAL_CABINET', 'ArcadeDisplayStyle.DUAL_CABINET || displayStyle == cn.piq.fcarcade.layout.ArcadeDisplayStyle.HOME_LARGE_LCD_TV || displayStyle == cn.piq.fcarcade.layout.ArcadeDisplayStyle.HOME_VINTAGE_TV', 1)
        report,*_=analyze(self.probe,screen_override=altered)
        self.assertTrue(next(c for c in report['checks'] if c['name']=='dynamic screen UV order')['ok'])
        bad=altered.replace('quad.lowerMaxX(), quad.normal(), 0, 1','quad.lowerMaxX(), quad.normal(), .1, 1')
        report,*_=analyze(self.probe,screen_override=bad)
        self.assertFalse(next(c for c in report['checks'] if c['name']=='dynamic screen UV order')['ok'])

    def test_missing_model_identity_check_rejects_stale_reload_cache(self):
        altered=PATHS['renderer'].read_text(encoding='utf-8').replace('cached == null || cached.model() != model','cached == null')
        report,*_=analyze(self.probe,renderer_override=altered)
        self.assertFalse(next(c for c in report['checks'] if c['name']=='model reload rebuilds quad and sprite cache')['ok'])

    def test_gui_fits_true_slot_and_large_display_would_fail(self):
        self.assertTrue((np.array(self.report['gui_pixel_bounds'])>=0).all());self.assertTrue((np.array(self.report['gui_pixel_bounds'])<=16).all())
        item=copy.deepcopy(self.item);item['display']['gui']['scale']=[2,2,2]
        report,*_=analyze(self.probe,item_override=item)
        self.assertFalse(next(c for c in report['checks'] if c['name']=='actual GUI slot bounds and front face visibility')['ok'])


if __name__=='__main__':unittest.main()
