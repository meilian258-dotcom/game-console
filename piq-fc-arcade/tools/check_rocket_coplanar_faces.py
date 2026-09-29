"""Read-only exact planar overlap audit of exported Minecraft model faces.

Reuses the preview's FaceBakery-equivalent element transforms. Convex polygon
clipping is performed on rotated quads, not their bounding boxes. Positive-area
coplanar overlaps are reported; edge/point contact is not an overlap. This is a
geometry audit, not a claim that every reported face is externally visible.
"""
from __future__ import annotations

import argparse
import itertools
import json
from collections import defaultdict
from pathlib import Path

import numpy as np

from render_rocket_arcade_preview import DEFAULT_MODEL, collect_quads, rotated, sha

EPSILON = 1e-7


def cross2(a, b):
    return float(a[0] * b[1] - a[1] * b[0])


def signed_area(polygon):
    if len(polygon) < 3:
        return 0.0
    return sum(cross2(polygon[i], polygon[(i + 1) % len(polygon)])
               for i in range(len(polygon))) * 0.5


def ccw(polygon):
    polygon = np.asarray(polygon, dtype=np.float64)
    return polygon if signed_area(polygon) >= 0 else polygon[::-1]


def intersect_convex(subject, clip):
    """Sutherland-Hodgman intersection of two convex polygons, either winding."""
    output = list(ccw(subject))
    clip = ccw(clip)
    for a, b in zip(clip, np.roll(clip, -1, axis=0)):
        if not output:
            break
        source, output = output, []
        previous = source[-1]
        old_distance = cross2(b - a, previous - a)
        for point in source:
            distance = cross2(b - a, point - a)
            old_inside, inside = old_distance >= -EPSILON, distance >= -EPSILON
            if inside != old_inside:
                denominator = old_distance - distance
                if abs(denominator) > EPSILON:
                    output.append(previous + (point - previous) * old_distance / denominator)
            if inside:
                output.append(point)
            previous, old_distance = point, distance
    return np.asarray(output, dtype=np.float64).reshape((-1, 2))


def coplanar_overlaps(model):
    quads = collect_quads(model)
    groups = defaultdict(list)
    for quad in quads:
        normal = np.cross(quad.vertices[1] - quad.vertices[0],
                          quad.vertices[2] - quad.vertices[0])
        length = np.linalg.norm(normal)
        if length <= EPSILON:
            continue
        normal /= length
        sign = next(float(np.sign(value)) for value in normal if abs(value) > EPSILON)
        canonical = normal * sign
        groups[tuple(np.round(canonical, 8))].append((quad, normal, canonical))
    result = []
    for group in groups.values():
        for (a, normal_a, normal), (b, normal_b, normal2) in itertools.combinations(group, 2):
            if a.element_index == b.element_index or np.linalg.norm(normal - normal2) > EPSILON:
                continue
            offset = float(np.dot(normal, a.vertices[0]))
            if np.max(np.abs(b.vertices @ normal - offset)) > EPSILON:
                continue
            dropped_axis = int(np.argmax(np.abs(normal)))
            axes = [i for i in range(3) if i != dropped_axis]
            av, bv = a.vertices[:, axes], b.vertices[:, axes]
            if np.any(np.minimum(av.max(axis=0), bv.max(axis=0)) -
                      np.maximum(av.min(axis=0), bv.min(axis=0)) <= EPSILON):
                continue
            overlap = intersect_convex(av, bv)
            area = abs(signed_area(overlap)) / abs(normal[dropped_axis])
            if area <= EPSILON:
                continue
            vertices = np.zeros((len(overlap), 3), dtype=np.float64)
            vertices[:, axes] = overlap
            vertices[:, dropped_axis] = (offset - overlap @ normal[axes]) / normal[dropped_axis]
            areas = [abs(signed_area(v)) / abs(normal[dropped_axis]) for v in (av, bv)]
            def describe(quad):
                return {"element_index": quad.element_index,
                        "name": model["elements"][quad.element_index].get("name", ""),
                        "face": quad.direction, "vertices": quad.vertices.tolist()}
            result.append({"a": describe(a), "b": describe(b), "area_model_units_squared": area,
                           "same_facing": bool(np.dot(normal_a, normal_b) > 0),
                           "duplicate_geometry": all(abs(area - value) <= EPSILON for value in areas),
                           "plane_normal": normal.tolist(), "plane_offset": offset,
                           "intersection_vertices": vertices.tolist()})
    return sorted(result, key=lambda item: (-item["area_model_units_squared"],
                                           item["a"]["element_index"], item["b"]["element_index"]))


