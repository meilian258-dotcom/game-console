package cn.piq.retro.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.*;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.loading.FMLPaths;
import java.io.IOException;
import java.util.*;
import java.util.function.*;

/** Main-thread local control router. Hosts retain all leases, packets and emulator queues. */
public final class KeyboardInput {
    public record Sample(int mask,boolean enabled,boolean armed,boolean locked){}
    private static final Map<KeyboardConfig.Profile,Supplier<int[][]>> LEGACY=new EnumMap<>(KeyboardConfig.Profile.class);
    private static final Map<KeyboardConfig.Profile,Supplier<int[]>> EXTRAS=new EnumMap<>(KeyboardConfig.Profile.class);
    private static KeyboardConfigStore.Loaded loaded;
    private static Object owner;private static KeyboardConfig.Profile profile;private static Supplier<int[][]> ownerLegacy;
    private static BooleanSupplier presence,authority;private static Runnable clear,changed,settings,presenceRefresh;
    private static KeyboardControlState state;private static boolean installed,flushing,notifying,refreshing,lastAuthorized;
    private static String status="未操作模拟器";
    private KeyboardInput(){}
    public static void install(){if(installed)return;installed=true;NeoForge.EVENT_BUS.addListener(KeyboardInput::opening);NeoForge.EVENT_BUS.addListener(KeyboardInput::tick);NeoForge.EVENT_BUS.addListener(KeyboardInput::movement);NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST,KeyboardInput::mouse);}
    public static void registerSettings(Runnable opener){settings=Objects.requireNonNull(opener);}
    public static KeyboardConfig.Profile settingsProfile(){refreshPresence();return owner!=null&&profile!=null&&present()?profile:KeyboardConfig.Profile.NES;}
    /** One aggregator refreshes exact held-device identities before the original key handler executes. */
    public static void registerPresenceRefresh(Runnable refresh){presenceRefresh=Objects.requireNonNull(refresh);}
    public static void registerLegacy(KeyboardConfig.Profile p,Supplier<int[][]> keys){LEGACY.put(p,Objects.requireNonNull(keys));}
    public static void registerLegacyExtras(KeyboardConfig.Profile p,Supplier<int[]> keys){EXTRAS.put(p,Objects.requireNonNull(keys));}
    public static int[][] keys(KeyMapping... mappings){int[][] result=new int[mappings.length][];for(int i=0;i<mappings.length;i++)result[i]=new int[]{legacyKey(mappings[i])};return result;}
    /** Negative mouse codes stay local; custom persisted bindings remain keyboard-only. */
    public static int legacyKey(KeyMapping mapping){var key=mapping.getKey();return key.getType()==InputConstants.Type.KEYSYM?key.getValue():key.getType()==InputConstants.Type.MOUSE?-1000-key.getValue():-1;}
    public static List<Integer> displayKeys(KeyboardConfig.Profile p){var result=new ArrayList<Integer>();for(int key:displayLegacyKeys(p))result.add(key<0?-1:key);return List.copyOf(result);}
    /** Actual legacy primaries, independent of the selected preset; mouse codes remain displayable. */
    public static List<Integer> displayLegacyKeys(KeyboardConfig.Profile p){ensureLoaded();return displayLegacyKeys(p,loaded.config());}
    public static List<Integer> displayLegacyKeys(KeyboardConfig.Profile p,KeyboardConfig draft){int[][] keys=effectiveBindings(p,rawOwnerKeys(p),draft);var result=new ArrayList<Integer>();for(int[] row:keys)result.add(row.length==0?-1:row[0]);return List.copyOf(result);}
    public static List<Integer> displayLegacyExtraKeys(KeyboardConfig.Profile p,KeyboardConfig draft){return Arrays.stream(effectiveExtras(p,rawOwnerKeys(p),draft)).boxed().toList();}
    public static List<Integer> displayLegacyExtraKeys(KeyboardConfig.Profile p){ensureLoaded();return displayLegacyExtraKeys(p,loaded.config());}
    public static KeyboardConfigStore.Loaded settingsSnapshot(){ensureLoaded();try{var snapshot=loadLive();String warning=conflictWarning(snapshot.config());return new KeyboardConfigStore.Loaded(snapshot.config(),snapshot.revision(),snapshot.warning()+(snapshot.warning().isEmpty()||warning.isEmpty()?"":"；")+warning);}catch(IOException e){return new KeyboardConfigStore.Loaded(loaded.config(),"unreadable",e.getMessage());}}
    public static void save(KeyboardConfig draft,String revision)throws IOException{if(hotkeyConflicts(draft)!=0)throw new IOException("有效游戏键仍与位置锁/设置键冲突");loaded=KeyboardConfigStore.save(FMLPaths.GAMEDIR.get(),draft,revision);if(owner!=null){clearHostKeys();state.configure(draft.bindings(profile).preset(),bindings(profile));clearHostKeys();flush();notice();}else status="键盘设置已保存";}
    public static String status(){if(owner==null||state==null)return status;if(!present())return "未手持有效设备；已恢复世界按键";if(!focused())return "菜单或窗口未激活；模拟器输入已暂停。"+status;if(!valid())return "已拿起设备：功能键已接管，尚未获得游戏输入授权。"+status;return status;}
    public static String keyName(int key){return key<=-1000?InputConstants.Type.MOUSE.getOrCreate(-1000-key).getDisplayName().getString():key<0?"未绑定":InputConstants.Type.KEYSYM.getOrCreate(key).getDisplayName().getString();}
    /** No second global InputOwnership acquire. The host supplies its existing exact authorization. */
    public static boolean attach(Object token,KeyboardConfig.Profile p,Supplier<int[][]> keys,BooleanSupplier authorized,Runnable forceRelease,Runnable edge){
        return attach(token,p,keys,authorized,authorized,forceRelease,edge);
    }
    /** Physical presence is capture-only; it does not acquire a runtime lease or authorize any packet. */
    public static boolean attach(Object token,KeyboardConfig.Profile p,Supplier<int[][]> keys,BooleanSupplier physicalPresent,BooleanSupplier authorized,Runnable forceRelease,Runnable edge){
        Objects.requireNonNull(token);Objects.requireNonNull(p);Objects.requireNonNull(physicalPresent);Objects.requireNonNull(authorized);Objects.requireNonNull(keys);ensureLoaded();install();
        if(!test(physicalPresent)){if(owner==token)release(token);return false;}
        boolean incomingAuthorized=test(authorized);
        if(owner!=null&&owner!=token){if(!KeyboardRouting.mayReplace(present(),valid(),true,incomingAuthorized))return false;release(owner);if(owner!=null)return false;}
        if(owner==token){presence=physicalPresent;authority=authorized;ownerLegacy=keys;clear=forceRelease;changed=edge;return synchronizePermission(token);}
        owner=token;profile=p;ownerLegacy=keys;presence=physicalPresent;authority=authorized;lastAuthorized=incomingAuthorized;clear=forceRelease;changed=edge;state=new KeyboardControlState(loaded.config().bindings(p).preset(),bindings(p));
        clearHostKeys();flush();if(owner!=token||state==null)return false;notice();return true;
    }
    public static Sample poll(Object token,int legacyMask,boolean active){
        refreshPresence();
        if(token!=owner||state==null)return new Sample(0,false,false,false);
        if(!synchronizePermission(token))return new Sample(0,false,false,false);
        if(state.bindings(bindings(profile)))flush();
        if(token!=owner||state==null)return new Sample(0,false,false,false);
        boolean allowed=active&&valid()&&focused();
        if(!state.armed()&&legacyExtraHeld())allowed=false;
        if(state.activate(allowed,KeyboardInput::physical,KeyboardInput::movementKey))flush();
        if(token!=owner||state==null)return new Sample(0,false,false,false);
        if(present()&&focused())clearHostKeys();
        return new Sample(state.mask(loaded.config().bindings(profile).preset()==KeyboardConfig.Preset.LEGACY?legacyMask:0,KeyboardInput::movementKey),state.enabled(),state.armed(),state.mode()==KeyboardControlState.Mode.LOCKED);
    }
    public static void pause(Object token){if(token==owner&&state!=null){clearHostKeys();if(state.pause())flush();}}
    public static void release(Object token){if(token!=owner)return;pause(token);if(owner!=token)return;owner=null;profile=null;ownerLegacy=null;presence=null;authority=null;lastAuthorized=false;clear=null;changed=null;state=null;status="未操作模拟器";}
    /** Legacy auxiliary Reset/Mute: sampled directly; never edits a KeyMapping binding. */
    public static boolean down(Object token,KeyMapping mapping){if(token!=owner||state==null||!state.armed()||!present()||!valid()||!focused()||loaded.config().bindings(profile).preset()!=KeyboardConfig.Preset.LEGACY)return false;int key=legacyKey(mapping);int[] raw=rawExtras(profile),effective=effectiveExtras(profile,rawOwnerKeys(profile),loaded.config());for(int i=0;i<raw.length;i++)if(raw[i]==key)return physical(effective[i]);return false;}
    /** Called only at KeyboardHandler HEAD, before vanilla key clicks/open-screen actions. */
    public static boolean intercept(long window,int key,int scan,int action,int mods){
        var mc=Minecraft.getInstance();if(window!=mc.getWindow().getWindow())return false;
        refreshPresence();
        if(!focused()){if(owner!=null)pause(owner);return false;}
        if(key==256)return false;
        ensureLoaded();var config=loaded.config();
        if(owner!=null)synchronizePermission(owner);
        boolean owned=owner!=null&&state!=null,held=owned&&present();
        int conflicts=hotkeyConflicts(config);
        boolean movement=movementKey(key,scan);
        var route=KeyboardRouting.route(key,settings==null||(conflicts&2)!=0?-1:config.settingsKey(),(conflicts&1)!=0?-1:config.toggleKey(),true,owned,held,
                owned?state.mode():null,owned&&(state.gameKey(key)||extra(key)),owned&&state.directionOnly(key)&&!extra(key),movement);
        if(route==KeyboardRouting.Route.SETTINGS){clearKey(key,scan);if(action==1){if(owner!=null)pause(owner);settings.run();}return true;}
        if(route==KeyboardRouting.Route.TOGGLE){
            clearKey(key,scan);
            if(action==1){clearHostKeys();state.toggle();clearHostKeys();flush();notice();signal();}
            return true;
        }
        if(route==KeyboardRouting.Route.GAME){state.key(key,action,movement);clearKey(key,scan);if(valid()&&state.armed())signal();return true;}
        if(route==KeyboardRouting.Route.MOVEMENT||route==KeyboardRouting.Route.WORLD){clearKey(key,scan);clearHostKeys();return true;}
        return false;
    }
    private static void signal(){if(notifying||changed==null)return;notifying=true;try{changed.run();}finally{notifying=false;}}
    /** Before vanilla MouseHandler clicks; earlier cancellations (notably the lightgun) retain priority. */
    private static void mouse(InputEvent.MouseButton.Pre event){
        if(event.isCanceled())return;
        refreshPresence();
        if(!focused()){if(owner!=null)pause(owner);return;}
        if(owner==null||!synchronizePermission(owner)||state==null||!present())return;
        int button=event.getButton(),action=event.getAction();
        if(button<0||button>7||(action!=0&&action!=1))return;
        int key=-1000-button;boolean movement=movementKey(key);
        if(!state.captures(key,movement)&&!extra(key))return;
        event.setCanceled(true);
        state.key(key,action,movement);clearMouse(button);
        if(valid()&&state.armed())signal();
    }
    private static void flush(){if(flushing||clear==null)return;flushing=true;try{clear.run();}finally{flushing=false;}}
    private static boolean test(BooleanSupplier supplier){try{return supplier!=null&&supplier.getAsBoolean();}catch(RuntimeException|LinkageError ignored){return false;}}
    private static boolean valid(){return test(authority);}
    private static boolean present(){return test(presence);}
    private static void refreshPresence(){if(refreshing||presenceRefresh==null)return;refreshing=true;try{presenceRefresh.run();}catch(RuntimeException|LinkageError ignored){if(owner!=null)pause(owner);}finally{refreshing=false;}}
    private static boolean synchronizePermission(Object token){
        if(token!=owner||state==null)return false;
        if(!present()){release(token);return false;}
        boolean authorized=valid();if(authorized!=lastAuthorized){lastAuthorized=authorized;clearHostKeys();state.pause();flush();}
        return owner==token&&state!=null;
    }
    private static boolean focused(){var mc=Minecraft.getInstance();return mc.player!=null&&mc.screen==null&&mc.isWindowActive()&&!mc.isPaused();}
    private static boolean physical(int key){long window=Minecraft.getInstance().getWindow().getWindow();return key>=0?InputConstants.isKeyDown(window,key):key<=-1000&&org.lwjgl.glfw.GLFW.glfwGetMouseButton(window,-1000-key)==org.lwjgl.glfw.GLFW.GLFW_PRESS;}
    private static int[][] legacy(KeyboardConfig.Profile p){var supplier=LEGACY.get(p);return supplier==null?KeyboardConfig.presetKeys(p,KeyboardConfig.Preset.LEGACY).stream().map(k->new int[]{k}).toArray(int[][]::new):supplier.get();}
    private static int hotkeyConflicts(KeyboardConfig config){var keys=new EnumMap<KeyboardConfig.Profile,int[][]>(KeyboardConfig.Profile.class);var extras=new EnumMap<KeyboardConfig.Profile,int[]>(KeyboardConfig.Profile.class);for(var p:KeyboardConfig.Profile.values()){keys.put(p,legacy(p));var extra=EXTRAS.get(p);if(extra!=null)extras.put(p,extra.get());}int result=KeyboardConfig.legacyHotkeyConflicts(config,keys,extras);if(owner!=null&&ownerLegacy!=null){keys.put(profile,ownerLegacy.get());result|=KeyboardConfig.legacyHotkeyConflicts(config,keys,extras);}return result;}
    private static String conflictWarning(KeyboardConfig config){for(var p:KeyboardConfig.Profile.values())if(config.bindings(p).preset()==KeyboardConfig.Preset.LEGACY){int[][] raw=rawOwnerKeys(p);if(!Arrays.deepEquals(raw,effectiveBindings(p,raw,config))||!Arrays.equals(rawExtras(p),effectiveExtras(p,raw,config)))return "旧绑定与位置锁/设置键冲突的部分已仅在模拟器内替换；请查看有效按键，Minecraft 绑定未修改";}return "";}
    private static int[][] rawOwnerKeys(KeyboardConfig.Profile p){return owner!=null&&profile==p&&ownerLegacy!=null?ownerLegacy.get():legacy(p);}
    private static int[] rawExtras(KeyboardConfig.Profile p){var supplier=EXTRAS.get(p);return supplier==null?new int[0]:supplier.get();}
    private static int[][] effectiveBindings(KeyboardConfig.Profile p,int[][] raw,KeyboardConfig draft){return KeyboardConfig.effectiveLegacyKeys(p,raw,rawExtras(p),draft.toggleKey(),draft.settingsKey());}
    private static int[] effectiveExtras(KeyboardConfig.Profile p,int[][] raw,KeyboardConfig draft){return KeyboardConfig.effectiveLegacyExtras(p,raw,rawExtras(p),draft.toggleKey(),draft.settingsKey());}
    private static int[][] bindings(KeyboardConfig.Profile p){var b=loaded.config().bindings(p);return b.preset()==KeyboardConfig.Preset.LEGACY?effectiveBindings(p,rawOwnerKeys(p),loaded.config()):(b.preset()==KeyboardConfig.Preset.CUSTOM?b.customKeys():KeyboardConfig.presetKeys(p,b.preset())).stream().map(k->new int[]{k}).toArray(int[][]::new);}
    private static boolean extra(int key){if(loaded.config().bindings(profile).preset()!=KeyboardConfig.Preset.LEGACY)return false;for(int k:effectiveExtras(profile,rawOwnerKeys(profile),loaded.config()))if((k>=0||k<=-1000)&&key==k)return true;return false;}
    private static boolean legacyExtraHeld(){if(loaded.config().bindings(profile).preset()==KeyboardConfig.Preset.LEGACY)for(int key:effectiveExtras(profile,rawOwnerKeys(profile),loaded.config()))if(physical(key))return true;return false;}
    private static KeyMapping[] movementKeys(){var o=Minecraft.getInstance().options;return new KeyMapping[]{o.keyUp,o.keyDown,o.keyLeft,o.keyRight,o.keyJump,o.keyShift,o.keySprint};}
    private static boolean movementKey(int key,int scan){for(var mapping:movementKeys())if(mapping.matches(key,scan))return true;return false;}
    private static boolean movementKey(int key){if(key>=0)return movementKey(key,0);if(key<=-1000)for(var mapping:movementKeys())if(mapping.matchesMouse(-1000-key))return true;return false;}
    private static void drain(KeyMapping key){KeyboardMappingState.clear(key);}
    private static void clearKey(int key,int scan){for(var mapping:Minecraft.getInstance().options.keyMappings)if(mapping.matches(key,scan))drain(mapping);}
    private static void clearMouse(int button){var mc=Minecraft.getInstance();for(var mapping:mc.options.keyMappings)if(mapping.matchesMouse(button))drain(mapping);if(mc.options.keyAttack.matchesMouse(button)&&mc.gameMode!=null)mc.gameMode.stopDestroyBlock();}
    private static void clearHostKeys(){
        if(state==null)return;
        var mappings=Minecraft.getInstance().options.keyMappings;
        KeyboardMappingState.clearSelected(mappings,mapping->{int key=legacyKey(mapping);return key!=-1&&(state.captures(key,movementKey(key))||extra(key));});
        if(state.mode()==KeyboardControlState.Mode.LOCKED)clearMovement();
    }
    private static void clearMovement(){var mc=Minecraft.getInstance();for(var key:movementKeys())drain(key);if(mc.player!=null){zero(mc.player.input);mc.player.setSprinting(false);}}
    private static void zero(net.minecraft.client.player.Input input){input.leftImpulse=0;input.forwardImpulse=0;input.up=input.down=input.left=input.right=input.jumping=input.shiftKeyDown=false;}
    private static void movement(MovementInputUpdateEvent e){if(owner!=null&&state!=null&&state.mode()==KeyboardControlState.Mode.LOCKED&&present()&&focused()){zero(e.getInput());clearHostKeys();}}
    private static void opening(ScreenEvent.Opening e){if(e.getNewScreen()!=null&&owner!=null)pause(owner);}
    private static void tick(ClientTickEvent.Pre e){refreshPresence();if(owner==null)return;Object token=owner;if(!synchronizePermission(token))return;if(!focused())pause(token);else {if(!valid())pause(token);clearHostKeys();}}
    private static void notice(){status=state.status(keyName(loaded.config().toggleKey()));if(!loaded.warning().isEmpty())status+="；"+loaded.warning();String warning=conflictWarning(loaded.config());if(!warning.isEmpty())status+="；"+warning;}
    private static KeyboardConfigStore.Loaded loadLive()throws IOException{
        var keys=new EnumMap<KeyboardConfig.Profile,int[][]>(KeyboardConfig.Profile.class);var extras=new EnumMap<KeyboardConfig.Profile,int[]>(KeyboardConfig.Profile.class);
        for(var p:KeyboardConfig.Profile.values()){keys.put(p,rawOwnerKeys(p));extras.put(p,rawExtras(p));}
        return KeyboardConfigStore.load(FMLPaths.GAMEDIR.get(),keys,extras);
    }
    private static void ensureLoaded(){if(loaded!=null)return;try{loaded=loadLive();}catch(IOException e){loaded=new KeyboardConfigStore.Loaded(KeyboardConfig.defaults(),"unreadable","键盘配置不可读，暂用默认布局；手持设备自动接管游戏功能键");}}
}
