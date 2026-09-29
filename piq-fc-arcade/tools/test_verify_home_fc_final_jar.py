import io
import json
import struct
import unittest
import warnings
import copy
import zipfile
from unittest.mock import patch

from verify_home_fc_final_jar import (ROOT, REQUIRED_CLASSES, EXTRA_ASSETS, ALPHA2_MANIFEST, SKIN, appearance,
                                     asset_checks, duplicate_entries, mod_version, pe_x64, sha, validate_manifest,
                                     ALPHA3_MANIFEST, ALPHA4_MANIFEST, ALPHA4_CLASSES, CONTROLLER_ITEM, ENUM_EXTENSIONS,
                                     enum_name_has_mod_prefix, has_protocol_literal, review_for, validate_enum_extensions,
                                     ALPHA4_MANIFEST_SHA, file_sha, reviewed_manifest_sha,
                                     ALPHA5_MANIFEST, ALPHA5_MANIFEST_SHA, ALPHA5_CLASSES, SUBOR_ASSETS,
                                     SUBOR_MESH, SUBOR_TEXTURE, SUBOR_SOURCE, SUBOR_SOURCE_SHA,
                                     validate_subor_mesh, has_double_constant, method_returns_constant_true,
                                     ALPHA6_MANIFEST, ALPHA5_JAR_SHA, DELIVERY, ALPHA6_ASSETS,
                                     ALPHA6_CLASSES, javap_method, validate_subor_wide_mesh,
                                     DUAL_BODY, LCD_BODY, LEGACY_BODY, LEGACY_TEXTURE, dual_source, ALPHA6_MANIFEST_SHA,
                                     validate_dual_model, validate_lcd_model, ALPHA7_MANIFEST,
                                     ALPHA7_CHANGED_ASSETS, SUBOR_WIDE_MESH, alpha6_asset,
                                     validate_lowered_dual_model, ALPHA6_JAR_SHA, ALPHA7_MANIFEST_SHA,
                                     ALPHA8_MANIFEST, ALPHA8_MANIFEST_SHA, ALPHA8_CHANGED_ASSETS, ALPHA8_CLASSES,
                                     alpha7_asset, alpha8_asset, validate_native_dual_model, ALPHA9_MANIFEST,
                                     ALPHA9_MANIFEST_SHA, ALPHA9_CLASSES, ALPHA9_ADDED_ASSETS, validate_header_dual_model,
                                     ALPHA9_INITIAL_MANIFEST, ALPHA9_INITIAL_MANIFEST_SHA)


