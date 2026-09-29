"""Positive/negative audits with read-only frozen bytes and in-memory metadata fixtures.

Fixtures are never delivery artifacts and do not execute production classes.
"""
import copy
import sys
import unittest
from pathlib import Path
import verify_retro_alpha19 as check

sys.path.insert(0,str(check.ROOT/"piq-sfc-home/tools"))
import merge_sfc_addon as merger


class Alpha19CompatibilityTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.baselines={}
        for key,(path,expected) in check.BASELINES.items():
            found,cls.baselines[key]=check.archive(path)
            if found!=expected:raise AssertionError("Frozen input changed")
        fc=dict(cls.baselines["fc"]);native=dict(cls.baselines["native"]);home=dict(cls.baselines["sfc"])
        fc[check.META]=fc[check.META].replace(b"0.31.0-alpha.18",b"0.31.0-alpha.19")
        native[check.META]=native[check.META].replace(b"0.1.0-alpha.5",b"0.1.0-alpha.6").replace(b"0.31.0-alpha.18",b"0.31.0-alpha.19")
        home[check.META]=home[check.META].replace(b"0.1.0-alpha.5",b"0.1.0-alpha.6").replace(b"0.31.0-alpha.18",b"0.31.0-alpha.19")
        sfc=dict(cls.baselines["core"]);sfc.update(home)
        sfc[check.META]=merger.combined_metadata(cls.baselines["core"],home,"0.1.0-alpha.6")
        # Ownership tests examine path placement only. This dummy is never loaded.
        fc["cn/piq/retro/client/GamepadInput.class"]=b"ownership-test-fixture-only"
        cls.fixture={"fc":fc,"sfc":sfc,"native":native}

    def setUp(self):self.jars={key:dict(value) for key,value in self.fixture.items()}

    def test_valid_optional_graph_and_single_runtime_owner(self):
        graph=check.metadata_graph(self.jars);owned=check.unique_ownership(self.jars)
        self.assertTrue(graph["fc_alone"]);self.assertTrue(graph["sfc_without_fc_rejected"])
        self.assertTrue(graph["native_without_fc_rejected"]);self.assertFalse(graph["independent_platform_mod"])
        self.assertEqual(owned["platform_owner"],"fc");self.assertEqual(owned["duplicate_classes"],0)

    def test_all_protocol_server_core_bytes_preserved(self):
        result=check.frozen_protection(self.jars,self.baselines)
        self.assertEqual(len(result["sfc"]["debug_tables_only_identical_execution"]),0)
        self.assertEqual(result["frozen_sfc6"]["byte_identical_entries"],60)
        self.assertGreater(len(result["fc"]["byte_identical_protected_entries"]),200)

    def test_missing_home_mod_id_rejected(self):
        self.jars["sfc"][check.META]=self.baselines["core"][check.META]
        with self.assertRaisesRegex(ValueError,"mod IDs"):check.metadata_graph(self.jars)

    def test_missing_core_mod_id_rejected(self):
        self.jars["sfc"][check.META]=self.baselines["sfc"][check.META].replace(b"0.1.0-alpha.5",b"0.1.0-alpha.6").replace(b"alpha.18",b"alpha.19")
        with self.assertRaisesRegex(ValueError,"mod IDs"):check.metadata_graph(self.jars)

    def test_fc_must_not_require_sfc(self):
        self.jars["fc"][check.META]+=b'\n[[dependencies.piq_fc_arcade]]\nmodId="piq_sfc_home"\ntype="required"\nversionRange="[0.1,)"\nordering="AFTER"\nside="BOTH"\n'
        with self.assertRaisesRegex(ValueError,"FC main"):check.metadata_graph(self.jars)

    def test_independent_platform_dependency_rejected(self):
        self.jars["native"][check.META]+=b'\n[[dependencies.piq_native_arcade]]\nmodId="piq_retro_platform"\ntype="required"\nversionRange="[0.1,)"\nordering="AFTER"\nside="BOTH"\n'
        with self.assertRaisesRegex(ValueError,"platform mod"):check.metadata_graph(self.jars)

    def test_optional_fc_dependency_cannot_hide_missing_main(self):
        for key in ("sfc","native"):
            with self.subTest(key=key):
                jars={k:dict(v) for k,v in self.fixture.items()}
                jars[key][check.META]=jars[key][check.META].replace(b'modId="piq_fc_arcade"\ntype="required"',b'modId="piq_fc_arcade"\ntype="optional"').replace(b'modId="piq_fc_arcade"\r\ntype="required"',b'modId="piq_fc_arcade"\r\ntype="optional"')
                with self.assertRaises(ValueError):check.metadata_graph(jars)

    def test_wrong_minimum_fc_version_rejected(self):
        self.jars["native"][check.META]=self.jars["native"][check.META].replace(b"alpha.19",b"alpha.18")
        with self.assertRaisesRegex(ValueError,"FC19"):check.metadata_graph(self.jars)

    def test_wrong_dependency_side_rejected(self):
        self.jars["sfc"][check.META]=self.jars["sfc"][check.META].replace(b'side="BOTH"',b'side="CLIENT"')
        with self.assertRaises(ValueError):check.metadata_graph(self.jars)

    def test_duplicate_platform_class_rejected(self):
        self.jars["sfc"]["cn/piq/retro/client/GamepadInput.class"]=b"duplicate"
        with self.assertRaisesRegex(ValueError,"Duplicate class"):check.unique_ownership(self.jars)

    def test_foreign_runtime_owner_rejected(self):
        self.jars["native"]["runtime/extra.dll"]=b"not a runtime"
        with self.assertRaisesRegex(ValueError,"runtime owner"):check.unique_ownership(self.jars)

    def test_missing_combined_core_binary_rejected(self):
        del self.jars["sfc"][merger.WASM]
        with self.assertRaises(ValueError):check.unique_ownership(self.jars)
        with self.assertRaises(ValueError):check.frozen_protection(self.jars,self.baselines)

    def test_frozen_core_class_and_assets_reject_each_mutation(self):
        names=[merger.AT,merger.WASM,next(name for name in self.baselines["core"] if name.endswith(".class"))]
        for name in names:
            with self.subTest(name=name):
                jars={k:dict(v) for k,v in self.fixture.items()};jars["sfc"][name]+=b"changed"
                with self.assertRaises(ValueError):check.frozen_protection(jars,self.baselines)

    def test_every_selected_network_family_is_protected(self):
        chosen={"fc":["cn/piq/fcarcade/FcNetwork.class","cn/piq/fcarcade/ArcadeInputPayload.class","cn/piq/fcarcade/server/ServerArcadeSessions.class","cn/piq/fcarcade/cabinet/CabinetLeaseLedger.class"],
                "sfc":["cn/piq/sfchome/net/SfcHomeNetwork.class","cn/piq/sfchome/net/SfcJoinNetwork$ControllerInput.class","cn/piq/sfchome/server/SfcHomeServer.class","cn/piq/sfchome/server/SfcJoinGate.class"],
                "native":["cn/piq/nativearcade/bridge/NativeProcessSession.class","cn/piq/nativearcade/NativeArcadeMod.class"]}
        for key,names in chosen.items():
            for name in names:
                with self.subTest(name=name):
                    self.assertTrue(check.protected_class(key,name));jars={k:dict(v) for k,v in self.fixture.items()}
                    del jars[key][name]
                    with self.assertRaisesRegex(ValueError,"Protected entry deleted"):check.frozen_protection(jars,self.baselines)

    def test_native_localonly_flag_class_cannot_change(self):
        name="cn/piq/nativearcade/NativeArcadeMod.class"
        self.jars["native"][name]=check.replace_class_utf8(self.jars["native"][name],"MAME 原生街机","MAME 改动")
        with self.assertRaisesRegex(ValueError,"Protected protocol"):check.frozen_protection(self.jars,self.baselines)

    def test_exact_server_notice_change_is_allowed_and_nothing_else(self):
        name="cn/piq/fcarcade/cabinet/ServerCabinets.class"
        self.jars["fc"][name]=check.replace_class_utf8(self.jars["fc"][name],"已结束街机；再次右键可选择游戏","已结束街机；再次右键启动，Shift 空手右键配置游戏")
        result=check.frozen_protection(self.jars,self.baselines)
        self.assertEqual(result["fc"]["exact_notice_constant_only"],[name])
        self.jars["fc"][name]=check.replace_class_utf8(self.jars["fc"][name],"已结束街机；再次右键启动，Shift 空手右键配置游戏","不正确的提示")
        with self.assertRaisesRegex(ValueError,"beyond exact permitted notice"):check.frozen_protection(self.jars,self.baselines)

    def test_frozen_core_metadata_cannot_silently_change(self):
        self.jars["sfc"][check.META]=self.jars["sfc"][check.META].replace(b"[0.26.0,)",b"[0.1.0,)")
        with self.assertRaisesRegex(ValueError,"Frozen core dependency"):check.frozen_protection(self.jars,self.baselines)


if __name__=="__main__":unittest.main()
