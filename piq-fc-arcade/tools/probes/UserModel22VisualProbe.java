import cn.piq.fcarcade.client.cabinet.CabinetPeerInputs;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

/** Real final class; proves the rendering projection never consumes/edits seat input. */
public final class UserModel22VisualProbe {
    static int assertions;
    static void check(boolean ok){assertions++;if(!ok)throw new AssertionError("assertion "+assertions);}
    static void pair(int[] actual,int a,int b){check(Arrays.equals(actual,new int[]{a,b}));}
    public static void main(String[]args)throws Exception{
        check(Path.of(CabinetPeerInputs.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(Path.of(args[0]).toRealPath()));
        var p=new CabinetPeerInputs();UUID[] members={UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID()};
        for(int i=0;i<4;i++){check(p.seat(members[i],i,true));check(p.input(members[i],i,1,1<<i));}
        for(int port=0;port<4;port++)for(int first:new int[]{0,2}){
            var guest=p.visualPair(first,port,256,false);pair(guest,port==first?256:0,port==first+1?256:0);
            var host=p.visualPair(first,port,256,true);pair(host,port==first?256:1<<first,port==first+1?256:1<<(first+1));
            host[0]=4095;guest[1]=4095;for(int i=0;i<4;i++)check(p.mask(i)==1<<i);
        }
        for(int first:new int[]{-1,1,3,4})pair(p.visualPair(first,0,1,true),0,0);
        pair(p.visualPair(0,-1,1,true),0,0);pair(p.visualPair(0,4,1,true),0,0);pair(p.visualPair(0,0,4096,true),0,0);pair(p.visualPair(0,0,-1,true),0,0);
        for(int i=0;i<4;i++){check(!p.input(members[i],i,1,128));check(p.input(members[i],i,2,1<<i));}
        check(p.seat(members[1],1,false));pair(p.visualPair(0,0,1,true),1,0);check(!p.input(members[1],1,2,2));
        UUID replacement=UUID.randomUUID();check(p.seat(replacement,1,true));pair(p.visualPair(0,0,1,true),1,0);
        check(!p.input(members[1],1,99,2048));check(p.input(replacement,1,0,32));pair(p.visualPair(0,0,1,true),1,32);
        System.out.println("{\"ok\":true,\"assertions\":"+assertions+",\"production_origin\":\"final-jar-only\",\"projection_does_not_change_masks_or_consume_sequences\":true,\"minecraft_or_native_core_started\":false}");
    }
}
