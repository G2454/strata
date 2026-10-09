package com.strata.player.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.strata.player.AppModel
import com.strata.player.NpView
import com.strata.player.Sheet
import com.strata.player.audio.Analyzer
import com.strata.player.audio.Engine
import com.strata.player.audio.Waveform
import com.strata.player.data.LyricLine
import com.strata.player.data.Order
import com.strata.player.data.RgMode
import com.strata.player.data.Track
import kotlinx.coroutines.delay

@Composable
fun rememberPosition(e: Engine, intervalMs: Long = 100): State<Long> =
    produceState(e.player.currentPosition, e.currentId, e.isPlaying) {
        while (true) {
            value = e.player.currentPosition
            if (!e.isPlaying) break
            delay(intervalMs)
        }
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NowPlayingScreen(model: AppModel) {
    val t = LocalTokens.current
    val e = model.engine
    val cur = model.track(e.currentId) ?: return
    val tech = model.tech[cur.id] ?: e.currentTags?.tech
    val glow = t.accentRaw.copy(alpha = if (t.dark) 0.24f else 0.4f)

    Column(
        Modifier
            .fillMaxSize()
            .testTag("nowplaying")
            .blockTouches()
            .background(t.bg)
            .background(Brush.verticalGradient(0f to glow, 0.58f to t.bg))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Top bar
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.chevronDown, "Close now playing", { model.nowOpen = false }, iconSize = 24.dp)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("PLAYING FROM", style = Type.label(10), color = t.sub)
                Text(e.ctxName, style = Type.body(13, FontWeight.SemiBold), color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconBtn(Ic.more, "More options", { model.sheet = Sheet.TrackMenu(cur.id) })
        }
        // View switch
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.Center) {
            Row(
                Modifier.clip(RoundedCornerShape(22.dp)).background(if (t.dark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f)).padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                NpView.entries.forEach { v ->
                    val on = model.npView == v
                    Box(
                        Modifier.height(38.dp).clip(RoundedCornerShape(19.dp))
                            .background(if (on) (if (t.dark) Color.White.copy(alpha = 0.14f) else Color.White) else Color.Transparent)
                            .clickable { model.npView = v }.padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(v.label, style = Type.body(13, FontWeight.Medium), color = if (on) t.text else t.sub) }
                }
            }
        }
        // Stage
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 28.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            Crossfade(model.npView, label = "stage") { v ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    when (v) {
                        NpView.ART -> Art(
                            cur,
                            Modifier.widthIn(max = 340.dp).fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true)
                                .shadow(30.dp, RoundedCornerShape(24.dp), ambientColor = t.accentRaw, spotColor = t.accentRaw),
                            RoundedCornerShape(24.dp), big = true,
                        )
                        NpView.LYRICS -> LyricsView(model, cur)
                        NpView.VIZ -> Visualizer(e)
                        NpView.INFO -> InfoTable(model, cur)
                    }
                }
            }
        }
        // Title
        Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 16.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(cur.title, style = Type.display(25), color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${cur.artist} · ${cur.album}", style = Type.body(15), color = t.sub, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { model.goTo(com.strata.player.Detail.AlbumD(cur.albumId)) },
                )
            }
            val fav = model.isFav(cur.id)
            IconBtn(if (fav) Ic.heartFill else Ic.heart, if (fav) "Remove from favorites" else "Add to favorites", { model.toggleFav(cur.id) }, tint = if (fav) t.accent else t.sub, size = 48.dp, iconSize = 26.dp)
        }
        // Badges
        FlowRow(Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val b = ArrayList<Pair<String, Boolean>>()
            b += cur.codec to false
            if (tech != null && tech.sampleRate > 0) b += (if (cur.lossless && tech.bits > 0) "${tech.bits}-bit / ${Fmt.rate(tech.sampleRate)} kHz" else "${Fmt.rate(tech.sampleRate)} kHz") to false
            if (cur.bitrateBps > 0) b += "${cur.bitrateBps / 1000} kbps" to false
            e.appliedGainDb?.let { g -> if (model.settings.rgMode != RgMode.OFF) b += "RG ${Fmt.db(g)}" to false }
            if (model.settings.speed != 1f) b += "${model.settings.speed}×" to true
            b.forEach { (label, hi) ->
                Text(label, style = Type.mono(10.5f), color = if (hi) t.accent else t.sub, modifier = Modifier.border(1.dp, t.line2, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 3.dp))
            }
        }
        // Seek
        SeekBar(model, cur)
        // Controls
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            val shuffleOn = model.settings.order.isShuffle
            val repeatOn = model.settings.order.isRepeat
            ModeButton(Ic.shuffle, "Shuffle: ${model.settings.order.label}", shuffleOn, null) {
                val seq = listOf(Order.DEFAULT, Order.SHUFFLE_TRACKS, Order.SHUFFLE_ALBUMS, Order.SHUFFLE_FOLDERS, Order.RANDOM)
                val i = seq.indexOf(model.settings.order)
                val next = if (i < 0) Order.SHUFFLE_TRACKS else seq[(i + 1) % seq.size]
                model.updateSettings { it.copy(order = next) }
                model.flash(next.label)
            }
            IconBtn(Ic.prev, "Previous track", e::previous, size = 60.dp, iconSize = 30.dp)
            Box(
                Modifier.size(78.dp).shadow(16.dp, CircleShape, ambientColor = t.accentRaw, spotColor = t.accentRaw).clip(CircleShape).background(t.accentFill)
                    .clickable(onClickLabel = if (e.playIntent) "Pause" else "Play") { e.togglePlay() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (e.playIntent) Ic.pause else Ic.play, if (e.playIntent) "Pause" else "Play", tint = t.onAccent, modifier = Modifier.size(32.dp))
            }
            IconBtn(Ic.next, "Next track", e::next, size = 60.dp, iconSize = 30.dp)
            ModeButton(Ic.repeat, "Repeat: ${model.settings.order.label}", repeatOn, if (model.settings.order == Order.REPEAT_TRACK) "1" else null) {
                val seq = listOf(Order.DEFAULT, Order.REPEAT_PLAYLIST, Order.REPEAT_TRACK)
                val i = seq.indexOf(model.settings.order)
                val next = if (i < 0) Order.REPEAT_PLAYLIST else seq[(i + 1) % seq.size]
                model.updateSettings { it.copy(order = next) }
                model.flash(next.label)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Text(
                "Order: ${model.settings.order.label} ▾", style = Type.mono(12, FontWeight.Medium), color = t.sub,
                modifier = Modifier.clip(RoundedCornerShape(17.dp)).clickable { model.sheet = Sheet.OrderSheet }.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        // Bottom actions
        val sleepLeft = sleepLabel(e)
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 10.dp), horizontalArrangement = Arrangement.SpaceAround) {
            ActionButton(Ic.headphones, "Output", false) { model.sheet = Sheet.Output }
            ActionButton(Ic.moon, sleepLeft ?: "Sleep", sleepLeft != null) { model.sheet = Sheet.Sleep }
            ActionButton(Ic.gauge, "${model.settings.speed}×", model.settings.speed != 1f) { model.sheet = Sheet.Speed }
            ActionButton(Ic.eq, "Sound", false) { model.nowOpen = false; model.selectTab(com.strata.player.Tab.SOUND) }
            val qn = e.timelineVersion.let { e.queuedItemsAfterCurrent().size }
            ActionButton(Ic.queue, if (qn > 0) "Queue $qn" else "Queue", qn > 0) { model.sheet = Sheet.Queue }
        }
    }
}

