package cn.piq.fcarcade.home;

import cn.piq.fcarcade.layout.*;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.Point;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.ScreenQuad;
import com.google.gson.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import static cn.piq.fcarcade.layout.ArcadeDisplayStyle.*;
import static org.junit.jupiter.api.Assertions.*;

/** Geometry/numeric regression only; this does not exercise a GPU or a shader pack. */
class UserTvScreenDepthTest {
    private static final ArcadeDisplayStyle[] STYLES = {
            HOME_GRAY_CRT, HOME_RED_CRT, HOME_PANEL_2, HOME_PANEL_2_WALL,
            HOME_PANEL_3, HOME_PANEL_3_WALL};
    private static final String[] MODELS = {
            "gray_crt_tv", "red_crt_tv", "panel_tv_2", "panel_tv_2_wall",
            "panel_tv_3", "panel_tv_3_wall"};

    @Test void allSixModelsHaveTwoMilliblocksOfRealGlassClearanceInFourDirections() throws Exception {
        for (int i = 0; i < STYLES.length; i++) {
            var style = STYLES[i];
            var glass = glass(model(i));
            assertFalse(glass.has("rotation"));
            assertEquals(1, glass.getAsJsonObject("faces").size());
            assertTrue(glass.getAsJsonObject("faces").has("north"));
            for (int turn = 0; turn < 4; turn++) {
                var q = UserTvLayout.screen(style, turn);
                Point physical = bakedPoint(glass.getAsJsonArray("from"), style, turn);
                for (Point vertex : corners(q)) {
                    double gap = dot(sub(vertex, physical), q.normal());
                    assertEquals(.002, gap, 2e-7, style + " facing " + turn);
                    // Also retain the gap after the renderer converts vertex coordinates to float.
                    assertTrue(dot(sub(asFloat(vertex), asFloat(physical)), q.normal()) > .00199);
                }
                assertEquals(q, ScreenSurfaceGeometry.frame(style, turn, 1, 1, false,
                        ScreenAspectFit.Aspect.FOUR_THREE).image());
            }
        }
    }

    @Test void adjustmentChangesOnlyDepthNotImageSizeOrientationOrModelCompensation() throws Exception {
        for (int i = 0; i < STYLES.length; i++) for (int turn = 0; turn < 4; turn++) {
            var style = STYLES[i];
            var q = UserTvLayout.screen(style, turn);
            var old = previousImage(style, turn);
            assertEquals(old.normal(), q.normal());
            assertEquals(4D / 3, q.aspectRatio(), 1e-10);
            for (int v = 0; v < 4; v++) {
                Point delta = sub(corners(q).get(v), corners(old).get(v));
                assertPoint(mul(q.normal(), .00175), delta, 1e-10);
            }
            var glass = glass(model(i));
            double xShift = UserTvLayout.width(style) == 3 ? 16 : 0;
            for (Point vertex : corners(q)) {
                Point north = RocketArcadeGeometry.rotate(vertex, Math.floorMod(-turn, 4));
                var from = glass.getAsJsonArray("from");
                var to = glass.getAsJsonArray("to");
                assertTrue(north.x() * 16 > from.get(0).getAsDouble() + xShift);
                assertTrue(north.x() * 16 < to.get(0).getAsDouble() + xShift);
                assertTrue(north.y() * 16 > from.get(1).getAsDouble());
                assertTrue(north.y() * 16 < to.get(1).getAsDouble());
            }
            assertEquals(new Point(0, 0, 0), ScreenSurfaceGeometry.translation(style, turn, true));
        }
    }

    @Test void lcdPictureStillSitsBehindTheExistingFrontBezel() throws Exception {
        for (int i = 2; i < STYLES.length; i++) {
            JsonObject model = model(i);
            for (int turn = 0; turn < 4; turn++) {
                var q = UserTvLayout.screen(STYLES[i], turn);
                int edges = 0;
                for (var entry : model.getAsJsonArray("elements")) {
                    var element = entry.getAsJsonObject();
                    if (!element.get("name").getAsString().startsWith("边框_")) continue;
                    Point bezel = bakedPoint(element.getAsJsonArray("from"), STYLES[i], turn);
                    // Positive is in front; pixels must remain recessed, not float over the case.
                    assertTrue(dot(sub(q.center(), bezel), q.normal()) < -.0045);
                    edges++;
                }
                assertEquals(4, edges);
            }
        }
    }

    @Test void gunMappingKeepsCornersCenterAndRejectsBordersAndRearInEveryDirection() {
        double[][] samples = {{0, 0}, {1, 0}, {0, 1}, {1, 1}, {128.5 / 256, 120.5 / 240}};
        int[][] pixels = {{0, 0}, {255, 0}, {0, 239}, {255, 239}, {128, 120}};
        for (var style : STYLES) for (int turn = 0; turn < 4; turn++) {
            var surface = ScreenSurfaceGeometry.frame(style, turn, 1, 1, false,
                    ScreenAspectFit.Aspect.FOUR_THREE);
            var q = surface.image();
            for (int i = 0; i < samples.length; i++) {
                Point target = at(q, samples[i][0], samples[i][1]);
                var hit = ScreenRayMapping.hit(surface, add(target, mul(q.normal(), 3)),
                        mul(q.normal(), -1), 4).orElseThrow();
                assertEquals(pixels[i][0], hit.x());
                assertEquals(pixels[i][1], hit.y());
                assertEquals(samples[i][0], hit.u(), 1e-10);
                assertEquals(samples[i][1], hit.v(), 1e-10);
            }
            for (double u : new double[]{-.01, 1.01})
                assertTrue(ScreenRayMapping.hit(surface, add(at(q, u, .5), mul(q.normal(), 3)),
                        mul(q.normal(), -1), 4).isEmpty());
            assertTrue(ScreenRayMapping.hit(surface, add(q.center(), mul(q.normal(), -3)),
                    q.normal(), 4).isEmpty());
        }
    }

