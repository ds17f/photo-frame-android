package com.photoframe.playlist

import com.photoframe.playlist.TestPhotos.landscape
import com.photoframe.playlist.TestPhotos.portrait
import com.photoframe.playlist.model.Orientation
import com.photoframe.playlist.model.PortraitMode
import com.photoframe.playlist.model.Slide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlideBuilderTest {

    // Landscape frame => portraits are the pairable (perpendicular) orientation.
    private val PORTRAIT_PAIRS = Orientation.PORTRAIT

    // Portrait frame => landscapes are the pairable orientation.
    private val LANDSCAPE_PAIRS = Orientation.LANDSCAPE

    @Test fun landscape_yieldsSingle_consumingOne() {
        val remaining = mutableListOf(landscape("a"), landscape("b"))
        val slide = SlideBuilder.nextSlide(remaining, PortraitMode.AUTO, PORTRAIT_PAIRS)
        assertTrue(slide is Slide.Single)
        assertEquals("a", (slide as Slide.Single).photo.id)
        assertEquals(listOf("b"), remaining.map { it.id })
    }

    @Test fun portrait_blurFillMode_neverPairs() {
        val remaining = mutableListOf(portrait("a"), portrait("b"))
        val slide = SlideBuilder.nextSlide(remaining, PortraitMode.BLUR_FILL, PORTRAIT_PAIRS)
        assertTrue(slide is Slide.Single)
        assertEquals(listOf("b"), remaining.map { it.id })
    }

    @Test fun portrait_auto_pairsWithPartnerWithinLookahead() {
        val remaining = mutableListOf(portrait("a"), landscape("x"), portrait("b"), landscape("y"))
        val slide = SlideBuilder.nextSlide(remaining, PortraitMode.AUTO, PORTRAIT_PAIRS)
        assertTrue(slide is Slide.Pair)
        slide as Slide.Pair
        assertEquals("a", slide.left.id)
        assertEquals("b", slide.right.id)
        assertEquals(listOf("x", "y"), remaining.map { it.id })
    }

    @Test fun portrait_auto_blurFillsWhenPartnerBeyondLookahead() {
        val remaining = mutableListOf(portrait("a"))
        repeat(10) { remaining.add(landscape("L$it")) }
        remaining.add(portrait("b"))
        val slide = SlideBuilder.nextSlide(remaining, PortraitMode.AUTO, PORTRAIT_PAIRS)
        assertTrue("expected blur-fill single, got $slide", slide is Slide.Single)
        assertEquals("a", (slide as Slide.Single).photo.id)
        assertTrue(remaining.any { it.id == "b" })
    }

    @Test fun portrait_pairMode_pairsAcrossWholeBag() {
        val remaining = mutableListOf(portrait("a"))
        repeat(20) { remaining.add(landscape("L$it")) }
        remaining.add(portrait("b"))
        val slide = SlideBuilder.nextSlide(remaining, PortraitMode.PAIR, PORTRAIT_PAIRS)
        assertTrue(slide is Slide.Pair)
        slide as Slide.Pair
        assertEquals("a", slide.left.id)
        assertEquals("b", slide.right.id)
    }

    @Test fun portraitFrame_portraitsAreNeverPaired() {
        // On a portrait frame the pairable orientation is LANDSCAPE, so two portraits must NOT
        // be shown side-by-side — each is a full-screen single (fixes the reported bug).
        val remaining = mutableListOf(portrait("a"), portrait("b"))
        val slide = SlideBuilder.nextSlide(remaining, PortraitMode.PAIR, LANDSCAPE_PAIRS)
        assertTrue("portrait must be single on a portrait frame, got $slide", slide is Slide.Single)
        assertEquals("a", (slide as Slide.Single).photo.id)
        assertTrue("partner portrait must remain", remaining.any { it.id == "b" })
    }

    @Test fun portraitFrame_landscapesPairInstead() {
        val remaining = mutableListOf(landscape("a"), portrait("p"), landscape("b"))
        val slide = SlideBuilder.nextSlide(remaining, PortraitMode.AUTO, LANDSCAPE_PAIRS)
        assertTrue(slide is Slide.Pair)
        slide as Slide.Pair
        assertEquals("a", slide.left.id)
        assertEquals("b", slide.right.id)
        assertEquals(listOf("p"), remaining.map { it.id })
    }

    @Test fun unknownPairOrientation_neverPairs() {
        val remaining = mutableListOf(portrait("a"), portrait("b"))
        val slide = SlideBuilder.nextSlide(remaining, PortraitMode.AUTO, Orientation.UNKNOWN)
        assertTrue(slide is Slide.Single)
    }

    @Test fun emptyBag_returnsNull() {
        assertNull(SlideBuilder.nextSlide(mutableListOf(), PortraitMode.AUTO, PORTRAIT_PAIRS))
    }
}
