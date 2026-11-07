package com.swpp.licenseplanner.data

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarPermissionsTest {
    private val read = Manifest.permission.READ_CALENDAR
    private val write = Manifest.permission.WRITE_CALENDAR

    @Test
    fun importNeedsOnlyReadAndExportNeedsBoth() {
        assertEquals(listOf(read), CalendarAccess.READ.permissions)
        assertEquals(listOf(read, write), CalendarAccess.EXPORT.permissions)
    }

    @Test
    fun grantMatrix() {
        val expected = mapOf(
            emptySet<String>() to (false to false),
            setOf(read) to (true to false),
            setOf(write) to (false to false),
            setOf(read, write) to (true to true),
        )
        for ((granted, access) in expected) {
            assertEquals("read with $granted", access.first, CalendarAccess.READ.isGranted(granted::contains))
            assertEquals("export with $granted", access.second, CalendarAccess.EXPORT.isGranted(granted::contains))
        }
    }

    @Test
    fun readAccessNeverAsksAboutWriting() {
        val asked = mutableListOf<String>()
        CalendarAccess.READ.isGranted { asked += it; true }
        assertEquals(listOf(read), asked)
    }
}