    @Test void illustrativeDepthArithmeticHasEightTimesMoreSeparationNotAShaderGuarantee() throws Exception {
        // A conventional near=.05/far=256 projection and 24-bit depth are an illustrative
        // precision check, not a claim about the user's GPU, shader projection or rasterizer.
        // Projecting the normal offset onto the view direction is conservative here.
        for (int i = 0; i < STYLES.length; i++) for (int turn = 0; turn < 4; turn++) {
            var q = UserTvLayout.screen(STYLES[i], turn);
            double gap = dot(sub(q.center(), bakedPoint(glass(model(i)).getAsJsonArray("from"),
                    STYLES[i], turn)), q.normal());
            for (double distance : new double[]{1, 3, 8, 20})
                for (double cosine : new double[]{1, Math.cos(Math.PI / 6), .5}) {
                    double oldSeparation = depth(distance + .00025 * cosine) - depth(distance);
                    double separation = depth(distance + gap * cosine) - depth(distance);
                    assertTrue(separation > oldSeparation * 7.9);
                    assertTrue(separation * 0xffffff > 1.5);
                    assertNotEquals(Math.round(depth(distance) * 0xffffff),
                            Math.round(depth(distance + gap * cosine) * 0xffffff));
                }
        }
    }

    private JsonObject model(int index) throws Exception {
        String path = "/assets/piq_fc_arcade/models/block/user_tv48/" + MODELS[index] + ".json";
        try (var in = getClass().getResourceAsStream(path)) {
            assertNotNull(in, path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
    private static JsonObject glass(JsonObject model) {
        JsonObject found = null;
        for (var entry : model.getAsJsonArray("elements")) {
            var element = entry.getAsJsonObject();
            String name = element.get("name").getAsString();
            if (!name.equals("独立液晶屏幕_16比9") && !name.equals("空白显像管屏幕")
                    && !name.equals("独立凸面屏幕")) continue;
            assertNull(found, "Each TV must have exactly one static black screen face");
            found = element;
        }
        assertNotNull(found);
        return found;
    }
    private static Point bakedPoint(JsonArray value, ArcadeDisplayStyle style, int turn) {
        // Match JSON float coordinates /16, then apply the only 3-wide renderer compensation.
        Point local = new Point(value.get(0).getAsFloat() / 16F, value.get(1).getAsFloat() / 16F,
                value.get(2).getAsFloat() / 16F);
        return add(RocketArcadeGeometry.rotate(local, turn), UserTvLayout.modelOffset(style, turn));
    }
    private static ScreenQuad previousImage(ArcadeDisplayStyle style, int turn) {
        double x0, x1, y0, y1, z;
        if (UserTvLayout.panel(style)) {
            x0 = 1.73; x1 = UserTvLayout.width(style) * 16 - 1.73; y0 = 3.88;
            y1 = UserTvLayout.width(style) == 3 ? 28.9075 : 19.9075;
            z = UserTvLayout.wall(style) ? 11.011 : 7.011;
        } else if (style == HOME_GRAY_CRT) {
            x0 = 2.08; x1 = 13.92; y0 = 2.55; y1 = 11.43; z = .403;
        } else {
            x0 = 3.36; x1 = 13.94; y0 = 3.36; y1 = 12.44; z = 1.116;
        }
        Point normal = sub(RocketArcadeGeometry.rotate(new Point(.5, 0, -.5), turn), new Point(.5, 0, .5));
        return ScreenAspectFit.fit(new ScreenQuad(point(x0, y0, z, turn), point(x1, y0, z, turn),
                point(x1, y1, z, turn), point(x0, y1, z, turn), normal), 4D / 3);
    }
    private static Point point(double x, double y, double z, int turn) {
        return RocketArcadeGeometry.rotate(new Point(x / 16, y / 16, z / 16), turn);
    }
    private static double depth(double distance) { return 256D / (256 - .05) * (1 - .05 / distance); }
    private static List<Point> corners(ScreenQuad q) {
        return List.of(q.lowerMinX(), q.lowerMaxX(), q.upperMaxX(), q.upperMinX());
    }
    private static Point at(ScreenQuad q, double u, double v) {
        return add(q.upperMaxX(), add(mul(sub(q.upperMinX(), q.upperMaxX()), u),
                mul(sub(q.lowerMaxX(), q.upperMaxX()), v)));
    }
    private static Point asFloat(Point p) { return new Point((float)p.x(), (float)p.y(), (float)p.z()); }
    private static Point add(Point a, Point b) { return new Point(a.x()+b.x(), a.y()+b.y(), a.z()+b.z()); }
    private static Point sub(Point a, Point b) { return new Point(a.x()-b.x(), a.y()-b.y(), a.z()-b.z()); }
    private static Point mul(Point p, double f) { return new Point(p.x()*f, p.y()*f, p.z()*f); }
    private static double dot(Point a, Point b) { return a.x()*b.x()+a.y()*b.y()+a.z()*b.z(); }
    private static void assertPoint(Point expected, Point actual, double tolerance) {
        assertEquals(expected.x(), actual.x(), tolerance);
        assertEquals(expected.y(), actual.y(), tolerance);
        assertEquals(expected.z(), actual.z(), tolerance);
    }
}
