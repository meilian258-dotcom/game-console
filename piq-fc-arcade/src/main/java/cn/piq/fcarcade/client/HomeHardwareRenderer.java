package cn.piq.fcarcade.client;

import cn.piq.fcarcade.FcArcadeMod;
import cn.piq.fcarcade.home.HomeConsoleBlockEntity;
import cn.piq.fcarcade.home.HomeConsoleLayout;
import cn.piq.fcarcade.home.SuborConsoleBlock;
import cn.piq.fcarcade.home.LcdTvBlock;
import cn.piq.fcarcade.home.HomeControllerData;
import cn.piq.fcarcade.home.FcCartridgeData;
import cn.piq.fcarcade.home.HomeHardware;
import cn.piq.fcarcade.home.HomeHardwareScale;
import cn.piq.fcarcade.home.HomeTvBlockEntity;
import cn.piq.fcarcade.home.HomeTvStructure;
import cn.piq.fcarcade.registry.ModBlockEntities;
import cn.piq.fcarcade.registry.ModItems;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

/** Client-only entry point: original hardware meshes, one cable per console and one card draw. */
public final class HomeHardwareRenderer implements BlockEntityRenderer<HomeConsoleBlockEntity> {
    private static final ResourceLocation CARD_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            FcArcadeMod.MOD_ID, "textures/block/home_fc_cartridge_skin.png");
    private static final ModelResourceLocation CARD_MODEL = ModelResourceLocation.standalone(
            ResourceLocation.fromNamespaceAndPath(FcArcadeMod.MOD_ID, "block/home_fc_cartridge"));
    private static final ModelResourceLocation SHELL_MODEL = controllerModel("home_fc_cartridge_shell");
    private static final ModelResourceLocation[] BOARD_MODELS = {
            controllerModel("home_fc_board_0"), controllerModel("home_fc_board_1"), controllerModel("home_fc_board_2")};
    private static final CachedTvModel[] BOARD_CACHE = new CachedTvModel[3];
    private static final ResourceLocation CONTROLLER_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            FcArcadeMod.MOD_ID, "textures/block/home_famicom_console.png");
    private static final ModelResourceLocation[] CONTROLLER_MODELS = {
            controllerModel("home_controller_p1_docked"), controllerModel("home_controller_p2_docked"),
            controllerModel("home_controller_p1_held"), controllerModel("home_controller_p2_held")
    };
    // Four meshes total, replaced on baked-model identity change (F3+T).
    private static final CachedTvModel[] CONTROLLER_CACHE = new CachedTvModel[4];
    // Weak keys release unloaded block entities; values contain no world or BE references.
    private final java.util.Map<HomeConsoleBlockEntity, CachedCable> cables = new java.util.WeakHashMap<>();

    private static ModelResourceLocation controllerModel(String name) {
        return ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(FcArcadeMod.MOD_ID, "block/" + name));
    }

    public HomeHardwareRenderer(BlockEntityRendererProvider.Context context) {}

    public static void register(IEventBus modBus) {
        HomeApplianceClient.register();
        DualCabinetRenderer.register(modBus);
        PortraitCabinetRenderer.register(modBus);
        LegacyArcadeSkinRenderer.register(modBus);
        modBus.addListener(HomeHardwareRenderer::registerRenderers);
        modBus.addListener(HomeHardwareRenderer::registerModels);
        modBus.addListener(HomeHardwareRenderer::registerItems);
        modBus.addListener(SuborHardwareMesh::registerReload);
        modBus.addListener(HomeAvCableRenderer::registerReload);
        ControllerPose.register();
    }

    private static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.HOME_CONSOLE.get(), HomeHardwareRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.HOME_TV.get(), TvRenderer::new);
    }

    private static void registerModels(ModelEvent.RegisterAdditional event) {
        event.register(CARD_MODEL);
        event.register(SHELL_MODEL);
        for (var model : BOARD_MODELS) event.register(model);
        for (ModelResourceLocation model : CONTROLLER_MODELS) event.register(model);
    }

    private static void registerItems(RegisterClientExtensionsEvent event) {
        event.registerItem(new IClientItemExtensions() {
            private BlockEntityWithoutLevelRenderer renderer;
            @Override public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (renderer == null) renderer = new SuborItemRenderer();
                return renderer;
            }
        }, ModItems.SUBOR_CONSOLE.get());
        event.registerItem(new IClientItemExtensions() {
            private BlockEntityWithoutLevelRenderer renderer;

            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (renderer == null) renderer = new CartridgeItemRenderer();
                return renderer;
            }
        }, ModItems.FC_CARTRIDGE.get(), ModItems.FC_CARTRIDGE_BOARD.get(), ModItems.FC_CARTRIDGE_SHELL.get());
        event.registerItem(new IClientItemExtensions() {
            private BlockEntityWithoutLevelRenderer renderer;
            @Override
            public net.minecraft.client.model.HumanoidModel.ArmPose getArmPose(
                    net.minecraft.world.entity.LivingEntity entity, net.minecraft.world.InteractionHand hand, ItemStack stack) {
                return ControllerPose.armPose(entity, hand, stack);
            }
            @Override
            public boolean applyForgeHandTransform(PoseStack poses, net.minecraft.client.player.LocalPlayer player,
                    net.minecraft.world.entity.HumanoidArm arm, ItemStack stack, float partialTick, float equip, float swing) {
                return ControllerPose.firstTransform(poses, player, arm, stack, equip, swing);
            }
            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (renderer == null) renderer = new ControllerItemRenderer();
                return renderer;
            }
        }, ModItems.FC_CONTROLLER.get());
    }

    @Override
    public void render(HomeConsoleBlockEntity console, float partialTick, PoseStack poses,
                       MultiBufferSource buffers, int light, int overlay) {
        boolean subor = console.getBlockState().getBlock() instanceof SuborConsoleBlock;
        boolean wide = SuborConsoleBlock.wide(console.getBlockState());
        boolean compact = SuborConsoleBlock.compact(console.getBlockState());
        poses.pushPose();
        try {
            poses.translate(0.5, 0, 0.5);
            poses.mulPose(Axis.YP.rotationDegrees(-90f * turns(console.getBlockState())));
            poses.translate(-0.5, 0, -0.5);
            if (wide) {
                SuborHardwareMesh.drawWide("body", compact, poses, buffers, light, overlay);
                SuborHardwareMesh.drawWide(console.insertedCartridge().isEmpty() ? "lid_closed" : "lid_open", compact, poses, buffers, light, overlay);
            } else if (subor) SuborHardwareMesh.draw("body", poses, buffers, light, overlay);
            PowerIndicatorRenderer.console(console, poses, buffers, light, overlay);
            // Both model families already include their reviewed world transform.
            for (int port = 0; port < 2; port++) {
                if (!console.controllerDocked(port)) continue;
                if (wide) SuborHardwareMesh.drawWide(port == 0 ? "p1_docked" : "p2_docked", compact, poses, buffers, light, overlay);
                else if (subor) SuborHardwareMesh.draw(port == 0 ? "p1_docked" : "p2_docked", poses, buffers, light, overlay);
                else drawController(port, poses, buffers, light, overlay);
            }
        } finally {
            poses.popPose();
        }
        ItemStack cartridge = console.insertedCartridge();
        if (!cartridge.isEmpty()) {
            poses.pushPose();
            try {
                poses.translate(0.5, 0, 0.5);
                poses.mulPose(Axis.YP.rotationDegrees(-90f * turns(console.getBlockState())));
                if (subor) {
                    poses.translate((wide ? HomeConsoleLayout.WIDE_CARD_X : HomeConsoleLayout.CARD_X) - .5,
                            wide ? HomeConsoleLayout.WIDE_CARD_Y : HomeConsoleLayout.CARD_Y,
                            HomeConsoleLayout.suborZ(wide ? HomeConsoleLayout.WIDE_CARD_Z : HomeConsoleLayout.CARD_Z,compact) - .5);
                    float scale = (float) (wide ? HomeConsoleLayout.WIDE_CARD_SCALE : HomeConsoleLayout.CARD_SCALE);
                    poses.scale(scale, scale, scale);
                    poses.translate(-.5, 0, -.5);
                } else {
                    float scale = (float) HomeHardwareScale.CONSOLE_SCALE;
                    poses.scale(scale, scale, scale);
                    poses.translate(-0.5, HomeHardwareRenderLayout.CARTRIDGE_Y,
                            -0.5 + HomeHardwareRenderLayout.CARTRIDGE_Z);
                }
                drawCartridge(cartridge, poses, buffers, light, overlay);
            } finally {
                poses.popPose();
            }
        }
        BlockPos tv = connectedTv(console);
        if (tv != null) drawCable(console, tv, poses.last(), buffers, light, overlay);
        else cables.remove(console);
        ControllerCableRenderer.renderFc(console, partialTick, poses, buffers, light, overlay);
    }

    @Override
    public boolean shouldRenderOffScreen(HomeConsoleBlockEntity console) {
        // A connected cable may cross sections even when the console section is off-screen.
        // Section registration must be stable before/after connecting; the full AABB and
        // normal 64-block BER distance still limit actual draws.
        return true;
    }

    @Override
    public AABB getRenderBoundingBox(HomeConsoleBlockEntity console) {
        // At 0.6 the original controller cords and the inserted card fit inside one block.
        AABB bounds = new AABB(console.getBlockPos());
        if (SuborConsoleBlock.wide(console.getBlockState())) {
            var b = HomeConsoleLayout.suborBounds(turns(console.getBlockState()), true,SuborConsoleBlock.compact(console.getBlockState()));
            bounds = bounds.minmax(new AABB(b.minX()/16,b.minY()/16,b.minZ()/16,
                    b.maxX()/16,b.maxY()/16,b.maxZ()/16).move(console.getBlockPos()));
        }
        BlockPos tv = connectedTv(console);
        if (tv != null) bounds = bounds.minmax(tvRenderBounds(tv, console.getLevel().getBlockState(tv)).inflate(0.7));
        return bounds.minmax(ControllerCableRenderer.bounds(console));
    }

    public static AABB tvRenderBounds(BlockPos anchor, BlockState state) {
        if (state.getBlock() instanceof cn.piq.fcarcade.home.RetroTvBlock block && cn.piq.fcarcade.home.UserTvLayout.supports(block.displayStyle())) {
            var b=cn.piq.fcarcade.home.UserTvLayout.bounds(block.displayStyle(),turns(state));
            return new AABB(b.minX()/16,b.minY()/16,b.minZ()/16,b.maxX()/16,b.maxY()/16,b.maxZ()/16).move(anchor);
        }
        if (state.getBlock() instanceof cn.piq.fcarcade.home.LargeLcdTvBlock) {
            var b = cn.piq.fcarcade.home.LargeLcdTvLayout.bounds(turns(state));
            return new AABB(b.minX()/16,b.minY()/16,b.minZ()/16,b.maxX()/16,b.maxY()/16,b.maxZ()/16).move(anchor);
        }
        if (state.getBlock() instanceof cn.piq.fcarcade.home.VintageTvBlock) {
            var b = cn.piq.fcarcade.home.VintageTvLayout.bounds(turns(state));
            return new AABB(b.minX()/16,b.minY()/16,b.minZ()/16,b.maxX()/16,b.maxY()/16,b.maxZ()/16).move(anchor);
        }
        if (state.getBlock() instanceof cn.piq.fcarcade.home.WideLcdTvBlock) {
            var b = cn.piq.fcarcade.home.WideLcdTvLayout.bounds(turns(state));
            return new AABB(b.minX()/16,b.minY()/16,b.minZ()/16,b.maxX()/16,b.maxY()/16,b.maxZ()/16).move(anchor);
        }
        if (state.getBlock() instanceof LcdTvBlock) {
            var b = cn.piq.fcarcade.home.LcdTvLayout.bounds(turns(state));
            return new AABB(b.minX()/16,b.minY()/16,b.minZ()/16,b.maxX()/16,b.maxY()/16,b.maxZ()/16).move(anchor);
        }
        var bounds = HomeHardwareRenderLayout.tvBounds(turns(state), HomeTvStructure.centered(state));
        return new AABB(bounds.min().x(), bounds.min().y(), bounds.min().z(),
                bounds.max().x(), bounds.max().y(), bounds.max().z()).move(anchor);
    }

    private static BlockPos connectedTv(HomeConsoleBlockEntity console) {
        BlockPos tv = console.tvPos();
        if (tv == null || console.getLevel() == null || !console.getLevel().hasChunkAt(tv)
                || console.getBlockPos().distSqr(tv) > 64) return null;
        // The common helper validates both stored endpoint UUIDs without loading either chunk.
        return HomeHardware.connectedConsole(console.getLevel(), tv) == console ? tv : null;
    }

    private static int turns(BlockState state) {
        Direction facing = state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                ? state.getValue(BlockStateProperties.HORIZONTAL_FACING) : Direction.NORTH;
        return switch (facing) {
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0;
        };
    }

    private void drawCable(HomeConsoleBlockEntity console, BlockPos tv, PoseStack.Pose pose,
                                  MultiBufferSource buffers, int light, int overlay) {
        var tvState = console.getLevel().getBlockState(tv);
        var consoleState = console.getBlockState();
        BlockPos origin = console.getBlockPos();
        java.util.UUID linkId = console.linkId();
        long cableSeed = linkId == null ? 0L : linkId.getMostSignificantBits() ^ linkId.getLeastSignificantBits();
        CachedCable cached = cables.get(console);
        if (cached == null || !cached.tv().equals(tv) || cached.consoleState() != consoleState || cached.tvState() != tvState
                || !java.util.Objects.equals(cached.linkId(), linkId)) {
            var style=((cn.piq.fcarcade.world.FcArcadeBlock)tvState.getBlock()).displayStyle();
            var route = cn.piq.fcarcade.home.UserTvLayout.supports(style)
                    ? UserTvCableMesh.buildFc(consoleState.getBlock() instanceof SuborConsoleBlock,
                        SuborConsoleBlock.wide(consoleState),SuborConsoleBlock.compact(consoleState),turns(consoleState),
                        style,turns(tvState),tv.getX()-origin.getX(),tv.getY()-origin.getY(),tv.getZ()-origin.getZ(),cableSeed)
                    : HomeAvCableMesh.build(consoleState.getBlock() instanceof SuborConsoleBlock,
                    SuborConsoleBlock.wide(consoleState), turns(consoleState), HomeTvStructure.centered(tvState),
                    tvState.getBlock() instanceof LcdTvBlock || tvState.getBlock() instanceof cn.piq.fcarcade.home.WideLcdTvBlock
                            || tvState.getBlock() instanceof cn.piq.fcarcade.home.LargeLcdTvBlock,
                    tvState.getBlock() instanceof cn.piq.fcarcade.home.WideLcdTvBlock,
                    tvState.getBlock() instanceof cn.piq.fcarcade.home.LargeLcdTvBlock,
                    tvState.getBlock() instanceof cn.piq.fcarcade.home.VintageTvBlock, turns(tvState),
                    tv.getX() - origin.getX(), tv.getY() - origin.getY(), tv.getZ() - origin.getZ(),SuborConsoleBlock.compact(consoleState));
            cached = new CachedCable(tv.immutable(), consoleState, tvState, linkId, route);
            cables.put(console, cached);
        }
        HomeAvCableRenderer.draw(cached.quads(), pose, buffers, light, overlay);
    }

    private record CachedCable(BlockPos tv, BlockState consoleState, BlockState tvState, java.util.UUID linkId,
                               java.util.List<HomeAvCableMesh.Quad> quads) {}

    private static void drawCartridge(ItemStack stack, PoseStack poses, MultiBufferSource buffers,
                                      int light, int overlay) {
        if (FcCartridgeData.isBoard(stack)) {
            int variant = FcCartridgeData.boardVariant(stack);
            BakedModel baked = Minecraft.getInstance().getModelManager().getModel(BOARD_MODELS[variant]);
            CachedTvModel cached = BOARD_CACHE[variant];
            if (cached == null || cached.model() != baked) {
                RandomSource rng = RandomSource.create(0x504951L);
                var quads = new java.util.ArrayList<BakedQuad>(baked.getQuads(null, null, rng));
                for (Direction side : Direction.values()) {
                    rng.setSeed(0x504951L); quads.addAll(baked.getQuads(null, side, rng));
                }
                cached = new CachedTvModel(baked, java.util.List.copyOf(quads));
                BOARD_CACHE[variant] = cached;
            }
            VertexConsumer target = buffers.getBuffer(RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS));
            for (BakedQuad quad : cached.quads()) target.putBulkData(poses.last(), quad, 1, 1, 1, 1, light, overlay);
            return; // Bare PCB never requests, composites or displays a game cover.
        }
        ResourceLocation texture = ClientCartridgeCovers.texture(stack);
        if (texture == null) texture = CARD_TEXTURE;
        // Fetch on every draw: models may be replaced by F3+T; never retain stale atlas sprites.
        BakedModel model = Minecraft.getInstance().getModelManager().getModel(
                FcCartridgeData.isShell(stack) ? SHELL_MODEL : CARD_MODEL);
        VertexConsumer target = buffers.getBuffer(RenderType.entityCutoutNoCull(texture));
        RandomSource random = RandomSource.create(0x504951L);
        drawQuads(model.getQuads(null, null, random), target, poses.last(), light, overlay);
        for (Direction side : Direction.values()) {
            random.setSeed(0x504951L);
            drawQuads(model.getQuads(null, side, random), target, poses.last(), light, overlay);
        }
    }

    static void drawQuads(java.util.List<BakedQuad> quads, VertexConsumer target,
                                  PoseStack.Pose pose, int light, int overlay) {
        for (BakedQuad quad : quads) {
            new RawSkinUv(target, quad.getSprite()).putBulkData(pose, quad, 1, 1, 1, 1, light, overlay);
        }
    }

    private static final class CartridgeItemRenderer extends BlockEntityWithoutLevelRenderer {
        private CartridgeItemRenderer() {
            super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
        }

        @Override
        public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack poses,
                                 MultiBufferSource buffers, int light, int overlay) {
            // ItemRenderer already applied item JSON display and (-.5,-.5,-.5); do not reapply it.
            drawCartridge(stack, poses, buffers, light, overlay);
        }
    }

    private static void drawController(int modelIndex, PoseStack poses, MultiBufferSource buffers,
                                       int light, int overlay) {
        drawController(modelIndex, poses, buffers, light, overlay, null, null);
    }

    private static void drawController(int modelIndex, PoseStack poses, MultiBufferSource buffers,
                                       int light, int overlay, ItemStack stack, ItemDisplayContext context) {
        BakedModel baked = Minecraft.getInstance().getModelManager().getModel(CONTROLLER_MODELS[modelIndex]);
        CachedTvModel cached = CONTROLLER_CACHE[modelIndex];
        if (cached == null || cached.model() != baked) {
            RandomSource random = RandomSource.create(0x504951L);
            java.util.ArrayList<BakedQuad> quads = new java.util.ArrayList<>(baked.getQuads(null, null, random));
            for (Direction side : Direction.values()) {
                random.setSeed(0x504951L);
                quads.addAll(baked.getQuads(null, side, random));
            }
            cached = new CachedTvModel(baked, java.util.List.copyOf(quads));
            CONTROLLER_CACHE[modelIndex] = cached;
        }
        if (modelIndex >= 2 && stack != null) {
            ControllerButtonRenderer.drawFamicom(modelIndex - 2, cached.quads(), stack, context,
                    poses, buffers, light, overlay);
        } else drawQuads(cached.quads(), buffers.getBuffer(RenderType.entityCutoutNoCull(CONTROLLER_TEXTURE)),
                    poses.last(), light, overlay);
    }

    private static final class ControllerItemRenderer extends BlockEntityWithoutLevelRenderer {
        private ControllerItemRenderer() {
            super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
        }

        @Override
        public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack poses,
                                 MultiBufferSource buffers, int light, int overlay) {
            int port = HomeControllerData.port(stack) == 1 ? 1 : 0;
            if (HomeControllerData.style(stack) == HomeControllerData.Style.SUBOR) {
                SuborHardwareMesh.drawHeld(port == 0 ? "p1_held" : "p2_held", stack, context, poses, buffers, light, overlay);
                return;
            }
            poses.pushPose();
            try {
                // ItemRenderer applied the common display transform; rotate only the
                // original side-facing controller so D-pad stays left of A/B for both.
                poses.translate(0.5, 0.5, 0.5);
                poses.mulPose(Axis.YP.rotationDegrees(HomeHardwareRenderLayout.heldControllerYaw(port)));
                poses.translate(-0.5, -0.5, -0.5);
                drawController(2 + port, poses, buffers, light, overlay, stack, context);
            } finally {
                poses.popPose();
            }
        }
    }

    private static final class SuborItemRenderer extends BlockEntityWithoutLevelRenderer {
        private SuborItemRenderer() {
            super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
        }
        @Override public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack poses,
                                           MultiBufferSource buffers, int light, int overlay) {
            poses.pushPose();
            try {
                // The world assembly is two blocks wide; inventory/held console stays icon-sized.
                poses.scale(.5f, .5f, .5f);
                SuborHardwareMesh.drawWide("body", true, poses, buffers, light, overlay);
                SuborHardwareMesh.drawWide("lid_closed", true, poses, buffers, light, overlay);
                SuborHardwareMesh.drawWide("p1_docked", true, poses, buffers, light, overlay);
                SuborHardwareMesh.drawWide("p2_docked", true, poses, buffers, light, overlay);
            } finally { poses.popPose(); }
        }
    }

    /**
     * The TV shell is drawn only here; RetroTvBlock disables chunk MODEL rendering.
     * Global BER registration avoids anchor-section frustum clipping, while NeoForge
     * still tests the full rotated reserved AABB and the normal 64-block distance.
     */
    private static final class TvRenderer implements BlockEntityRenderer<HomeTvBlockEntity> {
        // Different TV families can face the same direction. Cache by baked identity,
        // not direction, so adjacent models do not rebuild each other's quads per frame.
        private final java.util.Map<BakedModel, CachedTvModel> models = new java.util.IdentityHashMap<>();

        private TvRenderer(BlockEntityRendererProvider.Context context) {}

        @Override
        public boolean shouldRenderOffScreen(HomeTvBlockEntity tv) {
            return true;
        }

        @Override
        public AABB getRenderBoundingBox(HomeTvBlockEntity tv) {
            return tvRenderBounds(tv.getBlockPos(), tv.getBlockState());
        }

        @Override
        public void render(HomeTvBlockEntity tv, float partialTick, PoseStack poses,
                           MultiBufferSource buffers, int light, int overlay) {
            BlockState state = tv.getBlockState();
            // The state already includes the blockstate JSON rotation; do not rotate twice.
            BakedModel baked = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
            int facing = turns(state);
            CachedTvModel cached = models.get(baked);
            if (cached == null) {
                RandomSource random = RandomSource.create(0x504951L);
                java.util.ArrayList<BakedQuad> quads = new java.util.ArrayList<>(baked.getQuads(state, null, random));
                for (Direction side : Direction.values()) {
                    random.setSeed(0x504951L);
                    quads.addAll(baked.getQuads(state, side, random));
                }
                // Bounded across resource reloads; no old world or BE references retained.
                if (models.size() >= 64) models.clear();
                cached = new CachedTvModel(baked, java.util.List.copyOf(quads));
                models.put(baked, cached);
            }
            VertexConsumer target = buffers.getBuffer(RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS));
            poses.pushPose();
            try {
                var offset = HomeHardwareRenderLayout.tvOffset(facing, HomeTvStructure.centered(state));
                poses.translate(offset.x(), offset.y(), offset.z());
                if(state.getBlock() instanceof cn.piq.fcarcade.home.RetroTvBlock block && cn.piq.fcarcade.home.UserTvLayout.supports(block.displayStyle())) {
                    var modelOffset=cn.piq.fcarcade.home.UserTvLayout.modelOffset(block.displayStyle(),facing);
                    poses.translate(modelOffset.x(),modelOffset.y(),modelOffset.z());
                }
                for (BakedQuad quad : cached.quads()) {
                    target.putBulkData(poses.last(), quad, 1, 1, 1, 1, light, overlay);
                }
            } finally { poses.popPose(); }
            HomeApplianceClient.drawIdle(tv,poses,buffers);
            PowerIndicatorRenderer.television(tv,facing,poses,buffers,light,overlay);
            // This includes the one static black glass surface only. Live emulator pixels
            // remain exclusively in ArcadeBlockScreenRenderer, slightly in front of it.
        }
    }

    private record CachedTvModel(BakedModel model, java.util.List<BakedQuad> quads) {}

    /** Atlas coordinates -> the full original 1024 skin, also used by downloaded cover composites. */
    private record RawSkinUv(VertexConsumer target, TextureAtlasSprite sprite) implements VertexConsumer {
        @Override public VertexConsumer addVertex(float x, float y, float z) { target.addVertex(x, y, z); return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { target.setColor(r, g, b, a); return this; }
        @Override public VertexConsumer setUv(float u, float v) {
            target.setUv(HomeHardwareRenderLayout.textureCoordinate(u, sprite.getU0(), sprite.getU1()),
                    HomeHardwareRenderLayout.textureCoordinate(v, sprite.getV0(), sprite.getV1()));
            return this;
        }
        @Override public VertexConsumer setUv1(int u, int v) { target.setUv1(u, v); return this; }
        @Override public VertexConsumer setUv2(int u, int v) { target.setUv2(u, v); return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { target.setNormal(x, y, z); return this; }
    }
}
