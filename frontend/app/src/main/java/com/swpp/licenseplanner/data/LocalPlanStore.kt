package com.swpp.licenseplanner.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.charset.CharacterCodingException

/** Why saved plans could not be restored. Never shown with file contents or paths. */
enum class PlanLoadFailure { Unreadable, Malformed, Unsupported, Invalid }

sealed interface PlanStoreLoad {
    /** The saved collection; empty when nothing was ever saved in this scope. */
    data class Loaded(val snapshot: PlanSnapshot) : PlanStoreLoad
    /** Saved data exists but cannot be used; it is left exactly as it was. */
    data class Failed(val reason: PlanLoadFailure) : PlanStoreLoad
}

/** Device-local copy of the generated-plan collection; it never talks to a server. */
interface LocalPlanStore {
    suspend fun load(): PlanStoreLoad

    /** Replaces the saved collection as a whole. Returns whether the new snapshot was saved. */
    suspend fun save(snapshot: PlanSnapshot): Boolean
}

/** The saved bytes of one storage scope. */
interface PlanBytes {
    /** The committed bytes, or null when nothing was saved; throws [IOException] when unreadable. */
    fun read(): ByteArray?

    /** Replaces the committed bytes atomically; on [IOException] the earlier bytes remain. */
    fun write(bytes: ByteArray)
}

/**
 * [LocalPlanStore] over one scope's [PlanBytes], with encoding and IO off the main thread.
 * Saving is refused until a load has succeeded, so saved data that could not be read is never
 * overwritten.
 */
class BytesPlanStore(
    private val bytes: PlanBytes,
    private val scope: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : LocalPlanStore {
    @Volatile private var loaded = false

    override suspend fun load(): PlanStoreLoad = withContext(io) {
        val result = try {
            val raw = bytes.read()
            if (raw == null) PlanStoreLoad.Loaded(PlanSnapshot.EMPTY)
            else PlanSnapshotCodec.decode(raw.decodeToString(throwOnInvalidSequence = true), scope)
        } catch (_: CharacterCodingException) {
            PlanStoreLoad.Failed(PlanLoadFailure.Malformed)
        } catch (_: IOException) {
            PlanStoreLoad.Failed(PlanLoadFailure.Unreadable)
        }
        if (result is PlanStoreLoad.Loaded) loaded = true
        result
    }

    override suspend fun save(snapshot: PlanSnapshot): Boolean = withContext(io) {
        if (!loaded) return@withContext false
        try {
            bytes.write(PlanSnapshotCodec.encode(snapshot, scope).encodeToByteArray())
            true
        } catch (_: IOException) {
            false
        }
    }
}
