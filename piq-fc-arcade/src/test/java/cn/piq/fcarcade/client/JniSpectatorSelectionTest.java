package cn.piq.fcarcade.client;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JniSpectatorSelectionTest {
    private SpectatorSelection.Candidate c(long id,boolean playing,double distance){return new SpectatorSelection.Candidate(id,playing,distance*distance);}
    @Test void nearestSingleJniScreenDoesNotConsumeProcessSlots(){
        var candidates=List.of(c(1,false,3),c(2,false,4),c(3,false,5));
        assertEquals(Set.of(1L,3L),SpectatorSelection.selectWithJni(candidates,Set.of(),2,Set.of(1L,2L)));
    }
    @Test void nativeOperatorReleasesNativeObserverButRetainsOtherScreens(){
        var candidates=List.of(c(1,false,1),c(2,true,0),c(3,false,4));
        assertEquals(Set.of(2L,3L),SpectatorSelection.selectWithJni(candidates,Set.of(1L),2,Set.of(1L,2L)));
    }
    @Test void zeroLimitStillAllowsOperatorAndNoSpectators(){
        assertEquals(Set.of(2L),SpectatorSelection.selectWithJni(List.of(c(1,false,1),c(2,true,0)),Set.of(1L),0,Set.of(1L,2L)));
    }
    @Test void retentionAvoidsRapidNativeRestartAndFallsBackWhenOldScreenLeaves(){
        var candidates=List.of(c(1,false,5),c(2,false,4));
        assertEquals(Set.of(1L),SpectatorSelection.selectWithJni(candidates,Set.of(1L),2,Set.of(1L,2L)));
        assertEquals(Set.of(2L),SpectatorSelection.selectWithJni(List.of(c(2,false,4)),Set.of(1L),2,Set.of(1L,2L)));
    }
    @Test void noNativeCandidatesPreservesOriginalAdmission(){
        var candidates=List.of(c(1,false,3),c(2,true,0),c(3,false,5));
        for(int n=0;n<=8;n++)assertEquals(SpectatorSelection.select(candidates,Set.of(3L),n),SpectatorSelection.selectWithJni(candidates,Set.of(3L),n,Set.of()));
    }
}
