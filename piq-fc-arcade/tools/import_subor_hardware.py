"""Import the frozen SB926 free mesh, preserving its atlas and every original UV.

Only controller translation, external cord routing, and uniform coordinate transforms
change. No paint, AI, vanilla cube approximation, GUI, or game process is involved.
"""
from __future__ import annotations
import argparse
import base64
import copy
import hashlib
import io
import json
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw, ImageFont
from render_rocket_arcade_preview import Quad, collect_quads, render_view

PROJECT = Path(__file__).resolve().parents[1]
WORKSPACE = PROJECT.parent
ASSETS = PROJECT / 'src/main/resources/assets/piq_fc_arcade'
CATEGORY = WORKSPACE / '制作Mod/03-街机模拟/PIQ-FC街机'
SOURCE_DIR = CATEGORY / '小霸王SB926模型草案-v1'
OUTPUT_DIR = CATEGORY / '小霸王SB926模型接入-v2'
SOURCE = SOURCE_DIR / '小霸王SB926_完整套装.bbmodel'
TEXTURE = SOURCE_DIR / '小霸王_统一UV.png'
SOURCE_SHA = 'A113F1BD0A9EDCED23129EBE9725B0DBCAE4D12D0DB31BB4376421B20377F795'
TEXTURE_SHA = '39A7F6DE2FE4CB669A0CE4E88BFDAE23C234CE7F9A7314A150D13B6EB2A89C4C'
MESH_PATH = ASSETS / 'meshes/home_subor_sb926.json'
TEXTURE_PATH = ASSETS / 'textures/block/home_subor_sb926.png'
WORLD_SCALE = .45
WORLD_OFFSET = np.array([8., 0., 9.15])
HELD_SCALE = 12.69 / 7
GROUPS = ('body', 'p1_docked', 'p2_docked', 'p1_held', 'p2_held')


def sha(data):
    return hashlib.sha256(data).hexdigest().upper()


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, separators=(',', ':')) + '\n').encode('utf-8')


def load_source(source=SOURCE, texture=TEXTURE):
    raw, png = source.read_bytes(), texture.read_bytes()
    if sha(raw) != SOURCE_SHA or sha(png) != TEXTURE_SHA:
        raise ValueError('Frozen v1 source/texture hash mismatch; never silently import an edited source')
    doc = json.loads(raw)
    if doc['meta']['model_format'] != 'free' or doc['resolution'] != {'width': 2048, 'height': 2048}:
        raise ValueError('Unsupported source format/atlas')
    if len(doc['textures']) != 1 or base64.b64decode(doc['textures'][0]['source'].split(',', 1)[1]) != png:
        raise ValueError('Embedded and external atlas disagree')
    with Image.open(io.BytesIO(png)) as image:
        if image.size != (2048, 2048):
            raise ValueError('Unexpected texture dimensions')
    ids = [e['uuid'] for e in doc['elements']]
    children = [u for g in doc['outliner'] for u in g['children']]
    if len(ids) != 207 or len(set(ids)) != 207 or sorted(ids) != sorted(children):
        raise ValueError('Invalid/duplicate free-mesh ownership')
    for part in doc['elements'] + doc['outliner']:
        if part.get('rotation', [0, 0, 0]) != [0, 0, 0] or part.get('origin', [0, 0, 0]) != [0, 0, 0]:
            raise ValueError('Non-baked source transforms are not supported')
    return doc, png


def vertices(elements):
    return np.concatenate([np.array(list(e['vertices'].values()), dtype=float) for e in elements])


def bounds(elements):
    p = vertices(elements)
    return np.round([p.min(0), p.max(0)], 9).tolist()


def world(point):
    return WORLD_OFFSET + np.asarray(point) * [-WORLD_SCALE, WORLD_SCALE, -WORLD_SCALE]


def transform_element(element, transform):
    result = copy.deepcopy(element)
    result['vertices'] = {key: np.round(transform(value), 9).tolist() for key, value in element['vertices'].items()}
    return result


