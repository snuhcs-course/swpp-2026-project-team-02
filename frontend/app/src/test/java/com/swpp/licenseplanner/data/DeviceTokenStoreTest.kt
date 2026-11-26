package com.swpp.licenseplanner.data

import androidx.core.util.AtomicFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DeviceTokenStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private fun fileIn(dir: File) = File(dir, DeviceTokenStore.FILE_NAME)
    private fun store(file: File, bytes: (PlanBytes) -> PlanBytes = { it }) =
        DeviceTokenStore(bytes(AtomicFilePlanStore.AtomicFileBytes(AtomicFile(file))))

    private fun assertUnavailable(store: DeviceTokenStore) {
        try {
            store.token()
            fail("expected the credential to be unavailable")
        } catch (_: DeviceTokenUnavailableException) {
        }
    }

    @Test fun firstUseCreatesAndSavesA32ByteLowercaseHexToken() {
        val file = fileIn(folder.root)
        val token = store(file).token()

        assertTrue(Regex("[0-9a-f]{64}").matches(token))
        assertEquals(token, file.readText())
    }

    @Test fun everyRandomByteIsEncodedAsTwoLowercaseHexDigits() {
        val random = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) {
                for (i in bytes.indices) bytes[i] = (i * 8).toByte()
            }
        }
        val token = DeviceTokenStore(MemoryPlanBytes(), random).token()

        assertEquals((0 until 32).joinToString("") { "%02x".format(it * 8) }, token)
        assertTrue(token.endsWith("f0f8"))
    }

    @Test fun reopeningTheStoreReturnsTheSavedToken() {
        val file = fileIn(folder.root)
        val first = store(file).token()

        assertEquals(first, store(file).token())
        assertEquals(first, file.readText())
    }

    @Test fun aFreshPrivateDirectoryGetsANewToken() {
        val first = store(fileIn(folder.newFolder("first"))).token()
        val second = store(fileIn(folder.newFolder("second"))).token()

        assertNotEquals(first, second)
    }

    @Test fun concurrentFirstUseSavesAndSharesOneValue() {
        val file = fileIn(folder.root)
        val writes = AtomicInteger()
        val shared = store(file) { real ->
            object : PlanBytes by real {
                override fun write(bytes: ByteArray) {
                    writes.incrementAndGet()
                    real.write(bytes)
                }
            }
        }
        val threads = 16
        val start = CountDownLatch(1)
        val tokens = Collections.synchronizedList(mutableListOf<String>())
        val pool = Executors.newFixedThreadPool(threads)
        repeat(threads) { pool.execute { start.await(); tokens += shared.token() } }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))

        assertEquals(threads, tokens.size)
        assertEquals(1, tokens.toSet().size)
        assertEquals(1, writes.get())
        assertEquals(tokens.first(), file.readText())
    }

    @Test fun aFailedSaveIsNeverUsedAndIsTriedAgainLater() {
        val bytes = MemoryPlanBytes().apply { failWrites = true }
        val store = DeviceTokenStore(bytes)

        assertUnavailable(store)
        assertUnavailable(store)
        assertEquals(2, bytes.writes)
        assertNull(bytes.committed)

        bytes.failWrites = false
        val token = store.token()
        assertEquals(token, bytes.committed!!.decodeToString())
    }

    @Test fun anUnreadableSavedTokenFailsWithoutReplacingIt() {
        val bytes = MemoryPlanBytes("a".repeat(64).encodeToByteArray()).apply { failReads = true }

        assertUnavailable(DeviceTokenStore(bytes))
        assertEquals(0, bytes.writes)
    }

    @Test fun aMalformedSavedTokenFailsWithoutReplacingIt() {
        for (saved in listOf("", "A".repeat(64), "a".repeat(63), "a".repeat(64) + "\n", "g".repeat(64))) {
            val bytes = MemoryPlanBytes(saved.encodeToByteArray())

            assertUnavailable(DeviceTokenStore(bytes))
            assertEquals(0, bytes.writes)
            assertArrayEquals(saved.encodeToByteArray(), bytes.committed)
        }
    }
}
