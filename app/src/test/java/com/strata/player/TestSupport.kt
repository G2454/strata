package com.strata.player

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.test.espresso.IdlingPolicies
import com.strata.player.ui.StrataRoot
import java.util.concurrent.TimeUnit

/**
 * Starts the real UI for a test. Fails within 20 s (instead of Espresso's 60 s) if Compose never settles,
 * and says what was still busy, so a hang shows up as a readable error instead of a CI timeout.
 */
fun ComposeContentTestRule.launchStrata(model: AppModel) {
    IdlingPolicies.setMasterPolicyTimeout(20, TimeUnit.SECONDS)
    IdlingPolicies.setIdlingResourceTimeout(20, TimeUnit.SECONDS)
    try {
        setContent { StrataRoot(model) {} }
        waitForIdle()
    } catch (e: Throwable) {
        throw AssertionError("UI never settled. ${busyReport()}", e)
    }
}

/** Snapshot of what might keep Compose busy: pending state writes and every app/library thread's top frames. */
fun busyReport(): String = buildString {
    append("pendingSnapshotChanges=").append(Snapshot.current.hasPendingChanges()).append(". Threads: ")
    for ((t, st) in Thread.getAllStackTraces()) {
        val frames = st.take(5).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
        val interesting = st.any { f -> listOf("com.strata", "androidx", "media3", "coil", "kotlinx").any { f.className.contains(it) } }
        if (interesting || t.name.contains("strata") || t.name.contains("ExoPlayer") || t.name.contains("main")) {
            append("[").append(t.name).append(' ').append(t.state).append(": ").append(frames).append("] ")
        }
    }
}