def cable_centers(port):
    """29 rings, explicitly outside the front/side chassis AABBs until the intended plug."""
    side = -1 if port == 0 else 1
    cx = side * 8
    def p(x, y, z):
        return np.array([side*x, y, z])
    sections = [
        (6, [p(8, .5, 9.32), p(8, .5, 8.82), p(8.6, .36, 8.5), p(10, .36, 8.5)]),
        (4, [p(10, .36, 8.5), p(12, .36, 8.5), p(15, .36, 8.5), p(16.5, .36, 8.5)]),
        (4, [p(16.5, .36, 8.5), p(16.85, .36, 8.5), p(17, .36, 8.35), p(17, .36, 8)]),
        (10, [p(17, .36, 8), p(17, .36, 5), p(17, .7, -.9), p(17, 1, -3.9)]),
        (4, [p(17, 1, -3.9), p(17, 1, -4.2), p(16.8, 1, -4.4), p(16.25, 1, -4.4)]),
    ]
    values = []
    for count, control in sections:
        for t in np.linspace(0, 1, count + 1)[:-1]:
            values.append((1-t)**3*control[0] + 3*(1-t)**2*t*control[1] + 3*(1-t)*t*t*control[2] + t**3*control[3])
    values.append(sections[-1][1][-1])
    return np.array(values)


def routed_cable(element, port):
    centers = cable_centers(port)
    ring_vertices = []
    for i, center in enumerate(centers):
        tangent = centers[min(i+1, 28)] - centers[max(i-1, 0)]
        tangent /= np.linalg.norm(tangent)
        # Preserve the handedness of v1's Z-reflected source rings and therefore its face winding.
        across = -np.cross(tangent, [0, 1, 0])
        across /= np.linalg.norm(across)
        up = -np.cross(tangent, across)
        for angle in np.arange(6)*np.pi/3:
            ring_vertices.append(center + .065*(across*np.cos(angle) + up*np.sin(angle)))
    if set(element['vertices']) != {f'v{i}' for i in range(174)}:
        raise ValueError('Unexpected cable topology')
    result = copy.deepcopy(element)
    result['vertices'] = {f'v{i}': np.round(p, 9).tolist() for i, p in enumerate(ring_vertices)}
    return result


def triangles(elements):
    result = []
    for element in elements:
        for face in element['faces'].values():
            keys = face['vertices']
            if face['texture'] != 0 or len(keys) not in (3, 4) or len(set(keys)) != len(keys):
                raise ValueError('Unsupported face topology/texture')
            for i in range(1, len(keys)-1):
                tri = [keys[0], keys[i], keys[i+1]]
                p = np.array([element['vertices'][k] for k in tri])
                uv = np.array([face['uv'][k] for k in tri], dtype=float) / 2048
                n = np.cross(p[1]-p[0], p[2]-p[0])
                length = np.linalg.norm(n)
                if not np.isfinite(p).all() or not np.isfinite(uv).all() or length < 1e-12 or uv.min() < 0 or uv.max() > 1:
                    raise ValueError('Invalid triangle/UV/normal')
                result.append({'p': p.tolist(), 'uv': uv.tolist(), 'n': np.round(n / length, 9).tolist()})
    return result


def prove_cable_clearance(body, docked):
    """Disjoint triangle AABBs are a conservative proof of no intersections with chassis/key meshes."""
    obstacles = [(e['name'], np.array(bounds([e]))) for e in body if e['name'] not in ('左手柄接口', '右手柄接口')]
    findings = []
    for port, elements in enumerate(docked):
        cable = next(e for e in elements if e['name'] == '独立手柄线')
        for index, triangle in enumerate(triangles([cable])):
            p = np.array(triangle['p'])
            for name, box in obstacles:
                if np.all(np.minimum(p.max(0), box[1]) - np.maximum(p.min(0), box[0]) > -1e-10):
                    findings.append([port, index, name])
    if findings:
        raise ValueError(f'Cable/chassis possible intersection: {findings[:5]}')
    return {'method': 'Every cord triangle AABB is disjoint from every chassis/key/slot element AABB',
            'tested_cord_triangles': 672, 'obstacle_elements': len(obstacles), 'possible_intersections': 0,
            'intentional_contacts': 'Only final tube rings enter their matching side plug; controller-end ring enters its strain relief'}


