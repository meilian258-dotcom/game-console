"""Authorized local real-ROM determinism study; only owned children and temporary copies.

No delivery ROM/state contents, no production edits, no existing process interaction.
"""
import argparse,concurrent.futures,hashlib,json,os,shutil,subprocess,tempfile,time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
JDK=Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin')
JNA=Path('C:/Users/13498/.gradle/caches/modules-2/files-2.1/net.java.dev.jna/jna/5.14.0/67bf3eaea4f0718cb376a181a629e5f88fa1c9dd/jna-5.14.0.jar')
DLL=ROOT.parent/'piq-native-arcade-poc/vendor/mame_libretro.dll'
HELPER=ROOT.parent/'piq-fc-arcade/build/review-controls26-v2/piq-native-arcade/runtime/piq-native-helper.jar'
LOCKS={DLL:'6172A988AB67FE68F4177A6FC8FBB82619EB2044C330930F0F572F7B1EDC2301',JNA:'34ED1E1F27FA896BCA50DBC4E99CF3732967CEC387A7A0D5E3486C09673FE8C6',HELPER:'20F6F3028D76DAEB01212D1808BE90E35BFB5429D1E06153B7D8B32DD73E943C'}
def sha(path):
 h=hashlib.sha256()
 with Path(path).open('rb')as s:
  for data in iter(lambda:s.read(1024*1024),b''):h.update(data)
 return h.hexdigest().upper()
