"""Deterministic data-only importer for the two user supplied model kits."""
import argparse, json, zipfile, hashlib, copy
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
RES=ROOT/'src/main/resources'
ASSET=RES/'assets/piq_computer'
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--model-dir', type=Path, required=True, help='Directory containing the two source model kit ZIPs')
options=parser.parse_args()
for filename in ('黑白可组装主机_模型套件.zip', '经典黑白键鼠_模型套件.zip'):
    if not (options.model_dir/filename).is_file():
        parser.error('Missing model kit in --model-dir: '+filename)
def write(path,data):
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def transform(value):
    if isinstance(value,dict): return {k:transform(v) for k,v in value.items()}
    if isinstance(value,list): return [transform(v) for v in value]
    if isinstance(value,str): return value.replace('modular_black_pc:','piq_computer:').replace('classic_pc_peripherals:','piq_computer:')
    return value
report=[]
for filename,ns in [('黑白可组装主机_模型套件.zip','modular_black_pc'),('经典黑白键鼠_模型套件.zip','classic_pc_peripherals')]:
    archive=options.model_dir/filename
    with zipfile.ZipFile(archive) as z:
        prefix='minecraft_assets/assets/'+ns+'/'
        count=0
        for member in z.namelist():
            if not member.startswith(prefix) or member.endswith('/'): continue
            relative=Path(member[len(prefix):]);target=(ASSET/relative).resolve()
            if not target.is_relative_to(ASSET.resolve()): raise ValueError(member)
            if relative.parts[0] not in ('models','textures'):continue
            raw=z.read(member)
            if relative.suffix=='.json':write(target,transform(json.loads(raw)))
            elif relative.suffix=='.png':target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(raw)
            else:continue
            count+=1
        report.append(dict(file=filename,sha256=hashlib.sha256(archive.read_bytes()).hexdigest(),imported=count))

# Only small state spaces: installed parts are drawn from the BE, not 2^N blockstates.
write(ASSET/'models/block/empty_proxy.json',{'textures':{'particle':'piq_computer:block/pc_atlas'},'elements':[]})
for white in [False,True]:
    color='white' if white else 'black'
    case='case_white' if white else 'case'
    write(ASSET/f'blockstates/computer_{color}.json',{'variants':{'':{'model':'piq_computer:block/empty_proxy'}}})
    write(ASSET/f'models/item/computer_{color}.json',{'parent':'piq_computer:block/'+case,'display':{'gui':{'rotation':[15,225,0],'translation':[0,-1.55,0],'scale':[.53,.53,.53]}}})
    write(RES/f'data/piq_computer/loot_table/blocks/computer_{color}.json',{'type':'minecraft:block','pools':[]})
    for kind in ['keyboard','mouse']:
        write(ASSET/f'blockstates/{kind}_{color}.json',{'variants':{f'facing={f}':{'model':f'piq_computer:block/{kind}_body_{color}','y':y} for f,y in [('north',0),('east',90),('south',180),('west',270)]}})
        write(ASSET/f'models/item/{kind}_{color}.json',{'parent':f'piq_computer:block/{kind}_body_{color}'})
        write(RES/f'data/piq_computer/loot_table/blocks/{kind}_{color}.json',{'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':f'piq_computer:{kind}_{color}'}],'conditions':[{'condition':'minecraft:survives_explosion'}]}]})
