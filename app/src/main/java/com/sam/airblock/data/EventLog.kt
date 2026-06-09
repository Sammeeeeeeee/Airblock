package com.sam.airblock.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tiny ring-buffer activity log ("updated", "paused", "app opened"…), shown
 * in the app's Activity section. Plain text file, trimmed at 64 KB, fully
 * disabled (and deleted) via the settings toggle.
 */
object EventLog {
    private const val MAX_BYTES = 64L * 1024
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private fun file(context: Context) = File(context.filesDir, "events.log")

    @Synchronized
    fun append(context: Context, message: String) {
        val f = file(context)
        f.appendText("${fmt.format(Date())}  $message\n")
        if (f.length() > MAX_BYTES) {
            val lines = f.readLines()
            f.writeText(lines.takeLast(lines.size / 2).joinToString("\n") + "\n")
        }
    }

    /** Newest first. */
    @Synchronized
    fun read(context: Context, limit: Int = 200): List<String> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return f.readLines().asReversed().take(limit)
    }

    @Synchronized
    fun clear(context: Context) {
        file(context).delete()
    }
}
