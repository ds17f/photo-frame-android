package com.photoframe.render

import org.junit.Assert.assertTrue
import kotlin.random.Random
import org.junit.Test

class KenBurnsTest {

    @Test fun scales_areWithinDesignBounds() {
        repeat(500) { seed ->
            val s = KenBurnsSpec.generate(Random(seed.toLong()))
            assertTrue("startScale ${s.startScale}", s.startScale in 1.0f..1.02001f)
            assertTrue("endScale ${s.endScale}", s.endScale in KenBurnsSpec.MIN_END_ZOOM..KenBurnsSpec.MAX_END_ZOOM + 1e-4f)
        }
    }

    @Test fun translation_neverExceedsSafeRadius_acrossWholeAnimation() {
        val eps = 1e-4f
        repeat(500) { seed ->
            val s = KenBurnsSpec.generate(Random(seed.toLong()))
            // sample the whole 0..1 timeline
            var t = 0f
            while (t <= 1f) {
                val r = s.safeRadius(s.scaleAt(t))
                val tx = kotlin.math.abs(s.transXAt(t))
                val ty = kotlin.math.abs(s.transYAt(t))
                assertTrue("t=$t tx=$tx > r=$r (seed $seed)", tx <= r + eps)
                assertTrue("t=$t ty=$ty > r=$r (seed $seed)", ty <= r + eps)
                t += 0.05f
            }
        }
    }

    @Test fun animationZoomsIn_endScaleGreaterThanStart() {
        repeat(200) { seed ->
            val s = KenBurnsSpec.generate(Random(seed.toLong()))
            assertTrue(s.endScale > s.startScale)
        }
    }
}
