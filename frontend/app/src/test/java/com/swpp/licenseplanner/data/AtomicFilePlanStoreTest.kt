package com.swpp.licenseplanner.data

import androidx.core.util.AtomicFile
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AtomicFilePlanStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val scope = PlanStorageScope.of(demo = true, apiBaseUrl = "")

    @Test fun missingSnapshotIsAnEmptyCollection() = runTest {
        val file = File(folder.root, PlanStorageScope.fileName(scope))
        val store = BytesPlanStore(AtomicFilePlanStore.AtomicFileBytes(AtomicFile(file)), scope, StandardTestDispatcher(testScheduler))

        assertEquals(PlanStoreLoad.Loaded(PlanSnapshot.EMPTY), store.load())
    }

    @Test fun existingSnapshotThatCannotBeOpenedFailsAndIsNotOverwritten() = runTest {
        val file = File(folder.root, PlanStorageScope.fileName(scope))
        val saved = PlanSnapshotCodec.encode(PlanSnapshot.EMPTY, scope).encodeToByteArray()
        file.writeBytes(saved)
        file.setReadable(false, false)
        assumeFalse("permissions are not enforced for this user", file.canRead())
        val store = BytesPlanStore(AtomicFilePlanStore.AtomicFileBytes(AtomicFile(file)), scope, StandardTestDispatcher(testScheduler))

        try {
            assertEquals(PlanStoreLoad.Failed(PlanLoadFailure.Unreadable), store.load())
            assertFalse(store.save(PlanSnapshot.EMPTY))
        } finally {
            file.setReadable(true, false)
        }
        assertArrayEquals(saved, file.readBytes())
    }
}
