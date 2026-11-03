package com.strata.player.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.strata.player.AppModel
import com.strata.player.audio.Engine
import com.strata.player.data.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// Small, finite animations only: nothing here runs while the screen is idle.

/** A light tick, or a firmer "confirm" for things like hearting a track. */
fun haptic(view: View, strong: Boolean = false) {
    val c = when {
        Build.VERSION.SDK_INT >= 30 -> if (strong) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CLOCK_TICK
        else -> if (strong) HapticFeedbackConstants.VIRTUAL_KEY else HapticFeedbackConstants.CLOCK_TICK
    }
    view.performHapticFeedback(c)
}

/** Buttons shrink a little while pressed and spring back. Observes touches without consuming them. */
fun Modifier.pressScale(pressed: Float = 0.88f): Modifier = composed {
    var down by remember { mutableStateOf(false) }
    val s by animateFloatAsState(
        if (down) pressed else 1f,
        spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium), label = "press",
    )
    this
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                down = true
                try { waitForUpOrCancellation(PointerEventPass.Initial) } finally { down = false }
            }
        }
        .graphicsLayer { scaleX = s; scaleY = s }
}

/** Play/pause icon that pops from one to the other. */
@Composable
fun PlayPauseIcon(playing: Boolean, tint: Color, size: Dp) {
    AnimatedContent(
        playing,
        transitionSpec = {
            (scaleIn(tween(200), initialScale = 0.5f) + fadeIn(tween(200))) togetherWith
                (scaleOut(tween(140), targetScale = 0.5f) + fadeOut(tween(140)))
        },
        contentAlignment = Alignment.Center,
        label = "playpause",
    ) { p ->
        Icon(if (p) Ic.pause else Ic.play, null, tint = tint, modifier = Modifier.size(size))
    }
}

/** The favorite button: the heart pops, a ring and a few sparks burst out, and the phone ticks. */
@Composable
fun HeartButton(fav: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, boxSize: Dp = 48.dp, iconSize: Dp = 26.dp) {
    val t = LocalTokens.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val pop = remember { Animatable(1f) }
    val burst = remember { Animatable(1f) }
    val tint by animateColorAsState(if (fav) t.accent else t.sub, tween(220), label = "heart")
    val accent = t.accent
    Box(
        modifier.size(boxSize).drawBehind {
            val p = burst.value
            if (p < 1f) {
                val base = size.minDimension / 2f
                val r = base * (0.5f + 0.55f * p)
                drawCircle(accent.copy(alpha = (1f - p) * 0.55f), radius = r, style = Stroke(width = 3.dp.toPx() * (1f - p) + 0.5f))
                for (i in 0 until 7) {
                    val a = (i / 7f) * 2f * PI.toFloat() - PI.toFloat() / 2f
                    val d = base * (0.55f + 0.6f * p)
                    drawCircle(accent.copy(alpha = 1f - p), radius = 2.6.dp.toPx() * (1f - p * 0.7f), center = center + Offset(cos(a) * d, sin(a) * d))
                }
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        IconBtn(
            if (fav) Ic.heartFill else Ic.heart, if (fav) "Remove from favorites" else "Add to favorites",
            {
                val adding = !fav
                onToggle()
                haptic(view, strong = adding)
                scope.launch {
                    pop.snapTo(if (adding) 0.55f else 0.8f)
                    pop.animateTo(1f, spring(dampingRatio = 0.32f, stiffness = 420f))
                }
                if (adding) scope.launch {
                    burst.snapTo(0f)
                    burst.animateTo(1f, tween(480, easing = FastOutSlowInEasing))
                }
            },
            tint = tint, size = boxSize, iconSize = iconSize,
            modifier = Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value },
        )
    }
}

/** Five stars; tapping one fills them up to it with a quick staggered pop. */
@Composable
fun AnimatedStars(rating: Int, onRate: (Int) -> Unit) {
    val t = LocalTokens.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val pops = remember { List(5) { Animatable(1f) } }
    Row(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
        for (n in 1..5) {
            val on = n <= rating
            val tint by animateColorAsState(if (on) t.accent else t.faint, tween(200), label = "star")
            IconBtn(
                if (on) Ic.starFill else Ic.star, "Rate $n star" + if (n > 1) "s" else "",
                {
                    val r = if (rating == n) 0 else n
                    onRate(r)
                    haptic(view)
                    for (k in 1..r) scope.launch {
                        delay((k - 1) * 45L)
                        pops[k - 1].snapTo(0.6f)
                        pops[k - 1].animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 500f))
                    }
                },
                tint = tint,
                modifier = Modifier.graphicsLayer { scaleX = pops[n - 1].value; scaleY = pops[n - 1].value },
            )
        }
    }
}

// ------------------------------------------------------------------ swiping between tracks

/**
 * The playing track as a little pager: the cover (or the mini player's text) follows the finger, and the
 * next / previous track slides in next to it. The neighbours are always composed (invisible until a drag), so
 * their artwork is already loaded and a skip never shows a placeholder.
 */
@Stable
class TrackSwipeState(internal val scope: CoroutineScope) {
    val offset = Animatable(0f)
    var dy by mutableFloatStateOf(0f)
    var width by mutableIntStateOf(0)
}

@Composable
fun rememberTrackSwipe(): TrackSwipeState {
    val scope = rememberCoroutineScope()
    return remember { TrackSwipeState(scope) }
}

