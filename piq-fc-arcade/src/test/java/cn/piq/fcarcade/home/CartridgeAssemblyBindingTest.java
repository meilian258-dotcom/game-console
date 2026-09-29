package cn.piq.fcarcade.home;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class CartridgeAssemblyBindingTest {
    private final UUID id=UUID.randomUUID();
    private CartridgeAssemblyBinding request() { return new CartridgeAssemblyBinding(UUID.randomUUID(),3,id,7); }
    @Test void exactServerHandSnapshotCanAuthorizeOnePhysicalItem() {
        assertTrue(request().permits(3,id,7,1,true,true,true,true));
    }
    @Test void wrongSlotCardRevisionCountAndKindAreDenied() {
        var binding=request();
        assertFalse(binding.permits(4,id,7,1,true,true,true,true));
        assertFalse(binding.permits(3,UUID.randomUUID(),7,1,true,true,true,true));
        assertFalse(binding.permits(3,id,8,1,true,true,true,true));
        for(int count:new int[]{0,2,64})assertFalse(binding.permits(3,id,7,count,true,true,true,true));
        assertFalse(binding.permits(3,id,7,1,false,true,true,true));
    }
    @Test void ShiftAliveAndClosedExternalContainerMustAllComeFromServer() {
        var binding=request();
        assertFalse(binding.permits(3,id,7,1,true,false,true,true));
        assertFalse(binding.permits(3,id,7,1,true,true,false,true));
        assertFalse(binding.permits(3,id,7,1,true,true,true,false));
    }
    @Test void legacyMissingIdentityCannotAuthorizeAnUnrelatedUninitializedCard() {
        var binding=new CartridgeAssemblyBinding(UUID.randomUUID(),3,CartridgeAssemblyBinding.ZERO,0);
        assertFalse(binding.permits(3,null,0,1,true,true,true,true));
        assertFalse(binding.permits(3,CartridgeAssemblyBinding.ZERO,0,1,true,true,true,true));
    }
    @Test void oldRequestCannotDisassembleReassembledSameBoardEvenAfterGateEviction() {
        var binding=request();
        assertFalse(binding.permits(3,id,9,1,true,true,true,true));
    }
    @Test void invalidWireBindingsAreRejectedBeforeServiceWork() {
        assertThrows(IllegalArgumentException.class,()->new CartridgeAssemblyBinding(UUID.randomUUID(),9,id,0));
        assertThrows(IllegalArgumentException.class,()->new CartridgeAssemblyBinding(UUID.randomUUID(),0,id,-1));
        assertThrows(IllegalArgumentException.class,()->new CartridgeAssemblyBinding(CartridgeAssemblyBinding.ZERO,0,id,0));
    }
    @Test void replayAndRateGateAreBoundedAndRejectRepeatedRejectedRequests() {
        var gate=new CartridgeAssemblyGate(); UUID first=UUID.randomUUID(),rapid=UUID.randomUUID();
        assertTrue(gate.admit(first,100)); assertFalse(gate.admit(first,200));
        assertFalse(gate.admit(rapid,101)); assertFalse(gate.admit(rapid,300));
        assertTrue(gate.admit(UUID.randomUUID(),104));
        for(int i=0;i<200;i++)gate.admit(UUID.randomUUID(),200+i*4);
        assertEquals(64,gate.remembered());
    }
}
