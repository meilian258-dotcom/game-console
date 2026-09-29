import copy,json,unittest
from unittest.mock import patch
import verify_home_fc_alpha10_final_jar as audit
from vintage_tv_alpha10_archive import release as frozen_alpha10_release


class Alpha10FinalJarTests(unittest.TestCase):
    def document(self):return json.loads(audit.MANIFEST.read_bytes())
    def test_frozen_manifest_has_exact_64_paths_and_57_unchanged(self):
        self.assertEqual(64,len(audit.frozen_manifest()))
        self.assertEqual(7,len(audit.ADDED))
    def test_unfrozen_manifest_fails_closed(self):
        with patch.object(audit,'MANIFEST_SHA',None),self.assertRaises(ValueError):audit.frozen_manifest()
    def test_changed_prior_asset_is_rejected(self):
        d=self.document();n=next(n for n in d['assets'] if n not in audit.ADDED);d['assets'][n]='0'*64
        with self.assertRaises(ValueError):audit.validate_manifest(d)
    def test_missing_new_tv_file_is_rejected(self):
        d=self.document();d['assets'].pop(next(iter(audit.ADDED)))
        with self.assertRaises(ValueError):audit.validate_manifest(d)
    def test_extra_unreviewed_file_is_rejected(self):
        d=self.document();d['assets']['assets/piq_fc_arcade/textures/block/unreviewed.png']='0'*64
        with self.assertRaises(ValueError):audit.validate_manifest(d)
    def test_old_protocol_is_rejected(self):
        d=self.document();d['protocol']=27
        with self.assertRaises(ValueError):audit.validate_manifest(d)
    def test_new_geometry_real_vertices_and_negative_screen_mutation(self):
        for name,vintage in [('home_large_lcd_tv',False),('home_vintage_tv',True)]:
            model=(frozen_alpha10_release()['model'] if vintage else
                json.loads((audit.old.ASSETS/('assets/piq_fc_arcade/models/block/'+name+'.json')).read_bytes()))
            self.assertTrue(audit.television_geometry(model,vintage)['front_only_screen'])
            bad=copy.deepcopy(model);bad['elements'][16 if vintage else 4]['to'][0]+=.1
            with self.assertRaises(ValueError):audit.television_geometry(bad,vintage)
    def test_geometry_rejects_overflow_and_double_sided_screen(self):
        model=json.loads((audit.old.ASSETS/'assets/piq_fc_arcade/models/block/home_large_lcd_tv.json').read_bytes())
        bad=copy.deepcopy(model);bad['elements'][0]['from'][0]=-17
        with self.assertRaises(ValueError):audit.television_geometry(bad)
        bad=copy.deepcopy(model);bad['elements'][4]['faces']['south']=copy.deepcopy(bad['elements'][4]['faces']['north'])
        with self.assertRaises(ValueError):audit.television_geometry(bad)


if __name__=='__main__':unittest.main()