write(ASSET/'models/item/connector.json',{'parent':'minecraft:item/generated','textures':{'layer0':'minecraft:item/string'}})
names={'computer_black':'黑色可组装电脑','computer_white':'白色可组装电脑','keyboard_black':'黑色键盘','keyboard_white':'白色键盘','mouse_black':'黑色鼠标','mouse_white':'白色鼠标'}
lang={f'block.piq_computer.{k}':v for k,v in names.items()}
lang.update({f'item.piq_computer.{k}':v for k,v in {'motherboard':'电脑主板','cpu':'处理器','cooler':'CPU 散热器','ram':'内存条','gpu':'显卡','hdd':'硬盘','psu':'电源','connector':'外设连接器'}.items()})
write(ASSET/'lang/zh_cn.json',lang)
english={'computer_black':'Black Modular Computer','computer_white':'White Modular Computer','keyboard_black':'Black Keyboard','keyboard_white':'White Keyboard','mouse_black':'Black Mouse','mouse_white':'White Mouse'}
en={f'block.piq_computer.{k}':v for k,v in english.items()}
en.update({f'item.piq_computer.{k}':v for k,v in {'motherboard':'Motherboard','cpu':'Processor','cooler':'CPU Cooler','ram':'Memory Module','gpu':'Graphics Card','hdd':'Hard Drive','psu':'Power Supply','connector':'Peripheral Connector'}.items()})
write(ASSET/'lang/en_us.json',en)
def recipe(name,items):
    write(RES/f'data/piq_computer/recipe/{name}.json',{'type':'minecraft:crafting_shapeless','category':'misc','ingredients':[{'item':item if ':' in item else 'minecraft:'+item} for item in items],'result':{'id':'piq_computer:'+name,'count':1}})
for name,items in {
    'motherboard':['copper_ingot','copper_ingot','redstone','redstone','slime_ball'],
    'cpu':['gold_ingot','quartz','redstone'],
    'cooler':['iron_ingot','iron_ingot','iron_bars'],
    'ram':['gold_nugget','gold_nugget','redstone'],
    'gpu':['iron_ingot','gold_ingot','quartz','redstone'],
    'hdd':['iron_ingot','iron_ingot','redstone'],
    'psu':['iron_ingot','copper_ingot','redstone'],
    'connector':['string','copper_ingot'],
}.items():recipe(name,items)
for color in ['black','white']:
    recipe('computer_'+color,['iron_ingot']*6+[color+'_dye'])
    recipe('keyboard_'+color,['stone_button']*3+['iron_ingot','redstone',color+'_dye'])
    recipe('mouse_'+color,['stone_button','iron_nugget','redstone',color+'_dye'])
write(ROOT/'model-import.json',report)
# Render-only static shells omit printed fan discs; the renderer draws stationary rims and rotating blades.
for model,fan in [('case','rear_fan_grille'),('case_white','rear_fan_grille'),('cooler','cpu_fan_visible'),('gpu','gpu_fan_visible')]:
    data=json.loads((ASSET/f'models/block/{model}.json').read_text('utf-8'))
    data['elements']=[e for e in data['elements'] if e.get('name')!=fan]
    write(ASSET/f'models/block/{model}_static.json',data)
for color in ['black','white']:
    keyboard=json.loads((ASSET/f'models/block/keyboard_body_{color}.json').read_text('utf-8'))
    mouse=json.loads((ASSET/f'models/block/mouse_body_{color}.json').read_text('utf-8'))
    elements=[]
    for data,dx in [(keyboard,1.0),(mouse,8.35)]:
        for element in data['elements']:
            e=copy.deepcopy(element)
            for key in ['from','to']:
                e[key]=[e[key][0]*.68+dx,e[key][1]*.68,(e[key][2]-8)*.68+8]
            if 'rotation' in e:
                p=e['rotation']['origin'];e['rotation']['origin']=[p[0]*.68+dx,p[1]*.68,(p[2]-8)*.68+8]
            elements.append(e)
    keyboard['elements']=elements
    name='keyboard_mouse_'+color
    write(ASSET/f'models/block/{name}.json',keyboard)
    write(ASSET/f'blockstates/{name}.json',{'variants':{f'facing={f}':{'model':'piq_computer:block/'+name,'y':y} for f,y in [('north',0),('east',90),('south',180),('west',270)]}})
    write(ASSET/f'models/item/{name}.json',{'parent':'piq_computer:block/'+name})
    write(RES/f'data/piq_computer/loot_table/blocks/{name}.json',{'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':'piq_computer:'+name}],'conditions':[{'condition':'minecraft:survives_explosion'}]}]})
    recipe(name,['piq_computer:keyboard_'+color,'piq_computer:mouse_'+color])
    lang['block.piq_computer.'+name]=('黑色' if color=='black' else '白色')+'键鼠套装'
    en['block.piq_computer.'+name]=color.title()+' Keyboard and Mouse Set'
write(ASSET/'lang/zh_cn.json',lang);write(ASSET/'lang/en_us.json',en)
print(json.dumps(report,ensure_ascii=False))
