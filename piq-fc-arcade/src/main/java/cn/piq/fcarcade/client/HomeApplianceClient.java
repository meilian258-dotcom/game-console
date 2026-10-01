package cn.piq.fcarcade.client;

import cn.piq.fcarcade.home.*;
import cn.piq.fcarcade.layout.ScreenSurfaceGeometry;
import cn.piq.fcarcade.world.ArcadeStructure;
import cn.piq.fcarcade.world.FcArcadeBlock;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

/** Client presentation never starts sessions or writes device state. */
public final class HomeApplianceClient {
    private static DynamicTexture bars;
    private static ResourceLocation barsId;
    private static ResourceLocation closingId;
    private static ResourceLocation emptyId;
    private HomeApplianceClient() {}
    static void register() {
        NeoForge.EVENT_BUS.addListener(HomeApplianceClient::hint);
        NeoForge.EVENT_BUS.addListener(TelevisionTone::tickAll);
        NeoForge.EVENT_BUS.addListener((GameShuttingDownEvent event) -> {
            TelevisionTone.closeAll();
            PowerIndicatorRenderer.close();
            if (barsId != null) Minecraft.getInstance().getTextureManager().release(barsId);
            if (closingId != null) Minecraft.getInstance().getTextureManager().release(closingId);
            if (emptyId != null) Minecraft.getInstance().getTextureManager().release(emptyId);
            bars = null; barsId = null; closingId = null; emptyId = null;
        });
    }
    static double powerAmount(HomeTvBlockEntity tv) {
        if (tv.getLevel() == null) return tv.powered() ? 1 : 0;
        return tv.powerVisualAmount(TelevisionPowerTransition.presentationTime());
    }
    static void drawIdle(HomeTvBlockEntity tv, PoseStack poses, MultiBufferSource buffers) {
        if (PrivateHomeClient.ownsDisplay(tv)) return;
        double power = powerAmount(tv);
        if (power <= .00001 || tv.signalPresent() || tv.getLevel() == null
                || !(tv.getBlockState().getBlock() instanceof FcArcadeBlock block)) return;
        if (bars == null) {
            bars = new DynamicTexture(NoSignalPattern.WIDTH,NoSignalPattern.HEIGHT,false);
            bars.setFilter(false,false);
            var pixels = bars.getPixels();
            for (int y=0;y<NoSignalPattern.HEIGHT;y++) for(int x=0;x<NoSignalPattern.WIDTH;x++)
                pixels.setPixelRGBA(x,y,NoSignalPattern.pixel(x,y));
            bars.upload(); barsId = Minecraft.getInstance().getTextureManager().register("piq_no_signal",bars);
        }
        if (!tv.powered() && closingId == null) {
            var closing = new DynamicTexture(1, 1, false);
            closing.getPixels().setPixelRGBA(0, 0, 0xff383838);
            closing.upload();
            closingId = Minecraft.getInstance().getTextureManager().register("piq_tv_power_down", closing);
        }
        if (tv.emptyConsolePowered() && emptyId == null) {
            var empty = new DynamicTexture(1, 1, false);
            empty.getPixels().setPixelRGBA(0, 0, 0xff180f0b);
            empty.upload();
            emptyId = Minecraft.getInstance().getTextureManager().register("piq_tv_empty_console", empty);
        }
        var layout = ArcadeStructure.resolve(tv.getLevel(),tv.getBlockPos());
        int turns = switch(layout.facing()) { case EAST -> 1; case SOUTH -> 2; case WEST -> 3; default -> 0; };
        var offset = ScreenSurfaceGeometry.translation(block.displayStyle(),turns,HomeTvStructure.centered(tv.getBlockState()));
        poses.pushPose();
        try {
            poses.translate(offset.x(),offset.y(),offset.z());
            ResourceLocation idleTexture = !tv.powered() ? closingId : tv.emptyConsolePowered() ? emptyId : barsId;
            ArcadeBlockScreenRenderer.drawFace(buffers.getBuffer(RenderType.entityCutoutNoCull(idleTexture)),poses.last(),
                layout.facing(),layout.width(),layout.height(),block.displayStyle(),power);
            if (tv.emptyConsolePowered()) drawEmptyMessage(poses,buffers,block,layout,turns,power);
        } finally { poses.popPose(); }
    }
    private static void drawEmptyMessage(PoseStack poses, MultiBufferSource buffers, FcArcadeBlock block,
                                         ArcadeStructure layout, int turns, double power) {
        if (power < .03) return;
        var quad = ScreenSurfaceGeometry.frame(block.displayStyle(),turns,layout.width(),layout.height(),false,
                cn.piq.fcarcade.layout.ScreenAspectFit.Aspect.FOUR_THREE).image();
        var a = quad.upperMaxX(); var b = quad.upperMinX(); var c = quad.lowerMaxX();
        var right = new org.joml.Vector3f((float)(b.x()-a.x()),(float)(b.y()-a.y()),(float)(b.z()-a.z()));
        var down = new org.joml.Vector3f((float)(c.x()-a.x()),(float)(c.y()-a.y()),(float)(c.z()-a.z()));
        float width = right.length();
        if (width <= 0 || down.lengthSquared() <= 0) return;
        var font = Minecraft.getInstance().font;
        var message = Component.translatable("screen.piq_fc_arcade.no_cartridge");
        float scale = width / Math.max(110,font.width(message)+24);
        float vertical = (float) ArcadeBlockScreenRenderer.powerVertical(block.displayStyle(),power);
        right.normalize().mul(scale); down.normalize().mul(scale*vertical);
        var n = quad.normal();
        float cx = (float)((b.x()+c.x())*.5+n.x()*.002);
        float cy = (float)((b.y()+c.y())*.5+n.y()*.002);
        float cz = (float)((b.z()+c.z())*.5+n.z()*.002);
        var basis = new org.joml.Matrix4f().m00(right.x).m01(right.y).m02(right.z)
                .m10(down.x).m11(down.y).m12(down.z)
                .m20((float)n.x()*scale).m21((float)n.y()*scale).m22((float)n.z()*scale)
                .m30(cx).m31(cy).m32(cz);
        var matrix = new org.joml.Matrix4f(poses.last().pose()).mul(basis);
        int shade = (int)(cn.piq.fcarcade.home.TelevisionPowerTransition.brightness(power)*.85);
        font.drawInBatch(message,-font.width(message)/2f,-font.lineHeight/2f,0xff000000|shade<<16|shade<<8|shade,
                false,matrix,buffers,net.minecraft.client.gui.Font.DisplayMode.NORMAL,0,
                net.minecraft.client.renderer.LightTexture.FULL_BRIGHT);
    }
    private static void hint(RenderGuiEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.screen != null || mc.options.hideGui
                || !(mc.hitResult instanceof BlockHitResult hit)) return;
        var eye = mc.player.getEyePosition();
        var ship = cn.piq.fcarcade.compat.HomeShipSpace.at(mc.level,hit.getBlockPos());
        if (ship == null) return;
        if (ship.ship()) {
            eye = cn.piq.fcarcade.compat.HomeShipSpace.interpolatedEye(mc.player,mc.getTimer().getGameTimeDeltaPartialTick(true));
            if (eye == null) return;
        }
        HomeApplianceControl control = HomeApplianceService.controlAt(mc.level,hit.getBlockPos(),eye,
                eye.add(mc.player.getLookAngle().scale(Math.min(6,mc.player.blockInteractionRange()))),
                mc.getTimer().getGameTimeDeltaPartialTick(true));
        if (control != HomeApplianceControl.POWER && control != HomeApplianceControl.RESET
                && control.port()<0 && control!=HomeApplianceControl.VIDEO_DISCONNECT) return;
        var pos = hit.getBlockPos();
        var endpoint = mc.level.getBlockEntity(pos);
        if (endpoint instanceof HomeTvPartBlockEntity || endpoint instanceof WideLcdTvPartBlockEntity || endpoint instanceof LargeLcdTvPartBlockEntity || endpoint instanceof PanelTvPartBlockEntity) {
            var anchor = HomeTvStructure.resolveAnchor(mc.level,pos);
            endpoint = anchor == null ? null : mc.level.getBlockEntity(anchor);
        } else if (endpoint instanceof SuborPartBlockEntity) {
            var anchor = SuborStructure.resolveAnchor(mc.level,pos);
            endpoint = anchor == null ? null : mc.level.getBlockEntity(anchor);
        }
        boolean powered = endpoint instanceof HomeTvBlockEntity tv ? tv.powered()
                : endpoint instanceof HomeConsoleBlockEntity device ? device.visualPowered()
                : endpoint instanceof ExternalHomeConsoleBlockEntity external && external.visualPowered();
        String label=cn.piq.fcarcade.client.ui.DeviceNoticePolicy.buttonLabel(control.name(),powered);
        if(label==null)label=control.port()>=0?"右键 · "+(control.port()+1)+"P 手柄":"空手右键 · 拔下 AV 线";
        Component message = Component.literal(label);
        var gui = event.getGuiGraphics();
        int w = mc.font.width(message), x = (gui.guiWidth()-w)/2, y = gui.guiHeight()/2+18;
        gui.fill(x-4,y-3,x+w+4,y+mc.font.lineHeight+3,0xa0000000);
        gui.drawString(mc.font,message,x,y,0xffffff,true);
    }
}
