package cn.piq.computer;
import cn.piq.computer.stream.*;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StreamCadenceTest {
    @Test void complexFramesFitEveryTier()throws Exception{
        var rgba=new byte[800*600*4];new Random(1).nextBytes(rgba);
        for(int tier=0;tier<3;tier++){
            int limit=StreamImage.frameBudget(tier);var encoder=new StreamImage.Encoder(limit);
            for(int n=0;n<4;n++){var jpeg=encoder.encode(rgba,800,600);assertNotNull(jpeg,"tier "+tier);assertTrue(jpeg.length<=limit);assertEquals(640*480,StreamImage.decode(jpeg).length);}
        }
    }
    @Test void sustainedFifteenFpsWithAudioDoesNotExhaustTokens(){
        for(int tier=0;tier<3;tier++){
            var budget=new StreamBudget(tier,0);int frame=StreamImage.frameBudget(tier);int cost=frame+((frame+15999)/16000)*96;
            for(int tick=0;tick<1200;tick++){long now=tick*50_000_000L;assertTrue(budget.take(1200+96,now));if(tick%4!=3)assertTrue(budget.take(cost,now),"tier "+tier+" tick "+tick);}
        }
    }
    @Test void invalidBounds(){assertThrows(IllegalArgumentException.class,()->new StreamImage.Encoder(1));assertThrows(IllegalArgumentException.class,()->new StreamImage.Encoder(64001));}
}