def make_document(original, elements, chosen, title):
    doc = copy.deepcopy(original)
    ids = {e['uuid'] for e in elements}
    doc.update(name=title, model_identifier='subor_sb926_integrated_v2', elements=elements)
    doc['outliner'] = [dict(copy.deepcopy(g), children=[u for u in g['children'] if u in ids]) for g in chosen]
    for g in doc['outliner']:
        if g['name'].startswith('07 '):
            g['visibility'] = False
    return doc


def build():
    original, png = load_source()
    by_id = {e['uuid']: e for e in original['elements']}
    source_groups = [[by_id[u] for u in g['children']] for g in original['outliner']]
    body_source = sum(source_groups[:6], [])
    docked_source, held = [], []
    for port in (0, 1):
        old_cx = (-8.7, 7.9)[port]
        cx = (-8, 8)[port]
        moved = [routed_cable(e, port) if e['name'] == '独立手柄线' else
                 transform_element(e, lambda p: np.array(p) + [cx-old_cx, 0, 0]) for e in source_groups[7+port]]
        docked_source.append(moved)
        controller = [e for e in moved if e['name'] != '独立手柄线']
        center = np.mean(np.array(bounds(controller)), axis=0)
        held.append([transform_element(e, lambda p: np.array([p[0]-center[0], -(p[2]-center[2]), p[1]-center[1]]) * HELD_SCALE + 8) for e in controller])
    clearance = prove_cable_clearance(body_source, docked_source)
    body = [transform_element(e, world) for e in body_source]
    docked = [[transform_element(e, world) for e in group] for group in docked_source]
    card = [transform_element(e, world) for e in source_groups[6]]
    groups = dict(zip(GROUPS, [body, *docked, *held]))
    metadata = {
        'source_sha256': SOURCE_SHA, 'texture_sha256': TEXTURE_SHA,
        'source_to_world': {'scale': .45, 'rotation_y_degrees': 180, 'translation': WORLD_OFFSET.tolist()},
        'world_body_bounds': bounds(body), 'world_all_bounds': bounds(body + docked[0] + docked[1]),
        'source_body_bounds': bounds(body_source), 'source_complete_bounds': bounds(original['elements']),
        'anchors': {'cartridge_bottom_center': world([0, 1.8, -5.75]).tolist(),
                    'cartridge_size': [3.42, 2.34, .45], 'cartridge_front_normal': [0, 0, -1],
                    'cartridge_render_scale': .30, 'cartridge_source_bottom_center': [8, 0, 8],
                    'cartridge_inserted_bounds': inserted_card_bounds(),
                    'source_reference_card_size': [3.465, 2.0025, .333],
                    'av_socket': world([-10.6, 1.115, -7.75]).tolist(),
                    'av_cable_start': [12.77, .50175, 12.68], 'av_outward_normal': [0, 0, 1],
                    'audio_socket': world([-9.2, 1.115, -7.75]).tolist(),
                    'power_socket': world([-7.8, 1.115, -7.75]).tolist(),
                    'controller_sockets': [world([-16.25, 1, -4.4]).tolist(), world([16.25, 1, -4.4]).tolist()]},
        'held_contract': {'center': [8, 8, 8], 'button_normal': [0, 0, 1], 'dpad_side': '-X', 'a_side': '+X',
                          'uniform_scale_from_source': HELD_SCALE, 'width': 12.69, 'extra_held_yaw': False},
        'excluded_runtime_source_group': '07 可拆黄色学习卡', 'cable_clearance': clearance,
    }
    mesh = {'version': 1, 'texture': 'piq_fc_arcade:textures/block/home_subor_sb926.png',
            'texture_size': [2048, 2048], 'units': 'model_16', 'front': 'north_-z',
            'groups': {name: {'triangles': triangles(group), 'bounds': bounds(group), 'elements': len(group)} for name, group in groups.items()},
            'metadata': metadata}
    for value in mesh['groups'].values():
        value['triangle_count'] = len(value['triangles'])
    docs = {'小霸王SB926_接入摆位v2.bbmodel': make_document(original, body+card+docked[0]+docked[1], original['outliner'], '小霸王 SB926 接入摆位 v2（黄色示例卡默认隐藏）')}
    for port in (0, 1):
        docs[f'小霸王SB926_P{port+1}手持v2.bbmodel'] = make_document(original, held[port], [original['outliner'][7+port]], f'小霸王 SB926 P{port+1} 手持 canonical v2')
    return mesh, docs, png


