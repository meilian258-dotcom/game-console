"""Fetch only official FBNeo build/source into this new study; never installs a core."""
import hashlib,json,urllib.request,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def download(url,path,limit):
    if path.exists():raise FileExistsError(path)
    request=urllib.request.Request(url,headers={'User-Agent':'PIQ-local-FBNeo-study'})
    with urllib.request.urlopen(request,timeout=60)as response,path.open('xb')as out:
        total=0
        while data:=response.read(1024*1024):
            total+=len(data)
            if total>limit:raise ValueError('Download bounded limit')
            out.write(data)
        return {'url':url,'resolved_url':response.url,'last_modified':response.headers.get('Last-Modified'),'bytes':total,'sha256':hashlib.sha256(path.read_bytes()).hexdigest().upper()}
def main():
    vendor=ROOT/'vendor';vendor.mkdir(parents=True,exist_ok=True)
    base='https://buildbot.libretro.com/nightly/windows/x86_64/latest/fbneo_libretro.dll.zip'
    binary=download(base,vendor/'fbneo_libretro.dll.zip',40*1024*1024)
    with zipfile.ZipFile(vendor/'fbneo_libretro.dll.zip')as archive:
        assert archive.namelist()==['fbneo_libretro.dll']
        entry=archive.getinfo('fbneo_libretro.dll');assert entry.file_size<100*1024*1024
        with(vendor/'fbneo_libretro.dll').open('xb')as f:f.write(archive.read(entry))
    request=urllib.request.Request('https://api.github.com/repos/libretro/FBNeo/commits/master',headers={'User-Agent':'PIQ-local-FBNeo-study'})
    with urllib.request.urlopen(request,timeout=30)as r:commit=json.load(r)
    sha=commit['sha'];assert len(sha)==40
    source=download('https://codeload.github.com/libretro/FBNeo/zip/'+sha,vendor/('FBNeo-'+sha+'.zip'),120*1024*1024)
    with zipfile.ZipFile(vendor/('FBNeo-'+sha+'.zip'))as archive:
        license=archive.read('FBNeo-'+sha+'/src/license.txt')
        with(vendor/'license.txt').open('xb')as f:f.write(license)
    report={'schema':'piq-fbneo-official-study-1','official_binary':binary,'dll':{'path':str(vendor/'fbneo_libretro.dll'),'bytes':(vendor/'fbneo_libretro.dll').stat().st_size,'sha256':hashlib.sha256((vendor/'fbneo_libretro.dll').read_bytes()).hexdigest().upper()},'fixed_source':source,'source_commit':sha,'commit_time':commit['commit']['committer']['date'],'license_sha256':hashlib.sha256(license).hexdigest().upper(),'binary_to_source_exact_mapping_verified':False,'production_modified':False,'installed':False,'purpose':'Local isolated determinism research only; non-commercial licensing requires separate distribution review.'}
    with(ROOT/'official-artifacts.json').open('x',encoding='utf-8')as f:json.dump(report,f,ensure_ascii=False,indent=2)
    print(json.dumps(report,ensure_ascii=True))
if __name__=='__main__':main()
