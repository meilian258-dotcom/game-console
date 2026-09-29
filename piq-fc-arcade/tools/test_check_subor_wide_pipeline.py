import unittest
from check_subor_wide_pipeline import analyze,probe,PATHS


class SuborWidePipelineTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):cls.actual=probe();cls.report=analyze(cls.actual)

    def entry(self,name):return next(c for c in self.report['checks'] if c['name']==name)
    def test_all_production_wiring_passes(self):self.assertTrue(self.report['ok'],[c for c in self.report['checks'] if not c['ok']])
    def test_resource_split(self):self.assertTrue(self.entry('separate 7-group wide and 5-group legacy resource selection')['ok'])
    def test_world_pose(self):self.assertTrue(self.entry('world rotation precedes wide body without extra scale')['ok'])
    def test_lids_and_docked(self):self.assertTrue(self.entry('exactly one lid and docked controls hidden per port')['ok'])
    def test_old_path(self):self.assertTrue(self.entry('legacy wide-false body and dock paths retained')['ok'])
    def test_card_and_four_aabbs(self):self.assertEqual(4,len([c for c in self.report['checks'] if c['name'].startswith('four-turn') and c['ok']]))
    def test_item_fit(self):self.assertTrue(self.entry('actual item GUI shows keyboard and fits slot')['ok'])
    def test_held_preserved(self):self.assertTrue(self.entry('held old canonical meshes unchanged and no wide/yaw path')['ok'])
    def test_wrong_resource_route_fails(self):
        source=PATHS['loader'].read_text(encoding='utf-8').replace('wideMeshes.get(group)','meshes.get(group)')
        report=analyze(self.actual,loader_override=source);self.assertFalse(next(c for c in report['checks'] if c['name']=='separate 7-group wide and 5-group legacy resource selection')['ok'])
    def test_wrong_item_scale_fails(self):
        source=PATHS['renderer'].read_text(encoding='utf-8').replace('poses.scale(.5f, .5f, .5f);','poses.scale(.6f, .6f, .6f);')
        report=analyze(self.actual,renderer_override=source);self.assertFalse(next(c for c in report['checks'] if c['name']=='item uses one half-scale and closed wide assembly')['ok'])

    def assert_held_rejected(self,renderer=None,loader=None):
        report=analyze(self.actual,renderer_override=renderer,loader_override=loader)
        self.assertFalse(next(c for c in report['checks'] if c['name']=='held old canonical meshes unchanged and no wide/yaw path')['ok'])

    def test_held_partition_cannot_use_wide_mesh(self):
        source=PATHS['loader'].read_text(encoding='utf-8')
        changed=source.replace('float[] data=meshes.get(group);','float[] data=wideMeshes.get(group);')
        self.assertNotEqual(source,changed);self.assert_held_rejected(loader=changed)

    def test_held_missing_partition_fallback_cannot_use_wide_mesh(self):
        source=PATHS['loader'].read_text(encoding='utf-8')
        changed=source.replace('if(parts==null){draw(group,poses,buffers,light,overlay);return;}','if(parts==null){drawWide(group,poses,buffers,light,overlay);return;}')
        self.assertNotEqual(source,changed);self.assert_held_rejected(loader=changed)

    def test_held_cannot_fallthrough_fc_yaw(self):
        source=PATHS['renderer'].read_text(encoding='utf-8')
        changed=source.replace('SuborHardwareMesh.drawHeld(port == 0 ? "p1_held" : "p2_held", stack, context, poses, buffers, light, overlay);\n                return;',
                               'SuborHardwareMesh.drawHeld(port == 0 ? "p1_held" : "p2_held", stack, context, poses, buffers, light, overlay);')
        self.assertNotEqual(source,changed);self.assert_held_rejected(renderer=changed)

    def test_held_extra_whole_controller_scale_rejected(self):
        source=PATHS['loader'].read_text(encoding='utf-8')
        changed=source.replace('float[][] parts=heldParts.get(group);','poses.scale(.5F,.5F,.5F);float[][] parts=heldParts.get(group);')
        self.assertNotEqual(source,changed);self.assert_held_rejected(loader=changed)

    def test_held_animation_must_use_subor_local_basis(self):
        source=PATHS['loader'].read_text(encoding='utf-8')
        changed=source.replace('ControllerButtonRenderer.apply(part,animation,true,poses);','ControllerButtonRenderer.apply(part,animation,false,poses);')
        self.assertNotEqual(source,changed);self.assert_held_rejected(loader=changed)

    def test_legacy_direct_held_signature_is_still_supported(self):
        source=PATHS['renderer'].read_text(encoding='utf-8')
        changed=source.replace('SuborHardwareMesh.drawHeld(port == 0 ? "p1_held" : "p2_held", stack, context, poses, buffers, light, overlay);',
                               'SuborHardwareMesh.draw(port == 0 ? "p1_held" : "p2_held", poses, buffers, light, overlay);')
        self.assertNotEqual(source,changed)
        report=analyze(self.actual,renderer_override=changed)
        self.assertTrue(next(c for c in report['checks'] if c['name']=='held old canonical meshes unchanged and no wide/yaw path')['ok'])

    def test_shared_pose_before_subor_branch_rejected(self):
        source=PATHS['renderer'].read_text(encoding='utf-8')
        changed=source.replace('if (HomeControllerData.style(stack) == HomeControllerData.Style.SUBOR)',
                               'poses.scale(.5F,.5F,.5F); if (HomeControllerData.style(stack) == HomeControllerData.Style.SUBOR)')
        self.assertNotEqual(source,changed);self.assert_held_rejected(renderer=changed)


if __name__=='__main__':unittest.main()
