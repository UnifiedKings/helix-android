package com.example.helixapp.data

import android.content.Context
import com.example.helixapp.HistoryPage
import com.example.helixapp.parseHistoryPage

/** Listening history, search, artists and albums. */
interface LibraryRepository {
    /** A page of history, newest first; [event] is "completed", "skipped" or null for both. */
    suspend fun history(event: String?, offset: Int, limit: Int): HistoryPage
}

class HelixLibraryRepository(context: Context) : LibraryRepository {
    private val ctx = context.applicationContext

    override suspend fun history(event: String?, offset: Int, limit: Int): HistoryPage =
        parseHistoryPage(helixCall(ctx) { it.history(event = event, limit = limit, offset = offset) })
}
