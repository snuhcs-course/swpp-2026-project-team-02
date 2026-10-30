package com.swpp.licenseplanner.data

import java.io.IOException

/**
 * Committed plan bytes held in memory, standing in for one scope's file. Sharing one instance
 * between view models stands in for a process restart. A failing write keeps the earlier bytes.
 */
class MemoryPlanBytes(var committed: ByteArray? = null) : PlanBytes {
    var failReads = false
    var failWrites = false
    var writes = 0
        private set

    override fun read(): ByteArray? {
        if (failReads) throw IOException("unreadable")
        return committed?.copyOf()
    }

    override fun write(bytes: ByteArray) {
        writes++
        if (failWrites) throw IOException("write failed")
        committed = bytes.copyOf()
    }
}
