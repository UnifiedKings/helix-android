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
    if (!resp.isSuccessful) throw HelixHttpException(resp.code())
    return resp.body().orEmpty()
}

private val JSON = "application/json; charset=utf-8".toMediaType()

internal fun JSONObject.toJsonBody(): RequestBody = toString().toRequestBody(JSON)
