package com.example.octo.youtube

import android.content.Intent
import android.os.Bundle
import com.example.octo.R
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.example.octo.youtube.YoutubeUrlParser.parse
import kotlinx.coroutines.launch

/**
 * ACTION_SEND trampoline (docs/ARCHITECTURE.md §4.1): receives the YouTube app's shared
 * text, extracts the video URL, inserts a PENDING library row and enqueues the download —
 * then finishes immediately. Declared `Theme.Translucent.NoTitleBar`, noHistory,
 * excludeFromRecents so it is invisible.
 */
class ShareIngestActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedText = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            else -> ""
        }

        val parsed = parse(sharedText)
        if (parsed == null) {
            Toast.makeText(this, R.string.share_invalid_url, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val container = (application as com.example.octo.OctoApplication).appContainer
        val repository = container.videoRepository
        lifecycleScope.launch {
            val row = repository.addSharedUrl(sharedText)
            if (row != null) {
                Toast.makeText(this@ShareIngestActivity, R.string.share_added, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@ShareIngestActivity, R.string.share_invalid_url, Toast.LENGTH_SHORT).show()
            }
            finish()
        }
    }
}
