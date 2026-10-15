"""The front branding decal is omitted without editing an atlas or any other geometry."""
import json
import unittest
from unittest.mock import patch
import import_user_sfc_20260911 as importer


class SfcFrontBrandingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.current,cls.report=importer.build()
        with patch.object(importer,'EXCLUDED_BRANDING',frozenset()):
            cls.original,_=importer.build()

    def test_production_mesh_is_deterministic(self):
        raw=json.dumps(self.current,ensure_ascii=False,separators=(',',':')).encode('utf8')
        self.assertEqual(raw,(importer.ASSETS/'meshes/sfc_hardware.json').read_bytes())

    def test_only_front_branding_two_triangles_are_removed(self):
        old=self.original['groups']['body']['parts'];new=self.current['groups']['body']['parts']
        self.assertEqual([p['name'] for p in old],[p['name'] for p in new])
        source=importer.Model(importer.CONSOLE)
        decal=importer.convert(source,sorted(importer.EXCLUDED_BRANDING),'hardware')['parts'][0]['triangles']
        self.assertEqual(2,len(decal))
        for before,after in zip(old,new):
            if before['name']=='console_print':
                self.assertEqual(len(before['triangles'])-2,len(after['triangles']))
                self.assertEqual([t for t in before['triangles'] if t not in decal],after['triangles'])
                self.assertFalse(any(t in after['triangles'] for t in decal))
            else:self.assertEqual(before,after)
        for name in self.original['groups']:
            if name!='body':self.assertEqual(self.original['groups'][name],self.current['groups'][name])
        self.assertEqual(self.original['materials'],self.current['materials'])
        self.assertEqual(self.original['contract'],self.current['contract'])

    def test_atlases_are_unchanged_and_other_print_remains(self):
        for resource,source in importer.TEXTURES.values():
            target=importer.ASSETS/'textures'/(resource.split(':',1)[1]+'.png')
            self.assertEqual(source.read_bytes(),target.read_bytes())
        source=importer.Model(importer.CONSOLE)
        keep=[uuid for uuid,e in source.elements.items() if e['name']=='卡槽印字']
        self.assertEqual(1,len(keep))
        part=importer.convert(source,keep,'hardware')['parts'][0]
        actual=next(p for p in self.current['groups']['body']['parts'] if p['name']==part['name'])
        self.assertTrue(all(t in actual['triangles'] for t in part['triangles']))
        self.assertEqual(sorted(importer.EXCLUDED_BRANDING),self.report['excluded_branding_elements'])


if __name__=='__main__':unittest.main(verbosity=2)
