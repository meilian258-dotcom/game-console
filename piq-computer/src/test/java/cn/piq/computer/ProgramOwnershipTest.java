package cn.piq.computer;
import cn.piq.computer.client.ComputerPrograms;
import cn.piq.computer.net.ComputerNetwork.Open;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProgramOwnershipTest {
    @Test void localProgramNeverAcceptsOtherComputersInput()throws Exception{
        var target=ComputerPrograms.class.getDeclaredField("target");target.setAccessible(true);
        var loading=ComputerPrograms.class.getDeclaredField("loading");loading.setAccessible(true);
        var oldTarget=target.get(null);var oldLoading=loading.get(null);UUID id=UUID.randomUUID();
        try{target.set(null,new Open(ResourceLocation.parse("minecraft:overworld"),BlockPos.ZERO,id,true,UUID.randomUUID()));loading.set(null,new CompletableFuture<>());
            assertTrue(ComputerPrograms.localInput(id));assertFalse(ComputerPrograms.localInput(UUID.randomUUID()));
            loading.set(null,null);assertFalse(ComputerPrograms.localInput(id));
        }finally{target.set(null,oldTarget);loading.set(null,oldLoading);}
    }
}
