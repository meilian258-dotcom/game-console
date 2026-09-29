package cn.piq.fcarcade.client;

import cn.piq.fcarcade.FcArcadeMod;
import cn.piq.fcarcade.layout.*;
import cn.piq.fcarcade.layout.DualCabinetControls.*;
import cn.piq.fcarcade.world.*;
import cn.piq.fcarcade.registry.*;
import cn.piq.fcarcade.cabinet.*;
import cn.piq.fcarcade.client.cabinet.CabinetClientBackends;
import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.blockentity.*;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.*;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.client.extensions.common.*;
import java.util.*;

/** Original group pivots, source-space animation, uniform scale; one anchor draw only. */
public final class PortraitCabinetRenderer implements BlockEntityRenderer<PortraitCabinetBlockEntity> {
    private static final List<Part> PARTS=List.of(
            new Part("p1_button_1",0,0,7.025,16.3825,1.397),new Part("p1_button_2",0,1,5.793,16.3825,1.529),
            new Part("p1_button_3",0,2,4.561,16.3825,1.397),new Part("p1_button_4",0,3,7.025,16.3825,2.75),
            new Part("p1_button_5",0,4,5.793,16.3825,2.882),new Part("p1_button_6",0,5,4.561,16.3825,2.75),
            new Part("p1_start",0,6,8.499,16.333,1.386),new Part("p1_joystick",0,-1,10.6,16.3495,2.97));
    private record Cached(BakedModel model,List<BakedQuad> quads){}
    private static final Map<String,Cached> CACHE=new HashMap<>();
    public PortraitCabinetRenderer(BlockEntityRendererProvider.Context context){}
    private static ModelResourceLocation model(String name){return ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(FcArcadeMod.MOD_ID,"block/portrait/"+name));}
    public static void register(IEventBus bus){
        bus.addListener((EntityRenderersEvent.RegisterRenderers e)->e.registerBlockEntityRenderer(ModBlockEntities.PORTRAIT_CABINET.get(),PortraitCabinetRenderer::new));
        bus.addListener((ModelEvent.RegisterAdditional e)->{e.register(model("body"));PARTS.forEach(p->e.register(model(p.name())));});
        bus.addListener((RegisterClientExtensionsEvent e)->e.registerItem(new IClientItemExtensions(){
            private BlockEntityWithoutLevelRenderer renderer;
            @Override public BlockEntityWithoutLevelRenderer getCustomRenderer(){if(renderer==null)renderer=new ItemRenderer();return renderer;}
        },ModItems.PORTRAIT_CABINET.get()));
    }
    @Override public boolean shouldRenderOffScreen(PortraitCabinetBlockEntity e){return true;}
    @Override public AABB getRenderBoundingBox(PortraitCabinetBlockEntity e){return CabinetDataCableRenderer.bounds(e,new AABB(e.getBlockPos()).expandTowards(0,1,0).inflate(.1));}
    @Override public void render(PortraitCabinetBlockEntity e,float partial,PoseStack poses,MultiBufferSource buffers,int light,int overlay){
        CabinetPowerRenderer.render(e,poses,buffers);
        CabinetDataCableRenderer.render(e,poses,buffers,light,overlay);
        var face=e.getBlockState().getValue(FcArcadeBlock.FACING);int turns=RocketArcadeGeometry.quarterTurns(face.getStepX(),face.getStepZ());
        int[] inputs=CabinetBackends.NES.equals(e.cabinetBackend())?ClientArcadeEvents.cabinetVisualInputs(e.getBlockPos())
                :CabinetClientBackends.visualInputs(new CabinetTarget(e.getLevel().dimension().location(),e.getBlockPos(),e.cabinetId(),false));
        poses.pushPose();try{
            poses.translate(.5,0,.5);poses.mulPose(Axis.YP.rotationDegrees(-90*turns));poses.translate(-.5,0,-.5);
            poses.translate(PortraitCabinetGeometry.X,0,PortraitCabinetGeometry.Z);
            float scale=(float)PortraitCabinetGeometry.SCALE;poses.scale(scale,scale,scale);
            draw(poses,buffers,light,overlay,inputs.length==0?0:inputs[0],DualCabinetControls.layoutForBackend(e.cabinetBackend().toString()));
        }finally{poses.popPose();}
    }
    private static void draw(PoseStack poses,MultiBufferSource buffers,int light,int overlay,int input,InputLayout layout){
        part("body",poses,buffers,light,overlay);
        for(var p:PARTS){var m=DualCabinetControls.motion(p,input,layout);poses.pushPose();try{
            poses.translate(0,m.pressY()/16,0);
            if(p.joystick()){
                poses.translate(p.x()/16,p.y()/16,p.z()/16);
                poses.mulPose(Axis.ZP.rotationDegrees((float)m.tiltZ()));poses.mulPose(Axis.XP.rotationDegrees((float)m.tiltX()));
                poses.translate(-p.x()/16,-p.y()/16,-p.z()/16);
            }
            part(p.name(),poses,buffers,light,overlay);
        }finally{poses.popPose();}}
    }
    private static void part(String name,PoseStack poses,MultiBufferSource buffers,int light,int overlay){
        var model=Minecraft.getInstance().getModelManager().getModel(model(name));var c=CACHE.get(name);
        if(c==null||c.model()!=model){var r=RandomSource.create(42);var list=new ArrayList<>(model.getQuads(null,null,r));for(var d:Direction.values()){r.setSeed(42);list.addAll(model.getQuads(null,d,r));}c=new Cached(model,List.copyOf(list));CACHE.put(name,c);}
        var out=buffers.getBuffer(RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS));
        poses.pushPose();try{poses.scale(2,2,2);for(var q:c.quads())out.putBulkData(poses.last(),q,1,1,1,1,light,overlay);}finally{poses.popPose();}
    }
    private static final class ItemRenderer extends BlockEntityWithoutLevelRenderer {
        private ItemRenderer(){super(Minecraft.getInstance().getBlockEntityRenderDispatcher(),Minecraft.getInstance().getEntityModels());}
        @Override public void renderByItem(ItemStack stack,ItemDisplayContext context,PoseStack poses,MultiBufferSource buffers,int light,int overlay){
            poses.pushPose();try{poses.translate(.5,.5,.5);poses.scale(.43F,.43F,.43F);poses.translate(-.5,-35D/32,-9D/16);draw(poses,buffers,light,overlay,0,InputLayout.ARCADE);}finally{poses.popPose();}
        }
    }
}
