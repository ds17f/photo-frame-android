package com.photoframe.playlist

import com.photoframe.playlist.TestPhotos.landscape
import com.photoframe.playlist.TestPhotos.portrait
import com.photoframe.playlist.model.PortraitMode
import com.photoframe.playlist.model.Slide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlideBuilderTest {

    @Test fun landscape_yieldsSingle_consumingOne() {
        val remaining = mutableListOf(landscape("a"), landscape("b"))
        val slide = SlideBuilder.nextSlide(remaining, PortraitMode.AUTO)
        assertTrue(slide is Slide.Single)
        assertEquals("a", (slide as Slide.Single).photo.id)
        assertEquals(listOf("b"), remaining.map { it.id })
    }

    @Test fun portrait_blurFillMode_neverPairs() {
        val remaining = mutableListOf(portrait("a"), portrait("b"))
        val slide = SlideBuilder.nextSlide(remaining, PortraitMode.BLUR_FILL)
        assertTrue(slide is Slide.Single)
        assertEquals(listOf("b"), remaining.map { it.id })
    }

    @Test fun portrait_auto_pairsWithPartnerWithinLookahead() {
        // partner two positions ahead (within K=10)
        val remaining = mutableListOf(portrait("a"), landscape("x"), portrait("b"), landscape("y"))
        val slide = SlideBuilder.nextSlide(remaining, PortraitMode.AUTO)
        assertTrue(slide is Slide.Pair)
        slide as Slide.Pair
        assertEquals("a", slide.left.id)
        assertEquals("b", slide.right.id)
        // both portraits consumed; landscapes preserved in order
        assertEquals(listOf("x", "y"), remaining.map { it.id })
    }

    @Test fun portrait_auto_blurFillsWhenPartnerBeyondLookahead() {
        // first portrait, then 10 landscapes, then a portrait at index 11 (beyond K=10)
        val remaining = mutableListOf(portrait("a"))
        repeat(10) { remaining.add(landscape("L$it")) }
        remaining.add(portrait("b"))
        val slide = SlideBuilder.nextSlide(remaining, PortraitMode.AUTO)
        assertTrue("expected blur-fill single, got $slide", slide is Slide.Single)
        assertEquals("a", (slide as Slide.Single).photo.id)
        // partner b NOT consumed
        assertTrue(remaining.any { it.id == "b" })
    }

    @Test fun portrait_pairMode_pairsAcrossWholeBag() {
        val remaining = mutableListOf(portrait("a"))
        repeat(20) { remaining.add(landscape("L$it")) }
        remaining.add(portrait("b")) // far beyond K=10
        val slide = SlideBuilder.nextSlide(remaining, PortraitMode.PAIR)
        assertTrue(slide is Slide.Pair)
        slide as Slide.Pair
        assertEquals("a", slide.left.id)
        assertEquals("b", slide.right.id)
    }

    @Test fun emptyBag_returnsNull() {
        assertNull(SlideBuilder.nextSlide(mutableListOf(), PortraitMode.AUTO))
    }
}
