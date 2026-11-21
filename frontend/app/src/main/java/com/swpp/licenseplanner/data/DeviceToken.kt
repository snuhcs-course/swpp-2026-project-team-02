package com.swpp.licenseplanner.data

import android.content.Context
import androidx.core.util.AtomicFile
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.net.ProtocolException
import java.security.SecureRandom

/** This installation's device credential; throws [DeviceTokenUnavailableException] when it cannot be provided. */
fun interface DeviceTokenSource {
    fun token(): String
}

/** The device credential could not be read or durably saved; no request was sent. */
class DeviceTokenUnavailableException(cause: Throwable? = null) :
    IOException("device credential is unavailable", cause)

/**
 * A request or follow-up (such as a redirect) to a different scheme, host or port than the
 * configured API was stopped before it was written, so neither the credential nor the body
 * reached that origin. A [ProtocolException] so OkHttp does not retry it.
 */
class CrossOriginRequestException : ProtocolException("request to another origin was blocked")

/**
 * One random credential per installation, kept in its own file apart from the plan snapshots.
 * The first call reads the saved value or creates and durably saves a new one; every later
 * call returns that value. A saved value that cannot be read or is malformed is never
 * replaced, and a failed save is not used, so no request goes out with a temporary token.
 */
class DeviceTokenStore(
    private val bytes: PlanBytes,
    private val random: SecureRandom = SecureRandom(),
) : DeviceTokenSource {
    @Volatile private var saved: String? = null

    override fun token(): String = saved ?: synchronized(this) {
        saved ?: loadOrCreate().also { saved = it }
    }

    private fun loadOrCreate(): String {
        val existing = try {
            bytes.read()
        } catch (error: Exception) {
            throw DeviceTokenUnavailableException(error)
        }
        if (existing != null) {
            return existing.decodeToString().takeIf(::isDeviceToken) ?: throw DeviceTokenUnavailableException()
        }
        val created = ByteArray(TOKEN_BYTES).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        try {
            bytes.write(created.encodeToByteArray())
        } catch (error: Exception) {
            throw DeviceTokenUnavailableException(error)
        }
        return created
    }

    companion object {
        const val FILE_NAME = "device_credential"
        private const val TOKEN_BYTES = 32
        private var shared: DeviceTokenStore? = null

        /** The process-wide store in the app's private no-backup directory. */
        @Synchronized
        fun open(context: Context): DeviceTokenStore = shared ?: DeviceTokenStore(
            AtomicFilePlanStore.AtomicFileBytes(AtomicFile(File(context.applicationContext.noBackupFilesDir, FILE_NAME))),
        ).also { shared = it }
    }
}

/** Exactly 64 lowercase hexadecimal characters. */
fun isDeviceToken(value: String): Boolean = DEVICE_TOKEN.matches(value)

private val DEVICE_TOKEN = Regex("[0-9a-f]{64}")

/**
 * Sends [tokens] as `Authorization: Bearer` on every request to [apiBaseUrl]'s origin and stops
 * any follow-up (redirect or retry) to another origin before it is sent. The credential is read
 * on the calling worker thread; when it is unavailable the request fails without being sent.
 */
fun OkHttpClient.withDeviceCredential(apiBaseUrl: String, tokens: DeviceTokenSource): OkHttpClient {
    val origin = apiBaseUrl.toHttpUrl()
    return newBuilder()
        .addInterceptor(CredentialInterceptor(origin, tokens))
        .addNetworkInterceptor(SameOriginInterceptor(origin))
        .build()
}

private class CredentialInterceptor(private val origin: HttpUrl, private val tokens: DeviceTokenSource) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.sameOrigin(origin)) throw CrossOriginRequestException()
        val token = try {
            tokens.token()
        } catch (error: Exception) {
            throw error as? DeviceTokenUnavailableException ?: DeviceTokenUnavailableException(error)
        }
        if (!isDeviceToken(token)) throw DeviceTokenUnavailableException()
        return chain.proceed(request.newBuilder().header("Authorization", "Bearer $token").build())
    }
}

private class SameOriginInterceptor(private val origin: HttpUrl) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!chain.request().url.sameOrigin(origin)) throw CrossOriginRequestException()
        return chain.proceed(chain.request())
    }
}

private fun HttpUrl.sameOrigin(other: HttpUrl): Boolean =
    scheme == other.scheme && host == other.host && port == other.port
