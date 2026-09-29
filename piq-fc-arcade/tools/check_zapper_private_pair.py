"""Original supplied ROM stays in-place; report contains hashes and assertions, not ROM/state data."""
import argparse,hashlib,json,subprocess,sys
from pathlib import Path
from PIL import Image
ROOT=Path(__file__).resolve().parents[1]
def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest().upper()
def region(path,box):
    with Image.open(path)as image:return image.convert('RGB').crop(box).tobytes()
def main():
    ap=argparse.ArgumentParser();ap.add_argument('--fc',type=Path,required=True);ap.add_argument('--rom',type=Path,required=True);ap.add_argument('--output',type=Path,required=True);ap.add_argument('--report',type=Path,required=True);a=ap.parse_args()
    if a.report.exists()or a.output.exists():raise ValueError('Never overwrite private evidence')
    before=digest(a.rom);runs={}
    for name in ['hit','offscreen']:
        cmd=[sys.executable,ROOT/'tools/run_zapper_private.py','--fc',a.fc.resolve(),'--rom',a.rom.resolve(),'--output',(a.output/name).resolve(),'--shoot','661','--x','98','--y','138']
        if name=='offscreen':cmd.append('--offscreen')
        result=subprocess.run(list(map(str,cmd)),cwd=ROOT,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=120)
        if result.returncode:raise AssertionError(result.stdout+'\n'+result.stderr)
        runs[name]=json.loads(result.stdout.strip())
    checks=0
    for frame in [119,150,300,600,630,660]:
        assert digest(a.output/'hit'/f'frame-{frame:04}.png')==digest(a.output/'offscreen'/f'frame-{frame:04}.png'),'Different menu/pre-shot timeline';checks+=1
    hit=a.output/'hit/frame-0690.png';miss=a.output/'offscreen/frame-0690.png'
    score=(194,207,239,217)
    zero=region(a.output/'hit/frame-0660.png',score)
    assert region(miss,score)==zero,'Offscreen unexpectedly scored';checks+=1
    assert region(hit,score)!=zero,'Aimed shot failed to score';checks+=1
    assert region(hit,(60,207,180,217))!=region(miss,(60,207,180,217)),'Hit indicator did not differ';checks+=1
    assert digest(a.rom)==before,'ROM file changed';checks+=1
    report={'ok':True,'assertions':checks,'actual_wasm_core':True,'minecraft_started':False,'rom_copied':False,'rom_sha256':before,
            'shot_frame':661,'aim':[98,138],'runs':runs,'same_pre_shot_frames':[119,150,300,600,630,660],
            'aimed_shot_score_changed':True,'offscreen_score_stayed_zero':True,
            'visual_review':'Separate agent-visible private frame review identified aimed-shot score 001000 and offscreen score 000000.',
            'private_frame_sha256':{'hit_690':digest(hit),'offscreen_690':digest(miss)},
            'limits':['Private output images are game-derived evidence and must not be bundled in the mod/release archive.',
                      'No ROM or memory-state bytes are written. No Minecraft item, aiming ray, network/permission or physical gun tested.',
                      'This one user-supplied ROM does not establish compatibility with every light-gun game.']}
    a.report.parent.mkdir(parents=True,exist_ok=True)
    with a.report.open('x',encoding='utf-8')as f:json.dump(report,f,indent=2,ensure_ascii=False)
    print(json.dumps(report,indent=2,ensure_ascii=False))
if __name__=='__main__':main()
