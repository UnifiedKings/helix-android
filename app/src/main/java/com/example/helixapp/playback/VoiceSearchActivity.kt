package com.example.helixapp.playback

import android.app.Activity
import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.view.WindowManager
import com.example.helixapp.HelixPrefs
import com.example.helixapp.MainActivity

/**
 * Receives Android's play-from-search request ("Hey Google, play X on Helix" when Helix isn't
 * already connected to the assistant). Shows nothing and lets touches through; it hands the
 * request to [VoiceSearch] and stays open until playback starts, because a freshly started
 * process that drops to the background right away can have its network blocked. Without a
 * saved session it opens the app to sign in instead.
 */
class VoiceSearchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        val intent = intent
        when {
            intent?.action != MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH -> finish()
            HelixPrefs.getSessionToken(this).isNullOrBlank() -> {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
            }
            else -> {
                val job = VoiceSearch.startFromIntent(this, intent.getStringExtra(SearchManager.QUERY), intent.extras)
                job.invokeOnCompletion { runOnUiThread { finish() } }
                window.decorView.postDelayed({ finish() }, MAX_WAIT_MS)
            }
        }
    }

    private companion object {
        const val MAX_WAIT_MS = 15_000L
    }
}
