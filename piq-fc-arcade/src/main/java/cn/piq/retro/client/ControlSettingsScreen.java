package cn.piq.retro.client;

import cn.piq.fcarcade.client.ui.DeviceUi;
import cn.piq.retro.client.KeyboardConfig.Profile;
import cn.piq.retro.client.KeyboardConfig.Preset;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;

/** Shared local keyboard/controller hub. A draft is never live until Save. */
public final class ControlSettingsScreen extends Screen {
    private final Screen parent;
    private final String revision;
    private final KeyboardConfig saved;
    private KeyboardConfig draft;
    private Profile profile;
    private final String deviceLabel;
    private ControlPanelLayout box;
    private ControlHubLayout layout;
    private int captureHotkey; // 1 = input lock, 2 = open settings
    private String message = "只影响自己的控制；不会修改 Minecraft 原键位";

    public ControlSettingsScreen(Screen parent) {
        this(parent,KeyboardInput.settingsProfile(),"");
    }
    public ControlSettingsScreen(Screen parent,Profile initialProfile,String deviceLabel) {
        super(Component.literal("模拟器控制设置")); this.parent = parent;
        this.profile=java.util.Objects.requireNonNull(initialProfile);
        this.deviceLabel=java.util.Objects.requireNonNull(deviceLabel);
        var loaded = KeyboardInput.settingsSnapshot();
        saved = loaded.config(); draft = saved; revision = loaded.revision();
        if (!loaded.warning().isEmpty()) message = loaded.warning();
    }

    @Override protected void init() {
        clearWidgets(); box = ControlPanelLayout.of(width, height); layout = new ControlHubLayout(box);
        int x = box.innerX(), w = box.innerWidth(), half = (w - 6) / 2;
        if (!box.usable()) { button("返回", x, Math.max(22, height - 28), w, this::onClose); return; }
        int tabY = layout.tabs(), tabW = (w - 8) / 3;
        for (Profile p : Profile.values()) {
            button((p == profile ? "[ " : "") + ControlLabels.system(p.name()) + (p == profile ? " ]" : ""),
                    x + p.ordinal() * (tabW + 4), tabY, tabW, () -> { profile = p; captureHotkey = 0; init(); }).active = p != profile;
        }
        Preset preset = draft.bindings(profile).preset();
        button((KeyboardPresentation.pending(saved,draft,profile)?"待保存：":"当前生效：")
                        + KeyboardPresentation.name(preset, profile) + " …", x, layout.presets(), w,
                () -> {captureHotkey=0; minecraft.setScreen(new KeyboardPresetScreen(this,profile,preset,this::selectPreset));})
                .setTooltip(Tooltip.create(Component.literal(schemeDescription(preset))));
        int hotkeyY = layout.hotkeys();
        button("逐项改键 / 自定义…", x, layout.details(), half, this::editBindings);
        button(captureHotkey == 1 ? "请按位置锁快捷键…" : "仅锁移动：" + KeyboardInput.keyName(draft.toggleKey()),
                x, hotkeyY, half, () -> { captureHotkey = 1; message = "按一个键；Esc 取消；不会立刻应用"; init(); })
                .setTooltip(Tooltip.create(Component.literal("默认 N，可在此自定义。它不是屏蔽全部快捷键的开关。\n"
                        +KeyboardPresentation.scopeDescription(preset)+"\n解除只恢复移动，不停用游戏功能键；归还设备后恢复原键位。")));
        button(captureHotkey == 2 ? "请按设置快捷键…" : "设置页：" + KeyboardInput.keyName(draft.settingsKey()),
                x + half + 6, hotkeyY, w - half - 6, () -> { captureHotkey = 2; message = "按一个键；Esc 取消；不会立刻应用"; init(); });
        button("实体手柄设置…", x + half + 6, layout.details(), w-half-6,
                () -> { captureHotkey = 0; minecraft.setScreen(GamepadInput.settings(this,GamepadInput.ProfileKind.valueOf(profile.name()),deviceLabel)); })
                .setTooltip(Tooltip.create(Component.literal("设备选择、连接状态、按键测试、死区与重新映射。手柄设置单独保存。")));
        button("取消", x, box.footer(), half, this::onClose);
        button("保存键盘设置", x + half + 6, box.footer(), w - half - 6, this::save);
    }

