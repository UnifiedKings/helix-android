package com.example.helixapp

import android.content.Context
import android.content.pm.ApplicationInfo
import com.example.helixapp.playback.PlayerRealtime
import okhttp3.OkHttpClient
import okhttp3.Interceptor
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory

object HelixClient {
    private const val COOKIE_NAME = "mr_session"

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
            .addInterceptor(authCookieInterceptor(appContext))

        val debuggable = (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) {
            builder.addInterceptor(
                HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
            )
        }
        return builder.build()
    }

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
