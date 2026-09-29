// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.nativearcade.layout;

import cn.piq.fcarcade.layout.RocketArcadeGeometry;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.*;
import cn.piq.fcarcade.layout.ScreenAspectFit;
import java.util.List;

/** Frozen FC21 geometry for the retained dual_arcade_body asset, independent of newer shared cabinets.
 * All returned points are block units and include final world Y; video must not add .35 again. */
public final class NativeCabinetLayout {
    public static final float MODEL_SCALE=1F;
    public static final double MODEL_Y_OFFSET=.35;
    private static final Point NORTH_NORMAL=new Point(0,.3826834323650898,-.9238795325112867);
    private static final ScreenQuad NORTH=new ScreenQuad(point(4,16.55,5),point(28,16.55,5),
            point(28,29.02237368890237,10.166226336928712),point(4,29.02237368890237,10.166226336928712),NORTH_NORMAL);
    private static final List<ScreenQuad> SCREENS=List.of(NORTH,rotate(NORTH,1),rotate(NORTH,2),rotate(NORTH,3));
    private NativeCabinetLayout(){}
    public static Box bounds(int turns){
        var a=RocketArcadeGeometry.rotate(new Point(.0375,0,.02387563133125),turns);
        var b=RocketArcadeGeometry.rotate(new Point(1.9625,2.35,.915625),turns);
        return new Box(Math.min(a.x(),b.x()),0,Math.min(a.z(),b.z()),Math.max(a.x(),b.x()),2.35,Math.max(a.z(),b.z()));
    }
    public static ScreenQuad screen(int turns){return SCREENS.get(Math.floorMod(turns,4));}
    public static ScreenQuad frame(int turns,double contentAspect){return ScreenAspectFit.fit(screen(turns),contentAspect);}
    public static Point occupancy(int turns){return RocketArcadeGeometry.rotate(new Point(1,2.6,.5),turns);}
    private static Point point(double x,double y,double z){return new Point(x/16,y/16+NORTH_NORMAL.y()*.0015,z/16+NORTH_NORMAL.z()*.0015);}
    private static ScreenQuad rotate(ScreenQuad q,int turns){
        var end=RocketArcadeGeometry.rotate(new Point(.5,NORTH_NORMAL.y(),.5+NORTH_NORMAL.z()),turns);
        return new ScreenQuad(RocketArcadeGeometry.rotate(q.lowerMinX(),turns),RocketArcadeGeometry.rotate(q.lowerMaxX(),turns),
                RocketArcadeGeometry.rotate(q.upperMaxX(),turns),RocketArcadeGeometry.rotate(q.upperMinX(),turns),new Point(end.x()-.5,end.y(),end.z()-.5));
    }
}
