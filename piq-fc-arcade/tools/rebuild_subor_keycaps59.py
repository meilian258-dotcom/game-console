"""FC59 deterministic key-face typesetting on the three actual production meshes.

The historical generator and FC58 evidence remain unchanged. New atlas tiles are
keyed by label, material AND measured surface dimensions, not just the label.
Only key-face UVs and previously unused atlas pixels change; no mesh geometry,
non-key UV, branding, controller, old tile or texture dimensions are replaced.
This is a procedural source-asset build, not editing a screenshot with a model.
"""
from __future__ import annotations
import argparse
import copy
import hashlib
import io
import json
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw, ImageFont
from build_subor_reference_model import Model, SIZE, COLORS
from render_rocket_arcade_preview import Quad, render_view

PROJECT = Path(__file__).resolve().parents[1]
ROOT = PROJECT.parent
ASSETS = PROJECT / 'src/main/resources/assets/piq_fc_arcade'
TEXTURE = 'home_subor_sb926.png'
EXPECTED = {
    TEXTURE: '9E0C311763C13B63778B71BFA1C66532C1A31F4195599CB228FDC592ACE2C9C3',
    'home_subor_sb926.json': 'ED2D392A4B735306F60EAE4946820ABA36FB35C28C288D9220428F252DC256F6',
    'home_subor_sb926_wide.json': 'BD6F5E1B58D9DE37D39EC50A94906BC006950D99565A3418FE18A2F5A27A40DE',
    'home_subor_sb926_compact.json': '25C177B58B56BC846D4CD9689E5A00FF63C0F3C894E808A0028BA511B232FD62',
}
FONT = Path('C:/Windows/Fonts/arialbd.ttf')
START_Y = 1120
DENSITY = 56
SHORT_LABELS = {'Print': 'PrtSc', 'Scroll': 'ScrLk', 'Pause': 'Paus', 'Delete': 'Del', 'Insert': 'Ins', 'Caps Lock': 'Caps'}

def sha(raw): return hashlib.sha256(raw).hexdigest().upper()
def encode(value): return (json.dumps(value, ensure_ascii=False, separators=(',', ':'))+'\n').encode('utf-8')
def asset_path(name): return ASSETS / ('textures/block' if name.endswith('.png') else 'meshes') / name

def source_tiles():
    atlas = Model(key_hints=False).build().atlas
    assert atlas.y + atlas.row + 7 < START_Y
    return {tuple(v): (k.split('_', 3)[2], k.split('_', 3)[1])
            for k, v in atlas.tiles.items() if k.startswith('键_')}

def rect_of(triangle):
    uv = np.rint(np.asarray(triangle['uv']) * SIZE).astype(int)
    assert np.max(np.abs(np.asarray(triangle['uv'])*SIZE - uv)) < 1e-6
    return tuple(np.r_[uv.min(0), uv.max(0)].tolist())

def key_faces(doc, tiles):
    triangles = doc['groups']['body']['triangles']
    result = []
    i = 0
    while i < len(triangles):
        tri = triangles[i]
        if tri['n'][1] < .5 or rect_of(tri) not in tiles:
            i += 1
            continue
        old = rect_of(tri)
        assert i+1 < len(triangles) and rect_of(triangles[i+1]) == old
        vertices = {}
        for face in triangles[i:i+2]:
            for uv, point in zip(face['uv'], face['p']):
                k = tuple(np.rint(np.asarray(uv)*SIZE).astype(int))
                if k in vertices: assert np.allclose(vertices[k], point)
                vertices[k] = np.asarray(point)
        x, y, x1, y1 = old
        assert set(vertices) == {(x,y),(x1,y),(x,y1),(x1,y1)}
        width = float(np.linalg.norm(vertices[x1,y]-vertices[x,y]))
        height = float(np.linalg.norm(vertices[x,y1]-vertices[x,y]))
        label, material = tiles[old]
        result.append(dict(start=i, old=list(old), label=label, material=material, width=width, height=height))
        i += 2
    assert len(result) == 101, f'Expected 101 actual key surfaces, found {len(result)}'
    unit = float(np.median([r['height'] for r in result]))
    for row in result:
        row['relativeWidth'] = round(row['width']/unit, 4)
        row['relativeHeight'] = round(row['height']/unit, 4)
    return result

