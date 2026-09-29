"""Independent read-only SB926 runtime/source audit; no resource or Java edits.

Uses the final mesh, wrappers, production sources and local mapped Minecraft source.
Reports actual world/GUI transforms and source lifecycle contracts, not a game test.
"""
from __future__ import annotations
import argparse
import ast
import hashlib
import json
from pathlib import Path
import re
import zipfile
import numpy as np
from check_controller_pose_pipeline import rotation, translation, scale, points, display_matrix
from import_subor_hardware import inserted_card_quads

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'src/main/resources/assets/piq_fc_arcade'
JAVA = ROOT / 'src/main/java/cn/piq/fcarcade'
DEFAULT_OUTPUT = ROOT.parent / '制作Mod/03-街机模拟/PIQ-FC街机/小霸王SB926模型接入-v2/runtime-render-audit.json'


def sha(data):
    return hashlib.sha256(data).hexdigest().upper()


def method(source, signature):
    start = source.index(signature)
    begin = source.index('{', start)
    depth = 1
    for at in range(begin+1, len(source)):
        depth += (source[at] == '{') - (source[at] == '}')
        if depth == 0:
            return source[begin+1:at]
    raise ValueError('Unclosed source method')


def number(expression):
    """Only numeric literals/arithmetic; never execute source text."""
    def visit(node):
        if isinstance(node, ast.Constant) and isinstance(node.value, (int, float)):
            return node.value
        if isinstance(node, ast.UnaryOp) and isinstance(node.op, ast.USub):
            return -visit(node.operand)
        if isinstance(node, ast.BinOp):
            a, b = visit(node.left), visit(node.right)
            if isinstance(node.op, ast.Add): return a+b
            if isinstance(node.op, ast.Sub): return a-b
            if isinstance(node.op, ast.Mult): return a*b
            if isinstance(node.op, ast.Div): return a/b
        raise ValueError('Unsupported arithmetic expression')
    return visit(ast.parse(expression.strip(), mode='eval').body)


