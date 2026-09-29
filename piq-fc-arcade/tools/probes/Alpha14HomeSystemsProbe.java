package cn.piq.fcarcade.home;
public final class Alpha14HomeSystemsProbe {
    private static int assertions;
    private static void check(boolean value){assertions++;if(!value)throw new AssertionError("API check "+assertions);}
    private static void rejects(Runnable work){try{work.run();throw new AssertionError("Expected rejection");}catch(IllegalArgumentException|IllegalStateException expected){assertions++;}}
    public static void main(String[] args){
        var registry=new HomeSystemRegistry<String,Object>("nes");Object provider=new Object();
        registry.register("sfc",provider);check(registry.get("sfc")==provider);check(registry.get("missing")==null);
        rejects(()->registry.register("nes",new Object()));rejects(()->registry.register("sfc",new Object()));
        registry.lock();rejects(()->registry.register("other",new Object()));check(registry.get("sfc")==provider);
        var calls=new HomeSystemCalls();Object owner=new Object(),other=new Object();int[] errors={0},runs={0};
        check(calls.invoke(owner,()->runs[0]++,e->errors[0]++));check(runs[0]==1);
        check(!calls.invoke(owner,()->{throw new IllegalStateException();},e->errors[0]++));check(errors[0]==1);
        check(calls.invoke(owner,()->check(!calls.invoke(owner,()->runs[0]++,e->errors[0]++)),e->errors[0]++));
        check(runs[0]==1);check(calls.invoke(owner,()->check(calls.invoke(other,()->runs[0]++,e->errors[0]++)),e->errors[0]++));
        check(runs[0]==2);check(calls.invoke(owner,()->runs[0]++,e->errors[0]++));check(runs[0]==3);
        System.out.println("{\"ok\":true,\"assertions\":"+assertions+",\"source\":\"Final JAR registry and callback classes\"}");
    }
}
