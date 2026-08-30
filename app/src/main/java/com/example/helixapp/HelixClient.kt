package com.example.helixapp

import android.content.Context
import com.example.helixapp.playback.PlayerRealtime
import okhttp3.OkHttpClient
import okhttp3.Interceptor
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory

object HelixClient {
    private const val COOKIE_NAME = "mr_session"
    /** Shared OkHttpClient for both Retrofit and Coil (covers that require auth cookie). */
    fun okHttpClient(context: Context): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        return OkHttpClient.Builder()
            .addInterceptor(authCookieInterceptor(context))
            .addInterceptor(logging)
            .build()
    }
    private fun authCookieInterceptor(context: Context): Interceptor {
        return Interceptor { chain ->
            val token = HelixPrefs.getSessionToken(context)
            val req = if (!token.isNullOrBlank()) {
                chain.request().newBuilder()
                    .header("Cookie", "$COOKIE_NAME=$token")
                    .build()
            } else {
                chain.request()
            }
            chain.proceed(req)
        }
    }
    fun create(context: Context, baseUrl: String): HelixApi {
        // Start the same realtime player feed used by the web frontend. It is process-wide,
        // so subsequent API clients simply verify that the connection still matches the
        // current Helix URL/session.
        PlayerRealtime.ensureStarted(context.applicationContext)

        val okHttp = okHttpClient(context)
        val normalized = baseUrl.trim().trimEnd('/') + "/"

        return Retrofit.Builder()
            .baseUrl(normalized)
            .client(okHttp)
            .addConverterFactory(ScalarsConverterFactory.create())
            .build()
            .create(HelixApi::class.java)
    }
}