/**
 * Drag handling for [TrackStrip]. A swipe past [skip] (or a quick flick) slides to the neighbour and then
 * skips; an upward swipe past [up] calls [onUp]. At the ends of the list the drag only gives a little.
 */
fun Modifier.trackSwipe(
    state: TrackSwipeState,
    engine: Engine,
    skip: Dp,
    up: Dp,
    gap: Dp,
    onUp: () -> Unit,
    onSkip: () -> Unit = {},
): Modifier = pointerInput(state, engine) {
    val skipPx = skip.toPx()
    val upPx = up.toPx()
    val gapPx = gap.toPx()
    val flingPx = 700.dp.toPx()
    var dx = 0f
    val meter = FlingMeter()
    detectDragGestures(
        onDragStart = { dx = 0f; state.dy = 0f; meter.reset() },
        onDragEnd = {
            val vx = meter.velocity()
            val dy = state.dy
            state.dy = 0f
            val horizontal = abs(dx) > abs(dy)
            val dir = when {
                !horizontal -> 0
                dx < 0 && (dx < -skipPx || (vx < -flingPx && dx < -skipPx / 3f)) -> -1
                dx > 0 && (dx > skipPx || (vx > flingPx && dx > skipPx / 3f)) -> 1
                else -> 0
            }
            val target = when (dir) { -1 -> engine.peekNextId(); 1 -> engine.peekPreviousId(); else -> null }
            state.scope.launch {
                if (target != null) {
                    state.offset.animateTo(dir * (state.width + gapPx), tween(200, easing = FastOutSlowInEasing))
                    val old = engine.currentId
                    if (dir < 0) engine.next() else engine.previousTrack()
                    onSkip()
                    if (engine.currentId == old) withTimeoutOrNull(700) { snapshotFlow { engine.currentId }.first { it != old } }
                    state.offset.snapTo(0f)
                } else {
                    state.offset.animateTo(0f, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow))
                }
            }
            if (!horizontal && -dy > upPx) onUp()
        },
        onDragCancel = {
            state.dy = 0f
            state.scope.launch { state.offset.animateTo(0f, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)) }
        },
    ) { change, d ->
        change.consume()
        dx += d.x
        meter.add(change.uptimeMillis, dx)
        state.dy += d.y
        val blocked = (dx < 0 && engine.peekNextId() == null) || (dx > 0 && engine.peekPreviousId() == null)
        val shown = if (blocked) dx * 0.25f else dx
        state.scope.launch { state.offset.snapTo(shown) }
    }
}

/**
 * Horizontal speed at the end of a drag, from the last 80 ms of movement. Deliberately simple: when the samples
 * are too close together in time to tell (or there are too few), it reports 0, so only a real flick counts as one.
 */
internal class FlingMeter {
    private val times = LongArray(32)
    private val xs = FloatArray(32)
    private var count = 0
    private var head = 0

    fun reset() { count = 0; head = 0 }

    fun add(timeMillis: Long, x: Float) {
        times[head] = timeMillis
        xs[head] = x
        head = (head + 1) % times.size
        if (count < times.size) count++
    }

    /** Pixels per second; negative is leftwards. */
    fun velocity(): Float {
        if (count < 2) return 0f
        val last = (head - 1 + times.size) % times.size
        var oldest = last
        for (k in 1 until count) {
            val i = (last - k + times.size) % times.size
            if (times[last] - times[i] > 80) break
            oldest = i
        }
        val dt = times[last] - times[oldest]
        if (dt < 12) return 0f
        return (xs[last] - xs[oldest]) * 1000f / dt
    }
}

/** Lays out previous / current / next side by side and moves them with [state]. */
@Composable
fun TrackStrip(
    state: TrackSwipeState,
    model: AppModel,
    cur: Track,
    gap: Dp,
    modifier: Modifier = Modifier,
    clip: Boolean = false,
    content: @Composable (Track) -> Unit,
) {
    val e = model.engine
    val neighbours = remember(e.currentId, e.timelineVersion, model.index) {
        val p = model.track(e.peekPreviousId() ?: -1L)?.takeIf { it.id != cur.id }
        val n = model.track(e.peekNextId() ?: -1L)?.takeIf { it.id != cur.id }
        p to n
    }
    val gapPx = with(LocalDensity.current) { gap.toPx() }
    val moving by remember { derivedStateOf { state.offset.value != 0f } }
    Box(modifier.then(if (clip) Modifier.clipToBounds() else Modifier).onSizeChanged { state.width = it.width }) {
        val (prev, next) = neighbours
        val slots = listOfNotNull(
            prev?.takeIf { it.id != next?.id }?.let { it to -1 },
            next?.let { it to 1 },
            cur to 0,
        )
        // One call site keyed by track id: when the next track becomes current, its composition (and the cover it
        // already loaded) moves to the middle instead of being rebuilt.
        for ((tr, side) in slots) key(tr.id) { Slot(state, side, gapPx, moving) { content(tr) } }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.Slot(
    state: TrackSwipeState, side: Int, gapPx: Float, moving: Boolean, content: @Composable () -> Unit,
) {
    Box(
        (if (side == 0) Modifier else Modifier.matchParentSize().clearAndSetSemantics { }).graphicsLayer {
            translationX = state.offset.value + side * (state.width + gapPx)
            alpha = if (side == 0 || moving) 1f else 0f
        },
    ) { content() }
}
