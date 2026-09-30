"""Deterministic JSON/texture import, no raster editing or inferred animation rotations."""
import sys,zipfile,json,hashlib,copy,shutil
from pathlib import Path
root=Path(__file__).resolve().parents[1]; source=Path(sys.argv[1]);assets=root/'src/main/resources/assets/piq_md_home'
def write(path,data):
    path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(data,ensure_ascii=False,separators=(',',':')),encoding='utf-8')
with zipfile.ZipFile(source) as z:
    models=[]
    for key in ['empty','inserted']:
        model=json.loads(z.read(f'minecraft_assets/assets/retro_devices/models/item/md2_{key}.json'))
        model['textures']={'0':'piq_md_home:item/md2_cartridge_set','particle':'piq_md_home:item/md2_cartridge_set'}
        models.append(model)
        write(assets/f'models/block/md2_{key}.json',model)
        no_pad=copy.deepcopy(model);no_pad['elements']=[e for e in no_pad['elements'] if not e.get('name','').lower().startswith('p1')]
        write(assets/f'models/block/md2_{key}_borrowed.json',no_pad)
    texture=assets/'textures/item/md2_cartridge_set.png';texture.parent.mkdir(parents=True,exist_ok=True)
    texture.write_bytes(z.read('minecraft_assets/assets/retro_devices/textures/item/md2_cartridge_set.png'))
    # Model primitives and rotations are baked by the supplied exporter. Do not rotate the flaps again.
    pad=copy.deepcopy(models[0]);pad['elements']=[e for e in pad['elements'] if e.get('name','').lower().startswith('p1') and e['from'][2]<4]
    old_names={e.get('name') for e in models[0]['elements']}
    cart=copy.deepcopy(models[1]);cart['elements']=[e for e in cart['elements'] if e.get('name') not in old_names and '挡板' not in e.get('name','')]
    if not pad['elements'] or not cart['elements']:raise ValueError('missing extracted hardware')
    for name,m,scale,translation in [('md_controller',pad,[2.5]*3,[0,0,0]),('md_cartridge',cart,[2.5]*3,[0,0,0])]:
        center=[(min(e['from'][k] for e in m['elements'])+max(e['to'][k] for e in m['elements']))/2 for k in range(3)]
        for e in m['elements']:
            for field in ['from','to']:e[field]=[e[field][k]+8-center[k] for k in range(3)]
            if 'rotation' in e:e['rotation']['origin']=[e['rotation']['origin'][k]+8-center[k] for k in range(3)]
        m['display']={'gui':{'rotation':[25,-35,0],'translation':translation,'scale':scale},'firstperson_righthand':{'rotation':[0,-45,0],'translation':[0,2,0],'scale':[1.2]*3},'thirdperson_righthand':{'rotation':[60,0,0],'translation':[0,2,0],'scale':[.6]*3}}
        write(assets/f'models/item/{name}.json',m)
    write(assets/'models/item/md2.json',{'parent':'piq_md_home:block/md2_empty'})
    variants={}
    for facing,turn in [('north',0),('east',90),('south',180),('west',270)]:
        for inserted in [False,True]:
            for borrowed in [False,True]:
                key=f'facing={facing},inserted={str(inserted).lower()},borrowed={str(borrowed).lower()}'
                variants[key]={'model':'piq_md_home:block/md2_'+('inserted' if inserted else 'empty')+('_borrowed' if borrowed else ''),'y':turn}
    write(assets/'blockstates/md2.json',{'variants':variants})
    write(assets/'lang/zh_cn.json',{'block.piq_md_home.md2':'MD2 家用机（私人单人）','item.piq_md_home.md_cartridge':'MD 游戏卡带','item.piq_md_home.md_controller':'MD 6键手柄（借用）'})
    write(assets/'lang/en_us.json',{'block.piq_md_home.md2':'MD2 Console (Private P1)','item.piq_md_home.md_cartridge':'MD Game Cartridge','item.piq_md_home.md_controller':'MD 6-Button Controller (Loan)'})
    data=root/'src/main/resources/data/piq_md_home/loot_table/blocks'
    write(data/'md2.json',{'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':'piq_md_home:md2'}],'conditions':[{'condition':'minecraft:survives_explosion'}]}]})
    print(json.dumps({'sourceSha256':hashlib.sha256(source.read_bytes()).hexdigest(),'models':[len(m['elements']) for m in models],'controller':len(pad['elements']),'cartridge':len(cart['elements'])}))
