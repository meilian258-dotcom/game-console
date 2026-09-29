"""Generate only new furniture vanilla block particles and BEWLR display transforms."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'src/main/resources/assets/piq_fc_arcade'
WOODS = ('oak', 'spruce', 'birch', 'jungle', 'acacia', 'dark_oak', 'mangrove', 'cherry', 'bamboo', 'crimson', 'warped')


def make():
    outputs = {}
    for wood in WOODS:
        suffix = 'stem' if wood in ('crimson', 'warped') else 'block' if wood == 'bamboo' else 'log'
        particle = f'minecraft:block/stripped_{wood}_{suffix}'
        for kind in ('bench', 'stool'):
            name = f'furniture/{wood}_{kind}'
            outputs[f'blockstates/{name}.json'] = {'variants': {'': {'model': f'piq_fc_arcade:block/{name}'}}}
            outputs[f'models/block/{name}.json'] = {'textures': {'particle': particle}, 'elements': []}
            outputs[f'models/item/{name}.json'] = {
                'parent': 'builtin/entity', 'gui_light': 'side', 'textures': {'particle': particle},
                'display': {
                    'gui': {'rotation': [25, 135, 0], 'scale': [.85, .85, .85]},
                    'ground': {'translation': [0, 2, 0], 'scale': [.45, .45, .45]},
                    'fixed': {'rotation': [0, 180, 0], 'scale': [.7, .7, .7]},
                    'thirdperson_righthand': {'rotation': [75, 45, 0], 'translation': [0, 1.5, 0], 'scale': [.55, .55, .55]},
                    'thirdperson_lefthand': {'rotation': [75, 45, 0], 'translation': [0, 1.5, 0], 'scale': [.55, .55, .55]},
                    'firstperson_righthand': {'rotation': [0, 135, 0], 'translation': [1, 1, 0], 'scale': [.7, .7, .7]},
                    'firstperson_lefthand': {'rotation': [0, 135, 0], 'translation': [1, 1, 0], 'scale': [.7, .7, .7]},
                },
            }
    return outputs


if __name__ == '__main__':
    for relative, data in make().items():
        target = ASSETS / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(data, separators=(',', ':')) + '\n', encoding='utf-8')
    print('Generated 66 furniture-only blockstate/particle/item presentation resources.')
