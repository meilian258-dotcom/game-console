package cn.piq.gba.client;

import cn.piq.gba.GbaMod;
import cn.piq.fcarcade.client.ControllerArmPoseParameters;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemTransform;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import org.joml.Vector3f;
import java.util.*;

/** User-supplied geometry/UV, cached vanilla baking, exact local held screen only. No session ownership here. */
@EventBusSubscriber(modid=GbaMod.ID,bus=EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
public final class GbaHandheldRenderer extends BlockEntityWithoutLevelRenderer {
    private static final List<String> PARTS=List.of("body","dpad","button_a","button_b","button_select","button_start","shoulder_l","shoulder_r","screen");
    private static final ThreadLocal<Boolean> DRAWING_ARMS=ThreadLocal.withInitial(()->false);
    private static GbaHandheldRenderer instance;
    private record Cached(BakedModel model,List<BakedQuad> quads){}
    private final Map<String,Cached> cache=new HashMap<>();
    private GbaHandheldRenderer(){super(Minecraft.getInstance().getBlockEntityRenderDispatcher(),Minecraft.getInstance().getEntityModels());}
    private static GbaHandheldRenderer renderer(){if(instance==null)instance=new GbaHandheldRenderer();return instance;}
    private static ModelResourceLocation model(String part){return ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(GbaMod.ID,"item/handheld/"+part));}
    private static boolean handheld(ItemStack stack){return stack.is(GbaMod.HANDHELD.get());}
    @SubscribeEvent public static void additional(ModelEvent.RegisterAdditional event){for(String part:PARTS)event.register(model(part));}
    @SubscribeEvent public static void baking(ModelEvent.ModifyBakingResult event){
        var id=new ModelResourceLocation(ResourceLocation.fromNamespaceAndPath(GbaMod.ID,"handheld"),"inventory");
        BakedModel old=event.getModels().get(id);if(old!=null)event.getModels().put(id,new ItemModel(old));
    }
    @SubscribeEvent public static void extensions(RegisterClientExtensionsEvent event){
        event.registerItem(new IClientItemExtensions(){
            @Override public BlockEntityWithoutLevelRenderer getCustomRenderer(){return renderer();}
            @Override public HumanoidModel.ArmPose getArmPose(LivingEntity entity,InteractionHand hand,ItemStack stack){
                if(DRAWING_ARMS.get()||!(entity instanceof Player)||!handheld(stack)||!entity.isAlive()||entity.isUsingItem()||entity.isVisuallySwimming()||entity.isFallFlying())return null;
                boolean two=entity.getItemInHand(other(hand)).isEmpty();
                return (two?ControllerArmPoseParameters.TWO_HANDS:ControllerArmPoseParameters.SINGLE_HAND).getValue();
            }
            @Override public boolean applyForgeHandTransform(PoseStack poses,LocalPlayer player,HumanoidArm arm,ItemStack stack,float partial,float equip,float swing){
                // Fallback when vanilla retains hand ownership (invisibility/scoping/another pose).
                apply(poses,GbaHandheldLayout.first(arm==HumanoidArm.RIGHT,false,equip,swing));return true;
            }
        },GbaMod.HANDHELD.get());
    }
    @Override public void renderByItem(ItemStack stack,ItemDisplayContext context,PoseStack poses,MultiBufferSource buffers,int light,int overlay){
        if(!handheld(stack))return;
        var view=switch(context){
            case FIRST_PERSON_LEFT_HAND,FIRST_PERSON_RIGHT_HAND->GbaHandheldLayout.View.FIRST;
            case THIRD_PERSON_LEFT_HAND,THIRD_PERSON_RIGHT_HAND->GbaHandheldLayout.View.THIRD;
            case GUI->GbaHandheldLayout.View.GUI;case GROUND->GbaHandheldLayout.View.GROUND;default->GbaHandheldLayout.View.FIXED;
        };
        poses.pushPose();try{apply(poses,GbaHandheldLayout.item(view));sourceCenter(poses);
            draw(stack,context.firstPerson(),poses,buffers,light,overlay);
        }finally{poses.popPose();}
    }
    private void draw(ItemStack stack,boolean first,PoseStack poses,MultiBufferSource buffers,int light,int overlay){
        boolean local=first&&GbaHandheldClient.visualMatches(stack)&&GbaHandheldClient.running();
        int mask=local?GbaHandheldClient.visualInputMask():0;ResourceLocation texture=local?GbaHandheldClient.screenTexture():null;
        for(String part:PARTS){
            if(part.equals("screen")&&texture!=null)continue;
            var motion=GbaHandheldLayout.motion(part,mask);var pivot=motion.pivot();poses.pushPose();
            try{
                poses.translate((pivot.x()+motion.x())/16,(pivot.y()+motion.y())/16,(pivot.z()+motion.z())/16);
                poses.mulPose(Axis.XP.rotationDegrees((float)motion.pitch()));poses.mulPose(Axis.ZP.rotationDegrees((float)motion.roll()));
                poses.translate(-pivot.x()/16,-pivot.y()/16,-pivot.z()/16);drawPart(part,poses,buffers,light,overlay);
            }finally{poses.popPose();}
        }
        if(texture!=null){
            var vertices=buffers.getBuffer(cn.piq.fcarcade.client.ScreenRenderMaterial.of(texture));var pose=poses.last();
            float[][] uv={{0,0},{0,1},{1,1},{1,0}};
            for(int i=0;i<4;i++){var p=GbaHandheldLayout.screen(i);
                vertices.addVertex(pose,(float)(p.x()/16),(float)(p.y()/16),(float)(p.z()/16)).setColor(255,255,255,255)
                    .setUv(uv[i][0],uv[i][1]).setOverlay(overlay).setLight(0xF000F0).setNormal(pose,0,1,0);
            }
        }
    }
    private void drawPart(String part,PoseStack poses,MultiBufferSource buffers,int light,int overlay){
        BakedModel model=Minecraft.getInstance().getModelManager().getModel(model(part));Cached old=cache.get(part);
        if(old==null||old.model()!=model){
            var random=RandomSource.create(0x474241L);var quads=new ArrayList<>(model.getQuads(null,null,random));
            for(Direction direction:Direction.values()){random.setSeed(0x474241L);quads.addAll(model.getQuads(null,direction,random));}
            old=new Cached(model,List.copyOf(quads));cache.put(part,old);
        }
        var consumer=buffers.getBuffer(RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS));
        for(var quad:old.quads())consumer.putBulkData(poses.last(),quad,1,1,1,1,light,overlay);
    }
    private static void sourceCenter(PoseStack poses){poses.translate(-GbaHandheldLayout.CENTER_X/16,-GbaHandheldLayout.CENTER_Y/16,-GbaHandheldLayout.CENTER_Z/16);}
    private static void apply(PoseStack poses,GbaHandheldLayout.Pose p){poses.translate(p.x(),p.y(),p.z());poses.mulPose(Axis.YP.rotationDegrees((float)p.yaw()));poses.mulPose(Axis.XP.rotationDegrees((float)p.pitch()));poses.mulPose(Axis.ZP.rotationDegrees((float)p.roll()));poses.scale((float)p.scale(),(float)p.scale(),(float)p.scale());}
    private static InteractionHand other(InteractionHand hand){return hand==InteractionHand.MAIN_HAND?InteractionHand.OFF_HAND:InteractionHand.MAIN_HAND;}
    private static boolean eligible(LocalPlayer player,ItemStack stack){return GbaHandheldLayout.eligible(handheld(stack),player.isAlive(),player.isUsingItem(),player.isInvisible(),player.isScoping(),player.isVisuallySwimming(),player.isFallFlying());}
    @EventBusSubscriber(modid=GbaMod.ID,value=Dist.CLIENT)
    public static final class Hands {
        private Hands(){}
        @SubscribeEvent public static void render(RenderHandEvent event){
            if(event.isCanceled())return;var mc=Minecraft.getInstance();LocalPlayer player=mc.player;if(player==null)return;
            InteractionHand holding=event.getHand();ItemStack current=player.getItemInHand(holding),other=player.getItemInHand(other(holding));
            if(current.isEmpty()&&event.getItemStack().isEmpty()&&eligible(player,other)){event.setCanceled(true);return;}
            if(!eligible(player,current)||!ItemStack.isSameItemSameComponents(event.getItemStack(),current))return;
            if(!(mc.getEntityRenderDispatcher().getRenderer(player) instanceof PlayerRenderer arms))return;
            boolean two=other.isEmpty();HumanoidArm side=holding==InteractionHand.MAIN_HAND?player.getMainArm():player.getMainArm().getOpposite();
            event.setCanceled(true);PoseStack poses=event.getPoseStack();poses.pushPose();boolean previous=DRAWING_ARMS.get();
            try{
                apply(poses,GbaHandheldLayout.first(side==HumanoidArm.RIGHT,two,event.getEquipProgress(),event.getSwingProgress()));
                DRAWING_ARMS.set(true);
                for(HumanoidArm arm:HumanoidArm.values())if(two||arm==side){var p=GbaHandheldLayout.arm(arm==HumanoidArm.RIGHT);poses.pushPose();
                    try{poses.translate(p.x(),p.y(),p.z());poses.mulPose(Axis.ZP.rotationDegrees((float)p.roll()));poses.mulPose(Axis.XP.rotationDegrees((float)p.pitch()));poses.scale((float)p.scale(),(float)p.scale(),(float)p.scale());
                        if(arm==HumanoidArm.RIGHT)arms.renderRightHand(poses,event.getMultiBufferSource(),event.getPackedLight(),player);else arms.renderLeftHand(poses,event.getMultiBufferSource(),event.getPackedLight(),player);
                    }finally{poses.popPose();}
                }
                DRAWING_ARMS.set(previous);poses.pushPose();
                try{
                    // RenderHandEvent bypasses ItemRenderer's centre shift; apply the same item pose minus (.5,.5,.5).
                    poses.translate(-.5,-.5,-.5);apply(poses,GbaHandheldLayout.item(GbaHandheldLayout.View.FIRST));sourceCenter(poses);
                    renderer().draw(current,true,poses,event.getMultiBufferSource(),event.getPackedLight(),OverlayTexture.NO_OVERLAY);
                }finally{poses.popPose();}
            }finally{DRAWING_ARMS.set(previous);poses.popPose();}
        }
    }
    private static final class ItemModel extends BakedModelWrapper<BakedModel>{
        ItemModel(BakedModel original){super(original);}
        @Override public boolean isCustomRenderer(){return true;}
        @Override public BakedModel applyTransform(ItemDisplayContext context,PoseStack poses,boolean left){
            if(context==ItemDisplayContext.THIRD_PERSON_LEFT_HAND||context==ItemDisplayContext.THIRD_PERSON_RIGHT_HAND)
                new ItemTransform(new Vector3f(-8.23849004f,-25.80682901f,28.91855928f),new Vector3f(-2.97557615f/16,-2.0133141f/16,-1.16238744f/16),new Vector3f(1)).apply(left,poses);
            else originalModel.applyTransform(context,poses,left);return this;
        }
        @Override public List<BakedModel> getRenderPasses(ItemStack stack,boolean fabulous){return List.of(this);}
    }
}