class KeyAtlas:
    def __init__(self, original):
        self.image = original.copy()
        self.x, self.y, self.row = 8, START_Y, 0
        self.tiles = {}
        self.records = []
    def tile(self, face):
        label, material = face['label'], face['material']
        identity = (label, material, face['relativeWidth'], face['relativeHeight'])
        if identity in self.tiles: return self.tiles[identity]
        w = round(DENSITY*face['relativeWidth'])
        h = round(DENSITY*face['relativeHeight'])
        assert w > 12 and h > 12
        if self.x+w+8 > SIZE: self.x, self.y, self.row = 8, self.y+self.row+8, 0
        assert self.y+h+8 < SIZE, 'Key atlas overflow; never resize or repack unrelated tiles'
        x,y = self.x,self.y
        draw = ImageDraw.Draw(self.image)
        draw.rectangle((x-3,y-3,x+w+3,y+h+3), fill=COLORS[material])
        text = SHORT_LABELS.get(label,label).strip()
        # Each label category shares one size. No size depends on key geometry;
        # long/tall keys therefore never stretch or enlarge their lettering.
        size = 32 if len(text) == 1 else 26 if text.startswith('F') and text[1:].isdigit() else 18
        font = ImageFont.truetype(str(FONT), size)
        mask = Image.new('L',(w,h))
        if text:
            box = font.getbbox(text)
            tw,th = box[2]-box[0],box[3]-box[1]
            assert tw <= w-2 and th <= h-4, (label,w,h,tw,th)
            pos = ((w-tw)//2-box[0],(h-th)//2-box[1])
            ImageDraw.Draw(mask).text(pos,text,font=font,fill=255)
            # Font metric boxes include leading/overhang whitespace (arrows,
            # punctuation). Center the actual raster ink, without resizing it.
            glyph = mask.crop(mask.getbbox())
            mask = Image.new('L',(w,h))
            mask.paste(glyph,((w-glyph.width)//2,(h-glyph.height)//2))
            ink = Image.new('RGBA',(w,h),'#242d2a')
            self.image.paste(ink,(x,y),mask)
        rect = [x,y,x+w,y+h]
        record = dict(label=label,display=text,material=material,relativeWidth=identity[2],
                      relativeHeight=identity[3],rect=rect,fontSize=size,inkBounds=mask.getbbox())
        self.records.append(record)
        self.tiles[identity] = rect
        self.x += w+8
        self.row = max(self.row,h)
        return rect

def remap(doc, faces, atlas):
    result = copy.deepcopy(doc)
    triangles = result['groups']['body']['triangles']
    changed = set()
    for face in faces:
        x,y,x1,y1 = face['old']
        nx,ny,nx1,ny1 = atlas.tile(face)
        face['new'] = [nx,ny,nx1,ny1]
        for index in (face['start'],face['start']+1):
            triangles[index]['uv'] = [[(nx+(u*SIZE-x)/(x1-x)*(nx1-nx))/SIZE,
                                        (ny+(v*SIZE-y)/(y1-y)*(ny1-ny))/SIZE]
                                       for u,v in triangles[index]['uv']]
            changed.add(index)
    # Exact parsed-document comparison after reverting the 202 approved UVs.
    check = copy.deepcopy(result)
    for index in changed: check['groups']['body']['triangles'][index]['uv'] = doc['groups']['body']['triangles'][index]['uv']
    assert check == doc
    return result

def build(original, documents):
    tiles = source_tiles()
    atlas = KeyAtlas(original)
    revised = {}
    details = {name:key_faces(doc,tiles) for name,doc in documents.items()}
    # Pack tall keys together; declaration-order shelves waste whole rows on
    # isolated vertical Enter/+ keys and needlessly shrink every other label.
    all_faces = [face for faces in details.values() for face in faces]
    for face in sorted(all_faces,key=lambda f:(-f['relativeHeight'],-f['relativeWidth'],f['label'],f['material'])):
        atlas.tile(face)
    for name, doc in documents.items():
        faces = details[name]
        revised[name] = remap(doc,faces,atlas)
    before, after = np.asarray(original), np.asarray(atlas.image)
    assert np.array_equal(before[:START_Y-3],after[:START_Y-3])
    assert before.shape == after.shape == (SIZE,SIZE,4)
    assert np.array_equal(before[:,:,3],after[:,:,3])
    assert np.any(before != after)
    return atlas,revised,details

def quads(doc, key_only=False):
    triangles = doc['groups']['body']['triangles']
    if key_only:
        selected = set()
        for face in key_faces(doc,source_tiles()): selected.update((face['start'],face['start']+1))
        triangles = [t for i,t in enumerate(triangles) if i in selected]
    return [Quad(np.asarray(t['p']+[t['p'][-1]]),np.asarray(t['uv']+[t['uv'][-1]])*16,'skin',i,'mesh')
            for i,t in enumerate(triangles)]

def preview(before,after,old,new,output):
    # Actual production triangles are rasterized, never the source draft model.
    images=[]
    for title,doc,texture in [('FC58',before,old),('FC59',after,new)]:
        image,_=render_view(quads(doc),{'skin':np.asarray(texture)},(0,1,-.48),(1800,1000),1)
        canvas=Image.new('RGB',image.size,'#ecece1');canvas.paste(image,(0,0),image)
        ImageDraw.Draw(canvas).text((24,16),title,font=ImageFont.truetype(str(FONT),34),fill='#30372f')
        images.append(canvas)
    sheet=Image.new('RGB',(1800,2000));sheet.paste(images[0],(0,0));sheet.paste(images[1],(0,1000));sheet.save(output)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,default=ROOT/'outputs/subor59/keycaps-v1')
    parser.add_argument('--apply',action='store_true')
    args=parser.parse_args()
    assert not args.output.exists(), 'Use a new evidence directory; preserve previous runs'
    raws={name:asset_path(name).read_bytes() for name in EXPECTED}
    for name,raw in raws.items(): assert sha(raw)==EXPECTED[name], f'Changed source: {name}'
    original=Image.open(io.BytesIO(raws[TEXTURE])).convert('RGBA')
    # The previous procedural generation is an independent baseline identity check.
    assert np.array_equal(np.asarray(original),np.asarray(Model(key_hints=False).build().atlas.image))
    documents={name:json.loads(raw) for name,raw in raws.items() if name.endswith('.json')}
    atlas,revised,details=build(original,documents)
    output=io.BytesIO();atlas.image.save(output,format='PNG')
    candidates={TEXTURE:output.getvalue(),**{name:encode(doc) for name,doc in revised.items()}}
    args.output.mkdir(parents=True)
    for folder,files in [('before',raws),('candidate',candidates)]:
        (args.output/folder).mkdir()
        for name,raw in files.items(): (args.output/folder/name).write_bytes(raw)
    for name in documents: preview(documents[name],revised[name],original,atlas.image,args.output/(Path(name).stem+'-comparison.png'))
    report=dict(ok=True,applied=False,font=str(FONT),fontSha256=sha(FONT.read_bytes()),fontSizes={'symbols':32,'functionNumbers':26,'words':18},
                labelsCentered=True,dimensionsInTileIdentity=True,oldAtlasPixelsExact=True,alphaExact=True,
                allGeometryNormalsNonKeyUvsAndMetadataExact=True,meshes=details,tiles=atlas.records,
                assets=[dict(name=n,before=sha(raws[n]),after=sha(raw)) for n,raw in candidates.items()],
                limitation='Offline actual-mesh rendering; Minecraft, shaders, mipmaps and distant text not tested')
    if args.apply:
        for name in raws: assert asset_path(name).read_bytes()==raws[name], 'Source changed while building'
        for name,raw in candidates.items(): asset_path(name).write_bytes(raw)
        for name,raw in candidates.items(): assert asset_path(name).read_bytes()==raw
        report['applied']=True
    (args.output/'verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'ok':True,'applied':report['applied'],'tiles':len(atlas.records),'assets':report['assets'],'output':str(args.output)},ensure_ascii=False))

if __name__=='__main__': main()