class IndependentFinalJarAuditTest(unittest.TestCase):
    def test_required_classes_are_production_sources_not_test_harnesses(self):
        for name in REQUIRED_CLASSES + ALPHA4_CLASSES + ALPHA5_CLASSES + ALPHA6_CLASSES + ALPHA8_CLASSES + ALPHA9_CLASSES:
            source = ROOT / "src/main/java/cn/piq/fcarcade" / (name.split("$", 1)[0] + ".java")
            self.assertTrue(source.is_file(), str(source))

    def test_hash_and_unique_entry_check(self):
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w") as archive:
            archive.writestr("one", b"expected")
        with zipfile.ZipFile(stream) as archive:
            self.assertTrue(asset_checks(archive, {"one": sha(b"expected")})[0]["ok"])
            self.assertFalse(asset_checks(archive, {"one": sha(b"changed")})[0]["ok"])
            self.assertFalse(asset_checks(archive, {"missing": sha(b"expected")})[0]["ok"])

    def test_duplicate_identical_entries_are_still_forbidden(self):
        stream = io.BytesIO()
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(stream, "w") as archive:
                archive.writestr("same", b"same"); archive.writestr("same", b"same")
        with zipfile.ZipFile(stream) as archive:
            self.assertEqual({"same": 2}, duplicate_entries(archive))
            self.assertFalse(asset_checks(archive, {"same": sha(b"same")})[0]["ok"])

    def test_appearance_scope_is_exact_and_ignores_directory_entries(self):
        self.assertTrue(appearance("assets/piq_fc_arcade/models/block/a.json"))
        self.assertTrue(appearance("assets/piq_fc_arcade/textures/item/a.png"))
        self.assertFalse(appearance("assets/other_mod/models/a.json"))
        self.assertFalse(appearance("assets/piq_fc_arcade/lang/zh_cn.json"))
        self.assertFalse(appearance("assets/piq_fc_arcade/models/"))

    def test_pe_header_checks_actual_x64_machine_and_offsets(self):
        data = bytearray(96); data[:2] = b"MZ"; struct.pack_into("<I", data, 0x3C, 64)
        data[64:68] = b"PE\0\0"; struct.pack_into("<H", data, 68, 0x8664)
        self.assertTrue(pe_x64(data))
        struct.pack_into("<H", data, 68, 0x14C)
        self.assertFalse(pe_x64(data))
        struct.pack_into("<I", data, 0x3C, 1000)
        self.assertFalse(pe_x64(data))
        self.assertFalse(pe_x64(b"MZ"))

    def test_version_uses_mod_entry_not_random_text_or_duplicate_mod(self):
        self.assertEqual("0.31.0-alpha.3", mod_version(b'[[mods]]\nmodId="piq_fc_arcade"\nversion="0.31.0-alpha.3"'))
        self.assertIsNone(mod_version(b'[[mods]]\nmodId="other"\nversion="0.31.0-alpha.3"'))
        self.assertIsNone(mod_version(b'[[mods]]\nmodId="piq_fc_arcade"\nversion="a"\n[[mods]]\nmodId="piq_fc_arcade"\nversion="b"'))

    def test_manifest_only_allows_reviewed_seven_additions_and_new_skin(self):
        previous = json.loads(ALPHA2_MANIFEST.read_bytes())["assets"]
        assets = dict(previous, **EXTRA_ASSETS); assets[SKIN] = "A" * 64
        manifest = {"version": "0.31.0-alpha.3", "protocol": 23, "assets": assets}
        self.assertEqual(32, len(validate_manifest(manifest)))
        assets["assets/piq_fc_arcade/models/block/home_famicom_console.json"] = "B" * 64
        with self.assertRaises(ValueError): validate_manifest(manifest)

    def test_manifest_rejects_unchanged_default_skin_or_added_unreviewed_asset(self):
        previous = json.loads(ALPHA2_MANIFEST.read_bytes())["assets"]
        assets = dict(previous, **EXTRA_ASSETS)
        manifest = {"version": "0.31.0-alpha.3", "protocol": 23, "assets": assets}
        with self.assertRaises(ValueError): validate_manifest(manifest)
        assets[SKIN] = "A" * 64; assets["assets/piq_fc_arcade/models/block/unreviewed.json"] = "B" * 64
        with self.assertRaises(ValueError): validate_manifest(manifest)

    def test_target_version_selects_its_fixed_manifest_and_protocol(self):
        self.assertEqual(23, review_for("0.31.0-alpha.3")["protocol"])
        self.assertEqual(ALPHA3_MANIFEST, review_for("0.31.0-alpha.3")["manifest"])
        self.assertEqual(24, review_for("0.31.0-alpha.4")["protocol"])
        self.assertEqual(ALPHA4_MANIFEST, review_for("0.31.0-alpha.4")["manifest"])
        self.assertEqual(25, review_for("0.31.0-alpha.5")["protocol"])
        self.assertEqual(ALPHA5_MANIFEST, review_for("0.31.0-alpha.5")["manifest"])
        self.assertEqual(26, review_for("0.31.0-alpha.6")["protocol"])
        self.assertEqual(ALPHA6_MANIFEST, review_for("0.31.0-alpha.6")["manifest"])
        self.assertEqual(26, review_for("0.31.0-alpha.7")["protocol"])
        self.assertEqual(ALPHA7_MANIFEST, review_for("0.31.0-alpha.7")["manifest"])
        self.assertEqual(26, review_for("0.31.0-alpha.8")["protocol"])
        self.assertEqual(ALPHA8_MANIFEST, review_for("0.31.0-alpha.8")["manifest"])
        self.assertEqual(27, review_for("0.31.0-alpha.9")["protocol"])
        self.assertEqual(ALPHA9_MANIFEST, review_for("0.31.0-alpha.9")["manifest"])
        with self.assertRaises(ValueError): review_for("0.31.0-alpha.10")

    def test_frozen_alpha4_manifest_matches_all_actual_assets_and_31_inherited_hashes(self):
        self.assertEqual(ALPHA4_MANIFEST_SHA, file_sha(ALPHA4_MANIFEST))
        assets = validate_manifest(json.loads(ALPHA4_MANIFEST.read_bytes()), "0.31.0-alpha.4")
        previous = json.loads(ALPHA3_MANIFEST.read_bytes())["assets"]
        self.assertEqual([CONTROLLER_ITEM], [name for name in assets if assets[name] != previous[name]])
        for name, digest in assets.items():
            self.assertEqual(digest, file_sha(ROOT / "src/main/resources" / name), name)

    def test_alpha4_defaults_to_frozen_hash_and_optional_cli_cannot_override_it(self):
        self.assertEqual(ALPHA4_MANIFEST_SHA, reviewed_manifest_sha("0.31.0-alpha.4"))
        self.assertEqual(ALPHA4_MANIFEST_SHA, reviewed_manifest_sha("0.31.0-alpha.4", ALPHA4_MANIFEST_SHA.lower()))
        with self.assertRaises(ValueError): reviewed_manifest_sha("0.31.0-alpha.4", "A" * 64)
        with self.assertRaises(ValueError): reviewed_manifest_sha("0.31.0-alpha.3")
        self.assertEqual("A" * 64, reviewed_manifest_sha("0.31.0-alpha.3", "a" * 64))
        package_source = (ROOT / "tools/Package-HomeFcCandidate.ps1").read_text(encoding="utf-8-sig")
        self.assertIn("$manifestHash = '" + ALPHA4_MANIFEST_SHA + "'", package_source)

    def alpha4_manifest(self):
        assets = dict(json.loads(ALPHA3_MANIFEST.read_bytes())["assets"])
        assets[CONTROLLER_ITEM] = "A" * 64
        return {"version": "0.31.0-alpha.4", "protocol": 24, "assets": assets}

    def test_alpha4_only_changes_the_item_pose_and_retains_all_32_paths(self):
        manifest = self.alpha4_manifest()
        self.assertEqual(32, len(validate_manifest(manifest, "0.31.0-alpha.4")))
        self.assertEqual(32, len(validate_manifest(json.loads(ALPHA3_MANIFEST.read_bytes()))))
        for name in (SKIN, "assets/piq_fc_arcade/models/block/home_console_body.json"):
            changed = copy.deepcopy(manifest); changed["assets"][name] = "B" * 64
            with self.assertRaises(ValueError): validate_manifest(changed, "0.31.0-alpha.4")

    def test_alpha4_rejects_wrong_protocol_unreviewed_paths_and_bad_hashes(self):
        for mutate in (
            lambda value: value.update(protocol=23),
            lambda value: value["assets"].pop(CONTROLLER_ITEM),
            lambda value: value["assets"].update({"assets/piq_fc_arcade/models/item/extra.json": "B" * 64}),
            lambda value: value["assets"].update({CONTROLLER_ITEM: "not-a-sha"}),
        ):
            manifest = self.alpha4_manifest(); mutate(manifest)
            with self.assertRaises(ValueError): validate_manifest(manifest, "0.31.0-alpha.4")
        with self.assertRaises(ValueError): validate_manifest(self.alpha4_manifest())

    def enum_document(self):
        return json.loads((ROOT / "src/main/resources" / ENUM_EXTENSIONS).read_bytes())

    def enum_metadata(self, declaration=ENUM_EXTENSIONS):
        return ('[[mods]]\nmodId="piq_fc_arcade"\nversion="0.31.0-alpha.4"\nenumExtensions="' + declaration + '"').encode()

    def test_two_client_arm_pose_extensions_are_valid_without_loading_client_classes(self):
        entries = validate_enum_extensions(self.enum_metadata(), self.enum_document())
        self.assertEqual(2, len(entries))
        self.assertTrue(all(entry["enum"].startswith("net/minecraft/client/") for entry in entries))

    def test_loader_prefix_rule_is_case_insensitive_not_a_lowercase_name_requirement(self):
        self.assertTrue(enum_name_has_mod_prefix("PIQ_FC_ARCADE_CONTROLLER_TWO_HANDS"))
        self.assertTrue(enum_name_has_mod_prefix("piq_fc_arcade_controller_two_hands"))
        self.assertFalse(enum_name_has_mod_prefix("OTHER_CONTROLLER_TWO_HANDS"))

    def test_enum_extensions_reject_wrong_declaration_duplicate_fields_and_constructor(self):
        with self.assertRaises(ValueError): validate_enum_extensions(self.enum_metadata("elsewhere.json"), self.enum_document())
        with self.assertRaises(ValueError): validate_enum_extensions(b'enumExtensions="' + ENUM_EXTENSIONS.encode() + b'"\n[[mods]]\nmodId="piq_fc_arcade"', self.enum_document())
        for mutate in (
            lambda value: value["entries"].append(copy.deepcopy(value["entries"][0])),
            lambda value: value["entries"][1].update(name=value["entries"][0]["name"]),
            lambda value: value["entries"][0].update(constructor="(Z)V"),
            lambda value: value["entries"][0].update(enum="net/minecraft/server/OtherEnum"),
            lambda value: value["entries"][0]["parameters"].update(field="SINGLE_HAND"),
        ):
            document = self.enum_document(); mutate(document)
            with self.assertRaises(ValueError): validate_enum_extensions(self.enum_metadata(), document)

    def test_protocol_bytecode_literal_does_not_accept_prefix_matches(self):
        self.assertTrue(has_protocol_literal('4: ldc #1 // String 24\n', 24))
        self.assertFalse(has_protocol_literal('4: ldc #1 // String 240\n', 24))
        self.assertFalse(has_protocol_literal('4: ldc #1 // String 23\n', 24))

    def test_alpha5_mesh_appearance_scope_does_not_change_historical_reviews(self):
        self.assertFalse(appearance(SUBOR_MESH))
        self.assertTrue(appearance(SUBOR_MESH, True))
        self.assertFalse(appearance("assets/other_mod/meshes/a.json", True))
        self.assertFalse(appearance("assets/piq_fc_arcade/meshes/", True))

    def test_frozen_alpha5_manifest_matches_37_archived_assets_with_32_unchanged(self):
        self.assertEqual(ALPHA5_MANIFEST_SHA, file_sha(ALPHA5_MANIFEST))
        assets = validate_manifest(json.loads(ALPHA5_MANIFEST.read_bytes()), "0.31.0-alpha.5")
        previous = json.loads(ALPHA4_MANIFEST.read_bytes())["assets"]
        self.assertEqual(37, len(assets))
        self.assertEqual(SUBOR_ASSETS, set(assets) - set(previous))
        self.assertTrue(all(assets[name] == digest for name, digest in previous.items()))
        # A newer source tree may change the alpha.6 item display; history is checked
        # against the immutable alpha.5 delivery, never redefined by active sources.
        jar = DELIVERY / "piq_fc_arcade-0.31.0-alpha.5.jar"
        self.assertEqual(ALPHA5_JAR_SHA, file_sha(jar))
        with zipfile.ZipFile(jar) as archive:
            self.assertTrue(all(entry["ok"] for entry in asset_checks(archive, assets)))

    def test_alpha5_defaults_to_immutable_hash_and_cannot_override_it(self):
        self.assertEqual(ALPHA5_MANIFEST_SHA, reviewed_manifest_sha("0.31.0-alpha.5"))
        self.assertEqual(ALPHA5_MANIFEST_SHA, reviewed_manifest_sha("0.31.0-alpha.5", ALPHA5_MANIFEST_SHA.lower()))
        with self.assertRaises(ValueError): reviewed_manifest_sha("0.31.0-alpha.5", "A" * 64)

    def test_alpha5_cannot_reuse_alpha4_one_changed_item_exception(self):
        for name in (CONTROLLER_ITEM, SKIN, "assets/piq_fc_arcade/models/block/home_console_body.json"):
            manifest = json.loads(ALPHA5_MANIFEST.read_bytes())
            manifest["assets"][name] = "B" * 64
            with self.subTest(name=name), self.assertRaises(ValueError):
                validate_manifest(manifest, "0.31.0-alpha.5")

    def test_alpha5_rejects_protocol_missing_or_extra_assets_and_wrong_mesh_hash(self):
        for mutate in (
            lambda value: value.update(protocol=24),
            lambda value: value["assets"].pop(SUBOR_MESH),
            lambda value: value["assets"].update({"assets/piq_fc_arcade/meshes/unreviewed.json": "B" * 64}),
            lambda value: value["assets"].update({SUBOR_MESH: "B" * 64}),
            lambda value: value["assets"].update({SUBOR_TEXTURE: "B" * 64}),
            lambda value: value["assets"].update({SUBOR_MESH: "not-a-sha"}),
        ):
            manifest = json.loads(ALPHA5_MANIFEST.read_bytes()); mutate(manifest)
            with self.assertRaises(ValueError): validate_manifest(manifest, "0.31.0-alpha.5")
        with self.assertRaises(ValueError): validate_manifest(json.loads(ALPHA5_MANIFEST.read_bytes()), "0.31.0-alpha.4")

    def mesh(self):
        return json.loads((ROOT / "src/main/resources" / SUBOR_MESH).read_bytes())

    def test_packaged_mesh_matches_frozen_v1_uv_and_noncord_original_geometry(self):
        self.assertEqual(SUBOR_SOURCE_SHA, file_sha(SUBOR_SOURCE))
        report = validate_subor_mesh(self.mesh(), json.loads(SUBOR_SOURCE.read_bytes()))
        self.assertTrue(report["source_geometry_verified"])
        self.assertEqual(3996, report["source_uv_triangles_verified"])
        self.assertEqual(3324, report["source_noncord_geometry_triangles_verified"])

    def test_mesh_rejects_out_of_range_uv_nonfinite_or_degenerate_vertices(self):
        for mutate in (
            lambda tri: tri["uv"][0].__setitem__(0, 1.01),
            lambda tri: tri["p"][0].__setitem__(0, float("nan")),
            lambda tri: tri["p"].__setitem__(1, tri["p"][0][:]),
            lambda tri: tri.__setitem__("n", [0, 0, 0]),
        ):
            mesh = self.mesh(); mutate(mesh["groups"]["body"]["triangles"][0])
            with self.assertRaises(ValueError): validate_subor_mesh(mesh)

    def test_mesh_rejects_missing_group_wrong_bounds_or_anchor_and_extra_yaw(self):
        for mutate in (
            lambda value: value["groups"].pop("p2_held"),
            lambda value: value["groups"]["p1_held"]["bounds"][0].__setitem__(0, 0),
            lambda value: value["metadata"]["anchors"]["cartridge_bottom_center"].__setitem__(1, 8),
            lambda value: value["metadata"]["held_contract"].update(extra_held_yaw=True),
            lambda value: value["metadata"].update(source_sha256="A" * 64),
        ):
            mesh = self.mesh(); mutate(mesh)
            with self.assertRaises(ValueError): validate_subor_mesh(mesh)

    def test_mesh_source_uv_provenance_rejects_even_valid_range_remapping(self):
        mesh = self.mesh(); mesh["groups"]["p1_held"]["triangles"][0]["uv"][0][0] += .0001
        self.assertTrue(validate_subor_mesh(mesh)["ok"])
        with self.assertRaises(ValueError): validate_subor_mesh(mesh, json.loads(SUBOR_SOURCE.read_bytes()))

    def test_javap_double_constant_check_requires_exact_name_and_value(self):
        declaration = "public static final double FIRST_IDLE_Y = -0.62d;"
        self.assertTrue(has_double_constant(declaration, "FIRST_IDLE_Y", -.62))
        self.assertFalse(has_double_constant(declaration, "IDLE_Y", -.62))
        self.assertFalse(has_double_constant(declaration, "FIRST_IDLE_Y", -.6))
        self.assertTrue(has_double_constant("double CARD_SCALE = 3.0E-1d;", "CARD_SCALE", .3))

    def test_cross_section_registration_must_be_unconditional_and_typed(self):
        method = "public boolean shouldRenderOffScreen(home.Console);\nCode:\n0: iconst_1\n1: ireturn\n"
        self.assertTrue(method_returns_constant_true(method, "shouldRenderOffScreen", "home.Console"))
        self.assertFalse(method_returns_constant_true(method.replace("iconst_1", "iconst_0"), "shouldRenderOffScreen", "home.Console"))
        self.assertFalse(method_returns_constant_true(method, "shouldRenderOffScreen", "home.TV"))
        self.assertFalse(method_returns_constant_true(method.replace("0: iconst_1", "0: aload_1\n1: ifnull 6\n4: iconst_1"), "shouldRenderOffScreen", "home.Console"))

    def alpha6_manifest(self):
        assets = dict(json.loads(ALPHA5_MANIFEST.read_bytes())["assets"])
        assets.update({name: "B" * 64 for name in ALPHA6_ASSETS})
        return {"version": "0.31.0-alpha.6", "protocol": 26, "assets": assets}

    def test_alpha6_requires_exact_47_paths_without_any_old_resource_exception(self):
        with patch("verify_home_fc_final_jar.ALPHA6_MANIFEST_SHA", "A" * 64):
            self.assertEqual(47, len(validate_manifest(self.alpha6_manifest(), "0.31.0-alpha.6")))
            for name in (CONTROLLER_ITEM, SUBOR_MESH, SKIN, "assets/piq_fc_arcade/models/item/subor_console.json"):
                manifest = self.alpha6_manifest(); manifest["assets"][name] = "C" * 64
                with self.subTest(name=name), self.assertRaises(ValueError):
                    validate_manifest(manifest, "0.31.0-alpha.6")

    def test_frozen_alpha6_manifest_matches_47_archived_assets_with_37_unchanged(self):
        self.assertEqual(ALPHA6_MANIFEST_SHA,file_sha(ALPHA6_MANIFEST))
        assets=validate_manifest(json.loads(ALPHA6_MANIFEST.read_bytes()),"0.31.0-alpha.6")
        previous=json.loads(ALPHA5_MANIFEST.read_bytes())["assets"]
        self.assertEqual(47,len(assets));self.assertEqual(ALPHA6_ASSETS,set(assets)-set(previous))
        self.assertTrue(all(assets[name]==digest for name,digest in previous.items()))
        jar = DELIVERY / "piq_fc_arcade-0.31.0-alpha.6.jar"
        self.assertEqual(ALPHA6_JAR_SHA, file_sha(jar))
        with zipfile.ZipFile(jar) as archive:
            self.assertTrue(all(entry["ok"] for entry in asset_checks(archive, assets)))
        item=json.loads(alpha6_asset("assets/piq_fc_arcade/models/item/dual_cabinet.json"))
        self.assertEqual([.85,.85,.85],item["display"]["gui"]["scale"])

    def test_alpha6_rejects_unreviewed_addition_missing_proxy_and_wrong_protocol(self):
        with patch("verify_home_fc_final_jar.ALPHA6_MANIFEST_SHA", "A" * 64):
            for mutate in (
                lambda value: value.update(protocol=25),
                lambda value: value["assets"].pop(next(iter(ALPHA6_ASSETS))),
                lambda value: value["assets"].update({"assets/piq_fc_arcade/models/block/subor_part.json": "B" * 64}),
                lambda value: value["assets"].update({next(iter(ALPHA6_ASSETS)): "not-a-sha"}),
            ):
                manifest = self.alpha6_manifest(); mutate(manifest)
                with self.assertRaises(ValueError): validate_manifest(manifest, "0.31.0-alpha.6")

    def test_alpha6_fails_closed_before_freeze_and_cli_cannot_replace_final_hash(self):
        with patch("verify_home_fc_final_jar.ALPHA6_MANIFEST_SHA", None):
            with self.assertRaises(ValueError): reviewed_manifest_sha("0.31.0-alpha.6", "A" * 64)
            with self.assertRaises(ValueError): validate_manifest(self.alpha6_manifest(), "0.31.0-alpha.6")
        with patch("verify_home_fc_final_jar.ALPHA6_MANIFEST_SHA", "A" * 64):
            self.assertEqual("A" * 64, reviewed_manifest_sha("0.31.0-alpha.6", "a" * 64))
            with self.assertRaises(ValueError): reviewed_manifest_sha("0.31.0-alpha.6", "B" * 64)

    def test_bytecode_method_slice_cannot_use_neighboring_default_constructor_constant(self):
        text = "  public Example();\n    Code:\n       0: iconst_0\n  public boolean place();\n    Code:\n       0: iconst_1\n       1: ireturn\n  public void next();\n    Code:\n       0: return\n"
        body = javap_method(text, "public boolean place();")
        self.assertIn("iconst_1", body)
        self.assertNotIn("iconst_0", body)
        self.assertNotIn("next()", body)
        self.assertEqual("", javap_method(text, "missing();"))

    def wide_fixture(self):
        # Historical review fixtures always come from the frozen JAR, not a later generator.
        return json.loads(alpha6_asset(SUBOR_WIDE_MESH)), self.mesh(), json.loads(SUBOR_SOURCE.read_bytes())

    def test_wide_mesh_retained_uv_handedness_real_slot_and_lid_rotation(self):
        report = validate_subor_wide_mesh(*self.wide_fixture())
        self.assertTrue(report["ok"])
        self.assertEqual(1648, report["retained_source_uv_geometry_triangles"])
        self.assertEqual(600, report["held_triangles_unchanged"])
        self.assertEqual(1272, report["dock_original_uv_triangles"])
        self.assertEqual(35, len(report["slot_vertical_rays"]))

    def test_wide_mesh_rejects_extra_groups_uv_range_and_recorded_bound_changes(self):
        baseline, old, source = self.wide_fixture()
        for mutate in (
            lambda value: value["groups"].update(extra=copy.deepcopy(value["groups"]["lid_closed"])),
            lambda value: value["groups"]["body"]["triangles"][0]["uv"][0].__setitem__(0, 1.1),
            lambda value: value["groups"]["body"]["bounds"][1].__setitem__(1, 99),
            lambda value: value["groups"]["body"]["parts"][0].update(start=1),
        ):
            mesh = copy.deepcopy(baseline); mutate(mesh)
            with self.assertRaises(ValueError): validate_subor_wide_mesh(mesh, old, source)

    def test_wide_mesh_rejects_changed_hands_lid_anchor_and_material_tile(self):
        baseline, old, source = self.wide_fixture()
        for mutate in (
            lambda value: value["groups"]["p1_held"]["triangles"][0]["uv"][0].__setitem__(0, .5),
            lambda value: value["metadata"]["lid"].update(open_x_degrees=90),
            lambda value: value["metadata"]["anchors"]["cartridge_bottom_center"].__setitem__(1, 3),
            lambda value: value["metadata"]["material_uv_samples"].update(shell=[.5, .5]),
        ):
            mesh = copy.deepcopy(baseline); mutate(mesh)
            with self.assertRaises(ValueError): validate_subor_wide_mesh(mesh, old, source)


    def dual_fixture(self):
        root=ROOT/"src/main/resources"
        return (json.loads(alpha6_asset(DUAL_BODY)), dual_source(),
                json.loads((root/LEGACY_BODY).read_bytes()), (root/LEGACY_TEXTURE).read_bytes())

    def test_dual_all_227_elements_uv_and_full_four_facing_world_geometry(self):
        report=validate_dual_model(*self.dual_fixture())
        self.assertTrue(report["ok"]);self.assertEqual(227,report["original_uv_elements"])
        self.assertEqual(4,len(report["four_facing_bounds"]));self.assertEqual([8,0,8],report["rotation_pivot"])
        self.assertAlmostEqual(4/3,report["screen_aspect"])

    def test_dual_rejects_lost_controls_wrong_uv_and_double_scale(self):
        baseline,source,legacy,texture=self.dual_fixture()
        for mutate in (
            lambda model:model["elements"].pop(201),
            lambda model:model["elements"][47]["faces"]["north"]["uv"].__setitem__(0,7),
            lambda model:model["elements"][68]["to"].__setitem__(0,30),
            lambda model:model["elements"][47]["rotation"]["origin"].__setitem__(1,0),
            lambda model:model["textures"].update(particle="unreviewed"),
        ):
            model=copy.deepcopy(baseline);mutate(model)
            with self.assertRaises(ValueError):validate_dual_model(model,source,legacy,texture)

    def test_dual_rejects_modified_static_screen_skin_bytes(self):
        model,source,legacy,texture=self.dual_fixture()
        with self.assertRaises(ValueError):validate_dual_model(model,source,legacy,texture+b"x")

    def lcd_fixture(self):
        root=ROOT/"src/main/resources"
        return json.loads((root/LCD_BODY).read_bytes()),{
            color:(root/("assets/piq_fc_arcade/textures/block/home_retro_tv_"+color+".png")).read_bytes()
            for color in ("screen","dark","rim","back","metal","red","white","yellow")}

    def test_lcd_one_block_four_by_three_and_three_real_recessed_rca_centers(self):
        report=validate_lcd_model(*self.lcd_fixture())
        self.assertEqual(42,report["elements"]);self.assertEqual([[0,0,5],[16,13,11]],report["bounds"])
        self.assertAlmostEqual(4/3,report["screen_aspect"])
        self.assertEqual(3,report["recessed_sockets_verified"])

    def test_lcd_rejects_stretched_screen_unapproved_uv_and_outer_shell_socket_cap(self):
        baseline,textures=self.lcd_fixture()
        for mutate in (
            lambda model:model["elements"][4]["to"].__setitem__(0,14),
            lambda model:model["elements"][0]["faces"]["north"]["uv"].__setitem__(0,1),
            lambda model:model["elements"][5]["to"].__setitem__(2,8.1),
            lambda model:model["textures"].update(screen="unreviewed"),
        ):
            model=copy.deepcopy(baseline);mutate(model)
            with self.assertRaises(ValueError):validate_lcd_model(model,textures)

    def test_lcd_rejects_nonblack_default_screen_without_modifying_disk(self):
        from PIL import Image
        model,textures=self.lcd_fixture();stream=io.BytesIO()
        Image.new("RGBA",(1,1),(30,30,30,255)).save(stream,format="PNG")
        textures["screen"]=stream.getvalue()
        with self.assertRaises(ValueError):validate_lcd_model(model,textures)

    def alpha7_manifest(self):
        assets = dict(json.loads(ALPHA6_MANIFEST.read_bytes())["assets"])
        assets.update({name: "A" * 64 for name in ALPHA7_CHANGED_ASSETS})
        return {"version": "0.31.0-alpha.7", "protocol": 26, "assets": assets}

    def test_alpha7_fails_closed_without_root_freeze_even_with_user_supplied_hash(self):
        with patch("verify_home_fc_final_jar.ALPHA7_MANIFEST_SHA", None):
            for supplied in (None, "A" * 64):
                with self.assertRaises(ValueError): reviewed_manifest_sha("0.31.0-alpha.7", supplied)
            with self.assertRaises(ValueError): validate_manifest(self.alpha7_manifest(), "0.31.0-alpha.7")

    def test_alpha7_frozen_manifest_matches_47_actual_assets_and_only_two_geometry_changes(self):
        self.assertEqual(ALPHA7_MANIFEST_SHA, file_sha(ALPHA7_MANIFEST))
        assets = validate_manifest(json.loads(ALPHA7_MANIFEST.read_bytes()), "0.31.0-alpha.7")
        old = json.loads(ALPHA6_MANIFEST.read_bytes())["assets"]
        self.assertEqual(ALPHA7_CHANGED_ASSETS, {name for name in assets if assets[name] != old[name]})
        for name, digest in assets.items(): self.assertEqual(digest, sha(alpha7_asset(name)))
        self.assertEqual(ALPHA7_MANIFEST_SHA, reviewed_manifest_sha("0.31.0-alpha.7"))
        with self.assertRaises(ValueError): reviewed_manifest_sha("0.31.0-alpha.7", "B" * 64)

    def test_alpha7_only_two_geometries_can_change_without_adding_registry_or_texture_assets(self):
        with patch("verify_home_fc_final_jar.ALPHA7_MANIFEST_SHA", "A" * 64):
            self.assertEqual(47, len(validate_manifest(self.alpha7_manifest(), "0.31.0-alpha.7")))
            for name in (CONTROLLER_ITEM, SUBOR_MESH, SKIN, LCD_BODY, SUBOR_TEXTURE):
                value = self.alpha7_manifest(); value["assets"][name] = "C" * 64
                with self.subTest(name=name), self.assertRaises(ValueError): validate_manifest(value, "0.31.0-alpha.7")
            self.assertEqual("A" * 64, reviewed_manifest_sha("0.31.0-alpha.7", "a" * 64))
            with self.assertRaises(ValueError): reviewed_manifest_sha("0.31.0-alpha.7", "B" * 64)

    def test_alpha7_rejects_omitted_change_new_path_missing_path_and_wrong_protocol(self):
        with patch("verify_home_fc_final_jar.ALPHA7_MANIFEST_SHA", "A" * 64):
            for mutate in (
                lambda value: value.update(protocol=27),
                lambda value: value["assets"].pop(SUBOR_WIDE_MESH),
                lambda value: value["assets"].update({"assets/piq_fc_arcade/models/block/unreviewed.json": "A" * 64}),
                lambda value: value["assets"].update({DUAL_BODY: json.loads(ALPHA6_MANIFEST.read_bytes())["assets"][DUAL_BODY]}),
                lambda value: value["assets"].update({SUBOR_WIDE_MESH: "bad"}),
            ):
                value = self.alpha7_manifest(); mutate(value)
                with self.assertRaises(ValueError): validate_manifest(value, "0.31.0-alpha.7")

    def slim_fixture(self):
        return (json.loads(alpha7_asset(SUBOR_WIDE_MESH)),
                self.mesh(), json.loads(SUBOR_SOURCE.read_bytes()))

    def test_alpha7_slim_preserves_keys_hands_uv_and_real_recess_with_lower_anchors(self):
        report = validate_subor_wide_mesh(*self.slim_fixture(), slim=True)
        self.assertTrue(report["ok"]); self.assertTrue(report["slim_lower_shell"])
        self.assertEqual(1.2, report["lower_shell_reduction_model_units"])
        self.assertEqual([16., 1.52, 23.705], report["anchors"]["cartridge_bottom_center"])
        self.assertEqual(600, report["held_triangles_unchanged"])
        with self.assertRaises(ValueError): validate_subor_wide_mesh(*self.slim_fixture())

    def test_alpha7_slim_rejects_prior_thick_geometry_or_height_only_metadata_patch(self):
        with self.assertRaises(ValueError): validate_subor_wide_mesh(*self.wide_fixture(), slim=True)
        mesh, old, source = self.slim_fixture()
        mesh["metadata"]["anchors"]["av_cable_start"][1] = 2.25
        with self.assertRaises(ValueError): validate_subor_wide_mesh(mesh, old, source, slim=True)
        mesh, old, source = self.slim_fixture()
        mesh["groups"]["body"]["triangles"][0]["p"][0][1] *= .7
        with self.assertRaises(ValueError): validate_subor_wide_mesh(mesh, old, source, slim=True)

    def lowered_fixture(self):
        _, source, legacy, texture = self.dual_fixture()
        return json.loads(alpha7_asset(DUAL_BODY)), source, legacy, texture

    def test_alpha7_dual_preserves_screen_scale_controls_and_eye_height(self):
        report = validate_lowered_dual_model(*self.lowered_fixture())
        self.assertTrue(report["ok"]); self.assertTrue(report["standing_eye_within_screen"])
        self.assertAlmostEqual(2.4, report["height_blocks"])
        self.assertEqual(227, report["unchanged_uv_components"])
        self.assertEqual(12, report["preserved_footprint_cells"])
        with self.assertRaises(ValueError): validate_lowered_dual_model(*self.dual_fixture())

    def test_alpha7_dual_rejects_global_y_scaling_stretched_screen_and_displaced_base(self):
        original, source, legacy, texture = self.lowered_fixture()
        for mutate in (
            lambda value: value["elements"][47]["to"].__setitem__(1, value["elements"][47]["to"][1] - 1),
            lambda value: value["elements"][0]["from"].__setitem__(1, -.1),
            lambda value: value["elements"][68]["to"].__setitem__(0, 1),
            lambda value: value["elements"][47]["rotation"]["origin"].__setitem__(1, 0),
        ):
            value = copy.deepcopy(original); mutate(value)
            with self.assertRaises(ValueError): validate_lowered_dual_model(value, source, legacy, texture)


    def alpha8_manifest(self):
        assets = dict(json.loads(ALPHA7_MANIFEST.read_bytes())["assets"])
        assets.update({name: "A" * 64 for name in ALPHA8_CHANGED_ASSETS})
        return {"version": "0.31.0-alpha.8", "protocol": 26, "assets": assets}

    def test_alpha8_fails_closed_without_review_even_with_cli_hash(self):
        with patch("verify_home_fc_final_jar.ALPHA8_MANIFEST_SHA", None):
            for supplied in (None, "A" * 64):
                with self.assertRaises(ValueError): reviewed_manifest_sha("0.31.0-alpha.8", supplied)
            with self.assertRaises(ValueError): validate_manifest(self.alpha8_manifest(), "0.31.0-alpha.8")

    def test_alpha8_frozen_47_paths_exactly_two_changed_models_45_assets_unchanged(self):
        self.assertEqual(ALPHA8_MANIFEST_SHA, file_sha(ALPHA8_MANIFEST))
        assets = validate_manifest(json.loads(ALPHA8_MANIFEST.read_bytes()), "0.31.0-alpha.8")
        old = json.loads(ALPHA7_MANIFEST.read_bytes())["assets"]
        self.assertEqual(47, len(assets)); self.assertEqual(set(old), set(assets))
        self.assertEqual(ALPHA8_CHANGED_ASSETS, {name for name in assets if assets[name] != old[name]})
        for name, digest in assets.items(): self.assertEqual(digest, sha(alpha8_asset(name)))
        self.assertEqual(ALPHA8_MANIFEST_SHA, reviewed_manifest_sha("0.31.0-alpha.8"))
        with self.assertRaises(ValueError): reviewed_manifest_sha("0.31.0-alpha.8", "A" * 64)

    def test_alpha8_rejects_extra_changes_missing_change_and_bad_protocol(self):
        self.assertEqual(47, len(validate_manifest(self.alpha8_manifest(), "0.31.0-alpha.8")))
        for mutate in (
            lambda v: v.update(protocol=27),
            lambda v: v["assets"].pop(SUBOR_WIDE_MESH),
            lambda v: v["assets"].update({"assets/piq_fc_arcade/meshes/unreviewed.json": "B"*64}),
            lambda v: v["assets"].update({DUAL_BODY: json.loads(ALPHA7_MANIFEST.read_bytes())["assets"][DUAL_BODY]}),
            lambda v: v["assets"].update({SKIN: "C"*64}),
            lambda v: v["assets"].update({SUBOR_TEXTURE: "C"*64}),
            lambda v: v["assets"].update({SUBOR_WIDE_MESH: "bad"}),
        ):
            value=self.alpha8_manifest();mutate(value)
            with self.assertRaises(ValueError): validate_manifest(value,"0.31.0-alpha.8")

    def compact_fixture(self):
        return (json.loads(alpha8_asset(SUBOR_WIDE_MESH)),
                self.mesh(),json.loads(SUBOR_SOURCE.read_bytes()))

    def test_alpha8_compact_keeps_full_size_card_slot_lid_held_and_all_original_uv(self):
        report=validate_subor_wide_mesh(*self.compact_fixture(),compact=True)
        self.assertTrue(report["ok"]);self.assertTrue(report["compact_planar_chassis"])
        self.assertEqual(600,report["held_triangles_unchanged"])
        self.assertEqual([16,1.52,22.164],report["anchors"]["cartridge_bottom_center"])
        self.assertEqual([24.32,1.05,23.632],report["anchors"]["av_cable_start"])
        self.assertEqual(35,len(report["slot_vertical_rays"]))
        with self.assertRaises(ValueError): validate_subor_wide_mesh(*self.compact_fixture(),slim=True)
        with self.assertRaises(ValueError): validate_subor_wide_mesh(*self.slim_fixture(),compact=True)

    def test_alpha8_compact_rejects_scaled_card_lid_uv_changed_height_and_old_rca(self):
        baseline,old,source=self.compact_fixture()
        for mutate in (
            lambda v:v["metadata"]["anchors"]["cartridge_inserted_bounds"][1].__setitem__(1,5),
            lambda v:v["metadata"]["anchors"]["av_cable_start"].__setitem__(2,25.54),
            lambda v:v["groups"]["p1_held"]["triangles"][0]["uv"][0].__setitem__(0,.5),
            lambda v:v["groups"]["lid_closed"]["triangles"][0]["p"][0].__setitem__(0,13),
            lambda v:v["groups"]["body"]["triangles"][0]["p"][0].__setitem__(1,.2),
        ):
            value=copy.deepcopy(baseline);mutate(value)
            with self.assertRaises(ValueError):validate_subor_wide_mesh(value,old,source,compact=True)

    def native_dual_fixture(self):
        _,source,legacy,texture=self.dual_fixture()
        return json.loads(alpha8_asset(DUAL_BODY)),source,legacy,texture

    def test_alpha9_fails_closed_without_review_even_with_caller_digest(self):
        with patch("verify_home_fc_final_jar.ALPHA9_MANIFEST_SHA", None):
            for supplied in (None, "A" * 64):
                with self.assertRaises(ValueError): reviewed_manifest_sha("0.31.0-alpha.9", supplied)
            with self.assertRaises(ValueError): validate_manifest({}, "0.31.0-alpha.9")

    def alpha9_manifest(self):
        return json.loads(ALPHA9_MANIFEST.read_bytes())

    def test_alpha9_frozen_57_paths_preserve_46_old_assets_and_all_pngs(self):
        self.assertEqual(ALPHA9_MANIFEST_SHA,file_sha(ALPHA9_MANIFEST))
        self.assertEqual(ALPHA9_INITIAL_MANIFEST_SHA,file_sha(ALPHA9_INITIAL_MANIFEST))
        first=json.loads(ALPHA9_INITIAL_MANIFEST.read_bytes())["assets"]
        assets=validate_manifest(self.alpha9_manifest(),"0.31.0-alpha.9")
        self.assertEqual({"assets/piq_fc_arcade/models/item/wide_lcd_tv.json"},{n for n in first if first[n]!=assets[n]})
        old=json.loads(ALPHA8_MANIFEST.read_bytes())["assets"]
        self.assertEqual(57,len(assets));self.assertEqual(ALPHA9_ADDED_ASSETS,set(assets)-set(old))
        self.assertEqual({DUAL_BODY},{n for n in old if old[n]!=assets[n]})
        self.assertFalse(any(n.endswith(".png") for n in ALPHA9_ADDED_ASSETS))
        self.assertEqual(ALPHA9_MANIFEST_SHA,reviewed_manifest_sha("0.31.0-alpha.9"))
        with self.assertRaises(ValueError):reviewed_manifest_sha("0.31.0-alpha.9","A"*64)
        for name,digest in assets.items():self.assertEqual(digest,file_sha(ROOT/"src/main/resources"/name))

    def test_alpha9_rejects_extra_or_missing_paths_changed_old_assets_and_wrong_protocol(self):
        for mutate in (
            lambda m:m.update(protocol=26),
            lambda m:m["assets"].pop(next(iter(ALPHA9_ADDED_ASSETS))),
            lambda m:m["assets"].update({"assets/piq_fc_arcade/models/block/other.json":"A"*64}),
            lambda m:m["assets"].update({SUBOR_WIDE_MESH:"B"*64}),
            lambda m:m["assets"].update({SKIN:"C"*64}),
            lambda m:m["assets"].update({DUAL_BODY:json.loads(ALPHA8_MANIFEST.read_bytes())["assets"][DUAL_BODY]}),
        ):
            m=self.alpha9_manifest();mutate(m)
            with self.assertRaises(ValueError):validate_manifest(m,"0.31.0-alpha.9")

    def header_fixture(self):
        _,source,legacy,texture=self.native_dual_fixture()
        return json.loads((ROOT/"src/main/resources"/DUAL_BODY).read_bytes()),source,legacy,texture

    def test_alpha9_header_rebases_without_moving_200_playable_and_lower_world_parts(self):
        report=validate_header_dual_model(*self.header_fixture())
        self.assertTrue(report["ok"]);self.assertEqual(200,report["unchanged_non_header_world_parts"])
        self.assertEqual(2.35,report["height_blocks"]);self.assertEqual(.35,report["ber_y_offset"])
        self.assertTrue(report["standing_eye_within_screen"])
        with self.assertRaises(ValueError):validate_header_dual_model(*self.native_dual_fixture())

    def test_alpha9_header_rejects_missing_roof_face_wrong_rebase_or_changed_controls(self):
        baseline,source,legacy,texture=self.header_fixture()
        for mutate in (
            lambda m:m["elements"][48]["faces"].pop("up"),
            lambda m:m["elements"][55]["faces"].pop("down"),
            lambda m:m["elements"][68]["from"].__setitem__(1,10),
            lambda m:m["elements"][47]["rotation"]["origin"].__setitem__(1,16.55),
            lambda m:m["elements"][54]["to"].__setitem__(0,25),
            lambda m:m["elements"][49]["to"].__setitem__(1,37.6),
        ):
            m=copy.deepcopy(baseline);mutate(m)
            with self.assertRaises(ValueError):validate_header_dual_model(m,source,legacy,texture)

    def test_alpha9_wide_lcd_rejects_wrong_screen_depth_size_colour_and_socket_hole(self):
        from verify_home_fc_alpha9_geometry import wide_lcd
        baseline=json.loads((ROOT/"src/main/resources/assets/piq_fc_arcade/models/block/home_wide_lcd_tv.json").read_bytes())
        report=wide_lcd(baseline);self.assertTrue(report["ok"]);self.assertEqual(2,report["reserved_cells"])
        for mutate in (
            lambda m:m["elements"][4]["from"].__setitem__(2,5.5),
            lambda m:m["elements"][4]["to"].__setitem__(0,21),
            lambda m:next(e for e in m["elements"] if e["name"]=="yellow凹口底")["to"].__setitem__(2,8.23),
            lambda m:next(e for e in m["elements"] if e["name"]=="yellowRCA上边")["faces"]["north"].update(texture="#red"),
        ):
            m=copy.deepcopy(baseline);mutate(m)
            with self.assertRaises(ValueError):wide_lcd(m)

    def test_alpha9_pcb_shell_bounds_contacts_cover_and_chip_variant_are_not_generator_self_check(self):
        from verify_home_fc_alpha9_geometry import cartridge
        path=ROOT/"src/main/resources/assets/piq_fc_arcade/models/block"
        boards={"board_"+str(i):json.loads((path/("home_fc_board_"+str(i)+".json")).read_bytes()) for i in range(3)}
        baseline={**boards,"shell":json.loads((path/"home_fc_cartridge_shell.json").read_bytes())}
        whole=json.loads(alpha8_asset("assets/piq_fc_arcade/models/block/home_fc_cartridge.json"))
        report=cartridge(baseline,whole);self.assertTrue(report["ok"]);self.assertEqual(3,len(report["boards"]))
        for mutate in (
            lambda m:m["board_0"]["elements"][0]["to"].__setitem__(0,14),
            lambda m:m["board_1"]["textures"].update(pcb="piq_fc_arcade:block/unauthorized"),
            lambda m:next(e for e in m["board_2"]["elements"] if e["name"]=="黑胶圆角").update(name="lost"),
            lambda m:next(e for e in m["shell"]["elements"] if e["name"]=="前壳 / 中央游戏标签")["faces"]["north"].update(uv=[0,0,16,16]),
        ):
            m=copy.deepcopy(baseline);mutate(m)
            with self.assertRaises(ValueError):cartridge(m,whole)

    def test_alpha8_native_dual_has_unscaled_controls_16_by_9_glass_and_two_block_height(self):
        report=validate_native_dual_model(*self.native_dual_fixture())
        self.assertTrue(report["ok"]);self.assertTrue(report["controls_native_scale"])
        self.assertTrue(report["standing_eye_within_screen"])
        self.assertEqual(2,report["height_blocks"]);self.assertEqual(227,report["unchanged_uv_components"])
        self.assertAlmostEqual(16/9,report["physical_screen_aspect"])
        with self.assertRaises(ValueError):validate_native_dual_model(*self.lowered_fixture())

    def test_alpha8_native_dual_rejects_old_wedge_missing_controls_wrong_uv_glass_or_pivot(self):
        baseline,source,legacy,texture=self.native_dual_fixture()
        for mutate in (
            lambda v:v["elements"].pop(201),
            lambda v:v["elements"][47]["to"].__setitem__(1,30),
            lambda v:v["elements"][47]["rotation"]["origin"].__setitem__(1,0),
            lambda v:v["elements"][41]["from"].__setitem__(2,3.08),
            lambda v:v["elements"][68]["faces"]["north"]["uv"].__setitem__(0,1),
            lambda v:v["elements"][68]["to"].__setitem__(0,30),
        ):
            value=copy.deepcopy(baseline);mutate(value)
            with self.assertRaises(ValueError):validate_native_dual_model(value,source,legacy,texture)


if __name__ == "__main__":
    unittest.main()