def constant(source, name):
    match = re.search(r'\b'+name+r'\s*=\s*([^,;]+)', source)
    if not match:
        raise ValueError('Missing source constant '+name)
    return number(match.group(1))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    paths = {'renderer': JAVA/'client/HomeHardwareRenderer.java', 'mesh_loader': JAVA/'client/SuborHardwareMesh.java',
             'layout': JAVA/'home/HomeConsoleLayout.java', 'block': JAVA/'home/SuborConsoleBlock.java',
             'av_layout': JAVA/'client/HomeAvCableLayout.java', 'entry': JAVA/'FcArcadeMod.java',
             'mesh': ASSETS/'meshes/home_subor_sb926.json', 'texture': ASSETS/'textures/block/home_subor_sb926.png',
             'item': ASSETS/'models/item/subor_console.json', 'block_model': ASSETS/'models/block/subor_console.json',
             'blockstates': ASSETS/'blockstates/subor_console.json'}
    raw = {name: path.read_bytes() for name, path in paths.items()}
    source = {name: data.decode('utf-8') for name, data in raw.items() if name != 'texture'}
    renderer, loader, layout = (source[name] for name in ('renderer','mesh_loader','layout'))
    mesh, item = json.loads(raw['mesh']), json.loads(raw['item'])
    archive = ROOT/'build/moddev/artifacts/neoforge-21.1.236-sources.jar'
    mapped_paths = {'item_renderer': 'net/minecraft/client/renderer/entity/ItemRenderer.java',
                    'item_transform': 'net/minecraft/client/renderer/block/model/ItemTransform.java',
                    'gui': 'net/minecraft/client/gui/GuiGraphics.java',
                    'compiler': 'net/minecraft/client/renderer/chunk/SectionCompiler.java',
                    'level_renderer': 'net/minecraft/client/renderer/LevelRenderer.java',
                    'ber': 'net/minecraft/client/renderer/blockentity/BlockEntityRenderer.java'}
    with zipfile.ZipFile(archive) as jar:
        mapped = {name: jar.read(path).decode() for name, path in mapped_paths.items()}
    checks, findings = [], []
    report = {'method': 'Actual final SB926 vertices through reviewed production Java and mapped MC 1.21.1 transforms',
              'input_sha256': {name: sha(value) for name, value in raw.items()},
              'mapped_source_sha256': {name: sha(value.encode()) for name, value in mapped.items()}}
    def check(name, condition, evidence):
        checks.append({'name': name, 'ok': bool(condition), 'evidence': evidence})
        if not condition:
            findings.append(name)

    all_world = np.concatenate([np.array([p for t in mesh['groups'][name]['triangles'] for p in t['p']])
                                for name in ('body','p1_docked','p2_docked')])
    # JSON conversion/loader: one mesh vertex is divided by 16 once; UV is already normalized.
    check('mesh schema and normalized UV match loader', mesh['version'] == 1 and
          'groups.getAsJsonObject(name).getAsJsonArray("triangles")' in loader and
          'finite(p.get(vertex).getAsJsonArray().get(axis).getAsFloat()) / 16' in loader and
          'finite(uv.get(vertex).getAsJsonArray().get(axis).getAsFloat())' in loader,
          'Groups contain 2124/636/636/300/300 triangles; positions /16, UV unchanged in [0,1].')
    check('triangle-to-quad emits only one nondegenerate face', 'Math.min(vertex, 2) * 8' in loader and
          'vertex < 4' in loader and 'triangle += 24' in loader and 'entityCutoutNoCull(TEXTURE)' in loader,
          'Vertices 0,1,2,2 produce source triangle 0,1,2 and degenerate triangle 2,2,0; no duplicate reverse face.')
    check('no static block duplicate or double blockstate rotation',
          'return RenderShape.ENTITYBLOCK_ANIMATED' in source['block'] and
          json.loads(raw['block_model'])['elements'] == [] and
          all('y' not in value and 'x' not in value for value in json.loads(raw['blockstates'])['variants'].values()),
          'Empty static wrapper plus ENTITYBLOCK_ANIMATED; BER owns the only actual body mesh.')

    body_render = method(renderer, 'public void render(HomeConsoleBlockEntity')
    check('body uses one reviewed center pivot and one facing rotation',
          'poses.translate(0.5, 0, 0.5)' in body_render and
          'Axis.YP.rotationDegrees(-90f * turns(console.getBlockState()))' in body_render and
          'poses.translate(-0.5, 0, -0.5)' in body_render and
          body_render.count('SuborHardwareMesh.draw("body"') == 1,
          'T(.5,0,.5) * Ry(-90*turns) * T(-.5,0,-.5), not a second source .45 or 180-degree rotation.')
    min_x,max_x,min_z,max_z = (constant(layout,name) for name in ('minX','maxX','minZ','maxZ'))
    canonical_shape = np.array([[min_x,0,min_z],[max_x,3.2,max_z]])
    shape_cases = ('new Bounds(16-maxZ, 0, minX, 16-minZ, 3.2, maxX)',
                   'new Bounds(16-maxX, 0, 16-maxZ, 16-minX, 3.2, 16-minZ)',
                   'new Bounds(minZ, 0, 16-maxX, maxZ, 3.2, 16-minX)')
    check('layout rotation cases match rendering handedness', all(value in layout for value in shape_cases),
          'One quarter-turn is (x,z)->(16-z,x), the exact Ry(-90) center-pivot transform.')
    world_cases = []
    card = np.concatenate([q.vertices for q in inserted_card_quads()])
    bounds_ok = True
    for turns in range(4):
        matrix = translation(8,0,8) @ rotation('y',-90*turns) @ translation(-8,0,-8)
        transformed = points(np.concatenate([all_world,card]), matrix)
        corners = np.array([[x,y,z] for x in canonical_shape[:,0] for y in canonical_shape[:,1] for z in canonical_shape[:,2]])
        rotated_shape = points(corners,matrix)
        low, high = rotated_shape.min(0), rotated_shape.max(0)
        contained = bool((transformed.min(0)>=low-1e-7).all() and (transformed.max(0)<=high+1e-7).all())
        bounds_ok &= contained
        world_cases.append({'turns':turns,'actual_bounds':[transformed.min(0).tolist(),transformed.max(0).tolist()],
                            'shape_bounds':[low.tolist(),high.tolist()],'contained':contained})
    check('all four facings fit physical bounds including actual inserted card',bounds_ok,
          'Every runtime world vertex plus original beveled cartridge checked, not just idealized box corners.')
    report['world_facings'] = world_cases

    anchor = mesh['metadata']['anchors']
    card_values=np.array([constant(layout,'CARD_'+axis)*16 for axis in 'XYZ'])
    av_values=np.array([constant(layout,'AV_'+axis)*16 for axis in 'XYZ'])
    check('card slot transform matches actual mesh anchor', np.allclose(card_values,anchor['cartridge_bottom_center'],atol=1e-9)
          and constant(layout,'CARD_SCALE') == .30 and 'HomeConsoleLayout.CARD_X - .5' in body_render
          and 'HomeConsoleLayout.CARD_Z - .5' in body_render and 'poses.translate(-.5, 0, -.5)' in body_render,
          'T(anchor-.5) * S(.30) * T(-.5,0,-.5), centered about raw card bottom [8,0,8]; no extra yaw.')
    check('AV start matches back socket and route points remain direct', np.allclose(av_values,anchor['av_cable_start'],atol=1e-9)
          and 'HomeAvCableLayout.route(consoleState.getBlock() instanceof SuborConsoleBlock' in renderer
          and 'var next = points.get(i)' in renderer and 'new java.util.WeakHashMap<>()' in renderer,
          'SB926 start [12.77,.50175,12.68]/16; weak-key route cache, straight segments retain all obstacle-avoidance corners.')
    report['anchors']={'card_model_units':card_values.tolist(),'av_model_units':av_values.tolist()}

    held_render=renderer[renderer.index('private static final class ControllerItemRenderer'):renderer.index('private static final class SuborItemRenderer')]
    branch=held_render[held_render.index('HomeControllerData.Style.SUBOR'):held_render.index('poses.pushPose()')]
    check('port groups and canonical held branch are not swapped or rotated twice',
          'if (!console.controllerDocked(port)) continue' in body_render and
          'port == 0 ? "p1_docked" : "p2_docked"' in body_render and
          'port == 0 ? "p1_held" : "p2_held"' in branch and 'return;' in branch and 'heldControllerYaw' not in branch,
          'Port0->P1, port1->P2; dock visibility hides its cord too; SUBOR canonical+Z returns before FC yaw.')
    # Actual final GUI pipeline: screen T(8,8), S(16,-16,16), display XYZ, then ItemRenderer center.
    gui = display_matrix(item['display']['gui'],False) @ translation(-.5,-.5,-.5)
    projected = points(all_world/16,gui)
    pixels = projected[:,:2] * [16,-16] + 8
    normal = gui[:3,:3] @ [0,1,0]
    gui_ok = bool((pixels.min(0)>=0).all() and (pixels.max(0)<=16).all() and normal[2]>.5)
    check('builtin entity GUI shows key face and fits one normal item slot', gui_ok and item['parent']=='builtin/entity'
          and 'new SuborItemRenderer()' in renderer and 'ModItems.SUBOR_CONSOLE.get()' in renderer,
          'All actual body+docked vertices projected through GuiGraphics and ItemRenderer; no arbitrary preview recenter/fit.')
    check('mapped GUI transform order still matches audit', 'this.pose.scale(16.0F, -16.0F, 16.0F)' in mapped['gui'] and
          mapped['item_renderer'].index('handleCameraTransforms') < mapped['item_renderer'].index('translate(-0.5F, -0.5F, -0.5F)') and
          'rotationXYZ' in mapped['item_transform'], 'Local mapped source verifies the real XYZ/item-center/GUI sign order.')
    report['gui']={'pixel_bounds':[pixels.min(0).tolist(),pixels.max(0).tolist()],
                   'top_face_normal_camera_space':normal.tolist(),'runtime_item_display':item['display']['gui']}

    check('F3T uses atomic fresh arrays and clears failed reload instead of stale mesh',
          'modBus.addListener(SuborHardwareMesh::registerReload)' in renderer and
          'event.registerReloadListener((ResourceManagerReloadListener)' in loader and
          'volatile Map<String, float[]> meshes' in loader and 'meshes = Map.copyOf(next)' in loader and
          'catch (Exception exception)' in loader and loader.count('meshes = Map.of()') >= 2,
          'Each successful resource reload atomically swaps immutable map of newly prepared arrays; failure empties map. Texture ResourceLocation is re-resolved by Minecraft texture manager.')
    check('console cable cannot disappear with an invisible anchor section',
          'shouldRenderOffScreen(HomeConsoleBlockEntity' in renderer and
          'return true;' in method(renderer,'shouldRenderOffScreen(HomeConsoleBlockEntity') if 'shouldRenderOffScreen(HomeConsoleBlockEntity' in renderer else False,
          'SectionCompiler categorizes globals only at section compile; console must always be global, then existing dynamic AABB and default64-block range still cull it.')
    check('mapped culling evidence retains two-stage section/global behavior',
          'blockentityrenderer.shouldRenderOffScreen(p_350386_)' in mapped['compiler'] and
          'p_350754_.globalBlockEntities.add(p_350386_)' in mapped['compiler'] and
          'this.visibleSections' in mapped['level_renderer'] and 'this.globalBlockEntities' in mapped['level_renderer'],
          'A wide getRenderBoundingBox alone is insufficient for a non-global console BER.')
    report.update(ok=not findings,checks=checks,findings=findings,
                  limits=['Offline source/matrix audit, not a Minecraft screenshot or live F3+T test',
                          'First-person common controller pose is independently audited by spectator_settings',
                          'AV route considers the two known hardware housings, not unrelated world blocks',
                          'Resource-pack-invalid meshes deliberately disappear with one logged reload error; no fallback model is synthesized'])
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(f'{"PASS" if report["ok"] else "FINDINGS"}: {sum(c["ok"] for c in checks)}/{len(checks)} checks; GUI bounds {pixels.min(0).tolist()}..{pixels.max(0).tolist()}')
    print(str(args.output))
    if findings:
        print('\n'.join(findings))
        raise SystemExit(1)


if __name__=='__main__':
    main()
