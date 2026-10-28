package com.swpp.licenseplanner.data

import android.content.Context
import androidx.core.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Android storage for the plan collection: one [AtomicFile] per scope in the app's private
 * no-backup directory, so an interrupted save leaves the previous or the new snapshot.
 */
object AtomicFilePlanStore {
    fun open(context: Context, scope: String): LocalPlanStore {
        val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, PlanStorageScope.fileName(scope)))
        return BytesPlanStore(AtomicFileBytes(file), scope)
    }

    internal class AtomicFileBytes(private val file: AtomicFile) : PlanBytes {
        override fun read(): ByteArray? = try {
            file.readFully()
        } catch (error: FileNotFoundException) {
            // Also thrown when an existing snapshot cannot be opened, which must not read as empty.
            if (file.baseFile.exists()) throw error
            null
        }

        override fun write(bytes: ByteArray) {
            val out = file.startWrite()
            try {
                out.write(bytes)
            } catch (error: IOException) {
                file.failWrite(out)
                throw error
            }
            file.finishWrite(out)
            // finishWrite only logs a failed sync or rename, so confirm the committed bytes.
            if (!file.readFully().contentEquals(bytes)) throw IOException("snapshot was not committed")
        }
    }
}