def identity(path):return {'sha256':sha(path),'bytes':path.stat().st_size,'mtime_ns':path.stat().st_mtime_ns}
def compare(a,b):
 if len(a)!=len(b):return {'equal':False,'different_frames':max(len(a),len(b)),'reason':'length'}
 diffs=[i for i,(x,y) in enumerate(zip(a,b)) if x!=y]
 first=None
 if diffs:
  i=diffs[0];x=a[i].split(',');y=b[i].split(',');first={'frame':int(x[0]),'header_equal':x[:6]==y[:6],'video_equal':x[6]==y[6],'audio_equal':x[7]==y[7]}
 fields={'header':[],'video':[],'audio':[]}
 for i,(left,right) in enumerate(zip(a,b)):
  x=left.split(',');y=right.split(',')
  if x[:6]!=y[:6]:fields['header'].append(i)
  if x[6]!=y[6]:fields['video'].append(i)
  if x[7]!=y[7]:fields['audio'].append(i)
 return {'equal':not diffs,'different_frames':len(diffs),'first_difference':first,'fields':{kind:{'mismatches':len(rows),'first_index':rows[0]if rows else None,'last_index':rows[-1]if rows else None,'last_60_mismatches':sum(i>=len(a)-60 for i in rows)}for kind,rows in fields.items()}}
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--rom',required=True,type=Path);p.add_argument('--bios',type=Path);p.add_argument('--report',required=True,type=Path);p.add_argument('--snapshot-frame',type=int,default=2200);p.add_argument('--replay-frames',type=int,default=600);a=p.parse_args()
 if a.report.exists():raise FileExistsError(a.report)
 rom=a.rom.resolve(strict=True);files=[rom]+([a.bios.resolve(strict=True)]if a.bios else[])
 if not rom.name.isascii()or not rom.name.endswith('.zip'):raise ValueError('Exact ZIP driver required')
 for file in files:
  if not file.is_file()or file.is_symlink()or not 22<=file.stat().st_size<=64*1024*1024:raise ValueError('ROM/BIOS bounds')
 before={str(x):identity(x)for x in files};runtime={str(x):identity(x)for x in LOCKS}
 for file,digest in LOCKS.items():assert runtime[str(file)]['sha256']==digest
 result={'schema':'piq-native-determinism-study-1','ok':False,'sync_qualified':False,'game':rom.stem,'inputs':before,'runtime':runtime,'production_modified':False,'production_compiled':False,'minecraft_started':False,'commercial_rom_loaded':True,'source_roms_read_only':True,'core_profile':'Existing production Engine callbacks/options; no thread/throttle/config/autosave changes','limitations':['Authorized user ROM used only in owned temporary copies; no ROM or snapshot contents in this report.','One passed finite trace is not all-game compatibility or proof of every future execution.','Dedicated QA JVM halts only after native unload/deinit; natural upstream thread exit is not this test.']}
 started=time.time()
 with tempfile.TemporaryDirectory(prefix='piq-mame-determinism-')as directory:
  temp=Path(directory);classes=temp/'classes';classes.mkdir();cp=os.pathsep.join(map(str,[classes,HELPER,JNA]));probe=ROOT/'tools/qa/NativeDeterminismProbe.java'
  built=subprocess.run(list(map(str,[JDK/'javac.exe','-encoding','UTF-8','--release','21','-cp',cp,'-d',classes,probe])),capture_output=True,text=True,timeout=40)
  if built.returncode:raise RuntimeError(built.stdout+built.stderr)
  jobs=[]
  for name in ('a','b'):
   work=temp/name;work.mkdir()
   for source in files:shutil.copyfile(source,work/source.name);assert sha(work/source.name)==before[str(source)]['sha256']
   jobs.append((name,work))
  def run(job):
   name,work=job;peer=temp/'a/output/snapshot.bin'if name=='b'else'-'
   command=list(map(str,[JDK/'java.exe','-Xmx256m','-Djna.nosys=true','-cp',cp,'cn.piq.nativearcade.bridge.NativeDeterminismProbe',DLL,work/rom.name,HELPER,work/'output',a.snapshot_frame,a.replay_frames,peer]))
   with(work/'stdout.log').open('xb')as stdout,(work/'stderr.log').open('xb')as stderr:
    child=subprocess.Popen(command,cwd=work,stdout=stdout,stderr=stderr,creationflags=subprocess.CREATE_NO_WINDOW|subprocess.BELOW_NORMAL_PRIORITY_CLASS)
    try:code=child.wait(timeout=180)
    except subprocess.TimeoutExpired:child.kill();child.wait(timeout=10);code='timeout'
   tail=(work/'stderr.log').read_text(encoding='utf-8',errors='replace')[-12000:]
   out={'exit_code':code,'stderr_tail':tail,'native_teardown_completed':'NATIVE_TEARDOWN_COMPLETED'in tail}
   if (work/'output/result.json').is_file():out['result']=json.loads((work/'output/result.json').read_text())
   return name,out
  with concurrent.futures.ThreadPoolExecutor(max_workers=2)as pool:result['children']=dict(pool.map(run,jobs))
  if all(c['exit_code']==0 and c['native_teardown_completed']and c.get('result',{}).get('ok')for c in result['children'].values()):
   def lines(w,n):return(temp/w/'output'/n).read_text().splitlines()
   av=lines('a','baseline.csv');bv=lines('b','baseline.csv');ar=lines('a','restored.csv');br=lines('b','restored.csv');cross=lines('b','cross.csv')
   result['comparisons']={'independent_start':compare(av,bv),'a_local_restore':compare(av[a.snapshot_frame:],ar),'b_local_restore':compare(bv[a.snapshot_frame:],br),'a_snapshot_on_b':compare(av[a.snapshot_frame:],cross)}
   ra=result['children']['a']['result'];rb=result['children']['b']['result'];result['state_hashes']={'independent_snapshot_equal':ra['snapshot_sha256']==rb['snapshot_sha256'],'a_immediate_repeat_equal':ra['snapshot_sha256']==ra['immediate_repeat_save_sha256'],'b_immediate_repeat_equal':rb['snapshot_sha256']==rb['immediate_repeat_save_sha256'],'a_load_immediate_save_equal':ra['snapshot_sha256']==ra['restored_before_step_sha256'],'b_load_immediate_save_equal':rb['snapshot_sha256']==rb['restored_before_step_sha256'],'a_restored_end_equal':ra['ending_state_sha256']==ra['restored_ending_state_sha256'],'b_restored_end_equal':rb['ending_state_sha256']==rb['restored_ending_state_sha256'],'cross_end_equal':ra['ending_state_sha256']==rb['cross_ending_state_sha256']}
   initial_a=(temp/'a/output/snapshot.bin').read_bytes();initial_b=(temp/'b/output/snapshot.bin').read_bytes();offsets=[i for i,(x,y)in enumerate(zip(initial_a,initial_b))if x!=y]
   result['independent_snapshot_difference']={'bytes_a':len(initial_a),'bytes_b':len(initial_b),'different_bytes':len(offsets),'first_offsets_only':offsets[:24]}
   result['options_equal']=(temp/'a/output/options.txt').read_bytes()==(temp/'b/output/options.txt').read_bytes();result['options_sha256']=sha(temp/'a/output/options.txt')
   result['ok']=True;result['sync_qualified']=all(v['equal']for v in result['comparisons'].values())and all(result['state_hashes'].values())and result['options_equal']
  result['source_roms_unchanged']=before=={str(x):identity(x)for x in files}
  if not result['source_roms_unchanged']:raise AssertionError('Source changed')
 result['seconds']=round(time.time()-started,3)
 a.report.parent.mkdir(parents=True,exist_ok=True)
 with a.report.open('x',encoding='utf-8')as output:json.dump(result,output,ensure_ascii=False,indent=2);output.write('\n')
 print(json.dumps({'report':str(a.report),'ok':result['ok'],'sync_qualified':result['sync_qualified'],'comparisons':result.get('comparisons'),'state_hashes':result.get('state_hashes'),'seconds':result['seconds']},ensure_ascii=True))
if __name__=='__main__':main()