def strict_covering_elements(model, vertices):
    """Whole convex polygon strictly inside a different closed transformed box.

    Testing every vertex suffices: both the polygon and the box are convex.
    Boundary contact is deliberately excluded; it does not prove occlusion.
    """
    points = np.asarray(vertices, dtype=np.float64)
    covering = []
    for index, element in enumerate(model["elements"]):
        if len(element.get("faces", {})) != 6:
            continue
        offset = rotated(np.zeros((1, 3)), element.get("rotation"))[0]
        matrix = rotated(np.eye(3), element.get("rotation")) - offset
        local = (points - offset) @ np.linalg.inv(matrix)
        if (np.all(local > np.asarray(element["from"]) + EPSILON) and
                np.all(local < np.asarray(element["to"]) - EPSILON)):
            covering.append(index)
    return covering


def classify_overlaps(model, overlaps):
    """Conservative geometric proof, independent of camera and face names.

    Opposing faces of two nondegenerate closed boxes form an internal interface:
    their interiors lie on opposite sides of the common plane. Positive-area
    interior points of the intersection cannot be approached from exterior space
    without crossing one box. Perimeter edge contact has zero area.
    """
    for overlap in overlaps:
        covering = strict_covering_elements(model, overlap["intersection_vertices"])
        if covering:
            classification = "strictly_inside_closed_box"
        elif not overlap["same_facing"] and all(
                len(model["elements"][overlap[key]["element_index"]].get("faces", {})) == 6
                and np.all(np.asarray(model["elements"][overlap[key]["element_index"]]["to"]) >
                           np.asarray(model["elements"][overlap[key]["element_index"]]["from"]))
                for key in ("a", "b")):
            classification = "internal_opposed_interface"
        else:
            classification = "unproven_exterior"
        overlap["visibility_proof"] = {"classification": classification,
                                       "covering_element_indices": covering,
                                       "assumption": "closed box surfaces use opaque surviving cutout texels"}
    return overlaps


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", type=Path, default=DEFAULT_MODEL)
    parser.add_argument("--output", type=Path, help="optional standalone JSON report")
    args = parser.parse_args(argv)
    raw = args.model.read_bytes()
    model = json.loads(raw.decode("utf-8-sig"))
    overlaps = classify_overlaps(model, coplanar_overlaps(model))
    report = {"model": str(args.model.resolve()), "sha256": sha(raw),
              "elements": len(model["elements"]), "overlap_count": len(overlaps),
              "same_facing_count": sum(item["same_facing"] for item in overlaps),
              "duplicate_geometry_count": sum(item["duplicate_geometry"] for item in overlaps),
              "visibility_counts": {kind: sum(item["visibility_proof"]["classification"] == kind
                                               for item in overlaps)
                                    for kind in ("strictly_inside_closed_box", "internal_opposed_interface",
                                                 "unproven_exterior")},
              "epsilon_model_units": EPSILON, "overlaps": overlaps,
              "limitations": ["Conservative convex-box visibility proof, not a Minecraft screenshot",
                              "No texture-alpha classification", "Only exact coplanarity, not near planes"]}
    content = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(content, encoding="utf-8")
        print(json.dumps({k: v for k, v in report.items() if k != "overlaps"}, ensure_ascii=False))
    else:
        print(content, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
