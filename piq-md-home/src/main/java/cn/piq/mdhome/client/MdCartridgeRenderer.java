// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome.client;

import cn.piq.mdhome.MdMod;
import cn.piq.fcarcade.client.ContentCardCoverRenderer;
import cn.piq.fcarcade.home.content.ContentCardData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.*;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.*;
import com.mojang.blaze3d.vertex.PoseStack;

public final class MdCartridgeRenderer extends BlockEntityWithoutLevelRenderer {
    public static final ModelResourceLocation MODEL=ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(MdMod.ID,"item/md_cartridge_mesh"));
    public MdCartridgeRenderer(){super(Minecraft.getInstance().getBlockEntityRenderDispatcher(),Minecraft.getInstance().getEntityModels());}
    private BakedModel cachedModel;
    private java.util.List<net.minecraft.client.renderer.block.model.BakedQuad> cachedQuads=java.util.List.of();
    public void renderByItem(ItemStack stack,ItemDisplayContext context,PoseStack poses,MultiBufferSource buffers,int light,int overlay){
        var model=Minecraft.getInstance().getModelManager().getModel(MODEL);
        var out=buffers.getBuffer(RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS));
        if(cachedModel!=model){
            var random=RandomSource.create(42);var quads=new java.util.ArrayList<>(model.getQuads(null,null,random));
            for(var face:Direction.values()){random.setSeed(42);quads.addAll(model.getQuads(null,face,random));}
            cachedQuads=java.util.List.copyOf(quads);cachedModel=model;
        }
        for(var quad:cachedQuads)out.putBulkData(poses.last(),quad,1,1,1,1,light,overlay);
        ContentCardCoverRenderer.north(ContentCardData.cover(stack),poses,buffers,light,overlay,6.045/16,7.232/16,9.955/16,8.952/16,7.607/16);
    }
}
