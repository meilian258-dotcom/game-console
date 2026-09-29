import cn.piq.fcarcade.client.cabinet.CabinetBackend;
import cn.piq.fcarcade.cabinet.CabinetEmulator;
import cn.piq.retro.api.RetroEmulatorFactory;
import com.google.gson.Gson;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Final bytecode only. The bounded branch interpreter is NOT a live Minecraft session. */
public final class PreparedCabinetFactory31Probe implements Opcodes {
    private static final String CLIENT="cn/piq/fcarcade/client/cabinet/CabinetClientBackends";
    private static int assertions,paths;
    private static void check(boolean yes,String why){assertions++;if(!yes)throw new AssertionError(why);}
    private static ClassNode read(Path jar,String name)throws Exception{
        try(var z=new JarFile(jar.toFile());var in=z.getInputStream(z.getJarEntry(name+".class"))){
            var c=new ClassNode();new ClassReader(in).accept(c,0);return c;
        }
    }
    private static MethodNode method(ClassNode c,String name){return c.methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow();}
    private static int call(MethodNode m,String owner,String name){for(int i=0;i<m.instructions.size();i++)if(m.instructions.get(i)instanceof MethodInsnNode x&&x.owner.equals(owner)&&x.name.equals(name))return i;return -1;}
    private static int field(MethodNode m,int opcode,String name){for(int i=0;i<m.instructions.size();i++)if(m.instructions.get(i)instanceof FieldInsnNode x&&x.getOpcode()==opcode&&x.owner.equals(CLIENT)&&x.name.equals(name))return i;return -1;}
    private static AbstractInsnNode nextOp(AbstractInsnNode x){do{x=x.getNext();}while(x!=null&&x.getOpcode()<0);return x;}
    private static void origin(Class<?> type,Path jar)throws Exception{check(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(jar.toRealPath()),"real final jar origin "+type.getName());}
    private static void defaultFactory(Path jar)throws Exception{
        origin(CabinetBackend.class,jar);origin(RetroEmulatorFactory.class,jar);
        int[] opens={0};RetroEmulatorFactory factory=rom->{opens[0]++;throw new AssertionError("must not open");};
        CabinetBackend adapter=new CabinetBackend(){public String description(){return "probe";}public String extensions(){return ".test";}public CabinetEmulator open(Path p){throw new AssertionError("adapter open called");}};
        for(int i=0;i<100;i++)check(adapter.prepareFactory(factory,null,UUID.randomUUID(),null)==factory,"default returns original factory identity");
        check(opens[0]==0,"preparation performs no core open");
        var m=method(read(jar,"cn/piq/fcarcade/client/cabinet/CabinetBackend"),"prepareFactory");
        var op=new ArrayList<AbstractInsnNode>();for(var n:m.instructions)if(n.getOpcode()>=0)op.add(n);
        check(op.size()==2&&op.get(0)instanceof VarInsnNode v&&v.getOpcode()==ALOAD&&v.var==1&&op.get(1).getOpcode()==ARETURN,"default exact identity bytecode");
    }
    private record Outcome(boolean accepted,boolean openingCleared,boolean stopped){}
    private static Object pop(List<Object>s){return s.removeLast();}
    /** Interpret only the actual compiled post-hook guard, stopping before any MC/core side effect. */
    private static Outcome guard(MethodNode m,int start,int hook,int mismatch,boolean exception){
        var original=new HashMap<String,Object>();
        original.put("launch",new Object());original.put("sessionConnection",new Object());original.put("generation",41);
        original.put("backend",new Object());original.put("selectionKey",new Object());original.put("OPENING",new Object());
        String[] dimensions={"launch","sessionConnection","generation","backend","selectionKey"};
        var values=new HashMap<>(original);for(int i=0;i<5;i++)if((mismatch&(1<<i))!=0)values.put(dimensions[i],i==2?42:new Object());
        Object[] locals=new Object[m.maxLocals];
        for(int i=0;i<hook;i++)if(m.instructions.get(i)instanceof FieldInsnNode f&&f.getOpcode()==GETSTATIC&&f.owner.equals(CLIENT)&&original.containsKey(f.name)){
            var n=nextOp(f);if(n instanceof VarInsnNode v&&(v.getOpcode()==ASTORE||v.getOpcode()==ISTORE))locals[v.var]=original.get(f.name);
        }
        var stack=new ArrayList<Object>();stack.add(new Object());boolean cleared=false,stopped=false;int pc=start;
        for(int steps=0;steps<500;steps++,pc++){
            var n=m.instructions.get(pc);int op=n.getOpcode();if(op<0)continue;
            if(n instanceof VarInsnNode v){switch(op){case ALOAD,ILOAD->stack.add(locals[v.var]);case ASTORE,ISTORE->locals[v.var]=pop(stack);default->throw new AssertionError("unknown var opcode "+op);}continue;}
            if(n instanceof LdcInsnNode l){stack.add(l.cst);continue;}
            if(n instanceof TypeInsnNode&&op==CHECKCAST)continue;
            if(n instanceof FieldInsnNode f){
                check(f.owner.equals(CLIENT),"guard accesses only its own lifecycle fields");
                if(op==GETSTATIC){check(values.containsKey(f.name),"known field "+f.name);stack.add(values.get(f.name));continue;}
                if(op==PUTSTATIC&&f.name.equals("generation")){check(!exception,"exception must never start");return new Outcome(true,cleared,stopped);}
                throw new AssertionError("unexpected field write "+f.name);
            }
            if(n instanceof MethodInsnNode c){
                if(c.owner.equals("java/util/Objects")&&c.name.equals("requireNonNull")){pop(stack);Object result=pop(stack);stack.add(result);continue;}
                if(c.owner.equals(CLIENT)&&c.name.equals("current")){stack.add((mismatch&32)==0?1:0);continue;}
                if(c.owner.equals("java/util/concurrent/atomic/AtomicBoolean")&&c.name.equals("set")){check(Objects.equals(pop(stack),0),"invalid preparation only clears opening");pop(stack);cleared=true;continue;}
                if(c.owner.equals(CLIENT)&&c.name.equals("stop")){pop(stack);pop(stack);stopped=true;continue;}
                if(c.name.equals("getMessage")){pop(stack);stack.add("probe failure");continue;}
                throw new AssertionError("unexpected guard call "+c.owner+"."+c.name);
            }
            if(n instanceof InvokeDynamicInsnNode d&&d.name.equals("makeConcatWithConstants")){for(var ignored:Type.getArgumentTypes(d.desc))pop(stack);stack.add("probe notice");continue;}
            if(n instanceof JumpInsnNode j){boolean take=switch(op){
                case GOTO->true;case IFEQ->Objects.equals(pop(stack),0);case IFNE->!Objects.equals(pop(stack),0);
                case IF_ACMPEQ->{Object b=pop(stack),a=pop(stack);yield a==b;}case IF_ACMPNE->{Object b=pop(stack),a=pop(stack);yield a!=b;}
                case IF_ICMPEQ->{Object b=pop(stack),a=pop(stack);yield Objects.equals(a,b);}case IF_ICMPNE->{Object b=pop(stack),a=pop(stack);yield !Objects.equals(a,b);}
                default->throw new AssertionError("unexpected conditional "+op);};if(take)pc=m.instructions.indexOf(j.label)-1;continue;}
            switch(op){case ICONST_0->stack.add(0);case ICONST_1->stack.add(1);case IADD->{int b=(Integer)pop(stack),a=(Integer)pop(stack);stack.add(a+b);}case DUP->stack.add(stack.getLast());case RETURN->{return new Outcome(false,cleared,stopped);}default->throw new AssertionError("unknown guard opcode "+op);}
        }
        throw new AssertionError("guard did not terminate");
    }
    private static void lifecycle(Path jar)throws Exception{
        var c=read(jar,CLIENT);var m=method(c,"startGame");int hook=call(m,"cn/piq/fcarcade/client/cabinet/CabinetBackend","prepareFactory");
        int starter=field(m,GETSTATIC,"STARTER"),begin=field(m,PUTSTATIC,"generation");
        check(hook>=0&&starter>hook&&begin>hook&&begin<starter,"prepare before generation/worker submission");
        check(field(m,PUTSTATIC,"playing")>begin,"playing only after successful preparation");
        int handler=-1;for(var t:m.tryCatchBlocks)if(t.type!=null&&t.type.equals("java/lang/Exception")&&m.instructions.indexOf(t.start)<=hook&&m.instructions.indexOf(t.end)>hook)handler=m.instructions.indexOf(t.handler);
        check(handler>=0,"prepare exception handler present");
        for(int mismatch=0;mismatch<64;mismatch++){
            var out=guard(m,hook+1,hook,mismatch,false);paths++;
            check(out.accepted()==(mismatch==0),"post-hook context mask "+mismatch);
            if(mismatch!=0){check(out.openingCleared(),"canceled start releases opening");check(out.stopped()==((mismatch&7)==0),"never stop replacement launch/connection/generation");}
        }
        for(int mismatch=0;mismatch<32;mismatch++){
            var out=guard(m,handler,hook,mismatch,true);paths++;
            check(!out.accepted()&&out.openingCleared(),"failed preparation cannot queue worker");check(out.stopped()==((mismatch&7)==0),"failed preparation only stops original session");
        }
        var worker=c.methods.stream().filter(x->call(x,"cn/piq/retro/api/RetroEmulatorFactory","open")>=0).findFirst().orElseThrow();
        int opened=call(worker,"cn/piq/retro/api/RetroEmulatorFactory","open");
        check(call(worker,"cn/piq/fcarcade/client/cabinet/CabinetGameSelection","validate")<opened,"worker validates ROM before opening");
        int generationReads=0,closes=0;for(var n:worker.instructions){if(n instanceof FieldInsnNode f&&f.name.equals("generation")&&f.getOpcode()==GETSTATIC)generationReads++;if(n instanceof MethodInsnNode x&&x.owner.endsWith("/CabinetCleanup")&&x.name.equals("closeRetro"))closes++;}
        check(generationReads>=4&&closes>=3,"worker retains before/after open and pending cleanup guards");
        var install=c.methods.stream().filter(x->field(x,PUTSTATIC,"emulator")>=0&&call(x,"cn/piq/retro/api/RetroEmulator","maxPlayers")>=0).findFirst().orElseThrow();
        check(field(install,PUTSTATIC,"emulator")<call(install,"cn/piq/retro/api/RetroEmulator","maxPlayers"),"own opened core before fallible capability check");
        check(call(install,"cn/piq/fcarcade/client/cabinet/CabinetCleanup","closeRetro")>=0&&call(install,CLIENT,"current")>=0,"late game callback validates current and closes stale core");
        var stop=method(c,"stop");check(field(stop,PUTSTATIC,"generation")<field(stop,PUTSTATIC,"launch"),"cancel invalidates generation first");
        check(call(stop,"java/util/concurrent/atomic/AtomicReference","getAndSet")>=0&&call(stop,"cn/piq/fcarcade/client/cabinet/CabinetCleanup","closeRetro")>=0,"cancel drains pending core");
    }
    private static void gba(Path jar)throws Exception{
        var c=read(jar,"cn/piq/gba/client/GbaCabinetBackend");var prepare=method(c,"prepareFactory");var open=method(c,"openScoped");
        check(call(prepare,"net/minecraft/client/Minecraft","isSameThread")>=0&&call(prepare,"net/minecraft/network/Connection","isConnected")>=0,"GBA captures only live game-thread connection");
        check(call(prepare,"cn/piq/fcarcade/client/cabinet/CabinetGameSelection","key")>=0&&call(prepare,"cn/piq/gba/bridge/GbaSaveScope","of")>=0,"GBA validates launch key and captures save scope");
        check(call(prepare,"cn/piq/gba/bridge/GbaProcessSession","<init>")<0,"capture never starts native helper");
        check(call(open,"net/minecraft/client/Minecraft","getInstance")<0,"worker never fetches later MC context");
        check(call(open,"net/minecraft/network/Connection","isConnected")<call(open,"cn/piq/gba/bridge/GbaProcessSession","<init>"),"captured connection checked before process start");
        check(call(open,"cn/piq/gba/client/GbaCabinetBackend","connected")>call(open,"cn/piq/gba/bridge/GbaProcessSession","<init>"),"captured connection rechecked after constructor");
        var launch=read(jar,"cn/piq/gba/client/GbaCabinetBackend$Launch");
        check(launch.recordComponents.stream().map(x->x.descriptor).toList().equals(List.of("Ljava/nio/file/Path;","Ljava/nio/file/Path;","Lnet/minecraft/network/Connection;","Ljava/lang/Thread;")),"immutable launch captures only paths/exact connection/thread");
    }
    public static void main(String[] args)throws Exception{
        Path fc=Path.of(args[0]).toRealPath();defaultFactory(fc);lifecycle(fc);if(args.length>1)gba(Path.of(args[1]).toRealPath());
        System.out.println(new Gson().toJson(Map.of("ok",true,"assertions",assertions,"post_prepare_bytecode_paths",paths,"production_origin","final-jar-only","minecraft_or_native_core_started",false,"guard_execution","bounded interpretation of actual compiled guard; not a Minecraft session")));
    }
}
