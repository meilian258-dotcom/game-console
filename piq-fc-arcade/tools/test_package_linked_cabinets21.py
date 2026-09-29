import copy,unittest
import package_linked_cabinets21 as p

class EvidenceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):cls.reports={k:p.json_document(p.snapshot(p.ROOT/v).raw)for k,v in p.REPORTS.items()}
    def test_current_evidence(self):p.check_evidence(self.reports)
    def rejected(self,section,key,value):
        r=copy.deepcopy(self.reports);r[section][key]=value
        with self.assertRaises(ValueError):p.check_evidence(r)
    def test_recompiled_room_rejected(self):self.rejected('audit','production_compiled',True)
    def test_wrong_native_rejected(self):self.rejected('native','parent_jar_sha256','0'*64)
    def test_old_sfc_mode_rejected(self):self.rejected('sfc_playback','mode','source-fixture')
    def test_media_failure_rejected(self):self.rejected('media','ok',False)
    def test_sfc_changed_network_rejected(self):self.rejected('sfc_ports','home_network_core_and_all_assets_unchanged',False)
    def test_stage_failure_rejected(self):self.rejected('stage','ok',False)

if __name__=='__main__':unittest.main()
