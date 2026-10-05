package com.example.helixapp

import android.content.Context
import android.content.pm.ApplicationInfo
import com.example.helixapp.playback.PlayerRealtime
import okhttp3.OkHttpClient
import okhttp3.Interceptor
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import java.util.concurrent.TimeUnit

object HelixClient {
    private const val COOKIE_NAME = "mr_session"

    /**
     * Request header (stripped before sending) asking for a longer read timeout, in seconds,
     * for one slow endpoint; everything else keeps OkHttp's 10 s default so an unreachable
     * server still fails fast.
     */
    const val READ_TIMEOUT_HEADER = "X-Helix-Read-Timeout"

    @Volatile
    private var sharedClient: OkHttpClient? = null

    @Volatile
    private var cachedApi: Pair<String, HelixApi>? = null

    /**
     * Shared OkHttpClient for Retrofit and authenticated image/artwork fetches.
     *
     * One instance for the whole process so every call reuses the same connection pool and
     * dispatcher threads. The session cookie is read per request, so logging in or out takes
     * effect without rebuilding the client.
     */
    fun okHttpClient(context: Context): OkHttpClient {
        sharedClient?.let { return it }
        return synchronized(this) {
            sharedClient ?: buildClient(context.applicationContext).also { sharedClient = it }
        }
    }

    private fun buildClient(appContext: Context): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .addInterceptor(readTimeoutInterceptor())
            .addInterceptor(authCookieInterceptor(appContext))

        val debuggable = (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) {
            builder.addInterceptor(
                HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
            )
        }
        return builder.build()
    }

    private fun readTimeoutInterceptor() = Interceptor { chain ->
        val seconds = readTimeoutOverride(chain.request().header(READ_TIMEOUT_HEADER))
        if (seconds == null) {
            chain.proceed(chain.request())
        } else {
            val request = chain.request().newBuilder().removeHeader(READ_TIMEOUT_HEADER).build()
            chain.withReadTimeout(seconds, TimeUnit.SECONDS).proceed(request)
        }
    }

    /** The requested timeout in seconds, if the header holds a sensible one. */
    internal fun readTimeoutOverride(value: String?): Int? = value?.trim()?.toIntOrNull()?.takeIf { it in 1..300 }

    private fun authCookieInterceptor(context: Context): Interceptor {
        return Interceptor { chain ->
            val token = HelixPrefs.getSessionToken(context)
            val sentCookie = !token.isNullOrBlank()
            val req = if (sentCookie) {
                chain.request().newBuilder()
                    .header("Cookie", "$COOKIE_NAME=$token")
                    .build()
            } else {
                chain.request()
            }
            val resp = chain.proceed(req)
            if (AuthState.isSessionExpiry(resp.code, req.url.encodedPath, sentCookie)) {
                AuthState.markExpired("API ${req.url.encodedPath}")
            }
            resp
        }
    }

    fun create(
        context: Context,
        baseUrl: String,
        startRealtime: Boolean = true,
    ): HelixApi {
        if (startRealtime) {
            PlayerRealtime.ensureStarted(context.applicationContext)
        }

        val normalized = baseUrl.trim().trimEnd('/') + "/"
        cachedApi?.let { (url, api) -> if (url == normalized) return api }

        return synchronized(this) {
            cachedApi?.takeIf { it.first == normalized }?.second
                ?: Retrofit.Builder()
                    .baseUrl(normalized)
                    .client(okHttpClient(context))
                    .addConverterFactory(ScalarsConverterFactory.create())
                    .build()
                    .create(HelixApi::class.java)
                    .also { cachedApi = normalized to it }
        }
    }
}