@Composable
private fun sleepLabel(e: Engine): String? {
    if (e.sleepEndOfTrack) return "End trk"
    val at = e.sleepAtMs ?: return null
    val left by produceState(e.sleepRemainingMs() ?: 0L, at) {
        while (true) { value = e.sleepRemainingMs() ?: 0L; delay(1000) }
    }
    return Fmt.time(left)
}

@Composable
private fun ModeButton(icon: ImageVector, desc: String, on: Boolean, badge: String?, onClick: () -> Unit) {
    val t = LocalTokens.current
    Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClickLabel = desc, onClick = onClick).semantics { contentDescription = desc }, contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (on) t.accent else t.sub, modifier = Modifier.size(22.dp))
        if (on) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp).size(4.dp).clip(CircleShape).background(t.accentFill))
        if (badge != null) {
            Box(Modifier.align(Alignment.TopEnd).padding(top = 7.dp, end = 5.dp).size(14.dp).clip(CircleShape).background(t.accentFill), contentAlignment = Alignment.Center) {
                Text(badge, style = Type.body(9, FontWeight.Bold), color = t.onAccent)
            }
        }
    }
}

@Composable
private fun ActionButton(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    val t = LocalTokens.current
    Column(
        Modifier.width(68.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, null, tint = if (on) t.accent else t.sub, modifier = Modifier.size(22.dp))
        Text(label, style = Type.mono(10, FontWeight.Medium), color = if (on) t.accent else t.sub, maxLines = 1)
    }
}

