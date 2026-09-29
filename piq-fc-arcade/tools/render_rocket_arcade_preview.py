"""Deterministic orthographic previews of the *actual* exported Minecraft assets.

No model/texture generation, GUI, Minecraft process, network or element-name
heuristics. Uses Pillow + NumPy already provided by the local Codex runtime.

Geometry/UV conventions were checked against the local Minecraft mapped sources:
FaceInfo vertex order; BlockFaceUV.getU/getV/shiftedIndex; FaceBakery
applyElementRotation/rotateVertexBy. This is NOT a Minecraft screenshot:
no AO, world lighting, animation, texture-atlas shrink, neighbours or BER screen.
Unsupported geometry/tint features fail validation explicitly. Alpha uses the
final wrapper's minecraft:cutout layer: discard alpha < 0.1, no blending.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import math
import re
import sys
import zipfile
from collections import Counter
from dataclasses import dataclass
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

PROJECT = Path(__file__).resolve().parents[1]
ASSETS = PROJECT / "src/main/resources/assets"
DEFAULT_MODEL = ASSETS / "piq_fc_arcade/models/block/rocket_arcade_body.json"
DEFAULT_OUTPUT = PROJECT / "build/rocket-arcade-preview"
VALID_ANGLES = {-45.0, -22.5, 0.0, 22.5, 45.0}
VALID_FACES = {"north", "south", "east", "west", "up", "down"}
RESOURCE = re.compile(r"^[a-z0-9_.-]+:[a-z0-9/._-]+$")
# 0=min and 1=max along x/y/z. Exact Minecraft FaceInfo vertex order.
FACE_CORNERS = {
    "down": ((0, 0, 1), (0, 0, 0), (1, 0, 0), (1, 0, 1)),
    "up": ((0, 1, 0), (0, 1, 1), (1, 1, 1), (1, 1, 0)),
    "north": ((1, 1, 0), (1, 0, 0), (0, 0, 0), (0, 1, 0)),
    "south": ((0, 1, 1), (0, 0, 1), (1, 0, 1), (1, 1, 1)),
    "west": ((0, 1, 0), (0, 0, 0), (0, 0, 1), (0, 1, 1)),
    "east": ((1, 1, 1), (1, 0, 1), (1, 0, 0), (1, 1, 0)),
}
VIEWS = (
    ("front-three-quarter", (1.15, 0.6, -1.65), "正面 3/4 · 东北侧"),
    ("rear-three-quarter", (-1.25, 0.55, 1.6), "背面 3/4 · 西南侧"),
    ("east-side", (1.0, 0.0, 0.0), "侧面 · 东侧正投影"),
    ("front", (0.0, 0.0, -1.0), "正面 · 北侧正投影"),
)


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest().upper()


def numbers(value, length, label):
    if not isinstance(value, list) or len(value) != length:
        raise ValueError(f"{label}: expected {length} values")
    result = np.array(value, dtype=np.float64)
    if not np.isfinite(result).all():
        raise ValueError(f"{label}: non-finite values")
    return result


def resolve_texture(textures: dict, reference: str) -> str:
    visited = set()
    while reference.startswith("#"):
        key = reference[1:]
        if key in visited:
            raise ValueError(f"cyclic texture alias: {reference}")
        visited.add(key)
        if key not in textures:
            raise ValueError(f"missing texture alias: {reference}")
        reference = textures[key]
        if not isinstance(reference, str):
            raise ValueError(f"non-string texture reference: {key}")
    if ":" not in reference:
        reference = "minecraft:" + reference
    if not RESOURCE.fullmatch(reference):
        raise ValueError(f"invalid namespaced texture: {reference}")
    namespace, resource = reference.split(":", 1)
    if any(part in ("", ".", "..") for part in resource.split("/")):
        raise ValueError(f"unsafe texture path: {reference}")
    return namespace + ":" + resource


def texture_path(assets: Path, reference: str) -> Path:
    namespace, resource = reference.split(":", 1)
    path = assets / namespace / "textures" / (resource + ".png")
    path.resolve().relative_to(assets.resolve())
    return path


def rotated(vertices: np.ndarray, rotation: dict | None) -> np.ndarray:
    if not rotation:
        return vertices.copy()
    axis = "xyz".index(rotation["axis"])
    radians = math.radians(float(rotation["angle"]))
    c, s = math.cos(radians), math.sin(radians)
    matrix = (
        np.array(((1, 0, 0), (0, c, -s), (0, s, c))) if axis == 0 else
        np.array(((c, 0, s), (0, 1, 0), (-s, 0, c))) if axis == 1 else
        np.array(((c, -s, 0), (s, c, 0), (0, 0, 1)))
    )
    origin = np.asarray(rotation["origin"], dtype=np.float64)
    result = (vertices - origin) @ matrix.T
    if rotation.get("rescale", False):
        # FaceBakery rotates first, then scales the two perpendicular axes.
        angle = abs(float(rotation["angle"]))
        scale = 1 / math.cos(math.radians(22.5 if angle == 22.5 else 45.0))
        for dimension in range(3):
            if dimension != axis:
                result[:, dimension] *= scale
    return result + origin


def vertices_for(element: dict, direction: str) -> np.ndarray:
    low, high = np.asarray(element["from"]), np.asarray(element["to"])
    vertices = np.array([np.where(corner, high, low) for corner in FACE_CORNERS[direction]],
                        dtype=np.float64)
    return rotated(vertices, element.get("rotation"))


def uv_for(face: dict) -> np.ndarray:
    u0, v0, u1, v1 = face["uv"]
    uv = np.array(((u0, v0), (u0, v1), (u1, v1), (u1, v0)), dtype=np.float64)
    return np.roll(uv, -int(face.get("rotation", 0)) // 90, axis=0)


@dataclass
class Quad:
    vertices: np.ndarray
    uv: np.ndarray
    texture: str
    element_index: int
    direction: str


def collect_quads(model: dict) -> list[Quad]:
    quads = []
    for index, element in enumerate(model["elements"]):
        for direction, face in element.get("faces", {}).items():
            quads.append(Quad(vertices_for(element, direction), uv_for(face),
                              resolve_texture(model["textures"], face["texture"]),
                              index, direction))
    return quads


def inspect_assets(model_path: Path, assets_root: Path, expected_elements=155):
    raw_model = model_path.read_bytes()
    model = json.loads(raw_model.decode("utf-8-sig"))
    errors, warnings = [], []
    unsupported = [key for key in ("parent", "loader", "transform", "root_transform")
                   if key in model]
    if unsupported:
        errors.append("unsupported model inheritance/loader/transforms: " + ", ".join(unsupported))
    elements = model.get("elements")
    if not isinstance(elements, list) or not elements:
        raise ValueError("model must contain explicit nonempty elements")
    if len(elements) != expected_elements:
        errors.append(f"expected {expected_elements} elements, found {len(elements)}")
    textures = model.get("textures", {})
    used = set()
    angles, face_rotations = Counter(), Counter()
    bounds = []
    faces = 0
    for index, element in enumerate(elements):
        try:
            low = numbers(element["from"], 3, f"element {index} from")
            high = numbers(element["to"], 3, f"element {index} to")
            if (low > high).any():
                raise ValueError("from is greater than to")
            if (low < -16).any() or (high > 32).any():
                raise ValueError("unrotated coordinates outside Minecraft [-16,32]")
            bounds.extend([low, high])
            rotation = element.get("rotation")
            if rotation:
                if rotation.get("axis") not in ("x", "y", "z"):
                    raise ValueError("unsupported rotation axis")
                if float(rotation.get("angle", float("nan"))) not in VALID_ANGLES:
                    raise ValueError("Minecraft only allows 0, +/-22.5, +/-45 element rotations")
                numbers(rotation["origin"], 3, f"element {index} rotation origin")
                if not isinstance(rotation.get("rescale", False), bool):
                    raise ValueError("rescale must be boolean")
            angles[str(float(rotation["angle"]) if rotation else 0.0)] += 1
            for direction, face in element.get("faces", {}).items():
                faces += 1
                if direction not in VALID_FACES:
                    raise ValueError(f"unsupported face {direction}")
                uv = numbers(face.get("uv"), 4, f"element {index} face {direction} uv")
                if (uv < 0).any() or (uv > 16).any():
                    raise ValueError(f"{direction} UV outside [0,16]")
                rotation_uv = face.get("rotation", 0)
                if rotation_uv not in (0, 90, 180, 270):
                    raise ValueError(f"illegal UV rotation {rotation_uv}")
                face_rotations[str(rotation_uv)] += 1
                if face.get("tintindex", -1) != -1:
                    raise ValueError("world/biome tint is unsupported")
                used.add(resolve_texture(textures, face["texture"]))
        except (ValueError, KeyError, TypeError) as exc:
            errors.append(f"element {index}: {exc}")
    if len(used) != 1:
        errors.append(f"expected a single skin texture, found {len(used)}")
    arrays, texture_info = {}, []
    for reference in sorted(used):
        try:
            path = texture_path(assets_root, reference)
            data = path.read_bytes()
            with Image.open(io.BytesIO(data)) as source:
                if source.size != (2048, 2048):
                    errors.append(f"{reference}: expected 2048x2048, found {source.size}")
                array = np.array(source.convert("RGBA"))
            alpha = array[:, :, 3]
            partial = int(np.count_nonzero((alpha > 0) & (alpha < 255)))
            if partial:
                warnings.append(f"{reference}: {partial} semi-transparent texels rendered by cutout alpha >= 0.1; no alpha blending")
            arrays[reference] = array
            texture_info.append({"resource": reference, "path": str(path.resolve()),
                                 "archive_path": path.relative_to(assets_root.parent).as_posix(),
                                 "sha256": sha(data), "size": list(array.shape[1::-1]),
                                 "transparent_texels": int(np.count_nonzero(alpha == 0)),
                                 "semi_transparent_texels": partial})
        except (OSError, ValueError) as exc:
            errors.append(f"{reference}: {exc}")
    report = {
        "ok": not errors,
        "model": {"path": str(model_path.resolve()), "sha256": sha(raw_model),
                  "archive_path": model_path.relative_to(assets_root.parent).as_posix()},
        "textures": texture_info,
        "elements": len(elements), "faces": faces,
        "element_rotation_counts": dict(sorted(angles.items())),
        "uv_rotation_counts": dict(sorted(face_rotations.items())),
        "errors": errors, "warnings": warnings,
        "method": "Orthographic barycentric texture sampling + per-pixel depth buffer; unlit minecraft:cutout (alpha >= 0.1), no blending.",
        "not_simulated": ["Minecraft atlas UV shrink/mipmaps", "world AO/lighting",
                          "dynamic game screen / BER", "block neighbours", "item/GUI transforms"],
    }
    wrapper_path = assets_root / "piq_fc_arcade/models/block/legacy_fc_arcade.json"
    try:
        wrapper_bytes = wrapper_path.read_bytes()
        wrapper = json.loads(wrapper_bytes.decode("utf-8-sig"))
        if wrapper.get("parent") != "piq_fc_arcade:block/rocket_arcade_body":
            errors.append("legacy wrapper does not reference the rocket body")
        if wrapper.get("render_type") != "minecraft:cutout":
            errors.append("legacy wrapper must explicitly use minecraft:cutout for this preview's alpha policy")
        report["wrapper"] = {"path": str(wrapper_path.resolve()), "sha256": sha(wrapper_bytes),
                             "archive_path": wrapper_path.relative_to(assets_root.parent).as_posix(),
                             "parent": wrapper.get("parent"), "render_type": wrapper.get("render_type")}
    except (OSError, ValueError) as exc:
        errors.append(f"legacy wrapper: {exc}")
    report["ok"] = not errors
    if bounds:
        report["unrotated_bounds"] = {"min": np.min(bounds, axis=0).tolist(),
                                      "max": np.max(bounds, axis=0).tolist()}
    if errors:
        return model, arrays, [], report
    quads = collect_quads(model)
    all_vertices = np.concatenate([quad.vertices for quad in quads])
    report["rotated_bounds"] = {"min": np.min(all_vertices, axis=0).tolist(),
                               "max": np.max(all_vertices, axis=0).tolist()}
    degenerate = []
    for quad in quads:
        if np.linalg.norm(np.cross(quad.vertices[1]-quad.vertices[0],
                                   quad.vertices[2]-quad.vertices[0])) < 1e-9:
            degenerate.append({"element_index": quad.element_index, "face": quad.direction})
    report["degenerate_faces"] = degenerate
    if degenerate:
        warnings.append(f"{len(degenerate)} zero-area faces are not rasterized")
    # Detect the large, single north-facing 4:3 display geometrically. Names never
    # determine a face's shape, rotation, color or UV. The name is informational.
    screens = []
    for quad in quads:
        element = elements[quad.element_index]
        if len(element.get("faces", {})) != 1 or quad.direction != "north":
            continue
        width = float(np.linalg.norm(quad.vertices[3] - quad.vertices[0]))
        height = float(np.linalg.norm(quad.vertices[1] - quad.vertices[0]))
        if width < 8 or height <= 0 or abs(width / height - 4/3) > 1e-6:
            continue
        screens.append({"element_index": quad.element_index, "name": element.get("name", ""),
                        "direction": quad.direction, "width": width, "height": height,
                        "ratio": width / height, "rotation": element.get("rotation"),
                        "vertices_minecraft_model_units": quad.vertices.tolist(),
                        "uv_minecraft_0_to_16": quad.uv.tolist()})
    report["four_three_screens"] = screens
    if len(screens) != 1:
        errors.append(f"expected exactly one large single-face 4:3 north display, found {len(screens)}")
    report["ok"] = not errors
    return model, arrays, quads, report


def raster_triangle(canvas, depth, vertices, uv, texture):
    """Orthographic affine interpolation is exact; larger camera depth is nearer."""
    height, width = depth.shape
    left = max(0, int(math.floor(vertices[:, 0].min())))
    right = min(width, int(math.ceil(vertices[:, 0].max())))
    top = max(0, int(math.floor(vertices[:, 1].min())))
    bottom = min(height, int(math.ceil(vertices[:, 1].max())))
    if left >= right or top >= bottom:
        return
    x0, y0, z0 = vertices[0]
    x1, y1, z1 = vertices[1]
    x2, y2, z2 = vertices[2]
    divisor = (y1-y2)*(x0-x2) + (x2-x1)*(y0-y2)
    if abs(divisor) < 1e-10:
        return
    xx, yy = np.meshgrid(np.arange(left, right)+0.5, np.arange(top, bottom)+0.5)
    a = ((y1-y2)*(xx-x2) + (x2-x1)*(yy-y2)) / divisor
    b = ((y2-y0)*(xx-x2) + (x0-x2)*(yy-y2)) / divisor
    c = 1-a-b
    candidate_depth = a*z0 + b*z1 + c*z2
    depth_view = depth[top:bottom, left:right]
    inside = (a >= -1e-9) & (b >= -1e-9) & (c >= -1e-9)
    eligible = inside & (candidate_depth > depth_view + 1e-8)
    if not eligible.any():
        return
    rows, cols = np.nonzero(eligible)
    weights = np.column_stack((a[eligible], b[eligible], c[eligible]))
    tex_uv = weights @ uv
    tex_x = np.clip(np.floor(tex_uv[:, 0]*texture.shape[1]/16).astype(np.int32),
                    0, texture.shape[1]-1)
    tex_y = np.clip(np.floor(tex_uv[:, 1]*texture.shape[0]/16).astype(np.int32),
                    0, texture.shape[0]-1)
    sampled = texture[tex_y, tex_x]
    # Minecraft 1.21.1 rendertype_cutout.fsh discards alpha < 0.1. Its
    # RenderType has no blending, so surviving texels are opaque in this
    # composited asset preview. Never alter the source PNG or source array.
    visible = sampled[:, 3] >= 26
    rows, cols = rows[visible], cols[visible]
    colors = sampled[visible].copy()
    colors[:, 3] = 255
    canvas[top+rows, left+cols] = colors
    depth_view[rows, cols] = candidate_depth[rows, cols]


def normalize(vector):
    vector = np.asarray(vector, dtype=np.float64)
    return vector / np.linalg.norm(vector)


def render_view(quads, textures, direction, size=(900, 1100), supersample=2):
    camera = normalize(direction)
    right = normalize(np.cross((0, 1, 0), camera))
    up = normalize(np.cross(camera, right))
    all_vertices = np.concatenate([quad.vertices for quad in quads])
    center = (all_vertices.min(axis=0) + all_vertices.max(axis=0)) / 2
    projected_all = (all_vertices-center) @ np.column_stack((right, -up))
    low, high = projected_all.min(axis=0), projected_all.max(axis=0)
    width, height = size[0]*supersample, size[1]*supersample
    padding = 52*supersample
    scale = min((width-2*padding)/(high[0]-low[0]),
                (height-2*padding)/(high[1]-low[1]))
    offset = np.array((width, height))/2 - (low+high)*scale/2
    canvas = np.zeros((height, width, 4), dtype=np.uint8)
    depth = np.full((height, width), -np.inf, dtype=np.float64)
    drawn = 0
    for quad in quads:
        v = quad.vertices
        normal = np.cross(v[1]-v[0], v[2]-v[0])
        if np.dot(normal, camera) <= 1e-9:
            continue
        relative = v-center
        projected = np.column_stack((relative @ right, -(relative @ up))) * scale + offset
        vertices = np.column_stack((projected, relative @ camera))
        for indices in ((0, 1, 2), (0, 2, 3)):
            selected = list(indices)
            raster_triangle(canvas, depth, vertices[selected], quad.uv[selected], textures[quad.texture])
        drawn += 1
    image = Image.fromarray(canvas)
    if supersample != 1:
        image = image.resize(size, Image.Resampling.LANCZOS)
    return image, {"camera_from_center": camera.tolist(), "visible_quads": drawn,
                   "sampling": "nearest texel at pixel center; Lanczos supersample resolve"}


def font(size, bold=False):
    path = Path("C:/Windows/Fonts/msyhbd.ttc" if bold else "C:/Windows/Fonts/msyh.ttc")
    return ImageFont.truetype(str(path), size) if path.exists() else ImageFont.load_default(size=size)


def contact_sheet(images, report):
    width, height = images[0].size
    margin, header, footer, label = 28, 118, 130, 64
    sheet = Image.new("RGB", (2*width+3*margin, 2*(height+label)+header+footer+3*margin),
                      (234, 238, 244))
    draw = ImageDraw.Draw(sheet)
    draw.text((margin, 22), "FC 通用街机 · beta.3 资产 UV 预览", font=font(40, True), fill=(25, 34, 48))
    draw.text((margin, 74), f"{report['elements']} 元素 · 当前 2048×2048 皮肤 · 正交投影 / 像素深度测试",
              font=font(23), fill=(69, 81, 101))
    for index, (view, spec) in enumerate(zip(images, VIEWS)):
        x = margin+(index % 2)*(width+margin)
        y = header+margin+(index // 2)*(height+label+margin)
        draw.rectangle((x, y, x+width, y+height+label), fill=(249, 250, 252))
        sheet.paste(view, (x, y), view)
        draw.text((x+24, y+height+13), spec[2], font=font(26, True), fill=(30, 43, 61))
    y = sheet.height-footer+20
    draw.text((margin, y), "直接读取当前 JSON + PNG；按实际几何、旋转与 UV 确定性渲染。", font=font(22), fill=(40, 52, 68))
    draw.text((margin, y+36), "离线资产预览：不含游戏内光照/AO、动态屏幕、相邻方块及物品显示变换。",
              font=font(20), fill=(86, 95, 110))
    draw.text((margin, y+70), "模型 SHA256  "+report["model"]["sha256"][:32]+"…", font=font(17), fill=(101, 110, 121))
    return sheet


def inspect_jar(jar_path: Path, report):
    result = {"path": str(jar_path.resolve()), "sha256": sha(jar_path.read_bytes()),
              "entries": [], "ok": True}
    required = [report["model"], *report["textures"]]
    if "wrapper" in report:
        required.append(report["wrapper"])
    with zipfile.ZipFile(jar_path) as archive:
        names = archive.namelist()
        for item in required:
            entry = item["archive_path"]
            count = names.count(entry)
            actual = sha(archive.read(entry)) if count == 1 else None
            ok = count == 1 and actual == item["sha256"]
            result["entries"].append({"path": entry, "count": count, "sha256": actual,
                                      "source_sha256": item["sha256"], "ok": ok})
            result["ok"] &= ok
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", type=Path, default=DEFAULT_MODEL)
    parser.add_argument("--assets", type=Path, default=ASSETS)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--jar", type=Path, help="also require final JAR entries to match source bytes")
    parser.add_argument("--check-only", action="store_true")
    parser.add_argument("--expected-elements", type=int, default=155)
    parser.add_argument("--width", type=int, default=900)
    parser.add_argument("--height", type=int, default=1100)
    parser.add_argument("--supersample", type=int, default=2)
    args = parser.parse_args(argv)
    if not (200 <= args.width <= 2000 and 200 <= args.height <= 2400 and 1 <= args.supersample <= 3):
        parser.error("unsafe render dimensions")
    args.output.mkdir(parents=True, exist_ok=True)
    _, textures, quads, report = inspect_assets(args.model.resolve(), args.assets.resolve(), args.expected_elements)
    if args.jar:
        report["jar"] = inspect_jar(args.jar.resolve(), report)
        if not report["jar"]["ok"]:
            report["errors"].append("final JAR entries differ from validated source assets")
            report["ok"] = False
    outputs = []
    if report["ok"] and not args.check_only:
        images = []
        for name, direction, label in VIEWS:
            preview, details = render_view(quads, textures, direction,
                                           (args.width, args.height), args.supersample)
            path = args.output / (name+".png")
            preview.save(path, optimize=True)
            outputs.append({"view": name, "label": label, "path": str(path.resolve()),
                            "sha256": sha(path.read_bytes()), **details})
            images.append(preview)
        path = args.output / "rocket-arcade-final-assets-multiview.png"
        sheet = contact_sheet(images, report)
        sheet.save(path, optimize=True)
        report["contact_sheet"] = {"path": str(path.resolve()), "sha256": sha(path.read_bytes())}
        jpeg_path = args.output / "rocket-arcade-final-assets-multiview-review.jpg"
        review_width = 1280
        while True:
            review = sheet.copy()
            review.thumbnail((review_width, 1900), Image.Resampling.LANCZOS)
            review.save(jpeg_path, quality=90, optimize=True, subsampling=2)
            if jpeg_path.stat().st_size <= 245760 or review_width <= 896:
                break
            review_width -= 128
        report["review_jpeg"] = {"path": str(jpeg_path.resolve()), "sha256": sha(jpeg_path.read_bytes()),
                                 "size": list(review.size), "bytes": jpeg_path.stat().st_size,
                                 "quality": 90, "subsampling": "4:2:0"}
    report["views"] = outputs
    report_path = args.output / "asset-validation.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    print(json.dumps({"ok": report["ok"], "report": str(report_path.resolve()),
                      "contact_sheet": report.get("contact_sheet"), "review_jpeg": report.get("review_jpeg"),
                      "errors": report["errors"]},
                     ensure_ascii=False))
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
