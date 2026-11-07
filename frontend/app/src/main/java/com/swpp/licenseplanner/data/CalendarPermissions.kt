package com.swpp.licenseplanner.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * The calendar permissions each operation needs. Permission prompts, their already-granted
 * shortcut and answer recheck, and the provider adapters all use these definitions.
 */
enum class CalendarAccess(val permissions: List<String>) {
    /** Importing busy times only reads the calendar. */
    READ(listOf(Manifest.permission.READ_CALENDAR)),

    /** Exporting lists the writable calendars and adds events. */
    EXPORT(listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)),
    ;

    fun isGranted(context: Context): Boolean = isGranted { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /** Whether every required permission is granted, given an answer for a single permission. */
    internal fun isGranted(granted: (String) -> Boolean): Boolean = permissions.all(granted)
}
