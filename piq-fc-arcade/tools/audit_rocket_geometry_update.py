"""Audit beta.3 geometry against the retained, byte-verified beta.2 JAR.

Does not edit assets. Reports every semantic change and writes a unified diff.
The approved changes only separate coplanar surfaces, join the four screen-frame
corners edge-to-edge, and remove two caps strictly hidden by the opaque roof.
"""
from __future__ import annotations

import argparse
import copy
import difflib
import io
import json
import zipfile
from pathlib import Path

import numpy as np
from PIL import Image

from check_rocket_coplanar_faces import (classify_overlaps, coplanar_overlaps,
                                        strict_covering_elements)
from render_rocket_arcade_preview import (ASSETS, DEFAULT_MODEL, PROJECT, collect_quads,
                                         resolve_texture, sha, texture_path, vertices_for)

BASELINE_JAR = (PROJECT.parent / "制作Mod/03-街机模拟/PIQ-FC街机/"
                "piq_fc_arcade-0.30.0-beta.2.jar")
BODY_ENTRY = "assets/piq_fc_arcade/models/block/rocket_arcade_body.json"
BASELINE_BODY_SHA = "384E6C49268198EB48AAD29D91E9AB3FF333A71AC9435406F2443DBA583B6C1E"
SIDE_LAYERS = {8: 0, 9: 1, 10: 2, 11: 2, 12: 0, 13: 1, 14: 2, 15: 3, 16: 1, 17: 0}
EXTRA_ENDPOINTS = (
    (0, "from", 2, 2.985), (3, "from", 2, 2.925),
    (19, "from", 2, 4.285), (33, "from", 2, 4.285),
    (20, "from", 2, 2.495), (34, "from", 2, 2.495),
    (20, "from", 0, 14.455), (20, "to", 0, 15.295),
    (34, "from", 0, .705), (34, "to", 0, 1.545),
    (44, "from", 0, 2.58), (44, "to", 0, 13.42),
    (45, "from", 0, 2.58), (45, "to", 0, 13.42),
    (41, "to", 1, 27.435), (50, "from", 2, 2.705), (51, "from", 2, 2.705),
)


def load_baseline(path=BASELINE_JAR):
    with zipfile.ZipFile(path) as archive:
        if archive.namelist().count(BODY_ENTRY) != 1:
            raise ValueError("baseline JAR must contain exactly one body model")
        raw = archive.read(BODY_ENTRY)
    if sha(raw) != BASELINE_BODY_SHA:
        raise ValueError("baseline model bytes do not match the approved beta.2 geometry")
    return raw, json.loads(raw.decode("utf-8-sig"))


def approved_model(before):
    expected = copy.deepcopy(before)
    for index, level in SIDE_LAYERS.items():
        for target, sign in ((index, 1), (index + 14, -1)):
            for key in ("from", "to"):
                expected["elements"][target][key][0] = round(
                    before["elements"][target][key][0] + sign * level * .015, 6)
    for index in (9, 23):
        expected["elements"][index]["to"][2] = 14.485
        del expected["elements"][index]["faces"]["up"]
    for index, key, axis, value in EXTRA_ENDPOINTS:
        expected["elements"][index][key][axis] = value
    return expected


def bounds(model, transformed):
    values = (np.concatenate([quad.vertices for quad in collect_quads(model)]) if transformed else
              np.asarray([element[key] for element in model["elements"] for key in ("from", "to")]))
    return {"min": values.min(axis=0).tolist(), "max": values.max(axis=0).tolist()}


def semantic_changes(before, after, path=""):
    if isinstance(before, dict) and isinstance(after, dict):
        result = []
        for key in before.keys() | after.keys():
            subpath = path + "/" + str(key)
            if key not in before or key not in after:
                result.append({"path": subpath, "before": before.get(key), "after": after.get(key)})
            else:
                result.extend(semantic_changes(before[key], after[key], subpath))
        return sorted(result, key=lambda item: item["path"])
    if isinstance(before, list) and isinstance(after, list) and len(before) == len(after):
        return [change for i, (a, b) in enumerate(zip(before, after))
                for change in semantic_changes(a, b, path + "/" + str(i))]
    return [] if before == after else [{"path": path, "before": before, "after": after}]


def opaque_element_faces(model, texture_arrays):
    """Conservative full UV-rectangle alpha scan, including endpoint texels."""
    result = {}
    for index, element in enumerate(model["elements"]):
        face_info = []
        for direction, face in element.get("faces", {}).items():
            texture = texture_arrays[resolve_texture(model["textures"], face["texture"])]
            uv = np.asarray(face["uv"]) * np.array((texture.shape[1], texture.shape[0]) * 2) / 16
            x0, x1 = sorted((int(np.floor(uv[0])), int(np.floor(uv[2]))))
            y0, y1 = sorted((int(np.floor(uv[1])), int(np.floor(uv[3]))))
            x0, x1 = np.clip((x0, x1), 0, texture.shape[1] - 1)
            y0, y1 = np.clip((y0, y1), 0, texture.shape[0] - 1)
            alpha = texture[y0:y1 + 1, x0:x1 + 1, 3]
            face_info.append({"face": direction, "min_alpha": int(alpha.min()),
                              "discarded_texels": int(np.count_nonzero(alpha < 26))})
        result[index] = {"all_faces_opaque_cutout": all(item["discarded_texels"] == 0 for item in face_info),
                         "faces": face_info}
    return result


