package cn.piq.fcarcade.client.watch;

import cn.piq.fcarcade.cabinet.*;
import cn.piq.fcarcade.client.cabinet.CabinetClientBackends;
import cn.piq.fcarcade.client.cabinet.CabinetVideoDisplay;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Both linked cabinets use the same observer texture and one sound source. */
final class CabinetWatchDisplay implements WatchClient.DisplayAdapter {
    static final ResourceLocation PROVIDER = ResourceLocation.fromNamespaceAndPath("piq_fc_arcade", "cabinet");
    static void register() {
        WatchClient.registerDisplay(PROVIDER, new CabinetWatchDisplay());
        WatchClient.registerHost(PROVIDER, CabinetClientBackends::watchDemand);
    }
    private CabinetTarget target(WatchDescriptor d, WatchAnchor anchor) {
        var t = CabinetTarget.resolve(Minecraft.getInstance().level, anchor.pos());
        return t != null && t.identity().equals(anchor.identity()) && t.dimension().equals(d.dimension()) ? t : null;
    }
    @Override public boolean valid(WatchDescriptor d) {
        if (!PROVIDER.equals(d.provider()) || d.link() != null || target(d, d.origin()) == null) return false;
        for (WatchAnchor screen : d.screens()) if (target(d, screen) == null) return false;
        return true;
    }
    @Override public boolean isParticipant(WatchDescriptor d) { return CabinetClientBackends.hasLocalSession(); }
    @Override public int maximumDistance(){return cn.piq.fcarcade.client.cabinet.CabinetClientSettings.rules().exitRange();}
    @Override public boolean visible(RenderLevelStageEvent event,WatchDescriptor d){
        for(var screen:d.screens())if(CabinetVideoDisplay.visible(event.getCamera().getPosition(),target(d,screen)))return true;
        return false;
    }
    @Override public void render(RenderLevelStageEvent event, WatchDescriptor d, ResourceLocation texture, float aspect, int rotation) {
        for (WatchAnchor screen : d.screens()) CabinetVideoDisplay.render(event, target(d, screen), texture, aspect, rotation);
    }
    @Override public float volume(WatchDescriptor d) { return (float)Math.max(0, 1-Math.sqrt(WatchClient.distanceSquared(d))/cn.piq.fcarcade.client.cabinet.CabinetClientSettings.rules().range()); }
}