@Composable
private fun SeekBar(model: AppModel, cur: Track) {
    val t = LocalTokens.current
    val e = model.engine
    val ctx = LocalContext.current
    val pos by rememberPosition(e)
    var dragFrac by remember { mutableStateOf<Float?>(null) }
    val wave by produceState(Waveform.cached(cur.id), cur.id) {
        value = Waveform.cached(cur.id) ?: Waveform.load(ctx, cur.id, cur.uri, model.store.waveFile(cur.id))
    }
    val dur = cur.durationMs.coerceAtLeast(1)
    val frac = dragFrac ?: (pos.toFloat() / dur).coerceIn(0f, 1f)
    val curDur by rememberUpdatedState(dur)
    Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 10.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .semantics { contentDescription = "Seek bar, ${Fmt.time(pos)} of ${Fmt.time(dur)}" }
                .pointerInput(cur.id) {
                    detectTapGestures { o -> e.seekTo((((o.x - 10.dp.toPx()) / (size.width - 20.dp.toPx())).coerceIn(0f, 1f) * curDur).toLong()) }
                }
                .pointerInput(cur.id) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> dragFrac = ((o.x - 10.dp.toPx()) / (size.width - 20.dp.toPx())).coerceIn(0f, 1f) },
                        onDragEnd = { dragFrac?.let { e.seekTo((it * curDur).toLong()) }; dragFrac = null },
                        onDragCancel = { dragFrac = null },
                    ) { ch, _ ->
                        ch.consume()
                        dragFrac = ((ch.position.x - 10.dp.toPx()) / (size.width - 20.dp.toPx())).coerceIn(0f, 1f)
                    }
                },
        ) {
            val pad = 10.dp.toPx()
            val w = size.width - pad * 2
            val bars = Waveform.BARS
            val gap = 2.dp.toPx()
            val bw = (w - gap * (bars - 1)) / bars
            val data = wave
            for (i in 0 until bars) {
                val hv = data?.getOrNull(i) ?: 0.18f
                val bh = (size.height * hv).coerceAtLeast(3.dp.toPx())
                val x = pad + i * (bw + gap)
                val played = (i + 0.5f) / bars <= frac
                drawRoundRect(
                    if (played) t.accentFill else t.line2,
                    Offset(x, (size.height - bh) / 2), Size(bw, bh), CornerRadius(bw / 2),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp)) {
            val shown = dragFrac?.let { (it * dur).toLong() } ?: pos
            Text(Fmt.time(shown), style = Type.mono(11), color = t.sub)
            Spacer(Modifier.weight(1f))
            Text("−" + Fmt.time(dur - shown), style = Type.mono(11), color = t.sub)
        }
    }
}

@Composable
private fun LyricsView(model: AppModel, cur: Track) {
    val t = LocalTokens.current
    val e = model.engine
    var lines by remember(cur.id) { mutableStateOf<List<LyricLine>?>(null) }
    var loaded by remember(cur.id) { mutableStateOf(false) }
    LaunchedEffect(cur.id) { model.lyrics(cur) { lines = it; loaded = true } }
    val ls = lines
    if (ls == null) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (loaded) "No lyrics in this file" else "Looking for lyrics…", style = Type.display(20, FontWeight.SemiBold), color = t.text)
            if (loaded) Text(
                "Embed a LYRICS / USLT tag, or put a matching .lrc file next to the track.",
                style = Type.body(14), color = t.sub, textAlign = TextAlign.Center,
            )
        }
        return
    }
    val synced = ls.firstOrNull()?.timeMs?.let { it >= 0 } == true
    val pos by rememberPosition(e, 200)
    val ci = if (!synced) -1 else ls.indexOfLast { it.timeMs <= pos }.coerceAtLeast(0)
    val state = rememberLazyListState()
    LaunchedEffect(ci) { if (ci >= 0) state.animateScrollToItem((ci - 2).coerceAtLeast(0)) }
    LazyColumn(Modifier.fillMaxSize(), state = state, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 24.dp)) {
        itemsIndexed(ls) { i, l ->
            val isC = i == ci
            val color by animateColorAsState(if (!synced) t.text else if (isC) t.text else if (i < ci) t.faint else t.sub, label = "lyr")
            Text(
                l.text.ifEmpty { "· · ·" },
                style = if (synced) Type.display(if (isC) 25 else 20, if (isC) FontWeight.Bold else FontWeight.SemiBold) else Type.body(17),
                color = color,
                modifier = Modifier.fillMaxWidth().clickable(enabled = synced) { e.seekTo(l.timeMs) },
            )
        }
        item { Text(if (synced) "SYNCED · TAP A LINE TO SEEK" else "UNSYNCED LYRICS", style = Type.label(10), color = t.faint, modifier = Modifier.padding(top = 8.dp)) }
    }
}

