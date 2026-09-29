package cn.piq.fcarcade.client.cabinet;

import java.util.Random;

/** Standalone pure-production-class checks; does not run Minecraft or any emulator. */
public final class Alpha17ImmersiveProbe {
    private static final int[] P1={74,85,259,257,265,264,263,262,75,73,79,80};
    private static int assertions;
    private static void check(boolean ok){assertions++;if(!ok)throw new AssertionError("Immersive input assertion "+assertions);}
    private static int bit(int key){for(int i=0;i<P1.length;i++)if(P1[i]==key)return 1<<i;return 0;}
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Expected final JAR path");
        var finalJar=java.nio.file.Path.of(args[0]).toRealPath();
        for(Class<?> production:new Class<?>[]{CabinetImmersiveInput.class,CabinetClientOwner.class}) {
            var origin=java.nio.file.Path.of(production.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            check(origin.equals(finalJar));
        }
        Object first=new String("equal"),second=new String("equal");
        try {
            boolean rejected=false;
            try{CabinetClientOwner.acquire(null);}catch(IllegalArgumentException expected){rejected=true;}
            check(rejected);
            for(int i=0;i<1000;i++) {
                check(CabinetClientOwner.acquire(first));check(CabinetClientOwner.acquire(first));
                check(!CabinetClientOwner.acquire(second));CabinetClientOwner.release(second);
                check(!CabinetClientOwner.acquire(second));CabinetClientOwner.release(first);
                check(CabinetClientOwner.acquire(second));CabinetClientOwner.release(first);
                check(!CabinetClientOwner.acquire(first));CabinetClientOwner.release(second);
            }
        } finally{CabinetClientOwner.release(first);CabinetClientOwner.release(second);}

        var input=new CabinetImmersiveInput();
        check(input.mask()==0);
        for(int key:P1)check(!input.key(key,1));
        check(!input.activate(false));check(!input.activate(true));
        for(int a=0;a<P1.length;a++)for(int b=0;b<P1.length;b++){
            input.reset();input.activate(true);
            check(input.key(P1[a],1));check(input.mask()==1<<a);
            check(input.key(P1[b],1)==(a!=b));check(input.mask()==((1<<a)|(1<<b)));
            check(!input.key(P1[a],2));
            check(input.key(P1[a],0));check(input.mask()==(a==b?0:1<<b));
            check(input.activate(false));check(input.mask()==0);check(!input.activate(false));
            input.activate(true);check(!input.key(P1[b],2));check(!input.key(P1[b],0));check(input.mask()==0);
        }
        input.reset();input.activate(true);
        for(int key:new int[]{87,83,65,68,70,82,53,50,71,84,89,72,32,340,341,256,-1,999}){
            check(!input.key(key,1));check(!input.key(key,2));check(!input.key(key,0));check(input.mask()==0);
        }
        // Reference uses only a 12-bit integer, independent of the production HashSet.
        var random=new Random(0x50495117L);boolean active=true;int mask=0;
        for(int step=0;step<50000;step++){
            int operation=random.nextInt(8);
            if(operation==0){input.reset();active=false;mask=0;}
            else if(operation<=2){
                boolean next=random.nextBoolean();check(input.activate(next)==(active&&!next));
                active=next;if(!active)mask=0;
            }else{
                int key=random.nextBoolean()?P1[random.nextInt(P1.length)]:random.nextInt(500)-20;
                int action=random.nextInt(6)-1,b=bit(key);
                int next=mask;
                if(active){if(action==0)next&=~b;else if(action==1)next|=b;}
                check(input.key(key,action)==(next!=mask));mask=next;
            }
            check(input.mask()==mask);check((input.mask()&~4095)==0);
        }
        input.reset();check(input.mask()==0);check(!input.key(74,2));
        System.out.println("{\"ok\":true,\"assertions\":"+assertions
                +",\"deterministic_random_events\":50000,\"two_key_orders\":144"
                +",\"production_origin\":\"final-jar-only\",\"minecraft_or_native_core_started\":false"
                +",\"owner_handoff_cycles\":1000,\"scope\":\"pure input and owner only; no Minecraft event-order or game validation\"}");
    }
}
