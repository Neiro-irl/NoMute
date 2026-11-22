package com.neiro.aftertone.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryOrderPresentationTest {
    @Test
    fun dragPastOneRowMovesTheTrackDownByOneIndex() {
        assertEquals(2, nextLibraryReorderIndex(currentIndex = 1, accumulatedOffsetPx = 90f, rowStepPx = 90f, itemCount = 4))
    }

    @Test
    fun dragPastOneRowMovesTheTrackUpByOneIndex() {
        assertEquals(1, nextLibraryReorderIndex(currentIndex = 2, accumulatedOffsetPx = -90f, rowStepPx = 90f, itemCount = 4))
    }

    @Test
    fun dragWithinTheCurrentRowDoesNotReorder() {
        assertNull(nextLibraryReorderIndex(currentIndex = 1, accumulatedOffsetPx = 89f, rowStepPx = 90f, itemCount = 4))
    }

    @Test
    fun dragCannotMoveBeyondEitherEndOfTheLibrary() {
        assertNull(nextLibraryReorderIndex(currentIndex = 0, accumulatedOffsetPx = -90f, rowStepPx = 90f, itemCount = 4))
        assertNull(nextLibraryReorderIndex(currentIndex = 3, accumulatedOffsetPx = 90f, rowStepPx = 90f, itemCount = 4))
    }

    @Test
    fun overscrollBeyondAnEndIsDiscardedButMotionBackIntoTheListIsKept() {
        assertEquals(0f, clearOutOfBoundsLibraryReorderOffset(currentIndex = 0, accumulatedOffsetPx = -135f, itemCount = 4), 0f)
        assertEquals(45f, clearOutOfBoundsLibraryReorderOffset(currentIndex = 0, accumulatedOffsetPx = 45f, itemCount = 4), 0f)
        assertEquals(0f, clearOutOfBoundsLibraryReorderOffset(currentIndex = 3, accumulatedOffsetPx = 135f, itemCount = 4), 0f)
        assertEquals(-45f, clearOutOfBoundsLibraryReorderOffset(currentIndex = 3, accumulatedOffsetPx = -45f, itemCount = 4), 0f)
    }

    @Test
    fun editingIsDisabledWhileSearchHasText() {
        assertFalse(libraryOrderEditingEnabled("radiohead"))
    }

    @Test
    fun editingIsAvailableWhenSearchIsBlank() {
        assertTrue(libraryOrderEditingEnabled("   "))
    }
}