    private Button button(String text, int x, int y, int w, Runnable action) {
        return addRenderableWidget(DeviceUi.button(font, text, x, y, w, 20, action, true, DeviceUi.Tone.NORMAL));
    }
    private String schemeDescription(Preset preset){
        String text="正在编辑："+ControlLabels.system(profile.name())+"（与其它机型分别保存）\n"
                +"当前已保存："+KeyboardPresentation.name(saved.bindings(profile).preset(),profile)+"\n"
                +(KeyboardPresentation.pending(saved,draft,profile)?"下方是草稿预览，保存后才生效。\n":"下方是本机型当前生效的实际按键。\n")
                +KeyboardPresentation.description(preset)+"\n"+KeyboardPresentation.scopeDescription(preset);
        if(preset==Preset.LEGACY&&profile==Profile.NES){
            var extras=KeyboardInput.displayLegacyExtraKeys(profile,draft);
            if(extras.size()>=2)text+="\n额外快捷键：重置="+KeyboardInput.keyName(extras.get(0))+"，静音="+KeyboardInput.keyName(extras.get(1));
        }
        return text;
    }
    private String bindingDescription(String label,int bit,int key){
        String text=label+"\n"+(KeyboardPresentation.pending(saved,draft,profile)?"草稿预览，尚未保存":"本机型当前生效按键");
        if(key!=-1){
            var names=new java.util.LinkedHashSet<String>();
            for(var mapping:minecraft.options.keyMappings){
                if(KeyboardInput.legacyKey(mapping)!=key||mapping.getName().startsWith("key.piq_fc_arcade.")
                        ||mapping.getName().startsWith("key.piq_sfc_home."))continue;
                names.add(Component.translatable(mapping.getName()).getString());
            }
            if(!names.isEmpty()){
                text+="\n同键绑定："+String.join("、",names.stream().limit(6).toList())+(names.size()>6?"…":"");
                text+="\n这是已注册绑定列表，不代表冲突未被屏蔽。";
                text+=bit>=4&&bit<=7?"与世界移动重合时，未锁位置只移动人物。":"手持有效设备且本方案生效时，同键的普通世界功能让位于游戏。";
            }
        }
        return text;
    }
    private void selectPreset(Preset chosen) {
        try { draft = draft.withPreset(profile, chosen); captureHotkey = 0; message = KeyboardPresentation.description(chosen); }
        catch (IllegalArgumentException failure) { message = "不能切换：" + failure.getMessage(); }
        init();
    }
    private List<Integer> shownKeys() {
        var selected = draft.bindings(profile);
        if (selected.preset() == Preset.CUSTOM) return selected.customKeys();
        if (selected.preset() == Preset.LEGACY) return KeyboardInput.displayLegacyKeys(profile,draft);
        return KeyboardConfig.presetKeys(profile, selected.preset());
    }
    private void editBindings() {
        captureHotkey = 0;
        minecraft.setScreen(new KeyboardBindingsScreen(this, draft, profile, new ArrayList<>(shownKeys()), value -> {
            draft = value; message = "自定义键位已暂存，点击保存后生效";
        }));
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (captureHotkey != 0) {
            if (key == 256) { captureHotkey = 0; message = "已取消本次改键"; init(); return true; }
            try {
                draft = draft.withHotkeys(captureHotkey == 1 ? key : draft.toggleKey(), captureHotkey == 2 ? key : draft.settingsKey());
                captureHotkey = 0; message = "快捷键已暂存，保存后生效";
            } catch (IllegalArgumentException failure) { message = failure.getMessage(); }
            init(); return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }
    private void save() {
        if (captureHotkey != 0) { message = "请先完成或取消快捷键绑定"; return; }
        try { KeyboardInput.save(draft, revision); onClose(); }
        catch (Exception failure) { message = "未保存：" + failure.getMessage(); }
    }
    @Override public void onClose() { captureHotkey = 0; minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void renderBackground(GuiGraphics g, int mx, int my, float dt) {
        g.fill(0, 0, width, height, DeviceUi.BG);
        DeviceUi.panel(g, font, box.left(), box.top(), box.width(), box.height(),
                (deviceLabel.isEmpty()?"":deviceLabel+" · ")+ControlLabels.system(profile.name())+" · 控制设置（独立保存）", "");
    }
    @Override public void render(GuiGraphics g, int mx, int my, float dt) {
        super.render(g, mx, my, dt);
        if (!box.usable()) { DeviceUi.text(g,font,"请放大窗口或降低 GUI 缩放",box.innerX(),box.top()+30,box.innerWidth(),DeviceUi.TEXT); return; }
        var keys = shownKeys(); String[] names = ControlLabels.nativeButtons(profile.name());
        int[] order = KeyboardPresentation.order(profile);
        String hover = null;
        for (int i=0;i<order.length;i++) {
            int bit=order[i], key=keys.get(bit), x=layout.cellX(i), y=layout.cellY(i);
            String keyName=KeyboardPresentation.shortKey(key);
            if(keyName==null)keyName=KeyboardInput.keyName(key);
            String label=names[bit]+"："+keyName;
            DeviceUi.text(g,font,label,x,y,layout.cellWidth(),DeviceUi.TEXT);
            if(mx>=x&&mx<x+layout.cellWidth()&&my>=y&&my<y+font.lineHeight)hover=bindingDescription(label,bit,key);
        }
        if(hover!=null)g.renderTooltip(font,Component.literal(hover),mx,my);
        DeviceUi.text(g,font,KeyboardPresentation.scopeSummary(),box.innerX(),layout.scopeNotice(),box.innerWidth(),DeviceUi.MUTED);
        DeviceUi.text(g,font,message,box.innerX(),layout.message(),box.innerWidth(),DeviceUi.MUTED);
        if (my >= layout.scopeNotice() && my < layout.scopeNotice()+9 && mx >= box.innerX() && mx < box.innerX()+box.innerWidth())
            g.renderTooltip(font,Component.literal(KeyboardPresentation.scopeDescription(draft.bindings(profile).preset())),mx,my);
        if (my >= layout.message() && my < layout.message()+9 && mx >= box.innerX() && mx < box.innerX()+box.innerWidth())
            g.renderTooltip(font,Component.literal(message),mx,my);
    }
}