def mesh_quads(mesh, names):
    values = []
    for name in names:
        for index, triangle in enumerate(mesh['groups'][name]['triangles']):
            values.append(Quad(np.array(triangle['p'] + [triangle['p'][-1]]),
                               np.array(triangle['uv'] + [triangle['uv'][-1]]) * 16, 'skin', index, name))
    return values


def inserted_card_quads():
    model = json.loads((ASSETS / 'models/block/home_fc_cartridge.json').read_bytes())
    return [Quad((q.vertices - [8, 0, 8]) * .30 + [8, .81, 11.7375], q.uv, 'card', q.element_index, q.direction)
            for q in collect_quads(model)]


def inserted_card_bounds():
    p = np.concatenate([q.vertices for q in inserted_card_quads()])
    return np.round([p.min(0), p.max(0)], 9).tolist()


def preview(mesh, png):
    # Read the same serialized runtime mesh and PNG consumed by the renderer, not the authoring model.
    texture = np.array(Image.open(io.BytesIO(png)).convert('RGBA'))
    card_texture = np.array(Image.open(ASSETS / 'textures/block/home_fc_cartridge_skin.png').convert('RGBA'))
    image = Image.new('RGB', (1600, 1160), '#17202a')
    draw = ImageDraw.Draw(image)
    title_font = ImageFont.truetype('C:/Windows/Fonts/msyhbd.ttc', 27)
    label_font = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 20)
    draw.text((30, 16), '小霸王 SB926 · 接入摆位 v2 · 实际网格与原 UV 离线渲染', font=title_font, fill='#eef5f8')
    views = [('北侧前方：双手柄在键盘前方桌面', (1, .9, -1.5), ('body', 'p1_docked', 'p2_docked')),
             ('背面：AV / 音频 / 电源接口', (-1, .7, 1.4), ('body', 'p1_docked', 'p2_docked')),
             ('俯视：线材沿外侧绕行，不穿键盘或机壳', (0, 1, -.01), ('body', 'p1_docked', 'p2_docked')),
             ('取下的 P1 / P2：共用原尺寸握姿，不附带线材', (0, .12, 1), ('p1_held', 'p2_held'))]
    info = []
    for i, (label, direction, names) in enumerate(views):
        x, y = (i % 2)*800, 65+(i//2)*525
        quads = mesh_quads(mesh, names)
        if i < 2:
            quads += inserted_card_quads()
        if i == 3:
            # Preview-only translate two distinct actual canonical meshes side by side; no geometry rescale/UV edits.
            quads = []
            for port, name in enumerate(names):
                for q in mesh_quads(mesh, [name]):
                    quads.append(Quad(q.vertices + [(-7.5 if port == 0 else 7.5), 0, 0], q.uv, q.texture, q.element_index, q.direction))
        rendered, details = render_view(quads, {'skin': texture, 'card': card_texture}, direction, size=(780, 470), supersample=2)
        image.paste(rendered.convert('RGB'), (x+10, y+31))
        draw.text((x+20, y), label, font=label_font, fill='#d6e8ec')
        info.append(details)
    draw.text((30, 1120), '非游戏截图；前/后视图插入实际 FC 卡带。俯视不插卡检查卡槽。原贴图未改像素。', font=label_font, fill='#bdced8')
    outputs = {}
    for suffix in ('png', 'jpg'):
        buffer = io.BytesIO()
        image.save(buffer, format='PNG' if suffix == 'png' else 'JPEG', **({} if suffix == 'png' else {'quality': 90, 'optimize': True}))
        outputs[f'小霸王SB926_实际网格预览.{suffix}'] = buffer.getvalue()
    return outputs, info


def write_new(outputs):
    # Preflight the entire set before touching anything. An edited v2 or unrelated file is never overwritten.
    for path, data in outputs.items():
        if path.is_symlink() or (path.exists() and path.read_bytes() != data):
            raise FileExistsError(f'Refusing to overwrite different existing file: {path}')
    for path, data in outputs.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        if not path.exists():
            with path.open('xb') as stream:
                stream.write(data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write', action='store_true')
    parser.add_argument('--check-only', action='store_true')
    args = parser.parse_args()
    mesh, docs, png = build()
    raw = encoded(mesh)
    if args.check_only:
        if MESH_PATH.read_bytes() != raw or TEXTURE_PATH.read_bytes() != png:
            raise ValueError('Runtime resources differ from deterministic import')
        print('PASS: runtime mesh and original texture exactly match the frozen source conversion')
        return
    summary = {'groups': {name: {k: v for k, v in value.items() if k != 'triangles'} for name, value in mesh['groups'].items()},
               'metadata': mesh['metadata'], 'mesh_bytes': len(raw), 'mesh_sha256': sha(raw),
               'method': 'Deterministic actual free-mesh conversion; not a Minecraft screenshot/playtest'}
    if args.write:
        outputs = {MESH_PATH: raw, TEXTURE_PATH: png, OUTPUT_DIR/'小霸王_统一UV.png': png}
        outputs.update({OUTPUT_DIR/name: encoded(doc) for name, doc in docs.items()})
        pictures, details = preview(json.loads(raw), png)
        outputs.update({OUTPUT_DIR/name: data for name, data in pictures.items()})
        summary['previews'] = details
        summary['preview_cartridge_inputs'] = {name: sha((ASSETS / name).read_bytes()) for name in
                                               ('models/block/home_fc_cartridge.json', 'textures/block/home_fc_cartridge_skin.png')}
        summary['sha256'] = {str(path.relative_to(WORKSPACE)): sha(data) for path, data in outputs.items()}
        outputs[OUTPUT_DIR/'接入模型校验.json'] = encoded(summary)
        outputs[OUTPUT_DIR/'使用说明.md'] = ('# 小霸王 SB926 接入摆位 v2\n\n'
            '这是学习机外观的额外 FC 主机：游戏功能仍复用现有 FC 模拟器。键盘仅为可编辑外观模型，未实现学习 ROM、BASIC 或学习机键盘输入仿真。\n\n'
            '保留 v1 原稿。本目录为独立可编辑 free-mesh BBMODEL；主机、101 键帽、卡槽、P1/P2 手柄均保留原面和 UV。'
            '仅统一缩放/旋转、将两柄对称摆在键盘前方桌面，重排线材顶点使其绕过机壳外侧。\n\n'
            '黄色示例学习卡仅在 v2 BBMODEL 中保留为默认隐藏参考组，不在运行时网格内；游戏使用现有实体卡带渲染。'
            '手持模型独立居中到 (8,8,8)，按键朝 +Z，十字键在 -X，统一放大至宽 12.69，未拉伸。\n\n'
            '预览直接读取最终运行时网格 JSON 的实际三角形与原 PNG，非 AI 效果图、非游戏截图。'
            '线与壳体/键帽逐三角形包围盒不相交；仅插头/出线护套为有意接触。尚需 Minecraft 实机验收。\n\n'
            '复现：tools/import_subor_hardware.py --write；只读核验：--check-only。检测到任何不同已有输出会拒绝覆盖。\n').encode('utf-8')
        write_new(outputs)
        print(str(OUTPUT_DIR))
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
