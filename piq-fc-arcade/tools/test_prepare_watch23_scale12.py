import copy,json,unittest
import prepare_watch23_scale12 as stage

class VisualBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.card={'display':{'gui':{'scale':[1.22]*3,'rotation':[10,165,0]},'ground':{'scale':[.5]*3}},'parent':'builtin/entity'}
        self.old={stage.CARD:json.dumps(self.card).encode(),'cn/piq/sfchome/client/SfcWatchPublisher.class':b'watch',
                  'cn/piq/sfchome/server/SfcHomeServer.class':b'server','assets/piq_sfc_home/meshes/sfc_hardware.json':b'mesh'}
    def new(self):
        doc=copy.deepcopy(self.card);doc['display']['gui']['scale']=[2.4]*3
        return dict(self.old,**{stage.CARD:json.dumps(doc).encode()})
    def test_gui_only(self):self.assertEqual(stage.classify(self.old,self.new())['removed'],[])
    def test_no_removed(self):
        new=self.new();del new[stage.CARD]
        with self.assertRaises(ValueError):stage.classify(self.old,new)
    def test_network_is_protected(self):
        new=self.new();new['cn/piq/sfchome/server/SfcHomeServer.class']=b'changed'
        with self.assertRaises(ValueError):stage.classify(self.old,new)
    def test_watch_is_protected(self):
        new=self.new();new['cn/piq/sfchome/client/SfcWatchPublisher.class']=b'changed'
        with self.assertRaises(ValueError):stage.classify(self.old,new)
    def test_original_mesh_is_protected(self):
        new=self.new();new['assets/piq_sfc_home/meshes/sfc_hardware.json']=b'changed'
        with self.assertRaises(ValueError):stage.classify(self.old,new)
    def test_other_item_pose_is_protected(self):
        new=self.new();doc=json.loads(new[stage.CARD]);doc['display']['ground']['scale']=[2]*3;new[stage.CARD]=json.dumps(doc).encode()
        with self.assertRaises(ValueError):stage.classify(self.old,new)
    def test_unknown_class_rejected(self):
        new=self.new();new['cn/piq/sfchome/client/Unrequested.class']=b'class'
        with self.assertRaises(ValueError):stage.classify(self.old,new)
    def test_console_exact_gui_allowed(self):
        old=dict(self.old,**{stage.CONSOLE:json.dumps(self.card).encode()});new=dict(old)
        doc=copy.deepcopy(self.card);doc['display']['gui']=stage.CONSOLE_GUI;new[stage.CONSOLE]=json.dumps(doc).encode()
        stage.classify(old,new)
    def test_console_non_gui_rejected(self):
        old=dict(self.old,**{stage.CONSOLE:json.dumps(self.card).encode()});new=dict(old)
        doc=copy.deepcopy(self.card);doc['display']['gui']=stage.CONSOLE_GUI;doc['display']['ground']['scale']=[3]*3;new[stage.CONSOLE]=json.dumps(doc).encode()
        with self.assertRaises(ValueError):stage.classify(old,new)
    def test_console_different_fit_rejected(self):
        old=dict(self.old,**{stage.CONSOLE:json.dumps(self.card).encode()});new=dict(old)
        doc=copy.deepcopy(self.card);doc['display']['gui']=copy.deepcopy(stage.CONSOLE_GUI);doc['display']['gui']['scale']=[1]*3;new[stage.CONSOLE]=json.dumps(doc).encode()
        with self.assertRaises(ValueError):stage.classify(old,new)

if __name__=='__main__':unittest.main()
