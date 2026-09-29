// SPDX-License-Identifier: GPL-3.0-or-later
import com.google.gson.Gson;
import cpw.mods.jarhandling.JarContents;
import net.neoforged.fml.loading.moddiscovery.ModFile;
import net.neoforged.fml.loading.moddiscovery.readers.JarModsDotTomlModFileReader;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;

/** Final archive FML discovery, restricted common loading, real item registration and ownership.
 * No Minecraft client/world, native core, socket, ROM or save is created by this probe. */
public final class GbaHandheldCommon3Probe {
    private static final String COMMON_EXTENSION="net.neoforged.neoforge.client.extensions.IMenuProviderExtension";
    private static int assertions;
    private static void check(boolean ok,String message){assertions++;if(!ok)throw new AssertionError(message);}
    private static boolean compatible(Collection<IModInfo> mods){
        var versions=new HashMap<String,String>(Map.of("minecraft","1.21.1","neoforge","21.1.236"));
        for(var mod:mods)if(versions.putIfAbsent(mod.getModId(),mod.getVersion().toString())!=null)return false;
        for(var mod:mods)for(var dependency:mod.getDependencies())if(dependency.getType()==IModInfo.DependencyType.REQUIRED){
            String version=versions.get(dependency.getModId());if(version==null||!dependency.getVersionRange().containsVersion(new DefaultArtifactVersion(version)))return false;
        }return true;
    }
    private static boolean forbidden(String name){
        // NeoForge places this MenuProvider interface in a client-named package, but it
        // is inherited by common Minecraft classes. Its exact bytecode is checked below.
        if(name.equals(COMMON_EXTENSION))return false;
        return name.startsWith("net.minecraft.client.")||name.startsWith("net.neoforged.neoforge.client.")
            ||name.startsWith("cn.piq.fcarcade.client.")||name.startsWith("cn.piq.retro.client.")
            ||name.startsWith("cn.piq.gba.client.")||name.startsWith("cn.piq.gba.bridge.")
            ||name.startsWith("com.sun.jna.")||name.startsWith("org.lwjgl.");
    }
    private static final class CommonLoader extends URLClassLoader {
        final Set<String> attempted=new TreeSet<>(),loaded=new TreeSet<>();
        CommonLoader(URL[] urls){super(urls,ClassLoader.getPlatformClassLoader());}
        @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException{
            if(forbidden(name)){attempted.add(name);throw new ClassNotFoundException("Dedicated-side probe blocks "+name);}
            if(name.startsWith("cn.piq."))loaded.add(name);return super.loadClass(name,resolve);
        }
    }
    private static void origin(Class<?> type,Path path)throws Exception{
        check(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(path),"exact final CodeSource "+type.getName());
    }
    private static void reference(String internal){check(!forbidden(internal.replace('/','.')),"common bytecode may not reference client/bridge/native "+internal);}
    private static void type(Type type){
        if(type.getSort()==Type.OBJECT)reference(type.getInternalName());
        else if(type.getSort()==Type.ARRAY)type(type.getElementType());
        else if(type.getSort()==Type.METHOD){type(type.getReturnType());for(Type parameter:type.getArgumentTypes())type(parameter);}
    }
    private static void constant(Object value){
        if(value instanceof Type t)type(t);
        else if(value instanceof Handle h){reference(h.getOwner());type(Type.getType(h.getDesc()));}
        else if(value instanceof ConstantDynamic d){type(Type.getType(d.getDescriptor()));constant(d.getBootstrapMethod());for(int i=0;i<d.getBootstrapMethodArgumentCount();i++)constant(d.getBootstrapMethodArgument(i));}
    }
    private static void commonBytecode(byte[] bytes){
        ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);reference(node.superName);
        for(var field:node.fields)type(Type.getType(field.desc));
        for(var method:node.methods){type(Type.getMethodType(method.desc));for(var instruction:method.instructions){
            if(instruction instanceof MethodInsnNode call){reference(call.owner);type(Type.getMethodType(call.desc));}
            else if(instruction instanceof FieldInsnNode field){reference(field.owner);type(Type.getType(field.desc));}
            else if(instruction instanceof TypeInsnNode allocation){if(allocation.desc.startsWith("["))type(Type.getType(allocation.desc));else reference(allocation.desc);}
            else if(instruction instanceof LdcInsnNode ldc)constant(ldc.cst);
            else if(instruction instanceof InvokeDynamicInsnNode dynamic){type(Type.getMethodType(dynamic.desc));constant(dynamic.bsm);for(Object arg:dynamic.bsmArgs)constant(arg);}
        }}
    }
    public static void main(String[] args)throws Exception{
        check(args.length>=2,"exact final FC and GBA archives");var fc=Path.of(args[0]).toRealPath();var gba=Path.of(args[1]).toRealPath();
        String expectedVersion=args.length>=3?args[2]:"0.1.0-alpha.3";
        var archives=new ArrayList<Path>(List.of(fc,gba));for(int i=3;i<args.length;i++)archives.add(Path.of(args[i]).toRealPath());
        var contents=new ArrayList<JarContents>();var files=new ArrayList<ModFile>();var groups=new ArrayList<List<IModInfo>>();
        var discovery=new ArrayList<Map<String,Object>>();
        try{
            Set<String> allClasses=new HashSet<>();
            for(Path archive:archives){
                try(var zip=new ZipFile(archive.toFile())){for(var entry:zip.stream().toList())if(entry.getName().endsWith(".class"))check(allClasses.add(entry.getName()),"no duplicate production class "+entry.getName());}
                var content=JarContents.of(archive);contents.add(content);var found=new JarModsDotTomlModFileReader().read(content,ModFileDiscoveryAttributes.DEFAULT);
                check(found instanceof ModFile,"real FML reader");var file=(ModFile)found;files.add(file);check(file.identifyMods(),"real FML identifyMods");
                check(file.getFilePath().toRealPath().equals(archive),"FML exact archive");groups.add(List.copyOf(file.getModInfos()));
                var scan=file.compileContent();Set<String> declared=new TreeSet<>(),scanned=new TreeSet<>();
                for(var info:file.getModInfos())declared.add(info.getModId());
                for(var annotation:scan.getAnnotations())if(annotation.annotationType().getClassName().equals("net.neoforged.fml.common.Mod"))scanned.add(String.valueOf(annotation.annotationData().get("value")));
                check(declared.equals(scanned),"FML annotations agree with declared mods");
                if(archive.equals(gba))for(var annotation:scan.getAnnotations())if(annotation.annotationType().getClassName().equals("net.neoforged.fml.common.EventBusSubscriber")&&annotation.clazz().getClassName().startsWith("cn.piq.gba.client.")){
                    String side=String.valueOf(annotation.annotationData().get("value"));check(side.contains("CLIENT")&&!side.contains("DEDICATED_SERVER"),"every GBA client subscriber is client-only");
                }
                discovery.add(Map.of("jar",archive.toString(),"mod_ids",declared,"scanned_classes",scan.getClasses().size()));
            }
            check(groups.get(0).size()==1&&groups.get(0).getFirst().getModId().equals("piq_fc_arcade"),"FC only");
            check(groups.get(1).size()==1&&groups.get(1).getFirst().getModId().equals("piq_gba"),"GBA only");
            check(groups.get(1).getFirst().getVersion().toString().equals(expectedVersion),"requested handheld version");
            var completeSet=new ArrayList<IModInfo>();for(var group:groups)completeSet.addAll(group);
            check(compatible(completeSet),"all supplied mods have compatible actual FML dependencies");
            check(compatible(groups.get(0)),"FC dependencies");check(!compatible(groups.get(1)),"GBA rejects missing FC");
            var both=new ArrayList<IModInfo>(groups.get(0));both.addAll(groups.get(1));check(compatible(both),"actual Maven dependencies compatible");
            both.add(groups.get(1).getFirst());check(!compatible(both),"duplicate addon rejected");
            var urls=Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator)).map(Path::of).filter(p->!Files.isDirectory(p)).map(p->{try{return p.toUri().toURL();}catch(Exception e){throw new RuntimeException(e);}}).toArray(URL[]::new);
            Set<String> commonLoaded;
            try(var loader=new CommonLoader(urls)){
                ClassNode commonExtension=new ClassNode();
                try(var input=loader.getResourceAsStream(COMMON_EXTENSION.replace('.','/')+".class")){
                    check(input!=null,"actual NeoForge common extension bytes");byte[] bytes=input.readAllBytes();commonBytecode(bytes);new ClassReader(bytes).accept(commonExtension,0);
                }
                check((commonExtension.access&Opcodes.ACC_INTERFACE)!=0&&commonExtension.fields.isEmpty(),"exact common extension interface shape");
                var expectedExtension=Map.of("shouldTriggerClientSideContainerClosingOnOpen()Z",List.of(Opcodes.ICONST_1,Opcodes.IRETURN),
                    "writeClientSideData(Lnet/minecraft/world/inventory/AbstractContainerMenu;Lnet/minecraft/network/RegistryFriendlyByteBuf;)V",List.of(Opcodes.RETURN));
                check(commonExtension.methods.size()==expectedExtension.size(),"only two common extension defaults");
                for(var method:commonExtension.methods){var opcodes=new ArrayList<Integer>();for(var instruction:method.instructions)if(instruction.getOpcode()>=0)opcodes.add(instruction.getOpcode());
                    check(opcodes.equals(expectedExtension.get(method.name+method.desc)),"exact common default signature/instructions "+method.name+method.desc);}
                // DeferredItem now reaches BuiltInRegistries. Bootstrap inside this isolated
                // loader too; the outer process's registries are deliberately not shared.
                Class.forName("net.neoforged.fml.loading.LoadingModList",true,loader)
                    .getMethod("of",List.class,List.class,List.class,List.class,Map.class)
                    .invoke(null,List.of(),List.of(),List.of(),List.of(),Map.of());
                Class.forName("net.minecraft.SharedConstants",true,loader).getMethod("tryDetectVersion").invoke(null);
                Class.forName("net.minecraft.server.Bootstrap",true,loader).getMethod("bootStrap").invoke(null);
                var mod=Class.forName("cn.piq.gba.GbaMod",true,loader);origin(mod,gba);
                var busType=loader.loadClass("net.neoforged.bus.api.IEventBus");var calls=new ArrayList<String>();
                Object bus=java.lang.reflect.Proxy.newProxyInstance(loader,new Class<?>[]{busType},(proxy,method,values)->{calls.add(method.getName());if(method.getReturnType()==boolean.class)return false;if(method.getReturnType()==int.class)return 0;return null;});
                mod.getConstructor(busType).newInstance(bus);check(calls.contains("addListener"),"real common constructor attaches events");
                Object handheld=mod.getField("HANDHELD").get(null);check(handheld!=null,"deferred handheld available without client load");
                check(handheld.getClass().getMethod("getId").invoke(handheld).toString().equals("piq_gba:handheld"),"deferred stable item id");
                mod.getMethod("registerBackend").invoke(null);var registry=Class.forName("cn.piq.fcarcade.cabinet.CabinetBackends",true,loader);origin(registry,fc);
                Object backend=mod.getField("BACKEND").get(null);Object entry=registry.getMethod("find",backend.getClass()).invoke(null,backend);
                check(entry!=null&&!(boolean)entry.getClass().getMethod("localOnly").invoke(entry),"old server cabinet remains enabled");
                check((int)registry.getMethod("maxPlayers",backend.getClass()).invoke(null,backend)==1,"old GBA remains exactly one seat");
                check(loader.attempted.isEmpty(),"common constructor/registration attempts no client/bridge/native loading");commonLoaded=Set.copyOf(loader.loaded);
            }
            try(var zip=new ZipFile(gba.toFile())){
                for(String name:List.of("cn/piq/gba/GbaMod.class","cn/piq/gba/item/GbaHandheldItem.class")){var entry=zip.getEntry(name);check(entry!=null,"common production class exists");commonBytecode(zip.getInputStream(entry).readAllBytes());}
                check(zip.stream().noneMatch(e->e.getName().endsWith(".dll")||e.getName().startsWith("com/sun/jna/")||e.getName().equals("cn/piq/gba/bridge/GbaCore.class")||e.getName().equals("cn/piq/gba/bridge/GbaWorker.class")),"addon never embeds native core or worker");
            }
            var output=System.out;
            net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),Map.of());
            net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
            var bus=net.neoforged.bus.api.BusBuilder.builder().build();new cn.piq.gba.GbaMod(bus);
            var constructor=net.neoforged.neoforge.registries.RegisterEvent.class.getDeclaredConstructor(net.minecraft.resources.ResourceKey.class,net.minecraft.core.Registry.class);constructor.setAccessible(true);
            var registry=net.minecraft.core.registries.BuiltInRegistries.ITEM;((net.minecraft.core.MappedRegistry<?>)registry).unfreeze();
            bus.post(constructor.newInstance(registry.key(),registry));registry.freeze();
            Object deferred=cn.piq.gba.GbaMod.class.getField("HANDHELD").get(null);
            var item=(net.minecraft.world.item.Item)deferred.getClass().getMethod("get").invoke(deferred);origin(item.getClass(),gba);
            check(registry.getKey(item).toString().equals("piq_gba:handheld"),"real registered handheld id");
            var stack=new net.minecraft.world.item.ItemStack(item);check(stack.getCount()==1&&stack.getMaxStackSize()==1,"nonstacking real item");
            var lookup=net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
            var restored=net.minecraft.world.item.ItemStack.parse(lookup,stack.save(lookup)).orElseThrow();
            check(restored.getItem()==item&&restored.getCount()==1,"actual item NBT codec roundtrip");
            var lines=new ArrayList<net.minecraft.network.chat.Component>();item.appendHoverText(stack,net.minecraft.world.item.Item.TooltipContext.EMPTY,lines,net.minecraft.world.item.TooltipFlag.NORMAL);
            check(lines.size()>=2,"actual handheld tooltip");
            Object incumbent=new Object(),handheldOwner=new Object();
            check(cn.piq.fcarcade.client.cabinet.CabinetClientOwner.acquire(incumbent),"incumbent owns shared input");
            check(!cn.piq.fcarcade.client.cabinet.CabinetClientOwner.acquire(handheldOwner),"handheld cannot steal FC/SFC/cabinet input");
            cn.piq.fcarcade.client.cabinet.CabinetClientOwner.release(handheldOwner);
            check(cn.piq.retro.input.InputOwnership.owns(incumbent),"stale handheld release cannot free incumbent");
            cn.piq.fcarcade.client.cabinet.CabinetClientOwner.release(incumbent);
            check(cn.piq.fcarcade.client.cabinet.CabinetClientOwner.acquire(handheldOwner),"handheld may acquire free input");
            cn.piq.fcarcade.client.cabinet.CabinetClientOwner.release(incumbent);
            check(cn.piq.retro.input.InputOwnership.owns(handheldOwner),"stale incumbent cannot free current handheld");
            cn.piq.fcarcade.client.cabinet.CabinetClientOwner.release(handheldOwner);check(!cn.piq.retro.input.InputOwnership.occupied(),"probe releases only its own input");
            var result=new LinkedHashMap<String,Object>();result.put("ok",true);result.put("assertions",assertions);result.put("production_origin","final-jar-only");result.put("actual_fml_reader",true);result.put("actual_annotation_scan",true);result.put("actual_common_registration",true);result.put("actual_item_registry_and_codec",true);result.put("actual_shared_input_ownership",true);result.put("client_and_native_load_attempts",0);result.put("exact_common_package_exception",COMMON_EXTENSION);result.put("exception_signatures_and_instructions_verified",true);result.put("isolated_common_loaded",commonLoaded);result.put("discovery",discovery);result.put("minecraft_world_or_client_started",false);result.put("native_core_started",false);output.println(new Gson().toJson(result));
        }finally{for(var file:files)file.getSecureJar().close();for(var content:contents)content.close();}
    }
}
