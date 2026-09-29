"""Split the existing authored mesh without changing any geometry/UV or its full item model."""
from pathlib import Path
import json,re
root=Path(__file__).resolve().parents[1]/'src/main/resources/assets/piq_fc_arcade'
source=json.loads((root/'models/block/rocket_arcade_body.json').read_text('utf8'))
groups={'body':[],'joystick':[],'start':[],**{f'button_{i}':[] for i in range(1,7)}}
for element in source['elements']:
    name=element.get('name','');button=re.match(r'操作按钮([1-6])',name)
    key=f'button_{button[1]}' if button else 'joystick' if name.startswith('单人摇杆') and '胶套' not in name else 'start' if name.startswith('单人开始键') else 'body'
    groups[key].append(element)
assert all(groups.values())
out=root/'models/block/legacy_animated';out.mkdir(exist_ok=True)
for key,elements in groups.items():
    model={k:v for k,v in source.items() if k not in ('elements','display')};model['elements']=elements
    (out/f'{key}.json').write_text(json.dumps(model,ensure_ascii=False,indent=2)+'\n','utf8')
print({k:len(v) for k,v in groups.items()})
