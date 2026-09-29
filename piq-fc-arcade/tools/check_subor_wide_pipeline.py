"""Read-only v5 SB926 wiring audit; old alpha5/alpha6/alpha7 audit/results remain unchanged.

Checks actual production source contracts and transformed final vertices. A small
Java probe executes HomeConsoleLayout directly. No Gradle, game or asset rewrites.
"""
import argparse
import json
import re
import subprocess
import tempfile
from pathlib import Path
import numpy as np
from build_subor_wide_hardware import NEW_MESH,OLD_MESH_PATH,OLD_MESH_SHA,TEXTURE,TEXTURE_SHA,OUT,ASSETS
from import_subor_hardware import encoded,sha,write_new
from check_controller_pose_pipeline import translation,rotation,scale,points,display_matrix
from check_subor_render_pipeline import method
from render_rocket_arcade_preview import collect_quads

ROOT=Path(__file__).resolve().parents[1];JAVA=ROOT/'src/main/java/cn/piq/fcarcade'
JDK=Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin')
WIDE_SHA='2EC8E417F019B8D26AB9B5FB37975C4A9C1973B8687339AE2900E3D715BD1A34'
PATHS={'renderer':JAVA/'client/HomeHardwareRenderer.java','loader':JAVA/'client/SuborHardwareMesh.java',
       'layout':JAVA/'home/HomeConsoleLayout.java','item':ASSETS/'models/item/subor_console.json',
       'wide':NEW_MESH,'old':OLD_MESH_PATH,'texture':TEXTURE,
       'card':ASSETS/'models/block/home_fc_cartridge.json'}


def probe():
    with tempfile.TemporaryDirectory(prefix='piq-subor-wide-qa-') as tmp:
        subprocess.run([str(JDK/'javac.exe'),'-encoding','UTF-8','-d',tmp,str(PATHS['layout']),str(ROOT/'tools/qa/SuborWideGeometryProbe.java')],check=True,capture_output=True,timeout=30)
        output=subprocess.run([str(JDK/'java.exe'),'-cp',tmp,'SuborWideGeometryProbe'],check=True,capture_output=True,text=True,timeout=30).stdout
    lines=output.splitlines()
    return {'wide':np.array([float(v) for v in lines[0].split()[1:]]),'old':np.array([float(v) for v in lines[1].split()[1:]]),
            'bounds':[np.array([float(v) for v in line.split()[2:]]).reshape(2,3)/16 for line in lines[2:]],'stdout':output}


def world(turn):return translation(.5,0,.5)@rotation('y',-90*turn)@translation(-.5,0,-.5)


def card_matrix(turn,values):
    x,y,z,size=values
    return translation(.5,0,.5)@rotation('y',-90*turn)@translation(x-.5,y,z-.5)@scale([size]*3)@translation(-.5,0,-.5)


def mesh_points(mesh,names):
    return np.concatenate([np.array(t['p']) for name in names for t in mesh['groups'][name]['triangles']])/16


def canonical_held_path(renderer,loader):
    """Accept the old direct draw or the new local-button-only draw, never the wide/FC pose path."""
    controller=re.sub(r'\s+','',method(renderer,'private static final class ControllerItemRenderer'))
    prefix='if(HomeControllerData.style(stack)==HomeControllerData.Style.SUBOR){'
    legacy_draw=re.sub(r'\s+','',method(loader,'static void draw('))
    if legacy_draw!='drawData(meshes.get(group),poses,buffers,light,overlay);':return False
    if prefix not in controller:return False
    if any(s in controller[:controller.index(prefix)] for s in ('poses.scale(','poses.translate(','poses.mulPose(')):return False
    direct='SuborHardwareMesh.draw(port==0?"p1_held":"p2_held",poses,buffers,light,overlay);return;}'
    if prefix+direct in controller:return True
    animated='SuborHardwareMesh.drawHeld(port==0?"p1_held":"p2_held",stack,context,poses,buffers,light,overlay);return;}'
    if prefix+animated not in controller:return False
    if 'static void drawHeld(' not in loader:return False
    reload=re.sub(r'\s+','',method(loader,'private static void reload('))
    held=re.sub(r'\s+','',method(loader,'static void drawHeld('))
    return (all(s in reload for s in (
        'for(Stringgroup:newString[]{"p1_held","p2_held"}){float[]data=meshes.get(group);',
        'if(data!=null)next.put(group,ControllerButtonRenderer.partitionSubor(data));','heldParts=Map.copyOf(next);'))
        and all(s in held for s in ('float[][]parts=heldParts.get(group);',
        'if(parts==null){draw(group,poses,buffers,light,overlay);return;}',
        'varanimation=ClientControllerAnimation.state(stack,context);',
        'for(intpart=0;part<parts.length;part++){poses.pushPose();try{',
        'if(ControllerButtonRenderer.moving(part,animation))ControllerButtonRenderer.apply(part,animation,true,poses);',
        'drawData(parts[part],poses,buffers,light,overlay);',
        '}finally{poses.popPose();}'))
        and all(s not in held for s in ('wideMeshes','drawWide(','heldControllerYaw','poses.scale(','poses.translate(','poses.mulPose(')))


