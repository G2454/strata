package com.strata.player.data

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last crash in a small text file, so it can be copied from Settings and pasted somewhere useful.
 * Nothing leaves the phone unless you copy it yourself.
 */
object CrashLog {

    @Volatile private var installed = false

    private fun file(context: Context) = File(File(context.filesDir, "strata"), "last-crash.txt")

    /** Wraps the default handler once per process: the report is written, then the app crashes as usual. */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try { record(app, thread.name, error) } catch (_: Throwable) { }
            previous?.uncaughtException(thread, error)
        }
    }

    fun record(context: Context, threadName: String, error: Throwable, now: Long = System.currentTimeMillis()) {
        val f = file(context)
        f.parentFile?.mkdirs()
        f.writeText(report(context, threadName, error, now))
    }

    fun report(context: Context, threadName: String, error: Throwable, now: Long): String = buildString {
        appendLine("Strata crash report")
        appendLine("Time: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(now)))
        appendLine("App: " + appVersion(context))
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Thread: $threadName")
        appendLine()
        append(error.stackTraceToString().take(60_000))
    }

    /** The last report, or null if Strata hasn't crashed since it was cleared. */
    fun read(context: Context): String? = try {
        file(context).takeIf { it.exists() }?.readText()
    } catch (_: Exception) {
        null
    }

    fun clear(context: Context) {
        try { file(context).delete() } catch (_: Exception) { }
    }

    fun appVersion(context: Context): String = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }
}
