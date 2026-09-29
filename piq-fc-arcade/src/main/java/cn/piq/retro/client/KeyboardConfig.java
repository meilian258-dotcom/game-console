package cn.piq.retro.client;

import java.util.*;

/** Local keyboard choices. Masks never change the emulator's native bit order. */
public record KeyboardConfig(Map<Profile,Bindings> profiles,int toggleKey,int settingsKey) {
    public static final int DEFAULT_TOGGLE_KEY=78; // N; Z/X remain available to emulator buttons.
    public static final int DEFAULT_SETTINGS_KEY=296; // F7
    public enum Profile { NES(8),SFC(12),ARCADE(12); public final int bits;Profile(int bits){this.bits=bits;} }
    public enum Preset { LEGACY,NUMPAD,WASD,CUSTOM,CLASSIC }
    public record Bindings(Preset preset,List<Integer> customKeys) {
        public Bindings { Objects.requireNonNull(preset);customKeys=List.copyOf(customKeys); }
    }
    public KeyboardConfig {
        profiles=Map.copyOf(profiles);
        if(profiles.size()!=Profile.values().length||!validKey(toggleKey)||toggleKey<0||!validKey(settingsKey)||settingsKey<0||toggleKey==settingsKey)
            throw new IllegalArgumentException("切换键和设置键必须有效、不同，且不能为 Esc");
        for(Profile p:Profile.values()) {
            var b=Objects.requireNonNull(profiles.get(p));
            if(b.customKeys().size()!=p.bits)throw new IllegalArgumentException("按键数量与模拟器不符");
            var seen=new HashSet<Integer>();
            for(int key:b.customKeys())if(!validKey(key)||(key>=0&&(!seen.add(key)||(b.preset()==Preset.CUSTOM&&(key==toggleKey||key==settingsKey)))))
                throw new IllegalArgumentException("自定义按键重复、无效或与切换/设置键冲突");
            // Fixed/custom presets reject collisions; LEGACY is checked against live mappings by the facade.
            if(b.preset()!=Preset.CUSTOM&&b.preset()!=Preset.LEGACY)for(int key:presetKeys(p,b.preset()))if(key==toggleKey||key==settingsKey)
                throw new IllegalArgumentException("切换/设置键与当前预设冲突");
        }
    }
    public Bindings bindings(Profile profile){return profiles.get(profile);}
    public KeyboardConfig withPreset(Profile p,Preset preset){var map=new EnumMap<Profile,Bindings>(Profile.class);map.putAll(profiles);map.put(p,new Bindings(preset,bindings(p).customKeys()));return new KeyboardConfig(map,toggleKey,settingsKey);}
    public KeyboardConfig withCustom(Profile p,List<Integer> keys){var map=new EnumMap<Profile,Bindings>(Profile.class);map.putAll(profiles);map.put(p,new Bindings(Preset.CUSTOM,keys));return new KeyboardConfig(map,toggleKey,settingsKey);}
    public KeyboardConfig withHotkeys(int toggle,int settings){return new KeyboardConfig(profiles,toggle,settings);}
    public static KeyboardConfig defaults(){var map=new EnumMap<Profile,Bindings>(Profile.class);for(Profile p:Profile.values())map.put(p,new Bindings(Preset.WASD,presetKeys(p,Preset.WASD)));return new KeyboardConfig(map,DEFAULT_TOGGLE_KEY,DEFAULT_SETTINGS_KEY);}
    /** Bit 0 = toggle conflict, bit 1 = settings conflict; legacy keys are supplied live, including aliases. */
    public static int legacyHotkeyConflicts(KeyboardConfig config,Map<Profile,int[][]> keys,Map<Profile,int[]> extras){
        int result=0;for(Profile p:Profile.values())if(config.bindings(p).preset()==Preset.LEGACY){
            int[][] raw=keys.getOrDefault(p,new int[0][]);int[] extra=extras.getOrDefault(p,new int[0]);
            for(int[] row:effectiveLegacyKeys(p,raw,extra,config.toggleKey(),config.settingsKey()))for(int key:row)result|=conflict(config,key);
            for(int key:effectiveLegacyExtras(p,raw,extra,config.toggleKey(),config.settingsKey()))result|=conflict(config,key);
        }return result;
    }
    private static int conflict(KeyboardConfig c,int key){return key<0?0:(key==c.toggleKey()?1:0)|(key==c.settingsKey()?2:0);}
    private record Effective(int[][] keys,int[] extras){}
    /** Local overlay only: never mutates Minecraft's registered mappings or the supplied arrays. */
    public static int[][] effectiveLegacyKeys(Profile p,int[][] raw,int[] extras,int toggle,int settings){return effectiveLegacy(p,raw,extras,toggle,settings).keys();}
    public static int[] effectiveLegacyExtras(Profile p,int[][] raw,int[] extras,int toggle,int settings){return effectiveLegacy(p,raw,extras,toggle,settings).extras();}
    private static Effective effectiveLegacy(Profile p,int[][] raw,int[] extras,int toggle,int settings){
        Objects.requireNonNull(p);var used=new HashSet<Integer>();used.add(toggle);used.add(settings);
        for(int[] row:raw)if(row!=null)for(int key:row)used.add(key);
        for(int key:extras)used.add(key);
        int[][] result=new int[raw.length][];
        for(int bit=0;bit<raw.length;bit++){
            int[] row=raw[bit]==null?new int[0]:raw[bit];
            boolean collision=Arrays.stream(row).anyMatch(k->k==toggle||k==settings);
            int[] kept=Arrays.stream(row).filter(k->k!=toggle&&k!=settings).toArray();
            boolean hasBinding=Arrays.stream(kept).anyMatch(k->k!=-1);
            result[bit]=collision&&!hasBinding?new int[]{reserveFallback(used)}:kept;
        }
        int[] extra=extras.clone();for(int i=0;i<extra.length;i++)if(extra[i]==toggle||extra[i]==settings)extra[i]=reserveFallback(used);
        return new Effective(result,extra);
    }
    static int reserveFallback(Set<Integer> used){
        // Deliberately avoid WASD, sneak, sprint and Esc for migrated function buttons.
        for(int key:new int[]{74,75,76,73,79,80,88,67,86,66,78,77,85,72,71,70,82,84,89,91,93,290,291,292,293,294,295,298,299,300,301})
            if(used.add(key))return key;
        return -1; // All safe keys explicitly occupied: show Unbound, never steal another binding.
    }
    /** -1 is unbound. Only standard GLFW keyboard codes are persisted; Esc remains Minecraft's. */
    public static boolean validKey(int key){return key==-1||key==32||key==39||(key>=44&&key<=57)||key==59||key==61
            ||(key>=65&&key<=93)||key==96||key==161||key==162||(key>=257&&key<=269)
            ||(key>=280&&key<=284)||(key>=290&&key<=314)||(key>=320&&key<=336)||(key>=340&&key<=348);}
    public static List<Integer> presetKeys(Profile p,Preset preset){
        // Direction-key alternative to the default WASD layout. N is reserved for
        // movement lock; no function button occupies a world movement key.
        if(preset==Preset.CLASSIC)return switch(p){
            case NES -> List.of(75,74,259,257,265,264,263,262);
            case SFC -> List.of(74,76,259,257,265,264,263,262,75,73,79,80);
            // Arcade native buttons are numbered, not SNES B/Y/A/X. Keep two
            // adjacent three-key rows; never silently reinterpret them as a pad.
            case ARCADE -> List.of(74,75,259,257,265,264,263,262,76,73,79,80);
        };
        boolean pad=preset==Preset.NUMPAD,wasd=preset==Preset.WASD;
        int up=wasd?87:265,down=wasd?83:264,left=wasd?65:263,right=wasd?68:262;
        int a=pad?322:75,b=pad?321:74,y=pad?323:wasd?76:85,x=pad?324:73,l=pad?325:79,r=pad?326:80;
        if(p==Profile.ARCADE&&preset!=Preset.LEGACY)return List.of(b,a,259,257,up,down,left,right,y,x,l,r);
        return p==Profile.NES?List.of(a,b,259,257,up,down,left,right):List.of(b,y,259,257,up,down,left,right,a,x,l,r);
    }
}
