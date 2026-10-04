// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome.client;

import cn.piq.mdhome.MdMod;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/** Original textured BakedModels, animated only after the existing item display transform.
 * Model-identity caches naturally expire on resource reload; no files or world scans per frame. */
public final class MdControllerRenderer extends BlockEntityWithoutLevelRenderer {
    public static final ModelResourceLocation MODEL=model("mesh");
    public static final List<ModelResourceLocation> MODELS;
    private static final Direction[] FACES=Direction.values();
    private static final float[] BRIGHTNESS={1,1,1,1};
    static {
        var ids=new ArrayList<ModelResourceLocation>();ids.add(MODEL);
        for(String part:MdControllerGeometry.NAMES)ids.add(model(part));
        MODELS=List.copyOf(ids);
    }
    private static ModelResourceLocation model(String name) {
        return ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(MdMod.ID,"item/md_controller_"+name));
    }
    static final class QuadCache {
        private BakedModel model;
        private List<BakedQuad> quads=List.of();
        List<BakedQuad> get(BakedModel next) {
            if(model!=next) {
                var random=RandomSource.create(42);var loaded=new ArrayList<>(next.getQuads(null,null,random));
                for(var face:FACES) { random.setSeed(42);loaded.addAll(next.getQuads(null,face,random)); }
                quads=List.copyOf(loaded);model=next;
            }
            return quads;
        }
    }
    private final QuadCache[] cache=new QuadCache[MdControllerGeometry.PARTS+1];
    public MdControllerRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(),Minecraft.getInstance().getEntityModels());
        for(int i=0;i<cache.length;i++)cache[i]=new QuadCache();
    }
    static boolean held(ItemDisplayContext context) {
        return context==ItemDisplayContext.FIRST_PERSON_LEFT_HAND||context==ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                ||context==ItemDisplayContext.THIRD_PERSON_LEFT_HAND||context==ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
    }
    private List<BakedQuad> quads(int index) {
        BakedModel model=Minecraft.getInstance().getModelManager().getModel(MODELS.get(index));
        return cache[index].get(model);
    }
    @Override public void renderByItem(ItemStack stack,ItemDisplayContext context,PoseStack poses,
                                       MultiBufferSource buffers,int light,int overlay) {
        var target=buffers.getBuffer(RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS));
        int[] lights={light,light,light,light};
        if(!held(context)) {
            draw(quads(0),target,poses.last(),lights,overlay);
            return;
        }
        var state=MdControllerVisual.state(stack,context);
        for(int part=0;part<MdControllerGeometry.PARTS;part++) {
            int mask=MdControllerGeometry.mask(part);
            var transform=MdControllerGeometry.sample(part,mask==0?0:state.value(mask),
                    state.value(16),state.value(32),state.value(64),state.value(128));
            poses.pushPose();
            try {
                apply(part,transform,poses);
                draw(quads(part+1),target,poses.last(),lights,overlay);
            } finally { poses.popPose(); }
        }
    }
    static void draw(List<BakedQuad> quads,VertexConsumer target,PoseStack.Pose pose,int[] lights,int overlay) {
        // Preserve NeoForge baked face colors, including the MD8 SOKA overlays.
        // The short putBulkData overload discards those colors (readExistingColor=false).
        for(var quad:quads)target.putBulkData(pose,quad,BRIGHTNESS,1,1,1,1,lights,overlay,true);
    }
    static void apply(int part,MdControllerGeometry.Transform transform,PoseStack poses) {
        if(transform.equals(MdControllerGeometry.Transform.REST))return;
        poses.translate(0,transform.y()/16,transform.z()/16);
        if(part==MdControllerGeometry.DPAD) {
            poses.translate(MdControllerGeometry.DPAD_X/16,MdControllerGeometry.DPAD_Y/16,MdControllerGeometry.DPAD_Z/16);
            poses.mulPose(Axis.XP.rotationDegrees((float)transform.pitch()));
            poses.mulPose(Axis.ZP.rotationDegrees((float)transform.roll()));
            poses.translate(-MdControllerGeometry.DPAD_X/16,-MdControllerGeometry.DPAD_Y/16,-MdControllerGeometry.DPAD_Z/16);
        }
    }
}
