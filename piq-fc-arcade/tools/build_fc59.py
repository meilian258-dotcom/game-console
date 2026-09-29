"""FC59 local candidate freezer; SFC36 is rebuilt for ABI regression, not changed."""
from pathlib import Path
import hashlib, importlib.util
ROOT=Path(__file__).resolve().parents[2]
PROFILE=ROOT/'piq-fc-arcade/tools/build_admin58.py'
spec=importlib.util.spec_from_file_location('fc59_review_helpers',PROFILE)
p=importlib.util.module_from_spec(spec);spec.loader.exec_module(p)
g=p.g
g.b.VERSIONS.update(piq_fc_arcade='0.31.0-alpha.59',piq_sfc_home='0.1.0-alpha.36')
g.b.TEST_LIMITS={'piq-fc-arcade':(2136,8),'piq-sfc-home':(468,0)}
g.OUT=ROOT/'piq-fc-arcade/build/review-fc59-v1'
g.PLAN=ROOT/'outputs/fc59/reviewed-changes.json'
g.NAMES={'fc':'game_console-0.31.0-alpha.59.jar','sfc':'game_console_sfc-0.1.0-alpha.36.jar'}
base=ROOT/'piq-fc-arcade/build/review-admin58-v1'
g.BASE={'fc':(base/'game_console-0.31.0-alpha.58.jar','B9728B6052CD7A6EACF7587629EE439F6F054455EADEF749F978111AA1AE2A0E'),
 'sfc':(base/'game_console_sfc-0.1.0-alpha.36.jar','3F1C20CB902E5A7426B92F39FA20F35468E7B19F99F3482F478DABBD0DDAF51D')}
g.BASE_WITNESSES={k:(base/'build-witness.json','4F8CFF54CD7EB0EB932307137F7A04098092C08F8528943FCC98D7A7F1C09B9C') for k in g.BASE}
g.SOURCE_BEFORE=ROOT/'outputs/admin58/source-witness-v2.json'
g.SOURCE_BEFORE_SHA='912842AE0389AB58C14B38B739E46A5EA17F39238603FAA3147B45D25B087BA7'
g.COMPANIONS=[Path(__file__).resolve(),PROFILE,ROOT/'outputs/fc59/run_build.py',
 ROOT/'piq-fc-arcade/design/FC59-紧凑街机与航空学-测试说明.md',
 ROOT/'piq-fc-arcade/tools/rebuild_subor_keycaps59.py',
 ROOT/'piq-fc-arcade/tools/test_rebuild_subor_keycaps59.py']
original_overlay=g.overlay
def overlay(kind,old,compiled,plan):
    result,preserved=original_overlay(kind,old,compiled,plan)
    if kind=='sfc':g.b.require(result==old,'FC-only scope: SFC36 must stay byte-for-byte identical')
    return result,preserved
g.overlay=overlay
if __name__=='__main__':g.main()
