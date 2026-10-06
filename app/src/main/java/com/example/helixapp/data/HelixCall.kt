package com.example.helixapp.data

import android.content.Context
import com.example.helixapp.HelixApi
import com.example.helixapp.HelixClient
import com.example.helixapp.HelixHttpException
import com.example.helixapp.HelixPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import retrofit2.Response

/**
 * Run one request against the signed-in Helix server on the IO dispatcher. Returns the
 * response body, or throws [HelixHttpException] for a non-2xx status.
 */
internal suspend fun helixCall(ctx: Context, request: suspend (HelixApi) -> Response<String>): String {
    val api = HelixClient.create(ctx, HelixPrefs.getBaseUrl(ctx))
    val resp = withContext(Dispatchers.IO) { request(api) }
    if (!resp.isSuccessful) {
        val errorBody = withContext(Dispatchers.IO) { runCatching { resp.errorBody()?.string() }.getOrNull() }
        throw HelixHttpException(resp.code(), errorDetail(errorBody))
    }
    return resp.body().orEmpty()
}

/** The "detail" of a FastAPI error body when it's a short string; "" otherwise. */
internal fun errorDetail(body: String?): String {
    val detail = runCatching { JSONObject(body.orEmpty()).opt("detail") }.getOrNull()
    return (detail as? String)?.trim()?.takeIf { it.length <= MAX_DETAIL } ?: ""
}

private const val MAX_DETAIL = 160

private val JSON = "application/json; charset=utf-8".toMediaType()

internal fun JSONObject.toJsonBody(): RequestBody = toString().toRequestBody(JSON)
