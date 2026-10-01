"""Remove the standalone front branding decal from the editable Java model.

This is a model edit, not a bitmap edit. Original source/atlas remain unchanged.
Run after the historical importer when regenerating production resources.
"""
import argparse
import json
from pathlib import Path

DECAL = '镜片上方Nintendo标记'
BODY = Path(__file__).resolve().parents[1] / 'src/main/resources/assets/piq_gba/models/item/handheld/body.json'


def remove_front_decal(model):
    elements = model['elements']
    decals = [element for element in elements if element.get('name') == DECAL]
    if len(decals) > 1:
        raise ValueError('Ambiguous front branding decal')
    if not decals:
        return False
    decal = decals[0]
    if (decal['from'] != [7.49, 1.288, 6.21]
            or decal['to'] != [8.51, 1.297, 6.48]
            or set(decal['faces']) != {'up'} or 'rotation' in decal):
        raise ValueError('Unexpected front decal geometry; inspect the new model first')
    model['elements'] = [element for element in elements if element is not decal]
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Verify production already has no front decal')
    args = parser.parse_args()
    model = json.loads(BODY.read_text(encoding='utf-8'))
    changed = remove_front_decal(model)
    if args.check and changed:
        raise SystemExit('Production front branding decal has not been removed')
    if changed:
        BODY.write_text(json.dumps(model, ensure_ascii=False, separators=(',', ':')) + '\n', encoding='utf-8', newline='\n')
    print(json.dumps({'ok': True, 'changed': changed, 'bodyElements': len(model['elements']), 'textureChanged': False}))


if __name__ == '__main__':
    main()
