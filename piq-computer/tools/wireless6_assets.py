"""Deterministic PC6 resource derivation; never rerun the historical full importer."""
from pathlib import Path
import copy,json,sys
ROOT=Path(__file__).resolve().parents[1]
RES=ROOT/'src/main/resources'; A=RES/'assets/piq_computer'
def raw(p):return json.loads(p.read_text('utf8'))
def write(p,value):
    p.parent.mkdir(parents=True,exist_ok=True)
    p.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
def derive():
    result={}
    for color in ['black','white']:
        keyboard=raw(A/f'models/block/keyboard_body_{color}.json')
        mouse=raw(A/f'models/block/mouse_body_{color}.json')
        elements=[]
        # Looking at the NORTH/front face, viewer-right is model -X.
        for data,dx in [(keyboard,4.12),(mouse,-3.75)]:
            for original in data['elements']:
                e=copy.deepcopy(original)
                def transform(v):return [round(v[0]*.68+dx,8),round(v[1]*.68,8),round((v[2]-8)*.68+8,8)]
                for key in ['from','to']:e[key]=transform(e[key])
                if 'rotation' in e:e['rotation']['origin']=transform(e['rotation']['origin'])
                elements.append(e)
        keyboard['elements']=elements
        result[A/f'models/block/keyboard_mouse_{color}.json']=keyboard
        result[RES/f'data/piq_computer/recipe/keyboard_mouse_{color}.json']={
            'type':'minecraft:crafting_shapeless','category':'misc',
            'ingredients':[{'item':'minecraft:'+i} for i in ['stone_button','stone_button','stone_button','iron_ingot','redstone','copper_ingot',color+'_dye']],
            'result':{'id':'piq_computer:keyboard_mouse_'+color,'count':1}}
        result[RES/f'data/piq_computer/recipe/keyboard_mouse_{color}_legacy.json']={
            'type':'minecraft:crafting_shapeless','category':'misc',
            'ingredients':[{'item':'piq_computer:'+i+'_'+color} for i in ['keyboard','mouse']],
            'result':{'id':'piq_computer:keyboard_mouse_'+color,'count':1}}
    # Front USB slot, model coordinates BEFORE the common .6 case transform.
    # Reuse the supplied dark shell / silver metal atlas faces, no generated bitmap.
    case=raw(A/'models/block/case_static.json')
    donor=next(e for e in case['elements'] if 'usb' in e.get('name','').lower())
    dark=next(e for e in case['elements'] if e.get('name','').startswith('usb_hole_'))
    receiver={'textures':case['textures'],'elements':[]}
    for name,lo,hi in [('receiver_usb',[4.68,16.36,.35],[5.32,16.74,.84]),('receiver_body',[4.52,16.2,-.32],[5.48,16.90,.43])]:
        receiver['elements'].append({'name':name,'from':lo,'to':hi,'faces':copy.deepcopy((dark if name=='receiver_body' else donor)['faces'])})
    result[A/'models/block/wireless_receiver.json']=receiver
    for locale,label in [('zh_cn','USB 无线接收器配对工具'),('en_us','USB Receiver Pairing Tool')]:
        lang=raw(A/f'lang/{locale}.json');lang['item.piq_computer.connector']=label
        result[A/f'lang/{locale}.json']=lang
    return result
if __name__=='__main__':
    files=derive()
    for p,v in files.items():
        if '--check' in sys.argv:assert raw(p)==v,p
        else:write(p,v)
    print('PC6 resource files:',len(files))
