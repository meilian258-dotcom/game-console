import json, pathlib, hashlib, xml.etree.ElementTree as ET
root=pathlib.Path(__file__).resolve().parents[1]
base=root/'src/main/resources/assets/piq_computer'
count=elements=0
for p in base.rglob('*.json'):
    j=json.loads(p.read_text(encoding='utf-8'));count+=1
    for e in j.get('elements',[]):
        elements+=1
        assert all(-16<=v<=32 for key in ('from','to') for v in e[key]),(p,e)
        assert all(e['from'][i]<=e['to'][i] for i in range(3)),(p,e)
        assert e.get('rotation',{}).get('angle',0) in [-45,-22.5,0,22.5,45],(p,e)
        assert all(f.get('texture','').startswith('#') for f in e['faces'].values())
    for key,v in j.get('textures',{}).items():
        if v.startswith('piq_computer:'):assert (base/'textures'/(v.split(':')[1]+'.png')).is_file(),(p,v)
    parent=j.get('parent','')
    if parent.startswith('piq_computer:'):assert (base/'models'/(parent.split(':')[1]+'.json')).is_file(),(p,parent)
for model in ['case','side_panel','motherboard','cpu','cooler','ram','ram_2','gpu','hdd','psu']:
    data=json.loads((base/f'models/block/{model}.json').read_text())
    assert all(0<=n<=22.01 for e in data['elements'] for key in ('from','to') for n in [e[key][1]])
assert json.loads((base/'models/block/case.json').read_text())['textures']['0']=='piq_computer:block/pc_atlas'
print(json.dumps({'jsonFiles':count,'elements':elements,'status':'PASS','scope':'Model structure, bounds, rotation and resource references; not Minecraft visual verification'}))
