package cn.piq.fcarcade.layout;

import cn.piq.fcarcade.home.HomeHardwareScale;
import cn.piq.fcarcade.home.VintageTvLayout;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.Point;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.ScreenQuad;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScreenSurfaceGeometryTest {
    @Test void allOldFlatVerticesNormalsAndUvsRetainExactFloatBits() {
        for (var style : ArcadeDisplayStyle.values()) {
            if (style == ArcadeDisplayStyle.PORTRAIT_CABINET || style == ArcadeDisplayStyle.DUAL_CABINET || style == ArcadeDisplayStyle.LEGACY_GENERIC
                    || style == ArcadeDisplayStyle.HOME_WIDE_LCD_TV || style == ArcadeDisplayStyle.HOME_LARGE_LCD_TV
                    || style == ArcadeDisplayStyle.HOME_VINTAGE_TV
                    || cn.piq.fcarcade.home.UserTvLayout.supports(style)) continue;
            for (int width = 1; width <= 7; width++) for (int height = 1; height <= 7; height++)
                for (int turn = 0; turn < 4; turn++) for (boolean centered : new boolean[]{false, true}) {
                    var surface = ScreenSurfaceGeometry.frame(style, turn, width, height, centered, ScreenAspectFit.Aspect.FOUR_THREE);
                    assertArrayEquals(legacyFlat(style, turn, width, height), emitted(surface.image()), style+" "+width+"x"+height+" "+turn);
                    Point offset = centered && style == ArcadeDisplayStyle.HOME_RETRO_TV
                            ? switch(turn) { case 0 -> new Point(-.5,0,0); case 1 -> new Point(0,0,-.5);
                            case 2 -> new Point(.5,0,0); default -> new Point(0,0,.5); } : new Point(0,0,0);
                    assertEquals(offset, surface.translation());
                }
        }
    }

    @Test void allSlopedAndFittedSurfacesKeepTheirExistingOrderingAndFullUvs() {
        for (int turn=0;turn<4;turn++) for(var aspect:ScreenAspectFit.Aspect.values()) {
            for(var style:new ArcadeDisplayStyle[]{ArcadeDisplayStyle.DUAL_CABINET,ArcadeDisplayStyle.LEGACY_GENERIC,
                    ArcadeDisplayStyle.HOME_WIDE_LCD_TV,ArcadeDisplayStyle.HOME_LARGE_LCD_TV,ArcadeDisplayStyle.HOME_VINTAGE_TV}) {
                var expected=switch(style) {
                    case DUAL_CABINET -> DualScreenPresentation.frame(turn,aspect);
                    case LEGACY_GENERIC -> RocketArcadeGeometry.screen(turn);
                    case HOME_WIDE_LCD_TV -> WideLcdPresentation.frame(turn);
                    case HOME_LARGE_LCD_TV -> LargeLcdPresentation.frame(turn);
                    default -> VintageTvLayout.screen(turn);
                };
                var actual=ScreenSurfaceGeometry.frame(style,turn,2,3,true,aspect);
                assertSame(expected,actual.image());
                assertArrayEquals(emitted(expected),emitted(actual.image()));
                assertEquals(new Point(0,0,0),actual.translation());
            }
        }
    }

    @Test void actualRendererHasOneQuadPathAndCenteringRemainsSeparateExactlyOnce() throws Exception {
        Path root=Path.of("src/main/java/cn/piq/fcarcade/client");
        String renderer=Files.readString(root.resolve("ArcadeBlockScreenRenderer.java"));
        String home=Files.readString(root.resolve("HomeVideoDisplay.java"));
        assertTrue(renderer.contains("ScreenSurfaceGeometry.frame(displayStyle,"));
        assertTrue(renderer.contains("width, height, false,"));
        assertTrue(renderer.contains("? ClientArcadeEvents.dualScreenAspect() : cn.piq.fcarcade.layout.ScreenAspectFit.Aspect.FOUR_THREE"));
        assertTrue(renderer.contains("powerPoint(quad.lowerMaxX(), middle, vertical), quad.normal(), 0, 1, shade"));
        assertTrue(renderer.contains("powerPoint(quad.lowerMinX(), middle, vertical), quad.normal(), 1, 1, shade"));
        assertTrue(renderer.contains("powerPoint(quad.upperMinX(), middle, vertical), quad.normal(), 1, 0, shade"));
        assertTrue(renderer.contains("powerPoint(quad.upperMaxX(), middle, vertical), quad.normal(), 0, 0, shade"));
        assertTrue(renderer.contains("double vertical = powerVertical(displayStyle, amount)"));
        assertFalse(renderer.contains("switch (facing)"));
        for(String s:new String[]{renderer,home}) {
            assertEquals(1,s.split("ScreenSurfaceGeometry.translation",-1).length-1);
            assertEquals(1,s.split("stack.translate\\(offset.x\\(\\), offset.y\\(\\), offset.z\\(\\)\\)",-1).length-1);
        }
        assertTrue(home.contains("!linkId.equals(console.linkId())"));
        assertTrue(home.contains("!linkId.equals(tv.linkId())"));
        assertTrue(home.contains("!HomeTvStructure.complete(mc.level, televisionPos)"));
    }

    // Independent frozen alpha24 drawFace float arithmetic/order, not the new helper.
    private static int[] legacyFlat(ArcadeDisplayStyle style,int turn,int width,int height) {
        var b=ArcadeScreenBounds.resolve(width,height,style);
        float offset=style==ArcadeDisplayStyle.HOME_RETRO_TV?.00025F*(float)HomeHardwareScale.TV_SCALE:.002F;
        float n=b.frontInset()-offset, f=1F-b.frontInset()+offset;
        float[] v=switch(turn) {
            case 0 -> new float[]{b.max(),b.bottom(),n,0,1,0,0,-1,b.negativeMin(),b.bottom(),n,1,1,0,0,-1,
                    b.negativeMin(),b.top(),n,1,0,0,0,-1,b.max(),b.top(),n,0,0,0,0,-1};
            case 1 -> new float[]{f,b.bottom(),b.max(),0,1,1,0,0,f,b.bottom(),b.negativeMin(),1,1,1,0,0,
                    f,b.top(),b.negativeMin(),1,0,1,0,0,f,b.top(),b.max(),0,0,1,0,0};
            case 2 -> new float[]{b.min(),b.bottom(),f,0,1,0,0,1,b.positiveMax(),b.bottom(),f,1,1,0,0,1,
                    b.positiveMax(),b.top(),f,1,0,0,0,1,b.min(),b.top(),f,0,0,0,0,1};
            default -> new float[]{n,b.bottom(),b.min(),0,1,-1,0,0,n,b.bottom(),b.positiveMax(),1,1,-1,0,0,
                    n,b.top(),b.positiveMax(),1,0,-1,0,0,n,b.top(),b.min(),0,0,-1,0,0};
        };
        int[] bits=new int[v.length];for(int i=0;i<v.length;i++)bits[i]=Float.floatToIntBits(v[i]);return bits;
    }
    private static int[] emitted(ScreenQuad q) {
        Point[] points={q.lowerMaxX(),q.lowerMinX(),q.upperMinX(),q.upperMaxX()};
        float[][] uv={{0,1},{1,1},{1,0},{0,0}};int[] result=new int[32];int i=0;
        for(int p=0;p<4;p++)for(double d:new double[]{points[p].x(),points[p].y(),points[p].z(),uv[p][0],uv[p][1],q.normal().x(),q.normal().y(),q.normal().z()})
            result[i++]=Float.floatToIntBits((float)d);
        return result;
    }
}
