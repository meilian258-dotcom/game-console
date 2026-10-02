package cn.piq.mdhome.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MdControllerRendererTest {
    @BeforeAll static void bootstrap() {
        if(net.neoforged.fml.loading.LoadingModList.get()==null)
            net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),java.util.Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
    }
    @Test void onlyFourHandContextsAnimate() {
        int count=0;
        for(var context:ItemDisplayContext.values())if(MdControllerRenderer.held(context))count++;
        assertEquals(4,count);
        for(var context:new ItemDisplayContext[]{ItemDisplayContext.GUI,ItemDisplayContext.GROUND,ItemDisplayContext.FIXED,ItemDisplayContext.HEAD,ItemDisplayContext.NONE})
            assertFalse(MdControllerRenderer.held(context));
        assertEquals(11,MdControllerRenderer.MODELS.size());
        assertEquals(11,MdControllerRenderer.MODELS.stream().distinct().count());
    }
    @Test void actualPoseStackPushesEachDirectionIntoFaceAndLeavesPivotFixed() {
        double x=MdControllerGeometry.DPAD_X,y=MdControllerGeometry.DPAD_Y,z=MdControllerGeometry.DPAD_Z;
        double[][] samples={{1,0,0,0,x,y,z+.5},{0,1,0,0,x,y,z-.5},{0,0,1,0,x+.5,y,z},{0,0,0,1,x-.5,y,z}};
        for(double[] s:samples) {
            var pose=new PoseStack();MdControllerRenderer.apply(1,MdControllerGeometry.sample(1,0,s[0],s[1],s[2],s[3]),pose);
            var pivot=pose.last().pose().transformPosition(new Vector3f((float)x/16,(float)y/16,(float)z/16));
            assertEquals(x/16,pivot.x,1e-6);assertEquals(y/16,pivot.y,1e-6);assertEquals(z/16,pivot.z,1e-6);
            var pressed=pose.last().pose().transformPosition(new Vector3f((float)s[4]/16,(float)s[5]/16,(float)s[6]/16));
            assertTrue(pressed.y<y/16-.002,"actual endpoint must sink, not rise");
            var opposite=pose.last().pose().transformPosition(new Vector3f((float)(2*x-s[4])/16,(float)y/16,(float)(2*z-s[6])/16));
            assertTrue(opposite.y>y/16+.002);
        }
    }
    @Test void capTransformIsLocalTranslationAndDoesNotLeakAfterPop() {
        var pose=new PoseStack();pose.translate(2,3,4);var original=new org.joml.Matrix4f(pose.last().pose());
        for(int part=2;part<MdControllerGeometry.PARTS;part++) {
            pose.pushPose();MdControllerRenderer.apply(part,MdControllerGeometry.sample(part,1,0,0,0,0),pose);
            var actual=pose.last().pose().transformPosition(new Vector3f());
            assertEquals(2,actual.x,1e-6);
            assertEquals(3-(part==9?0:MdControllerGeometry.travel(part)/16),actual.y,1e-6);
            assertEquals(4-(part==9?.06/16:0),actual.z,1e-6);
            pose.popPose();assertEquals(original,pose.last().pose());
        }
    }
    @Test void quadCacheRebuildsOnModelIdentityReloadAndNotEveryFrame() {
        var calls=new AtomicInteger();var cache=new MdControllerRenderer.QuadCache();
        var quad=new BakedQuad(new int[32],-1,Direction.UP,null,true);
        BakedModel first=model(calls,quad),replacement=model(calls,quad);
        List<BakedQuad> initial=cache.get(first);assertEquals(7,calls.get());assertEquals(7,initial.size());
        assertSame(initial,cache.get(first));assertEquals(7,calls.get());
        List<BakedQuad> reloaded=cache.get(replacement);assertNotSame(initial,reloaded);assertEquals(14,calls.get());
        assertEquals(initial,reloaded);assertThrows(UnsupportedOperationException.class,()->reloaded.clear());
    }
    private static BakedModel model(AtomicInteger calls,BakedQuad quad) {
        return (BakedModel)Proxy.newProxyInstance(BakedModel.class.getClassLoader(),new Class<?>[]{BakedModel.class},(proxy,method,args)->{
            if(method.getName().equals("getQuads")){calls.incrementAndGet();return List.of(quad);}
            throw new AssertionError("Unexpected model call "+method.getName());
        });
    }
    @Test void renderRetainsBakedSokaColorUvLightAndOverlay() {
        var quad=new BakedQuad(new int[32],-1,Direction.UP,null,true);
        var calls=new AtomicInteger();var pose=new PoseStack().last();int[] lights={42,42,42,42};
        var target=new VertexConsumer() {
            public VertexConsumer addVertex(float x,float y,float z){throw new AssertionError("Use baked quad rendering");}
            public VertexConsumer setColor(int r,int g,int b,int a){return this;}
            public VertexConsumer setUv(float u,float v){return this;}
            public VertexConsumer setUv1(int u,int v){return this;}
            public VertexConsumer setUv2(int u,int v){return this;}
            public VertexConsumer setNormal(float x,float y,float z){return this;}
            @Override public void putBulkData(PoseStack.Pose actual,BakedQuad actualQuad,float[] brightness,
                                              float r,float g,float b,float a,int[] actualLights,int overlay,boolean readColor) {
                calls.incrementAndGet();assertSame(quad,actualQuad);assertSame(pose,actual);assertSame(lights,actualLights);
                assertArrayEquals(new float[]{1,1,1,1},brightness);assertEquals(1,r);assertEquals(1,g);assertEquals(1,b);assertEquals(1,a);
                assertEquals(73,overlay);assertTrue(readColor,"SOKA tint must not be discarded");
            }
        };
        MdControllerRenderer.draw(List.of(quad),target,pose,lights,73);assertEquals(1,calls.get());
    }
}
