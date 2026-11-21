package com.vknxworks.recorderctrl.edit

import com.vknxworks.recorderctrl.edit.EditMath.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditMathTest {
    @Test fun validity() {
        assertTrue(EditMath.valid(Mode.TRIM, 1_000, 5_000, 10_000))
        assertFalse(EditMath.valid(Mode.TRIM, 0, 10_000, 10_000))
        assertFalse(EditMath.valid(Mode.TRIM, 1_000, 1_200, 10_000))
        assertTrue(EditMath.valid(Mode.CUT, 1_000, 5_000, 10_000))
        assertFalse(EditMath.valid(Mode.CUT, 0, 10_000, 10_000))
        assertFalse(EditMath.valid(Mode.CUT, 0, 9_800, 10_000))
        assertFalse(EditMath.valid(Mode.CUT, -1, 5_000, 10_000))
    }

    @Test fun timesMove() {
        assertEquals(0L, EditMath.mapTime(Mode.TRIM, 2_000, 2_000, 5_000))
        assertNull(EditMath.mapTime(Mode.TRIM, 5_000, 2_000, 5_000))
        assertEquals(1_000L, EditMath.mapTime(Mode.CUT, 1_000, 2_000, 5_000))
        assertNull(EditMath.mapTime(Mode.CUT, 3_000, 2_000, 5_000))
        assertEquals(3_000L, EditMath.mapTime(Mode.CUT, 6_000, 2_000, 5_000))
    }

    @Test fun bookmarks() {
        assertEquals(listOf(0L, 2_500L), EditMath.mapBookmarks(Mode.TRIM, listOf(1_000, 2_000, 4_500, 9_000), 2_000, 5_000))
        assertEquals(listOf(1_000L, 4_000L), EditMath.mapBookmarks(Mode.CUT, listOf(1_000, 3_000, 7_000), 2_000, 5_000))
    }

    @Test fun bytesAreWholeFrames() {
        assertEquals(192_000L, EditMath.byteAt(1_000, 192.0, 4, 1_000_000))
        assertEquals(1_000_000L, EditMath.byteAt(10_000, 192.0, 4, 1_000_000))
        assertEquals(96L, EditMath.byteAt(1, 97.0, 2, 1_000))
    }
}
