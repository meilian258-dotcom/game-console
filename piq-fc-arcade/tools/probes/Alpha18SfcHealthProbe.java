package cn.piq.sfchome.server;
import java.nio.file.Path;
/** Actual final-JAR P1/P2 watchdog isolation and original rate-budget regression. */
public final class Alpha18SfcHealthProbe {
    private static int assertions;
    private static void check(boolean value){assertions++;if(!value)throw new AssertionError("Packaged health check "+assertions);}
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("Exact final SFC JAR required");Path jar=Path.of(args[0]).toRealPath();
        check(Path.of(SfcInputHealth.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(jar));
        for(int seed=0;seed<128;seed++){
            long base=seed*1000L;var health=new SfcInputHealth();health.start(base);
            check(!health.expired(base,true));check(!health.expiredPort(0,base+100));check(health.expiredPort(0,base+101));
            check(!health.packet(-1,base));check(!health.packet(2,base));check(!health.packet(0,base-1));
            for(int port=0;port<2;port++){
                for(int i=0;i<64;i++)check(health.packet(port,base+1));
                check(!health.packet(port,base+1));check(health.packet(port,base+2));
            }
            for(int elapsed=10;elapsed<=200;elapsed+=10){
                check(health.packet(0,base+elapsed));check(!health.expiredPort(0,base+elapsed));
                check(health.expiredPort(1,base+elapsed)==(elapsed>102));check(!health.expired(base+elapsed,false));
                check(health.expired(base+elapsed,true)==(elapsed>102));
            }
            health.start(base+300);check(!health.expiredPort(0,base+300));check(!health.expiredPort(1,base+300));
            for(int i=0;i<64;i++)check(health.packet(1,base+300));check(!health.packet(1,base+300));check(health.packet(0,base+300));
            for(int invalid:new int[]{-1,2,Integer.MIN_VALUE,Integer.MAX_VALUE}){
                boolean rejected=false;try{health.expiredPort(invalid,base+300);}catch(IllegalArgumentException expected){rejected=true;}check(rejected);
            }
        }
        System.out.println("{\"ok\":true,\"assertions\":"+assertions+",\"production_origin\":\"final-jar-only\",\"minecraft_or_native_core_started\":false}");
    }
}
