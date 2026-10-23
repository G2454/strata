@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.strata.player.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.strata.player.data.LONG_FILE_MS
import com.strata.player.data.Order
import com.strata.player.data.RgMode
import com.strata.player.data.Settings
import com.strata.player.data.TagInfo
import com.strata.player.data.TagReader
import com.strata.player.data.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UpItem(val index: Int, val trackId: Long, val queued: Boolean)

/** Owns the ExoPlayer instance, the DSP chain and the foobar2000-style playlist logic. */
class Engine(
    private val app: Context,
    private val scope: CoroutineScope,
    private val tags: TagReader,
) {
    val core = DspCore()
    private val processor = StrataAudioProcessor(core)

    val player: ExoPlayer

    /** Resolves ids to tracks (with the user's tag overrides applied). */
    var lookup: (Long) -> Track? = { null }
    var onTrackStarted: (Long) -> Unit = {}
    var onTick: (positionMs: Long) -> Unit = {}
    var onMessage: (String) -> Unit = {}
    /** Track, play/pause or queue changed: refresh things outside the app (home-screen widgets). */
    var onStateChanged: () -> Unit = {}
    /** Remembered positions of long files changed (persist them). */
    var onPositionsChanged: (Map<Long, Long>) -> Unit = {}

    var currentId by mutableLongStateOf(-1L); private set
    var isPlaying by mutableStateOf(false); private set
    /** True while the user wants playback (also during buffering), so play/pause buttons reflect intent. */
    var playIntent by mutableStateOf(false); private set
    var ctxName by mutableStateOf("All tracks"); private set
    var order by mutableStateOf(Order.DEFAULT); private set
    var timelineVersion by mutableIntStateOf(0); private set
    var sleepAtMs by mutableStateOf<Long?>(null); private set
    var sleepEndOfTrack by mutableStateOf(false); private set
    var sleepChoice by mutableStateOf<Int?>(null); private set
    /** "Stop after N songs": how many songs are left, counting the current one. */
    var sleepTracksLeft by mutableStateOf<Int?>(null); private set
    var currentTags by mutableStateOf<TagInfo?>(null); private set
    var appliedGainDb by mutableStateOf<Float?>(null); private set
    var preferredDeviceId by mutableIntStateOf(0); private set

    var context: List<Long> = emptyList(); private set
    private var settings = Settings()
    private var nonce = 0L
    private var playingQueuedMediaId: String? = null
    private var ticker: Job? = null
    private val positions = HashMap<Long, Long>()
    private var devicesPrimed = false

    init {
        val renderers = object : DefaultRenderersFactory(app) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf<AudioProcessor>(processor))
                .build()
        }
        player = ExoPlayer.Builder(app, renderers)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val prevQueued = playingQueuedMediaId
                val newMediaId = mediaItem?.mediaId
                currentId = idOf(mediaItem)
                playingQueuedMediaId = newMediaId?.takeIf { it.startsWith("q:") }
                if (prevQueued != null && prevQueued != newMediaId) {
                    val idx = indexOfMediaId(prevQueued)
                    if (idx >= 0 && idx != player.currentMediaItemIndex) player.removeMediaItem(idx)
                }
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                ) {
                    if (currentId >= 0) onTrackStarted(currentId)
                }
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) countDownSleepTracks()
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) resumeLongFile()
                loadCurrentTags()
                bump()
                onStateChanged()
            }

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                // Leaving a long file: remember where we were, or forget it when it played to the end.
                val oldId = idOf(oldPosition.mediaItem)
                if (oldId < 0 || oldPosition.mediaItemIndex == newPosition.mediaItemIndex && oldPosition.mediaItem == newPosition.mediaItem) return
                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) forgetPosition(oldId)
                else rememberPosition(oldId, oldPosition.positionMs)
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (playing) startTicker()
                if (!playing && sleepEndOfTrack && player.playbackState != Player.STATE_BUFFERING) {
                    sleepEndOfTrack = false
                    sleepTracksLeft = null
                    player.pauseAtEndOfMediaItems = false
                    player.volume = 1f
                    onMessage("Sleep timer: playback stopped")
                }
                if (!playing) rememberPosition(currentId, player.currentPosition)
                onTick(player.currentPosition)
                onStateChanged()
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                bump()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                refreshIntent()
                onStateChanged()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                refreshIntent()
            }
        })

        // Resume when headphones / Bluetooth connect. Android calls onAudioDevicesAdded once right away
        // with the devices already connected; that first call is skipped.
        try {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.registerAudioDeviceCallback(object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                    if (!devicesPrimed) { devicesPrimed = true; return }
                    onOutputsConnected(addedDevices.filter { it.isSink }.map { it.type })
                }
            }, Handler(Looper.getMainLooper()))
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------ headphones

    /** Called with the types of newly connected outputs. Resumes playback if the settings ask for it. */
    fun onOutputsConnected(types: List<Int>) {
        if (player.mediaItemCount == 0 || player.playWhenReady) return
        val wired = types.any { it in WIRED_TYPES }
        val bluetooth = types.any { it in BLUETOOTH_TYPES }
        val go = (wired && settings.resumeOnWired) || (bluetooth && settings.resumeOnBluetooth)
        if (!go) return
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(player.currentMediaItemIndex, 0)
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
        refreshIntent()
        onMessage(if (bluetooth && !wired) "Bluetooth connected · playing" else "Headphones connected · playing")
    }

    // ------------------------------------------------------------------ settings → player/DSP

    fun applySettings(s: Settings) {
        val orderChanged = s.order != order
        settings = s
        player.skipSilenceEnabled = s.skipSilence
        player.playbackParameters = PlaybackParameters(s.speed, if (s.keepPitch) 1f else s.speed)
        player.setHandleAudioBecomingNoisy(s.pauseOnDisconnect)
        if (orderChanged) changeOrder(s.order) else applyRepeat()
        pushDsp()
    }

    private fun pushDsp() {
        val s = settings
        val info = currentTags
        var peak: Float? = null
        var gain: Float? = null
        if (s.rgMode != RgMode.OFF && info != null) {
            val useAlbum = when (s.rgMode) {
                RgMode.ALBUM -> true
                RgMode.TRACK -> false
                else -> isAlbumRun()
            }
            if (useAlbum) {
                gain = info.rgAlbumGain ?: info.rgTrackGain; peak = info.rgAlbumPeak ?: info.rgTrackPeak
            } else {
                gain = info.rgTrackGain ?: info.rgAlbumGain; peak = info.rgTrackPeak ?: info.rgAlbumPeak
            }
        }
        val total = when {
            s.rgMode == RgMode.OFF -> 0f
            gain != null -> gain + s.rgPreamp
            else -> s.rgPreampNoInfo
        }
        appliedGainDb = if (s.rgMode == RgMode.OFF) null else total
        core.params = DspParams(
            eqOn = s.eqOn,
            bandsDb = s.bands.toFloatArray(),
            preampDb = s.preamp,
            gainDb = total,
            clipGuardPeak = if (s.rgPreventClip && gain != null) (peak ?: 0f) else 0f,
            widen = s.widen,
            mono = s.mono,
            balance = s.balance,
            limiter = s.limiter,
            chain = s.chain,
        )
    }

    /** "Smart" ReplayGain: album gain while neighbouring items come from the same album. */
    private fun isAlbumRun(): Boolean {
        if (order == Order.SHUFFLE_TRACKS || order == Order.RANDOM) return false
        val cur = lookup(currentId) ?: return false
        val i = player.currentMediaItemIndex
        val neighbors = listOf(i - 1, i + 1).filter { it in 0 until player.mediaItemCount }
        return neighbors.any { lookup(idOf(player.getMediaItemAt(it)))?.albumId == cur.albumId }
    }

    private fun loadCurrentTags() {
        val t = lookup(currentId) ?: return
        val cached = tags.cached(t.id)
        if (cached != null) {
            currentTags = cached; pushDsp(); prefetchNext(); return
        }
        currentTags = null
        pushDsp()
        val want = t.id
        scope.launch(Dispatchers.IO) {
            val info = tags.read(t)
            withContext(Dispatchers.Main) {
                if (currentId == want) { currentTags = info; pushDsp() }
                // ExoPlayer must only be touched on the main thread.
                prefetchNext()
            }
        }
    }

    private fun prefetchNext() {
        val i = player.currentMediaItemIndex + 1
        if (i >= player.mediaItemCount) return
        val t = lookup(idOf(player.getMediaItemAt(i))) ?: return
        if (tags.cached(t.id) == null) scope.launch(Dispatchers.IO) { tags.read(t) }
    }

    // ------------------------------------------------------------------ playlist logic

    fun playContext(ids: List<Long>, startId: Long, name: String, startPosMs: Long = 0, play: Boolean = true) {
        val valid = ids.filter { lookup(it) != null }
        if (valid.isEmpty()) return
        val first = if (startId in valid) startId else valid.first()
        context = valid
        ctxName = name
        val seq = sequenceFor(valid, order, first)
        val items = seq.map { item(lookup(it)!!, false) }
        playingQueuedMediaId = null
        player.setMediaItems(items, seq.indexOf(first).coerceAtLeast(0), startPosMs)
        applyRepeat()
        player.prepare()
        if (play) {
            player.play()
            onTrackStarted(first)
        }
        bump()
    }

    fun shuffleContext(ids: List<Long>, name: String) {
        if (ids.isEmpty()) return
        if (!order.isShuffle) order = Order.SHUFFLE_TRACKS
        playContext(ids, ids.random(), name)
    }

    fun changeOrder(o: Order) {
        order = o
        if (player.mediaItemCount == 0) { applyRepeat(); return }
        val queued = queuedItemsAfterCurrent().map { player.getMediaItemAt(it) }
        relayout(sequenceFor(context, o, currentId), queued)
        pushDsp()
    }

    private fun sequenceFor(ids: List<Long>, o: Order, first: Long): List<Long> = when (o) {
        Order.SHUFFLE_TRACKS, Order.RANDOM -> {
            val rest = ids.filter { it != first }.shuffled()
            if (first in ids) listOf(first) + rest else rest
        }
        Order.SHUFFLE_ALBUMS -> groupShuffle(ids, first) { it.albumId.toString() }
        Order.SHUFFLE_FOLDERS -> groupShuffle(ids, first) { it.folder }
        else -> ids
    }

    private fun groupShuffle(ids: List<Long>, first: Long, key: (Track) -> String): List<Long> {
        val groups = LinkedHashMap<String, MutableList<Long>>()
        ids.forEach { id -> lookup(id)?.let { groups.getOrPut(key(it)) { mutableListOf() }.add(id) } }
        val all = groups.values.toMutableList()
        val firstGroup = all.firstOrNull { first in it }
        if (firstGroup != null) all.remove(firstGroup)
        all.shuffle()
        val ordered = if (firstGroup != null) listOf(firstGroup) + all else all
        return ordered.flatten()
    }

    private fun relayout(seq: List<Long>, queued: List<MediaItem>) {
        val n = player.mediaItemCount
        if (n == 0) return
        val ci = player.currentMediaItemIndex
        val k = seq.indexOf(currentId)
        if (ci + 1 < n) player.removeMediaItems(ci + 1, n)
        if (ci > 0) player.removeMediaItems(0, ci)
        val before = if (k > 0) seq.subList(0, k) else emptyList()
        val after = if (k >= 0) seq.subList(k + 1, seq.size) else seq
        if (before.isNotEmpty()) player.addMediaItems(0, before.mapNotNull { id -> lookup(id)?.let { item(it, false) } })
        player.addMediaItems(queued + after.mapNotNull { id -> lookup(id)?.let { item(it, false) } })
        applyRepeat()
        bump()
    }

    private fun applyRepeat() {
        player.shuffleModeEnabled = false
        player.repeatMode = when (order) {
            Order.REPEAT_TRACK -> Player.REPEAT_MODE_ONE
            Order.DEFAULT -> Player.REPEAT_MODE_OFF
            else -> Player.REPEAT_MODE_ALL
        }
    }

    fun queuedItemsAfterCurrent(): List<Int> {
        val out = ArrayList<Int>()
        var i = player.currentMediaItemIndex + 1
        while (i < player.mediaItemCount && player.getMediaItemAt(i).mediaId.startsWith("q:")) { out += i; i++ }
        return out
    }

    fun playNext(t: Track) {
        if (player.mediaItemCount == 0) { playContext(listOf(t.id), t.id, "Queue"); return }
        player.addMediaItem(player.currentMediaItemIndex + 1, item(t, true))
        bump()
    }

    fun addToQueue(t: Track) {
        if (player.mediaItemCount == 0) { playContext(listOf(t.id), t.id, "Queue"); return }
        val at = player.currentMediaItemIndex + 1 + queuedItemsAfterCurrent().size
        player.addMediaItem(at, item(t, true))
        bump()
    }

    /** Many tracks at once, in order: right after the current one, or at the end of the playback queue. */
    fun enqueue(ts: List<Track>, next: Boolean) {
        if (ts.isEmpty()) return
        if (player.mediaItemCount == 0) { playContext(ts.map { it.id }, ts.first().id, "Queue"); return }
        val at = player.currentMediaItemIndex + 1 + if (next) 0 else queuedItemsAfterCurrent().size
        player.addMediaItems(at, ts.map { item(it, true) })
        bump()
    }

    fun removeAt(index: Int) {
        if (index in 0 until player.mediaItemCount && index != player.currentMediaItemIndex) player.removeMediaItem(index)
    }

    fun clearQueue() {
        queuedItemsAfterCurrent().sortedDescending().forEach { player.removeMediaItem(it) }
    }

    /** Drag-to-reorder. The current track itself doesn't move. */
    fun move(from: Int, to: Int) {
        val n = player.mediaItemCount
        val ci = player.currentMediaItemIndex
        if (from !in 0 until n || to !in 0 until n || from == to || from == ci || to == ci) return
        player.moveMediaItem(from, to)
        bump()
    }

    /** Swipe right in the queue: this item plays right after the current one. */
    fun moveToNext(index: Int) {
        val ci = player.currentMediaItemIndex
        if (index !in 0 until player.mediaItemCount || index == ci) return
        val mi = player.getMediaItemAt(index)
        if (mi.mediaId.startsWith("q:")) {
            val to = if (index > ci) ci + 1 else ci
            player.moveMediaItem(index, to)
        } else {
            val t = lookup(idOf(mi)) ?: return
            player.removeMediaItem(index)
            player.addMediaItem(player.currentMediaItemIndex + 1, item(t, true))
        }
        bump()
    }

    /** Removes an item and returns what is needed to undo it. */
    fun removeForUndo(index: Int): Pair<Int, MediaItem>? {
        if (index !in 0 until player.mediaItemCount || index == player.currentMediaItemIndex) return null
        val mi = player.getMediaItemAt(index)
        player.removeMediaItem(index)
        bump()
        return index to mi
    }

    fun restore(removed: Pair<Int, MediaItem>) {
        player.addMediaItem(removed.first.coerceIn(0, player.mediaItemCount), removed.second)
        bump()
    }

    /** Tracks already played in this queue, oldest first. */
    fun history(limit: Int = 25): List<UpItem> {
        val ci = player.currentMediaItemIndex
        if (player.mediaItemCount == 0) return emptyList()
        return (maxOf(0, ci - limit) until ci).map { i -> player.getMediaItemAt(i).let { UpItem(i, idOf(it), it.mediaId.startsWith("q:")) } }
    }

    /** Every track in the queue, in play order. */
    fun timelineIds(): List<Long> = (0 until player.mediaItemCount).map { idOf(player.getMediaItemAt(it)) }.filter { it >= 0 }

    /** Loads a saved queue exactly in its saved order (no reshuffle). */
    fun loadQueue(ids: List<Long>, index: Int, positionMs: Long, name: String, play: Boolean) {
        val valid = ids.filter { lookup(it) != null }
        if (valid.isEmpty()) return
        val start = ids.getOrNull(index)?.let { valid.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        context = valid
        ctxName = name
        playingQueuedMediaId = null
        player.setMediaItems(valid.map { item(lookup(it)!!, false) }, start, positionMs)
        applyRepeat()
        player.prepare()
        if (play) player.play()
        bump()
        onStateChanged()
    }

    fun seekBy(deltaMs: Long) {
        val dur = player.duration.takeIf { it > 0 } ?: (lookup(currentId)?.durationMs ?: 0L)
        val to = (player.currentPosition + deltaMs).coerceAtLeast(0)
        player.seekTo(if (dur > 0) to.coerceAtMost(dur - 500).coerceAtLeast(0) else to)
    }

    fun upNext(limit: Int = 40): List<UpItem> {
        val out = ArrayList<UpItem>()
        val n = player.mediaItemCount
        if (n == 0) return out
        var i = player.currentMediaItemIndex + 1
        while (i < n && out.size < limit) {
            val mi = player.getMediaItemAt(i)
            out += UpItem(i, idOf(mi), mi.mediaId.startsWith("q:"))
            i++
        }
        return out
    }

    fun jumpTo(index: Int) {
        if (index in 0 until player.mediaItemCount) {
            player.seekTo(index, 0)
            player.play()
        }
    }

    // ------------------------------------------------------------------ transport

    private fun refreshIntent() {
        val st = player.playbackState
        playIntent = player.playWhenReady && st != Player.STATE_IDLE && st != Player.STATE_ENDED
    }

    fun togglePlay() {
        if (playIntent) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0, 0)
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        }
        refreshIntent()
    }

    fun next() {
        if (player.hasNextMediaItem()) player.seekToNextMediaItem()
        else if (player.mediaItemCount > 0 && order != Order.DEFAULT) player.seekTo(0, 0)
    }

    fun previous() {
        if (player.currentPosition > 3000 || !player.hasPreviousMediaItem()) player.seekTo(0) else player.seekToPreviousMediaItem()
    }

    fun seekTo(ms: Long) = player.seekTo(ms.coerceAtLeast(0))

    // ------------------------------------------------------------------ sleep timer, fades

    fun setSleepMinutes(min: Int?) {
        sleepAtMs = min?.let { SystemClock.elapsedRealtime() + it * 60_000L }
        sleepChoice = min
        sleepTracksLeft = null
        if (sleepEndOfTrack) { sleepEndOfTrack = false; player.pauseAtEndOfMediaItems = false }
        if (min == null) player.volume = 1f
        startTicker()
    }

    fun setSleepEndOfTrack() {
        sleepAtMs = null
        sleepChoice = null
        sleepTracksLeft = null
        sleepEndOfTrack = true
        player.pauseAtEndOfMediaItems = true
        startTicker()
    }

    /** Stops after [n] songs, counting the one playing now. */
    fun setSleepAfterTracks(n: Int) {
        if (n <= 1) { setSleepEndOfTrack(); return }
        sleepAtMs = null
        sleepChoice = null
        sleepEndOfTrack = false
        player.pauseAtEndOfMediaItems = false
        sleepTracksLeft = n
        startTicker()
    }

    /** "+15 min": extends a running timer, or starts one. */
    fun addSleepMinutes(min: Int) {
        val base = sleepAtMs ?: SystemClock.elapsedRealtime()
        sleepAtMs = base + min * 60_000L
        sleepChoice = null
        sleepTracksLeft = null
        if (sleepEndOfTrack) { sleepEndOfTrack = false; player.pauseAtEndOfMediaItems = false }
        startTicker()
    }

    /** One song finished on its own (also called by tests). */
    @androidx.annotation.VisibleForTesting
    fun countDownSleepTracks() {
        val left = sleepTracksLeft ?: return
        val now = left - 1
        if (now <= 1) {
            sleepTracksLeft = null
            sleepEndOfTrack = true
            player.pauseAtEndOfMediaItems = true
        } else {
            sleepTracksLeft = now
        }
    }

    fun sleepRemainingMs(): Long? = sleepAtMs?.let { (it - SystemClock.elapsedRealtime()).coerceAtLeast(0) }

    /** Volume factor for the sleep fade: the last 30 s of a timer, or the last 10 s of the final track. */
    fun sleepFade(): Float {
        sleepRemainingMs()?.let { left -> if (left < SLEEP_FADE_MS) return left.toFloat() / SLEEP_FADE_MS }
        if (player.pauseAtEndOfMediaItems && sleepEndOfTrack) {
            val dur = player.duration
            if (dur > 0) {
                val left = dur - player.currentPosition
                if (left in 0 until TRACK_FADE_MS) return left.toFloat() / TRACK_FADE_MS
            }
        }
        return 1f
    }

    // ------------------------------------------------------------------ long files remember their position

    fun installPositions(saved: Map<Long, Long>) {
        positions.clear(); positions.putAll(saved)
    }

    fun savedPosition(id: Long): Long? = positions[id]

    private fun isLong(id: Long): Boolean {
        val t = lookup(id) ?: return false
        return settings.rememberLongPositions && t.durationMs >= LONG_FILE_MS
    }

    private fun rememberPosition(id: Long, pos: Long) {
        if (id < 0 || !isLong(id)) return
        val dur = lookup(id)?.durationMs ?: return
        if (pos < 15_000 || pos > dur - 15_000) {
            if (positions.remove(id) != null) onPositionsChanged(positions.toMap())
            return
        }
        if (positions[id] != pos) { positions[id] = pos; onPositionsChanged(positions.toMap()) }
    }

    private fun forgetPosition(id: Long) {
        if (positions.remove(id) != null) onPositionsChanged(positions.toMap())
    }

    /** A long file that starts from the top jumps to where you left it. */
    private fun resumeLongFile() {
        val id = currentId
        if (!isLong(id)) return
        val saved = positions[id] ?: return
        if (player.currentPosition > 2_000) return
        player.seekTo(saved)
        onMessage("Resuming at ${fmtTime(saved)}")
    }

    private fun fmtTime(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            var n = 0
            while (isActive) {
                delay(100)
                val playing = player.isPlaying
                // Fade between tracks (volume follows real output position).
                val fade = settings.fadeSec * 1000L
                if (fade > 0 && playing) {
                    val pos = player.currentPosition
                    val dur = player.duration
                    var v = 1f
                    if (pos < fade) v = pos.toFloat() / fade
                    if (dur > 0 && dur - pos < fade && (player.hasNextMediaItem() || order != Order.DEFAULT)) v = minOf(v, (dur - pos).toFloat() / fade)
                    player.volume = v.coerceIn(0f, 1f)
                } else if (player.volume != 1f && sleepFade() >= 1f) {
                    player.volume = 1f
                }
                // The sleep timer fades out instead of cutting off.
                val sf = sleepFade()
                if (sf < 1f && playing) player.volume = minOf(player.volume, sf).coerceIn(0f, 1f)
                val at = sleepAtMs
                if (at != null && SystemClock.elapsedRealtime() >= at) {
                    sleepAtMs = null
                    sleepChoice = null
                    player.pause()
                    player.volume = 1f
                    onMessage("Sleep timer ended. Playback paused.")
                }
                if (++n % 50 == 0) {
                    onTick(player.currentPosition)
                    rememberPosition(currentId, player.currentPosition)
                }
                if (!playing && sleepAtMs == null) break
            }
        }
    }

    // ------------------------------------------------------------------ output devices

    fun outputDevices(): List<AudioDeviceInfo> {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter {
            it.type != AudioDeviceInfo.TYPE_TELEPHONY && it.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        }
    }

    fun setPreferredDevice(device: AudioDeviceInfo?) {
        player.setPreferredAudioDevice(device)
        preferredDeviceId = device?.id ?: 0
    }

    /** Rough output latency, used to line up the visualizer with what you hear. */
    fun latencyFrames(): Int = (core.tapRate * 0.12).toInt()

    // ------------------------------------------------------------------ helpers

    private fun bump() { timelineVersion++ }

    private fun indexOfMediaId(mediaId: String): Int {
        for (i in 0 until player.mediaItemCount) if (player.getMediaItemAt(i).mediaId == mediaId) return i
        return -1
    }

    private fun item(t: Track, queued: Boolean): MediaItem = MediaItem.Builder()
        .setMediaId(if (queued) "q:${t.id}:${nonce++}" else "t:${t.id}")
        .setUri(t.uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(t.title)
                .setArtist(t.artist)
                .setAlbumTitle(t.album)
                .setAlbumArtist(t.albumArtist)
                .setArtworkUri(t.artUri)
                .setTrackNumber(t.trackNo)
                .setIsPlayable(true)
                .build(),
        )
        .build()

    companion object {
        fun idOf(item: MediaItem?): Long = item?.mediaId?.split(':')?.getOrNull(1)?.toLongOrNull() ?: -1L

        const val SLEEP_FADE_MS = 30_000L
        const val TRACK_FADE_MS = 10_000L

        val WIRED_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_LINE_ANALOG,
        )
        val BLUETOOTH_TYPES = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, 26 /* TYPE_BLE_HEADSET */, 27 /* TYPE_BLE_SPEAKER */)
    }
}
