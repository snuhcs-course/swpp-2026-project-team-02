package com.swpp.licenseplanner.data

import kotlinx.serialization.json.Json

/** Shared JSON configuration. */
val ApiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}