def analyze(actual=None,renderer_override=None,loader_override=None):
    raw={name:path.read_bytes() for name,path in PATHS.items()};renderer=renderer_override or raw['renderer'].decode();loader=loader_override or raw['loader'].decode()
    r=re.sub(r'\s+','',renderer);l=re.sub(r'\s+','',loader)
    wide=json.loads(raw['wide']);old=json.loads(raw['old']);item=json.loads(raw['item']);card=json.loads(raw['card']);actual=actual or probe();checks=[]
    def check(name,condition,evidence):checks.append({'name':name,'ok':bool(condition),'evidence':evidence})
    check('v5 mesh and alpha5 mesh/PNG frozen',sha(raw['wide'])==WIDE_SHA and sha(raw['old'])==OLD_MESH_SHA and sha(raw['texture'])==TEXTURE_SHA,
          'Exact frozen resource hashes; this tool does not regenerate model or rerun the 16 geometry tests')
    check('separate 7-group wide and 5-group legacy resource selection',all(s in l for s in (
        '"meshes/home_subor_sb926_wide.json"','"meshes/home_subor_sb926.json"',
        'meshes=load(manager,MODEL,GROUPS);','wideMeshes=load(manager,WIDE_MODEL,WIDE_GROUPS);',
        'drawData(wideMeshes.get(group),poses,buffers,light,overlay);','drawData(meshes.get(group),poses,buffers,light,overlay);'))
        and len(wide['groups'])==7 and len(old['groups'])==5,
        'Independent immutable maps; wide loader names include both lids, old loader remains five groups')
    render=re.sub(r'\s+','',method(renderer,'public void render(HomeConsoleBlockEntity console'))
    check('world rotation precedes wide body without extra scale',
          'poses.translate(0.5,0,0.5);poses.mulPose(Axis.YP.rotationDegrees(-90f*turns(console.getBlockState())));poses.translate(-0.5,0,-0.5);if(wide){SuborHardwareMesh.drawWide("body"' in render,
          'T(.5) Ry(-90turn) T(-.5); final mesh already contains world modelling transform')
    check('exactly one lid and docked controls hidden per port',all(s in render for s in (
          'console.insertedCartridge().isEmpty()?"lid_closed":"lid_open"',
          'if(!console.controllerDocked(port))continue;',
          'if(wide)SuborHardwareMesh.drawWide(port==0?"p1_docked":"p2_docked"')),
          'Card presence selects one lid; each absent controller skips its body and cord group')
    check('legacy wide-false body and dock paths retained',
          'elseif(subor)SuborHardwareMesh.draw("body"' in render and 'elseif(subor)SuborHardwareMesh.draw(port==0?"p1_docked":"p2_docked"' in render,
          'No legacy visual migration or use of wide map when wide=false')
    check('card production branch uses matching scale/anchor',all(s in render for s in (
          '(wide?HomeConsoleLayout.WIDE_CARD_X:HomeConsoleLayout.CARD_X)-.5',
          'wide?HomeConsoleLayout.WIDE_CARD_Y:HomeConsoleLayout.CARD_Y',
          '(wide?HomeConsoleLayout.WIDE_CARD_Z:HomeConsoleLayout.CARD_Z)-.5',
          'wide?HomeConsoleLayout.WIDE_CARD_SCALE:HomeConsoleLayout.CARD_SCALE',
          'poses.scale(scale,scale,scale);poses.translate(-.5,0,-.5);')) and
          np.allclose(actual['wide'],[1,1.52/16,22.164/16,.6]) and np.allclose(actual['old'],[.5,.81/16,11.7375/16,.3]),
          {'compiled_wide_values':actual['wide'].tolist(),'compiled_old_values':actual['old'].tolist()})
    card_raw=np.concatenate([q.vertices for q in collect_quads(card)])/16;details=[]
    for turn in range(4):
        transformed_card=points(card_raw,card_matrix(turn,actual['wide']))
        expected_card=points((card_raw-[.5,0,.5])*.6+actual['wide'][:3],world(turn))
        bodies=[]
        for lid in ('lid_closed','lid_open'):
            bodies.append(points(mesh_points(wide,['body','p1_docked','p2_docked',lid]),world(turn)))
        all_points=np.concatenate(bodies+[transformed_card]);bounds=actual['bounds'][turn]
        check('four-turn card alignment and full AABB '+str(turn),np.allclose(transformed_card,expected_card,atol=1e-12) and
              np.all(all_points>=bounds[0]-1e-9) and np.all(all_points<=bounds[1]+1e-9),
              {'actual_full_bounds':[all_points.min(0).tolist(),all_points.max(0).tolist()],'production_bounds':bounds.tolist()})
        details.append({'turn':turn,'card_center_bottom':points([[.5,0,.5]],card_matrix(turn,actual['wide']))[0].tolist()})
    subor_item=re.sub(r'\s+','',method(renderer,'private static final class SuborItemRenderer'))
    names=re.findall(r'SuborHardwareMesh\.drawWide\("([^"]+)"',subor_item)
    check('item uses one half-scale and closed wide assembly',
          subor_item.count('poses.scale(.5f,.5f,.5f);')==1 and names==['body','lid_closed','p1_docked','p2_docked'] and item['parent']=='builtin/entity',
          'S.5 once, no inserted card or open lid, no old body mixed in')
    gui=display_matrix(item['display']['gui'],False)@translation(-.5,-.5,-.5)@scale([.5]*3)
    projected=points(mesh_points(wide,['body','lid_closed','p1_docked','p2_docked']),gui);pixels=projected[:,:2]*[16,-16]+8
    check('actual item GUI shows keyboard and fits slot',np.all(pixels>=0) and np.all(pixels<=16) and (gui[:3,:3]@[0,1,0])[2]>0,
          {'pixel_bounds':[pixels.min(0).tolist(),pixels.max(0).tolist()]})
    check('held old canonical meshes unchanged and no wide/yaw path',all(wide['groups'][name]['triangles']==old['groups'][name]['triangles'] for name in ('p1_held','p2_held')) and
          canonical_held_path(renderer,loader),
          'Original canonical 12.69-unit held triangles remain identical; local-button partitions come from legacy meshes and fallback stays legacy. SUBOR always returns before FC heldYaw; no whole-held scaling/rotation or wide body path.')
    check('reload fresh bounded arrays and fail-closed maps',all(s in l for s in ('privatestaticvolatileMap<String,float[]>meshes=Map.of();','privatestaticvolatileMap<String,float[]>wideMeshes=Map.of();',
          'triangles.isEmpty()||triangles.size()>12000','returnMap.copyOf(next);','catch(Exceptionexception)','returnMap.of();')) and
          'modBus.addListener(SuborHardwareMesh::registerReload);' in r,
          'Reload reconstructs groups off draw path; invalid resource clears only its map, no stale geometry retained')
    check('loader preserves model units UV normals and triangle topology',all(s in l for s in (
          'finite(p.get(vertex).getAsJsonArray().get(axis).getAsFloat())/16',
          'finite(uv.get(vertex).getAsJsonArray().get(axis).getAsFloat())','finite(normal.get(axis).getAsFloat())',
          'triangle+=24','Math.min(vertex,2)*8','RenderType.entityCutoutNoCull(TEXTURE)')),
          'Positions /16 only; UV stays normalized; 0,1,2,2 gives one triangle plus a degenerate fourth vertex')
    return {'ok':all(c['ok'] for c in checks),'checks':checks,'check_count':len(checks),'turns':details,
            'source_sha256':{name:sha(value) for name,value in raw.items()},'production_probe':actual['stdout'],
            'limits':['Read-only source/matrix wiring audit, not Minecraft or F3T playtest',
              'Reuses frozen v5 19-test geometric audit; does not replace or relax alpha5/alpha6/alpha7 acceptance',
              'AV external cable routing is separately audited by the other agent']}


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--write',action='store_true');args=parser.parse_args();report=analyze()
    if args.write:write_new({OUT/'wide-runtime-render-audit.json':encoded(report)})
    print(json.dumps(report,ensure_ascii=True,indent=2))
    if not report['ok']:raise SystemExit(1)


if __name__=='__main__':main()
