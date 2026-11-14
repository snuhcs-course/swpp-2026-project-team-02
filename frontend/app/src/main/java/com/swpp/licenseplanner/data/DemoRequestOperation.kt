package com.swpp.licenseplanner.data

/** Request boundary that can be made to fail once in the demo flavor for manual UI review. */
enum class DemoRequestOperation { QUESTIONS, GENERATION, ADJUSTMENT, RELOAD }

/** Internal one-shot failure slots; only the Demo flavor exposes an action to arm them. */
internal object DemoFailureInjector {
    private val queued = mutableMapOf<DemoRequestOperation, ApiError>()

    @Synchronized fun arm(operation: DemoRequestOperation, error: ApiError) {
        queued[operation] = error
    }

    @Synchronized fun take(operation: DemoRequestOperation): ApiError? = queued.remove(operation)
}
