"""Freeze the exact alpha9 appearance review without modifying prior reviews."""
import json
from pathlib import Path
from import_subor_hardware import sha, encoded, write_new

ROOT=Path(__file__).resolve().parents[1]
PREVIOUS=ROOT/'tools/home-fc-alpha8-final-reviewed-assets.json'
EXPECTED_PREVIOUS='4CE2ABC1732C3909D75341F852D20332DDE29A27EB0E0961F50AB8553EC7BFE7'
MODEL='assets/piq_fc_arcade/models/block/dual_arcade_body.json'
NEW=[
 'assets/piq_fc_arcade/models/block/home_fc_board_0.json',
 'assets/piq_fc_arcade/models/block/home_fc_board_1.json',
 'assets/piq_fc_arcade/models/block/home_fc_board_2.json',
 'assets/piq_fc_arcade/models/block/home_fc_cartridge_shell.json',
 'assets/piq_fc_arcade/models/item/fc_cartridge_board.json',
 'assets/piq_fc_arcade/models/item/fc_cartridge_shell.json',
 'assets/piq_fc_arcade/models/block/home_wide_lcd_tv.json',
 'assets/piq_fc_arcade/models/item/wide_lcd_tv.json',
 'assets/piq_fc_arcade/blockstates/wide_lcd_tv.json',
 'assets/piq_fc_arcade/blockstates/wide_lcd_tv_part.json']

def build():
    if sha(PREVIOUS.read_bytes())!=EXPECTED_PREVIOUS:raise ValueError('Frozen alpha8 changed')
    previous=json.loads(PREVIOUS.read_bytes())['assets']; assets={}
    if len(previous)!=47:raise ValueError('Expected 47 prior paths')
    for path,old in previous.items():
        current=sha((ROOT/'src/main/resources'/path).read_bytes())
        if path!=MODEL and current!=old:raise ValueError('Unapproved old asset change: '+path)
        assets[path]=current
    for path in NEW:
        if path in assets:raise ValueError('Expected a new path')
        assets[path]=sha((ROOT/'src/main/resources'/path).read_bytes())
    if len(assets)!=57:raise ValueError('Expected 57 paths')
    return {'schema':1,'version':'0.31.0-alpha.9','protocol':27,
      'review':{'previous_manifest':PREVIOUS.name,'previous_manifest_sha256':EXPECTED_PREVIOUS,
        'unchanged_alpha8_assets':46,'changed_existing_assets':[MODEL], 'new_appearance_assets':NEW,
        'supersedes_draft_manifest':'home-fc-alpha9-final-reviewed-assets.json',
        'supersedes_draft_sha256':'DAE926EBEC54220DCF5BF2C4A6276E29C7271DF1297CC27871E3E1B8CA066748',
        'verification':'Only dual body updated: 37.6 world units, raw Y offset -5.6 with BER compensation; screen and control vertices unchanged. New hollow detached shell preserves original cover UV; three original code-native PCBs use vanilla textures, no new PNG. New 1.5-wide 16:9 LCD keeps old LCD/CRT assets. Controller motion is runtime geometry only. Protocol27 adds parts/wideLCD. V2 freezes the LCD item GUI centering correction after pixel-bound verification; all other 56 draft assets are unchanged.'},'assets':assets}

if __name__=='__main__':
    draft=ROOT/'tools/home-fc-alpha9-final-reviewed-assets.json'
    if sha(draft.read_bytes())!='DAE926EBEC54220DCF5BF2C4A6276E29C7271DF1297CC27871E3E1B8CA066748':raise ValueError('Alpha9 draft review changed')
    result=build()
    for name,old in json.loads(draft.read_bytes())['assets'].items():
        if name!='assets/piq_fc_arcade/models/item/wide_lcd_tv.json' and result['assets'][name]!=old:raise ValueError('Unexpected change since draft: '+name)
    data=encoded(result);out=ROOT/'tools/home-fc-alpha9-final-reviewed-assets-v2.json';write_new({out:data})
    print(json.dumps({'path':str(out),'sha256':sha(data),'count':57},ensure_ascii=True))