def audit(before, after, textures):
    changes = semantic_changes(before, after)
    removed = [(i, direction) for i, element in enumerate(before["elements"])
               for direction in element["faces"] if direction not in after["elements"][i]["faces"]]
    unchanged_uv = before["textures"] == after["textures"] and all(
        face == before["elements"][i]["faces"].get(direction)
        for i, element in enumerate(after["elements"]) for direction, face in element["faces"].items())
    opaque = opaque_element_faces(after, textures)
    removed_proofs = []
    for index, direction in removed:
        # vertices_for uses the updated box even though its face has been removed.
        vertices = vertices_for(after["elements"][index], direction)
        covering = strict_covering_elements(after, vertices)
        removed_proofs.append({"element_index": index, "face": direction,
                               "vertices": vertices.tolist(), "strictly_covered_by": covering,
                               "opaque_covering_elements": [i for i in covering if opaque[i]["all_faces_opaque_cutout"]]})
    overlaps = classify_overlaps(after, coplanar_overlaps(after))
    for item in overlaps:
        proof = item["visibility_proof"]
        if proof["classification"] == "strictly_inside_closed_box":
            satisfied = any(opaque[i]["all_faces_opaque_cutout"] for i in proof["covering_element_indices"])
        elif proof["classification"] == "internal_opposed_interface":
            satisfied = all(opaque[item[key]["element_index"]]["all_faces_opaque_cutout"] for key in ("a", "b"))
        else:
            satisfied = False
        proof["actual_texture_cutout_verified"] = satisfied
    raw_before, raw_after = bounds(before, False), bounds(after, False)
    rotated_before, rotated_after = bounds(before, True), bounds(after, True)
    screen_before, screen_after = before["elements"][47], after["elements"][47]
    checks = {
        "only_approved_geometry_changes": after == approved_model(before),
        "element_count_unchanged_155": len(before["elements"]) == len(after["elements"]) == 155,
        "all_surviving_face_uv_rotation_texture_unchanged": unchanged_uv,
        "screen_47_entire_element_and_four_corners_exact": screen_before == screen_after and np.array_equal(
            vertices_for(screen_before, "north"), vertices_for(screen_after, "north")),
        "unrotated_bounds_exact": raw_before == raw_after,
        "rotated_bounds_exact": rotated_before == rotated_after,
        "only_two_deleted_caps_with_strict_opaque_cover": removed == [(9, "up"), (23, "up")] and all(
            proof["opaque_covering_elements"] for proof in removed_proofs),
        "horizontal_screen_frames_join_vertical_frames_without_gap": all(
            after["elements"][i]["from"][0] == after["elements"][43]["to"][0]
            and after["elements"][i]["to"][0] == after["elements"][42]["from"][0] for i in (44, 45)),
        "all_remaining_overlaps_have_opaque_internal_proof": all(
            item["visibility_proof"]["actual_texture_cutout_verified"] for item in overlaps),
    }
    return {"ok": all(checks.values()), "checks": checks, "semantic_changes": changes,
            "changed_element_indices": sorted({int(item["path"].split("/")[2]) for item in changes}),
            "faces_before": sum(len(e["faces"]) for e in before["elements"]),
            "faces_after": sum(len(e["faces"]) for e in after["elements"]),
            "screen_vertices_before": vertices_for(screen_before, "north").tolist(),
            "screen_vertices_after": vertices_for(screen_after, "north").tolist(),
            "unrotated_bounds_before": raw_before, "unrotated_bounds_after": raw_after,
            "rotated_bounds_before": rotated_before, "rotated_bounds_after": rotated_after,
            "removed_face_proofs": removed_proofs, "remaining_overlaps": overlaps,
            "opacity_scan": opaque,
            "limitations": ["Offline analytic geometry and actual UV alpha, not an in-game screenshot",
                            "Exact coplanarity is checked; near-plane depth precision is not simulated"]}


def load_textures(model, assets=ASSETS):
    arrays, hashes = {}, {}
    references = {resolve_texture(model["textures"], face["texture"])
                  for element in model["elements"] for face in element["faces"].values()}
    for reference in references:
        raw = texture_path(assets, reference).read_bytes()
        arrays[reference] = np.array(Image.open(io.BytesIO(raw)).convert("RGBA"))
        hashes[reference] = sha(raw)
    return arrays, hashes


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline-jar", type=Path, default=BASELINE_JAR)
    parser.add_argument("--model", type=Path, default=DEFAULT_MODEL)
    parser.add_argument("--assets", type=Path, default=ASSETS)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    raw_before, before = load_baseline(args.baseline_jar)
    raw_after = args.model.read_bytes()
    after = json.loads(raw_after.decode("utf-8-sig"))
    textures, texture_hashes = load_textures(after, args.assets)
    report = audit(before, after, textures)
    report.update({"baseline_jar": str(args.baseline_jar.resolve()), "baseline_body_sha256": sha(raw_before),
                   "actual_model": str(args.model.resolve()), "actual_body_sha256": sha(raw_after),
                   "texture_sha256": texture_hashes})
    args.output.mkdir(parents=True, exist_ok=True)
    report_path = args.output / "geometry-change-audit.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    diff = difflib.unified_diff(raw_before.decode("utf-8-sig").splitlines(keepends=True),
                                raw_after.decode("utf-8-sig").splitlines(keepends=True),
                                fromfile="beta.2.jar/" + BODY_ENTRY, tofile=str(args.model.resolve()))
    (args.output / "geometry-before-after.diff").write_text("".join(diff), encoding="utf-8")
    print(json.dumps({"ok": report["ok"], "report": str(report_path.resolve()), "checks": report["checks"],
                      "body_sha256": sha(raw_after), "remaining_overlap_count": len(report["remaining_overlaps"])},
                     ensure_ascii=False))
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
