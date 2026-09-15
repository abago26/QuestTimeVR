package com.questtime.vr

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Turning a point on the panel into a row.
 *
 * Pure arithmetic, and worth pinning down here rather than in a headset: a ray that
 * highlights the row above the one you are pointing at is both obvious to a person
 * and very hard to reason about from the inside.
 */
class RowAtTest {

    private val files = 5

    /** The y, in thousandths of panel height, at the centre of visible row [i]. */
    private fun atRow(i: Int, firstVisible: Int = 0): Int {
        val h = MenuBar.listHeight(files).toFloat()
        val y = 16f + 152f - 40f + (i - firstVisible) * 62f + 27f
        return (y / h * 1000f).toInt()
    }

    @Test
    fun eachVisibleRowMapsToItself() {
        for (i in 0 until files) {
            assertEquals("row $i", i, MenuBar.rowAt(atRow(i), files, firstVisible = 0))
        }
    }

    @Test
    fun theSettingsBandIsTheRowAfterTheLastFile() {
        val h = MenuBar.listHeight(files).toFloat()
        val mid = h - 16f - 104f - MenuBar.SETTINGS_H / 2f
        assertEquals(files, MenuBar.rowAt((mid / h * 1000f).toInt(), files, 0))
    }

    @Test
    fun thePointerMissesAboveTheFirstRowAndBelowTheControls() {
        assertEquals("the title area is not a row", -1, MenuBar.rowAt(10, files, 0))
        assertEquals("the controller strip is not a row", -1, MenuBar.rowAt(995, files, 0))
    }

    @Test
    fun aMissIsReportedAsAMiss() {
        assertEquals(-1, MenuBar.rowAt(-1, files, 0))
    }

    /** Past the end of a short list is empty space, not a row that can be chosen. */
    @Test
    fun emptyRowsBelowTheFilesAreNotSelectable() {
        assertEquals(-1, MenuBar.rowAt(atRow(files + 1), files, 0))
    }

    /** With the list scrolled, screen position maps to the file actually shown. */
    @Test
    fun scrollingOffsetsWhichFileARowMeansTo() {
        val many = 20
        val first = MenuBar.PAGE                     // second page
        assertEquals(first, MenuBar.rowAt(atRow(first, first), many, first))
        assertEquals(first + 3, MenuBar.rowAt(atRow(first + 3, first), many, first))
    }
}
