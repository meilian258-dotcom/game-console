import copy,unittest
from unittest.mock import patch
import verify_home_fc_alpha11_final_jar as audit


class Alpha11FinalJarTests(unittest.TestCase):
    def test_frozen_manifest_sha_and_all_paths(self):
        self.assertEqual(64,len(audit.frozen_manifest()))
    def document(self):
        assets=dict(audit.alpha10.frozen_manifest());assets[audit.CHANGED]='F'*64
        return {'version':audit.VERSION,'protocol':28,'assets':assets}
    def model(self):
        face={'uv':[0,0,1,1],'texture':'#0'}
        return {'textures':{'0':'piq_fc_arcade:block/home_vintage_tv'},'elements':[
            {'from':[.25,.1,4],'to':[15.75,14.15,14.04],'faces':{'south':copy.deepcopy(face)}},
            {'from':[4.35,2,3.35],'to':[14.55,9.65,3.38],'faces':{'north':copy.deepcopy(face)}}]}
    def test_precisely_64_paths_and_63_unchanged(self):
        self.assertEqual(64,len(audit.validate_manifest(self.document())))
    def test_unfrozen_manifest_fails_closed(self):
        with patch.object(audit,'MANIFEST_SHA',None),self.assertRaises(ValueError):audit.frozen_manifest()
    def test_unchanged_vintage_is_rejected(self):
        d=self.document();d['assets']=audit.alpha10.frozen_manifest()
        with self.assertRaises(ValueError):audit.validate_manifest(d)
    def test_any_second_change_is_rejected(self):
        d=self.document();n=next(n for n in d['assets'] if n!=audit.CHANGED);d['assets'][n]='0'*64
        with self.assertRaises(ValueError):audit.validate_manifest(d)
    def test_missing_asset_is_rejected(self):
        d=self.document();d['assets'].pop(audit.CHANGED)
        with self.assertRaises(ValueError):audit.validate_manifest(d)
    def test_extra_asset_is_rejected(self):
        d=self.document();d['assets']['assets/piq_fc_arcade/textures/unreviewed.png']='0'*64
        with self.assertRaises(ValueError):audit.validate_manifest(d)
    def test_wrong_version_or_protocol_is_rejected(self):
        for key,value in [('version','0.31.0-alpha.10'),('protocol',29)]:
            d=self.document();d[key]=value
            with self.assertRaises(ValueError):audit.validate_manifest(d)
    def test_malformed_hash_is_rejected(self):
        d=self.document();d['assets'][audit.CHANGED]='pending'
        with self.assertRaises(ValueError):audit.validate_manifest(d)
    def test_screen_predicate_accepts_exact_unobscured_aperture(self):
        result=audit.television_geometry(self.model(),True)
        self.assertEqual(81,result['front_aperture_ray_samples'])
        self.assertEqual(4/3,result['physical_screen_aspect'])
    def test_screen_misalignment_and_double_face_are_rejected(self):
        for double in (False,True):
            m=self.model()
            if double:m['elements'][1]['faces']['south']=dict(m['elements'][1]['faces']['north'])
            else:m['elements'][1]['from'][0]+=.1
            with self.assertRaises(ValueError):audit.television_geometry(m,True)
    def test_aperture_blocker_is_rejected(self):
        m=self.model();blocker=copy.deepcopy(m['elements'][1]);blocker['from']=[6,3,3];blocker['to']=[7,4,3.02];m['elements'].append(blocker)
        with self.assertRaises(ValueError):audit.television_geometry(m,True)
    def test_new_geometry_cannot_overflow_old_collision(self):
        m=self.model();m['elements'][0]['to'][1]=14.5
        with self.assertRaises(ValueError):audit.television_geometry(m,True)
    def test_legacy_ids_match_whole_registry_literal_not_prefix(self):
        valid='\n'.join('// String '+n for n in audit.RETIRED)
        self.assertTrue(all(audit.registered_legacy_ids(valid).values()))
        self.assertFalse(any(audit.registered_legacy_ids(valid.replace('\n','_fake\n')+'_fake').values()))
    def test_actionbar_requires_true_argument_at_the_actual_invocation(self):
        valid='12: iconst_1\n  13: invokevirtual #2 // Method ServerPlayer.displayClientMessage:(LComponent;Z)V'
        self.assertTrue(audit.has_actionbar_true(valid))
        self.assertFalse(audit.has_actionbar_true(valid.replace('iconst_1','iconst_0')))
        self.assertFalse(audit.has_actionbar_true('1: iconst_1\n2: istore_3\n'+valid.replace('iconst_1','iconst_0')))


if __name__=='__main__':unittest.main()
