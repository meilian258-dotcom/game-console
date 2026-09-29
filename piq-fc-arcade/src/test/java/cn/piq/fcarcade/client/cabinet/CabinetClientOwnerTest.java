package cn.piq.fcarcade.client.cabinet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CabinetClientOwnerTest {
    @Test void onlyActualOwnerCanReleaseAndLateReleaseCannotStealNewOwner(){
        Object generic=new Object(),legacy=new Object();
        try{assertTrue(CabinetClientOwner.acquire(generic));assertTrue(CabinetClientOwner.acquire(generic));assertFalse(CabinetClientOwner.acquire(legacy));CabinetClientOwner.release(legacy);assertFalse(CabinetClientOwner.acquire(legacy));CabinetClientOwner.release(generic);assertTrue(CabinetClientOwner.acquire(legacy));CabinetClientOwner.release(generic);assertFalse(CabinetClientOwner.acquire(generic));}
        finally{CabinetClientOwner.release(generic);CabinetClientOwner.release(legacy);}
    }
    @Test void rejectsMissingOwner(){assertThrows(IllegalArgumentException.class,()->CabinetClientOwner.acquire(null));}
}
