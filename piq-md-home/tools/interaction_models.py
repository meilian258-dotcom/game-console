"""Mechanical JSON update: FC's hand rig + a dynamic cover-capable cartridge. No raster edits."""
from pathlib import Path
import json


def prepare(assets):
    items = assets / 'models/item'
    card = json.loads((items / 'md_cartridge.json').read_text(encoding='utf-8'))
    if 'elements' in card:
        (items / 'md_cartridge_mesh.json').write_text(json.dumps(card, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
        stub = {'parent': 'builtin/entity', 'gui_light': 'front', 'textures': {'particle': 'piq_md_home:item/md2_cartridge_set'}, 'display': card['display']}
        (items / 'md_cartridge.json').write_text(json.dumps(stub, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    pad = json.loads((items / 'md_controller.json').read_text(encoding='utf-8'))
    root = Path(__file__).resolve().parents[2]
    fc = json.loads((root / 'piq-fc-arcade/src/main/resources/assets/piq_fc_arcade/models/item/fc_controller.json').read_text(encoding='utf-8'))
    for hand in ('firstperson_righthand', 'firstperson_lefthand', 'thirdperson_righthand', 'thirdperson_lefthand'):
        pose = json.loads(json.dumps(fc['display'][hand]))
        # FC face is +Z; the supplied MD controller buttons are +Y. Align only the item display.
        pose['rotation'][0] += 90
        pose['scale'] = [round(v * 2.8, 8) for v in pose['scale']]
        pad['display'][hand] = pose
    (items / 'md_controller.json').write_text(json.dumps(pad, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')


if __name__ == '__main__':
    prepare(Path(__file__).resolve().parents[1] / 'src/main/resources/assets/piq_md_home')
