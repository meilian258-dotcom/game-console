package cn.piq.fcarcade.skin;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class SkinTextureCacheTest {
    @Test
    void eighthTextureLimitEvictsLeastRecentlyUsedBeforeAllocatingReplacement() {
        ArrayList<Integer> released = new ArrayList<>();
        SkinTextureCache<Integer> cache = new SkinTextureCache<>(SkinTransferLimits.MAX_TEXTURES, released::add);
        for (int i = 0; i < 8; i++) cache.put("skin" + i, i);
        assertEquals(0, cache.get("skin0"));
        assertEquals("skin1", cache.evictIfFull());
        assertEquals(7, cache.size());
        assertEquals(java.util.List.of(1), released);
        cache.put("skin8", 8);
        assertEquals(8, cache.size());
        assertNull(cache.get("skin1"));
        assertEquals(0, cache.get("skin0"));
        cache.clear();
        assertEquals(0, cache.size());
        assertEquals(9, released.size());
        assertEquals(9, released.stream().distinct().count());
    }

    @Test
    void continuousNewSkinsRemainBoundedAndDisconnectReleasesEveryRemainingTexture() {
        ArrayList<Integer> released = new ArrayList<>();
        SkinTextureCache<Integer> cache = new SkinTextureCache<>(8, released::add);
        for (int i = 0; i < 100; i++) {
            cache.put("skin" + i, i);
            assertTrue(cache.size() <= 8);
        }
        assertEquals(92, released.size());
        cache.clear();
        assertEquals(100, released.stream().distinct().count());
    }

    @Test
    void failedReleaseDoesNotPreventTheRestOfDisconnectCleanup() {
        ArrayList<Integer> attempted = new ArrayList<>();
        SkinTextureCache<Integer> cache = new SkinTextureCache<>(8, value -> {
            attempted.add(value);
            if (value == 0) throw new IllegalStateException("mock native failure");
        });
        cache.put("a", 0);
        cache.put("b", 1);
        assertThrows(IllegalStateException.class, cache::clear);
        assertEquals(java.util.List.of(0, 1), attempted);
        assertEquals(0, cache.size());
    }
}
