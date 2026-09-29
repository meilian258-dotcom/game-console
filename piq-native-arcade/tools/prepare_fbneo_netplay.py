"""Stage a fixed, separately licensed, unmodified FBNeo core for local evaluation.
No downloads, ROMs, BIOS, arbitrary libraries, installers or live instance writes.
"""
from pathlib import Path
import argparse, hashlib, json, shutil
ROOT=Path(__file__).resolve().parents[1]
VENDOR=ROOT/'design/fbneo-study/vendor'
CORE_SHA='52343F65453C0E1FC06B3AA818B48CA4FED18361F7F336A684E9E3CFFA5F5F3C'
LICENSE_SHA='BB2369F1B75F42242968A78191B47EE90F85682224D3AD1EF63044E244B3E202'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest().upper()
def main():
 p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True);a=p.parse_args()
 core=VENDOR/'fbneo_libretro.dll';license=VENDOR/'license.txt'
 assert core.stat().st_size==61913629 and sha(core)==CORE_SHA
 assert sha(license)==LICENSE_SHA
 root=a.output.resolve();assert root.is_relative_to((ROOT/'build').resolve())
 dest=root/'native-runtime/win-x64-fbneo-v1';dest.mkdir(parents=True,exist_ok=True)
 notices=root/'META-INF/piq-native/fbneo';notices.mkdir(parents=True,exist_ok=True)
 shutil.copy2(core,dest/core.name);shutil.copy2(license,notices/'LICENSE.txt')
 receipt=dict(core='FinalBurn Neo v1.0.0.03 260904 GITa251c76',sha256=CORE_SHA,bytes=61913629,
  upstream='https://buildbot.libretro.com/nightly/windows/x86_64/latest/fbneo_libretro.dll.zip',
  source='https://github.com/libretro/FBNeo/tree/a251c76229f1637e433b93e29845039752771b6d',
  sourceArchiveSha256='55CC0F5BF305D8953FA0A20C3598164D39EFC03EF3740C7B01E7EBF143CD4D7A',
  modified=False,reproducibleBuildVerified=False,licenseSha256=LICENSE_SHA,
  notice='Separate FBNeo non-commercial license, NOT GPL relicensing. Local evaluation candidate; no public release approval. No ROM or BIOS included.')
 (notices/'provenance.json').write_text(json.dumps(receipt,indent=2)+'\n',encoding='utf8')
 assert sha(dest/core.name)==CORE_SHA and sha(notices/'LICENSE.txt')==LICENSE_SHA
if __name__=='__main__':main()
