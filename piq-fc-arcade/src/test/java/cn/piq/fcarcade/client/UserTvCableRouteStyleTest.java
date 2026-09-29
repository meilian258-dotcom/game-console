package cn.piq.fcarcade.client;

import cn.piq.fcarcade.client.HomeHardwareRenderLayout.Point;
import cn.piq.fcarcade.home.UserTvLayout;
import cn.piq.fcarcade.layout.ArcadeDisplayStyle;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UserTvCableRouteStyleTest {
    @Test void regularFallbackKeepsAxisAlignedLongRunsAndOnlyShortRoundedDiagonals() {
        for(var tv:Arrays.stream(ArcadeDisplayStyle.values()).filter(UserTvLayout::supports).filter(s->!UserTvLayout.wall(s)).toList())
        for(int type=0;type<4;type++)for(int turns=0;turns<4;turns++) {
            var sockets=HomeAvCableMesh.consoleSockets(type!=0,type>=2,type==3,turns);
            var box=HomeAvCableMesh.consoleHousing(type!=0,type>=2,type==3,turns).outer();
            var route=HomeAvCableLayout.routeUserTv(sockets,box,turns,tv,0,4,0,2,null,0,false);
            assertFalse(route.isEmpty(),tv+"/"+type+"/"+turns);
            double diagonal=0,axis=0;
            for(int i=2;i<route.size()-1;i++) {
                Point a=route.get(i-1),b=route.get(i);
                double dx=Math.abs(b.x()-a.x()),dz=Math.abs(b.z()-a.z()),length=Math.hypot(dx,dz);
                if(dx>1e-8&&dz>1e-8) {
                    diagonal+=length;
                    assertTrue(diagonal<.8,"Long rigid diagonal instead of bounded rounded corner: "+tv);
                } else {diagonal=0;axis+=length;}
                assertEquals(HomeAvCableLayout.USER_TV_SUPPORT,b.y(),1e-9);
            }
            assertTrue(axis>1,"Main run must be horizontal/vertical");
            assertTrue(route.size()<=HomeAvCableLayout.MAX_ROUTE_POINTS);
            assertFalse(UserTvCableMesh.buildFc(type!=0,type>=2,type==3,turns,tv,0,4,0,2).isEmpty());
        }
    }
    @Test void diagonalOffsetWallRoutesRemainSupportedAtTheTelevision() {
        for(var tv:List.of(ArcadeDisplayStyle.HOME_PANEL_2_WALL,ArcadeDisplayStyle.HOME_PANEL_3_WALL)) {
            var route=HomeAvCableLayout.routeUserTv(HomeAvCableMesh.consoleSockets(false,false,0),
                HomeAvCableMesh.consoleHousing(false,false,false,0).outer(),0,tv,0,3,2,3);
            assertFalse(route.isEmpty());
            Point top=route.get(route.size()-2);
            for(int i=1;i<route.size()-1;i++)if(route.get(i).y()>.25)
                assertTrue(Math.hypot(route.get(i).x()-top.x(),route.get(i).z()-top.z())<.23);
            assertFalse(UserTvCableMesh.buildFc(false,false,false,0,tv,0,3,2,3).isEmpty());
        }
    }
}
