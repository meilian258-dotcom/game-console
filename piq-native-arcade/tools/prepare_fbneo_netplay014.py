"""Stage reviewed PGM snapshot-cycle fix. Separately licensed; no downloads or user content."""
from pathlib import Path
import argparse,hashlib,json,shutil
ROOT=Path(__file__).resolve().parents[1];V=ROOT/'design/fbneo-study/pgm-cycle75'
CORE_SHA='73579C4C50D1F16F5D52A1E5BF4D81106C40962BC284E83E4A96DC7BB68FD424'
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest().upper()
def main():
    p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    core=V/'fbneo_libretro.dll';lic=ROOT/'design/fbneo-study/vendor/license.txt'
    assert core.stat().st_size==61378560 and sha(core)==CORE_SHA
    assert sha(lic)=='BB2369F1B75F42242968A78191B47EE90F85682224D3AD1EF63044E244B3E202'
    root=a.output.resolve();assert root.is_relative_to((ROOT/'build').resolve())
    dest=root/'native-runtime/win-x64-fbneo-pgm-v2';dest.mkdir(parents=True,exist_ok=True)
    notice=root/'META-INF/piq-native/fbneo';notice.mkdir(parents=True,exist_ok=True)
    shutil.copy2(core,dest/core.name);shutil.copy2(lic,notice/'LICENSE.txt')
    for name in ('pgm-cycle-carry.patch','build.json'):shutil.copy2(V/name,notice/name)
    receipt=dict(core='FBNeo a251c76 + PIQ PGM cycle-carry snapshot fix',sha256=CORE_SHA,bytes=core.stat().st_size,
        source='https://github.com/libretro/FBNeo/tree/a251c76229f1637e433b93e29845039752771b6d',
        modified=True,reproducibleBuildVerified=False,sourceBundle='FBNeo-a251c76-pgm-cycle75-source.zip',
        patch='pgmScan: include nCyclesDone in ACB_DRIVER_DATA; no change to CRC policy or game allowlist',
        notice='Separate FBNeo non-commercial license, NOT GPL relicensing. Local test candidate only. Distribute complete patched source bundle alongside this artifact. No ROM/BIOS.')
    (notice/'provenance.json').write_text(json.dumps(receipt,indent=2)+'\n',encoding='utf8')
    assert sha(dest/core.name)==CORE_SHA
if __name__=='__main__':main()
