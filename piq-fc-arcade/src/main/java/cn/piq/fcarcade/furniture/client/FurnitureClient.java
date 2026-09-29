package cn.piq.fcarcade.furniture.client;

import cn.piq.fcarcade.furniture.FurnitureRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

/** Loaded only by the client mod bus; common furniture registration never references this class. */
@EventBusSubscriber(modid = "piq_fc_arcade", bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class FurnitureClient {
    private FurnitureClient() {}

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(FurnitureRegistry.FURNITURE_ENTITY.get(), FurnitureRenderer::new);
        event.registerEntityRenderer(FurnitureRegistry.SEAT.get(), NoopRenderer::new);
    }

    @SubscribeEvent
    public static void reload(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) manager -> FurnitureRenderer.replace(
                FurnitureResources.load(manager, message -> com.mojang.logging.LogUtils.getLogger().error(message))));
    }

    @SubscribeEvent
    public static void items(RegisterClientExtensionsEvent event) {
        IClientItemExtensions extension = new IClientItemExtensions() {
            private BlockEntityWithoutLevelRenderer renderer;
            @Override public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (renderer == null) renderer = new FurnitureRenderer.ItemRenderer(
                        Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
                return renderer;
            }
        };
        FurnitureRegistry.BENCHES.values().forEach(block -> event.registerItem(extension, block.get().asItem()));
        FurnitureRegistry.STOOLS.values().forEach(block -> event.registerItem(extension, block.get().asItem()));
    }
}
