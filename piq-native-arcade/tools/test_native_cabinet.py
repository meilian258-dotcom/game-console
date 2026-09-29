import unittest
import check_native_cabinet as q

class NativeCabinetModuleTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.report,_=q.analyze()
        cls.structure=(q.ROOT/'src/main/java/cn/piq/nativearcade/world/NativeCabinetStructure.java').read_text(encoding='utf-8')
        cls.renderer=(q.ROOT/'src/main/java/cn/piq/nativearcade/client/NativeCabinetRenderer.java').read_text(encoding='utf-8')
    def test_actual_geometry_lifecycle_and_resources(self):self.assertTrue(self.report['ok'],[c for c in self.report['checks'] if not c['ok']])
    def test_exact_twenty_pure_java_tests(self):self.assertEqual(self.report['java'],'NATIVE_CABINET_PURE_TESTS=20 PASS')
    def test_mutated_registry_source_fails(self):
        s=self.structure.replace('NativeArcadeRegistries.','ModItems.');self.assertFalse(q.source_checks(s,self.renderer)[0][1])
    def test_rollback_identity_guard_is_required(self):
        s=self.structure.replace('level.getBlockEntity(cell.pos()) == cell.entity()','true');self.assertFalse(q.source_checks(s,self.renderer)[1][1])
    def test_permission_recheck_is_required(self):
        s=self.structure.replace('usePermitted(player,anchor,hit)','true',1);self.assertFalse(q.source_checks(s,self.renderer)[4][1])
    def test_video_must_not_keep_raw_yaw(self):
        r=self.renderer.replace('finally{poses.popPose();}\n        // Missing video integration','// Missing video integration');self.assertFalse(q.source_checks(self.structure,r)[5][1])
    def test_reload_cache_cannot_ignore_model_identity(self):
        r=self.renderer.replace('cached.model()!=model','false');self.assertFalse(q.source_checks(self.structure,r)[6][1])
    def test_wrong_item_recenter_is_rejected(self):
        r=self.renderer.replace('poses.translate(-1,-1.175,-.5)','poses.translate(-1,-1,-.5)');self.assertFalse(q.source_checks(self.structure,r)[7][1])

if __name__=='__main__':unittest.main()
