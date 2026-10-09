package com.strata.player.data

import android.os.Process
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * Low-priority worker threads, so library scanning, waveform decoding and colour extraction
 * never compete with the UI thread or the audio thread for CPU.
 */
object Background {
    private fun lowPriority(name: String): CoroutineDispatcher =
        Executors.newSingleThreadExecutor { r ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                r.run()
            }, name).apply { isDaemon = true }
        }.asCoroutineDispatcher()

    val scan: CoroutineDispatcher = lowPriority("strata-scan")
    val wave: CoroutineDispatcher = lowPriority("strata-wave")
    val art: CoroutineDispatcher = lowPriority("strata-art")
}
