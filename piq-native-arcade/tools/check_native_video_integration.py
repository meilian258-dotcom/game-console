"""Real pure Java video geometry + local mapped NeoForge/Minecraft method signature audit."""
from pathlib import Path
import argparse,json,subprocess,tempfile
from check_native_cabinet import ROOT,FC,JDK,sha

def inspect():
    cache=Path('C:/Users/13498/.gradle/caches/modules-2/files-2.1')
    jars=[next(cache.glob(p)) for p in ('org.junit.jupiter/junit-jupiter-api/5.13.4/*/*.jar','org.junit.platform/junit-platform-commons/1.13.4/*/*.jar','org.opentest4j/opentest4j/1.3.0/*/*.jar','org.apiguardian/apiguardian-api/1.1.2/*/*.jar')]
    sources=[FC/'src/main/java/cn/piq/fcarcade/layout'/n for n in ('RocketArcadeGeometry.java','DualCabinetGeometry.java','ScreenAspectFit.java')]
    sources += [ROOT/'src/main/java/cn/piq/nativearcade/layout'/n for n in ('NativeCabinetLayout.java','NativeVideoPresentation.java')]
    sources += [ROOT/'src/test/java/cn/piq/nativearcade/layout/NativeVideoIntegrationTest.java']
    with tempfile.TemporaryDirectory(prefix='native-video-qa-') as tmp:
        cp=';'.join(map(str,jars));p=subprocess.run([str(JDK/'javac.exe'),'-encoding','UTF-8','-cp',cp,'-d',tmp,*map(str,sources)],capture_output=True,text=True,timeout=30)
        if p.returncode:raise RuntimeError(p.stderr)
        p=subprocess.run([str(JDK/'java.exe'),'-cp',tmp+';'+cp,'cn.piq.nativearcade.layout.NativeVideoIntegrationTest'],capture_output=True,text=True,timeout=30)
        if p.returncode:raise RuntimeError(p.stdout+p.stderr)
    checks=[{'name':'six actual Java UV/cabinet/normal-offset integration tests','ok':p.stdout.strip()=='NATIVE_VIDEO_INTEGRATION_TESTS=6 PASS'}]
    mc=FC/'build/moddev/artifacts/neoforge-21.1.236-merged.jar';neo=FC/'build/moddev/artifacts/neoforge-21.1.236.jar';api=[]
    methods={
      'net.minecraft.client.Minecraft':['getSingleplayerServer()','isWindowActive()','setScreen(net.minecraft.client.gui.screens.Screen)'],
      'net.minecraft.client.server.IntegratedServer':['isPublished()'],
      'net.minecraft.client.renderer.texture.DynamicTexture':['DynamicTexture(int, int, boolean)','getPixels()','upload()'],
      'com.mojang.blaze3d.platform.NativeImage':['setPixelRGBA(int, int, int)'],
      'net.minecraft.client.renderer.texture.TextureManager':['release(net.minecraft.resources.ResourceLocation)','register(net.minecraft.resources.ResourceLocation, net.minecraft.client.renderer.texture.AbstractTexture)'],
      'net.minecraft.client.Options':['getSoundSourceVolume(net.minecraft.sounds.SoundSource)'],
      'net.minecraft.client.gui.screens.Screen':['keyPressed(int, int, int)','removed()','onClose()'],
      'net.neoforged.neoforge.event.GameShuttingDownEvent':['GameShuttingDownEvent()'],
    }
    for cls,tokens in methods.items():
        result=subprocess.run([str(JDK/'javap.exe'),'-classpath',str(mc)+';'+str(neo),cls],capture_output=True,text=True,timeout=20)
        for token in tokens:checks.append({'name':cls+' '+token,'ok':result.returncode==0 and token in result.stdout})
        api.append({'class':cls,'signature_sha256':sha(result.stdout.encode())})
    return {'ok':all(c['ok'] for c in checks),'checks':checks,'java':p.stdout.strip(),'api':api,'source_sha256':{str(s.relative_to(ROOT)) if s.is_relative_to(ROOT) else str(s):sha(s.read_bytes()) for s in sources},
      'limits':'Geometry and mapped API check only; does not prove a Minecraft frame or audio device. Process shutdown/permission/runtime integration is reviewed separately.'}

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--write',action='store_true');a=p.parse_args();r=inspect();print(json.dumps(r,ensure_ascii=True,indent=2))
    if not r['ok']:raise SystemExit(1)
    if a.write:
        from import_subor_hardware import write_new
        dest=ROOT.parent/'制作Mod/03-街机模拟/PIQ原生街机/原生机柜模块-alpha1/客户端接口QA-v1/native-video-integration.json'
        write_new({dest:(json.dumps(r,ensure_ascii=False,indent=2)+'\n').encode()})
