"""Model compiler regression tests; synthetic cuboids and read-only shipped assets."""
import json
from pathlib import Path
import tempfile
import unittest

import prepare_furniture36 as m
from preview_furniture36 import coplanar, geometry_checks


def box(name,lo,hi,rotation=(0,0,0),origin=(0,0,0)):
    return {"name":name,"type":"cube","from":lo,"to":hi,"rotation":rotation,"origin":origin,
            "faces":{direction:{"uv":[0,0,16,16],"texture":0}
                     for direction in ("north","south","east","west","up","down")}}


def fixture(elements):
    with tempfile.TemporaryDirectory(prefix="piq-furniture-test-") as tmp:
        path=Path(tmp)/"test.bbmodel"
        path.write_text(json.dumps({"resolution":{"width":16,"height":16},"elements":elements}),encoding="utf-8")
        return m.load_model(path)[1:]


class FurnitureGeometryTest(unittest.TestCase):
    def assert_surface(self,elements,expected):
        solids,faces=fixture(elements)
        polys,_=m.union_faces(solids,faces)
        self.assertAlmostEqual(expected,sum(m.area(p) for f,p in polys),places=6)
        self.assertEqual([],coplanar(polys))
        return polys

    def test_identical_boxes_have_one_boundary_owner(self):
        polys=self.assert_surface([box("a",[0,0,0],[1,1,1]),box("b",[0,0,0],[1,1,1])],6)
        self.assertEqual({"a"},{f.name for f,p in polys})

    def test_adjacent_boxes_remove_both_internal_touching_faces(self):
        self.assert_surface([box("a",[0,0,0],[1,1,1]),box("b",[1,0,0],[2,1,1])],10)

    def test_partial_overlap_keeps_union_boundary(self):
        self.assert_surface([box("a",[0,0,0],[1,1,1]),box("b",[.5,0,0],[1.5,1,1])],8)

    def test_contained_box_has_no_visible_faces(self):
        polys=self.assert_surface([box("a",[0,0,0],[2,2,2]),box("b",[.5,.5,.5],[1.5,1.5,1.5])],24)
        self.assertEqual({"a"},{f.name for f,p in polys})

    def test_contained_rotated_box_has_no_visible_faces(self):
        self.assert_surface([box("a",[-2,-2,-2],[2,2,2]),box("b",[-.5,-.5,-.5],[.5,.5,.5],(45,0,0))],96)

    def test_disjoint_coplanar_faces_are_not_removed(self):
        self.assert_surface([box("a",[0,0,0],[1,1,1]),box("b",[2,0,0],[3,1,1])],12)

    def test_detail_geometry_stays_unmodified(self):
        solids,faces=fixture([box("外架左斜腿",[0,0,0],[1,1,1]),box("织带",[0,0,0],[1,1,1])])
        polys,_=m.union_faces(solids,faces)
        self.assertEqual(6,len([p for f,p in polys if f.role=="cloth"]))

    def test_uv_tile_cuts_preserve_surface_area(self):
        poly=[(-3,0,0),(-3,0,10),(38,0,10),(38,0,0)]
        parts=m.split_uv(poly,lambda p:(p[0]/16,p[2]/16))
        self.assertAlmostEqual(m.area(poly),sum(m.area(p) for p in parts))
        self.assertEqual(4,len(parts))

    def test_existing_models_regenerate_exactly_without_writes(self):
        for key in m.MODELS:
            mesh,report,_=m.compile_mesh(key,False)
            self.assertFalse(report["minecraftTextureCopied"])
            self.assertEqual(16,mesh["unitsPerBlock"])
            self.assertEqual(1,mesh["version"])

    def test_original_cap_overlap_is_removed_with_full_coverage(self):
        report=geometry_checks()
        self.assertEqual(8,len(report["bench"]["originalCoplanarOverlaps"]))
        for key in ("stool_open","stool_folded"):
            self.assertEqual(112,len(report[key]["originalCoplanarOverlaps"]))
            self.assertEqual(8,len(report[key]["crossbarEndCapCoverage"]))
        for value in report.values(): self.assertEqual([],value["remainingCoplanarOverlaps"])


if __name__ == "__main__": unittest.main()