@Composable
private fun Visualizer(e: Engine) {
    val t = LocalTokens.current
    val analyzer = remember { Analyzer() }
    val bands = remember { FloatArray(32) }
    val scope = remember { FloatArray(128) }
    var peaks by remember { mutableStateOf(floatArrayOf(0f, 0f)) }
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(e.isPlaying) {
        while (true) {
            withFrameNanos { }
            if (e.isPlaying) {
                peaks = analyzer.analyze(e.core, bands, scope, e.latencyFrames())
            } else {
                for (i in bands.indices) bands[i] *= 0.9f
                for (i in scope.indices) scope[i] *= 0.8f
                peaks = floatArrayOf(peaks[0] * 0.9f, peaks[1] * 0.9f)
            }
            frame++
        }
    }
    val panel = if (t.dark) Color.White.copy(alpha = 0.07f) else Color.Black.copy(alpha = 0.05f)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row { Text("SPECTRUM · 32 BANDS", style = Type.label(10), color = t.faint, modifier = Modifier.weight(1f)); Text(if (e.isPlaying) "LIVE" else "PAUSED", style = Type.label(10), color = t.faint) }
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            frame.let { }
            val n = bands.size
            val gap = 3.dp.toPx()
            val bw = (size.width - gap * (n - 1)) / n
            for (i in 0 until n) {
                val h = (bands[i] * size.height).coerceAtLeast(3.dp.toPx())
                drawRoundRect(
                    if (i % 4 == 0) t.accentFill else t.accentFill.copy(alpha = 0.8f),
                    Offset(i * (bw + gap), size.height - h), Size(bw, h), CornerRadius(3.dp.toPx()),
                )
            }
        }
        Text("OSCILLOSCOPE", style = Type.label(10), color = t.faint)
        Canvas(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(10.dp)).background(panel)) {
            frame.let { }
            val p = Path()
            val n = scope.size
            for (i in 0 until n) {
                val x = i * size.width / (n - 1)
                val y = size.height / 2 - scope[i] * size.height * 0.45f
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            drawPath(p, t.accentFill, style = Stroke(1.8.dp.toPx(), join = StrokeJoin.Round))
        }
        Text("PEAK METER", style = Type.label(10), color = t.faint)
        listOf("L" to peaks[0], "R" to peaks[1]).forEach { (ch, v) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ch, style = Type.mono(10), color = t.sub, modifier = Modifier.width(16.dp))
                Canvas(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(panel)) {
                    // -48 dB .. 0 dB scale
                    val db = if (v <= 0f) -60f else (20f * kotlin.math.log10(v))
                    val f = ((db + 48f) / 48f).coerceIn(0f, 1f)
                    drawRect(
                        Brush.horizontalGradient(0f to t.accentFill, 0.7f to t.accentFill, 0.7001f to Color(0xFFE8B93A), 0.88f to Color(0xFFE8B93A), 0.8801f to Color(0xFFE5533F), startX = 0f, endX = size.width),
                        size = Size(size.width * f, size.height),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("−48", "−24", "−12", "−6", "−3", "0 dB").forEach { Text(it, style = Type.mono(9), color = t.faint) }
        }
    }
}

@Composable
private fun InfoTable(model: AppModel, cur: Track) {
    val t = LocalTokens.current
    val info = model.engine.currentTags
    val tech = info?.tech ?: model.tech[cur.id]
    val rows = buildList {
        add("Codec" to cur.codec + if (cur.lossless) " (lossless)" else " (lossy)")
        if (tech != null && tech.sampleRate > 0) add("Sample rate" to "${Fmt.rate(tech.sampleRate)} kHz")
        add("Bit depth" to if (tech != null && tech.bits > 0) "${tech.bits}-bit" else "n/a")
        if (tech != null) add("Channels" to "${tech.channels}" + if (tech.channels == 2) " · stereo" else "")
        if (cur.bitrateBps > 0) add("Bitrate" to "${cur.bitrateBps / 1000} kbps")
        val samples = info?.totalSamples?.takeIf { it > 0 }
        add("Duration" to Fmt.time(cur.durationMs) + (samples?.let { " · %,d samples".fmt(it) } ?: ""))
        add("Track gain" to (info?.rgTrackGain?.let { Fmt.db(it) } ?: "—"))
        add("Album gain" to (info?.rgAlbumGain?.let { Fmt.db(it) } ?: "—"))
        add("Track peak" to (info?.rgTrackPeak?.let { "%.6f".fmt(it) } ?: "—"))
        add("Tags" to (info?.tagType?.ifEmpty { null } ?: "—"))
        add("File size" to Fmt.size(cur.sizeBytes))
        add("Path" to cur.path)
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(if (t.dark) Color.White.copy(alpha = 0.07f) else Color.Black.copy(alpha = 0.05f))
            .verticalScroll(rememberScrollState()),
    ) {
        rows.forEachIndexed { i, (k, v) ->
            if (i > 0) Divider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp)) {
                Text(k, style = Type.mono(11), color = t.sub, modifier = Modifier.width(92.dp))
                Text(v, style = Type.body(13), color = t.text, modifier = Modifier.weight(1f))
            }
        }
    }
}
