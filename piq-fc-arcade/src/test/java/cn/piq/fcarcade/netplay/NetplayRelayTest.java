package cn.piq.fcarcade.netplay;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class NetplayRelayTest {
    final Object host=new Object(),player=new Object(),viewer=new Object(),stranger=new Object();
    record Received(Object target,NetplayRelay.Delivery data){}
    final List<Received> messages=new ArrayList<>();
    final NetplayRelay<Object> room=new NetplayRelay<>(68,host,(who,d)->messages.add(new Received(who,d)));
    NetplayChunk c(UUID ticket,int kind,long seq){return new NetplayChunk(68,ticket,kind,seq,kind==NetplayChunk.DATA?new byte[]{3}:new byte[0]);}
    @Test void serverNotClientChoosesRole(){var t=room.grant(viewer,false);room.receive(viewer,c(t.id(),0,0));assertSame(host,messages.getFirst().target);assertFalse(messages.getFirst().data.player());}
    @Test void stolenTicketOnAnotherConnectionFails(){var t=room.grant(player,true);room.receive(stranger,c(t.id(),0,0));assertTrue(messages.isEmpty());}
    @Test void changedRoleRetiresOldCapability(){var t=room.grant(player,true);var changed=room.grant(player,false);assertNotEquals(t.id(),changed.id());messages.clear();room.receive(player,c(t.id(),0,0));assertTrue(messages.isEmpty());room.receive(player,c(changed.id(),0,0));assertFalse(messages.getFirst().data.player());}
    @Test void directionsHaveIndependentOrderedStreams(){var t=room.grant(player,true);room.receive(player,c(t.id(),0,0));room.receive(host,c(t.id(),1,0));room.receive(player,c(t.id(),2,0));room.receive(host,c(t.id(),2,0));assertEquals(4,messages.size());assertSame(host,messages.get(2).target);assertSame(player,messages.get(3).target);}
    @Test void wrongSequenceRetiresStream(){var t=room.grant(player,true);room.receive(player,c(t.id(),0,0));room.receive(player,c(t.id(),2,4));assertEquals(3,messages.size());assertEquals(3,messages.getLast().data.chunk().kind());messages.clear();room.receive(player,c(t.id(),2,0));assertTrue(messages.isEmpty());}
    @Test void authorityRenewalAndCloseRevokePeers(){var p=room.grant(player,true);var v=room.grant(viewer,false);room.renew(Set.of(host,player));assertEquals(2,messages.size());messages.clear();room.receive(viewer,c(v.id(),0,0));assertTrue(messages.isEmpty());room.close();assertEquals(2,messages.size());assertThrows(IllegalStateException.class,()->room.grant(stranger,false));}
    @Test void capacityBounded(){for(int i=0;i<8;i++)assertNotNull(room.grant(new Object(),false));assertNull(room.grant(new Object(),false));assertNotNull(room.grant(host,true));}
    @Test void oversizedTrafficRevokes(){var t=room.grant(player,true);room.receive(player,c(t.id(),0,0));for(int i=0;i<160;i++)room.receive(player,new NetplayChunk(68,t.id(),2,i,new byte[16384]));assertEquals(3,messages.getLast().data.chunk().kind());}
    @Test void payloadBoundsAndDefensiveCopy(){UUID t=UUID.randomUUID();byte[] b={2};var chunk=new NetplayChunk(68,t,2,0,b);b[0]=9;assertEquals(2,chunk.bytes()[0]);chunk.bytes()[0]=8;assertEquals(2,chunk.bytes()[0]);assertThrows(IllegalArgumentException.class,()->new NetplayChunk(68,t,2,0,new byte[16385]));assertThrows(IllegalArgumentException.class,()->new NetplayChunk(68,t,0,0,b));assertThrows(IllegalArgumentException.class,()->new NetplayChunk(68,t,2,-1,b));}
    @Test void nesToRetroPadPreservesEveryButton(){assertEquals(256,NetplayProcess.retroPad(1));assertEquals(1,NetplayProcess.retroPad(2));for(int i=2;i<8;i++)assertEquals(1<<i,NetplayProcess.retroPad(1<<i));}
    @Test void configNeverAutoSavesOrPublishesRoom(){String c=NetplayProcess.config(false,false);assertTrue(c.contains("netplay_start_as_spectator = \"true\""));assertTrue(c.contains("netplay_nat_traversal = \"false\""));assertTrue(c.contains("savestate_auto_save = \"false\""));assertTrue(c.contains("netplay_public_announce = \"false\""));}
}
