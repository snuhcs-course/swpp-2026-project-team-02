package com.swpp.licenseplanner.data

import kotlinx.serialization.json.Json

/** Shared JSON configuration (design D5). */
val ApiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}
