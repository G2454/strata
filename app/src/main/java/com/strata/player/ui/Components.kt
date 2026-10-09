package com.strata.player.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.strata.player.data.Tech
import com.strata.player.data.Track
import kotlin.math.roundToInt

// ------------------------------------------------------------------ formatting

/** Locale-independent formatting so badges read the same on every phone (44.1, not 44,1). */
fun String.fmt(vararg args: Any?): String = String.format(java.util.Locale.US, this, *args)

object Fmt {
    fun time(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".fmt(h, m, sec) else "%d:%02d".fmt(m, sec)
    }

    fun long(ms: Long): String {
        val totalMin = (ms / 60000.0).roundToInt()
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) "$h h $m min" else "$m min"
    }

    fun plural(n: Int, w: String) = "$n $w" + if (n == 1) "" else "s"

    fun rate(hz: Int): String {
        if (hz <= 0) return "?"
        val k = hz / 1000.0
        return if (k == Math.floor(k)) k.toInt().toString() else "%.1f".fmt(k)
    }

    fun db(v: Float): String = (if (v < 0) "−" else "+") + "%.2f dB".fmt(kotlin.math.abs(v))
    fun db1(v: Float): String = (if (v < 0) "−" else if (v > 0) "+" else "") + "%.1f dB".fmt(kotlin.math.abs(v))

    fun badge(t: Track, tech: Tech?): String = when {
        t.lossless && tech != null && tech.sampleRate > 0 && tech.bits > 0 -> "${t.codec} ${tech.bits}/${rate(tech.sampleRate)}"
        t.lossless -> t.codec
        t.bitrateBps > 0 -> "${t.codec} ${t.bitrateBps / 1000}"
        else -> t.codec
    }

    fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.2f GB".fmt(bytes / (1L shl 30).toDouble())
        else -> "%.1f MB".fmt(bytes / (1L shl 20).toDouble())
    }
}

// ------------------------------------------------------------------ basic building blocks

/**
 * Makes a full-screen overlay the hit target so taps don't reach the screen below it.
 * It must NOT consume events: Compose cancels a child's click when a parent consumes the
 * pointer change (checked on the Final pass), which is what broke every overlay button before.
 * Having a pointer-input node is enough, because hit testing stops at the topmost sibling that has one.
 */
fun Modifier.blockTouches(): Modifier = this.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent()
        }
    }
}

/** Lets a child extend [h] past its parent's horizontal padding on both sides (full-bleed headers in padded grids). */
fun Modifier.bleed(h: Dp): Modifier = this.layout { measurable, constraints ->
    val extra = h.roundToPx() * 2
    val w = constraints.maxWidth + extra
    val p = measurable.measure(constraints.copy(minWidth = w, maxWidth = w))
    layout(constraints.maxWidth, p.height) { p.place(-extra / 2, 0) }
}

@Composable
fun IconBtn(
    icon: ImageVector,
    desc: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LocalTokens.current.text,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp,
    background: Color = Color.Transparent,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .clickable(role = Role.Button, onClickLabel = desc, onClick = onClick)
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Type.display(34), color = LocalTokens.current.text, modifier = modifier)
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = Type.label(11), color = LocalTokens.current.faint, modifier = modifier.padding(horizontal = 20.dp, vertical = 8.dp))
}

@Composable
fun CardBox(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val t = LocalTokens.current
    Column(
        modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(t.surface),
        content = content,
    )
}

@Composable
fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(LocalTokens.current.line))
}

@Composable
fun Pill(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, mono: Boolean = false) {
    val t = LocalTokens.current
    val bg by animateColorAsState(if (selected) t.text else Color.Transparent, label = "pill")
    Box(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .border(1.dp, if (selected) t.text else t.line2, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = if (mono) Type.mono(12, FontWeight.Medium) else Type.body(14, FontWeight.Medium), color = if (selected) t.bg else t.sub)
    }
}

@Composable
fun AccentChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val t = LocalTokens.current
    Box(
        Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) t.accentFill else Color.Transparent)
            .border(1.dp, if (selected) t.accentFill else t.line2, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Type.body(13, FontWeight.Medium), color = if (selected) t.onAccent else t.sub)
    }
}

@Composable
fun HScroll(modifier: Modifier = Modifier, gap: Dp = 8.dp, padding: PaddingValues = PaddingValues(horizontal = 20.dp), content: @Composable RowScope.() -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(padding),
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier, mono: Boolean = false) {
    val t = LocalTokens.current
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(t.surface2)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (value, label) ->
            val on = value == selected
            val bg by animateColorAsState(if (on) t.accentFill else Color.Transparent, label = "seg")
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(bg)
                    .clickable { onSelect(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = if (mono) Type.mono(12, FontWeight.Medium) else Type.body(13, FontWeight.SemiBold), color = if (on) t.onAccent else t.sub, maxLines = 1)
            }
        }
    }
}

@Composable
fun SwitchVisual(on: Boolean) {
    val t = LocalTokens.current
    val x by animateDpAsState(if (on) 22.dp else 2.dp, label = "knob")
    val bg by animateColorAsState(if (on) t.accentFill else t.surface2, label = "track")
    Box(
        Modifier
            .size(width = 48.dp, height = 28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .border(1.dp, if (on) Color.Transparent else t.line2, RoundedCornerShape(14.dp)),
    ) {
        Box(
            Modifier
                .offset(x = x, y = 2.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(if (on) t.onAccent else t.sub),
        )
    }
}

@Composable
fun SwitchRow(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit, topLine: Boolean = false) {
    val t = LocalTokens.current
    if (topLine) Divider()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Switch) { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = Type.body(15, FontWeight.Medium), color = t.text)
            if (sub != null) Text(sub, style = Type.body(12), color = t.sub)
        }
        SwitchVisual(checked)
    }
}

@Composable
fun RadioRow(title: String, sub: String?, selected: Boolean, onClick: () -> Unit, topLine: Boolean) {
    val t = LocalTokens.current
    if (topLine) Divider()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Type.body(15, FontWeight.Medium), color = t.text)
            if (sub != null) Text(sub, style = Type.body(12), color = t.sub)
        }
        Box(
            Modifier.size(22.dp).clip(CircleShape).border(2.dp, if (selected) t.accentFill else t.line2, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(t.accentFill))
        }
    }
}

/** Horizontal slider drawn in Strata style. [centered] fills from zero instead of from the left. */
@Composable
fun StrataSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    centered: Boolean = false,
    label: String = "",
) {
    val t = LocalTokens.current
    val cb by rememberUpdatedState(onChange)
    fun valueAt(x: Float, width: Float, pad: Float): Float {
        val f = ((x - pad) / (width - pad * 2)).coerceIn(0f, 1f)
        val raw = range.start + f * (range.endInclusive - range.start)
        return ((raw / step).roundToInt() * step).coerceIn(range.start, range.endInclusive)
    }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .semantics { contentDescription = label }
            .pointerInput(range, step) {
                detectTapGestures { cb(valueAt(it.x, size.width.toFloat(), 10.dp.toPx())) }
            }
            .pointerInput(range, step) {
                detectHorizontalDragGestures(onDragStart = { cb(valueAt(it.x, size.width.toFloat(), 10.dp.toPx())) }) { change, _ ->
                    change.consume()
                    cb(valueAt(change.position.x, size.width.toFloat(), 10.dp.toPx()))
                }
            },
    ) {
        val pad = 10.dp.toPx()
        val w = size.width - pad * 2
        val cy = size.height / 2
        val f = (value - range.start) / (range.endInclusive - range.start)
        val zero = if (centered) (0f - range.start) / (range.endInclusive - range.start) else 0f
        val th = 4.dp.toPx()
        drawRoundRect(t.surface2, Offset(pad, cy - th / 2), Size(w, th), CornerRadius(th / 2))
        val a = minOf(f, zero)
        val b = maxOf(f, zero)
        drawRoundRect(t.accentFill, Offset(pad + a * w, cy - th / 2), Size((b - a) * w, th), CornerRadius(th / 2))
        drawCircle(t.accentFill, radius = 9.dp.toPx(), center = Offset(pad + f * w, cy))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    track: Track,
    badge: String,
    current: Boolean,
    columns: Boolean,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    subtitle: String = "${track.artist} · ${track.album}",
    number: String? = null,
    onLongClick: () -> Unit = onMenu,
    selecting: Boolean = false,
    selected: Boolean = false,
) {
    val t = LocalTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) t.soft else if (current && !selecting) t.soft else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Select")
            .semantics { if (selecting) this.selected = selected }
            .height(if (columns) 52.dp else 66.dp)
            .padding(start = 20.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (columns) {
            Text(number ?: "%02d".fmt(track.trackNo), style = Type.mono(12), color = if (current) t.accent else t.faint, modifier = Modifier.width(24.dp))
        } else {
            Art(track, Modifier.size(46.dp), RoundedCornerShape(10.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                track.title, style = Type.body(if (columns) 14 else 15, FontWeight.Medium), color = if (current) t.accent else t.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(subtitle, style = Type.body(if (columns) 12 else 13), color = t.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (columns) {
            Text(badge, style = Type.mono(11), color = t.sub, maxLines = 1, modifier = Modifier.width(78.dp))
            Text(Fmt.time(track.durationMs), style = Type.mono(12), color = t.sub, modifier = Modifier.width(40.dp))
        } else if (!selecting) {
            Text(
                badge, style = Type.mono(10), color = t.sub, maxLines = 1,
                modifier = Modifier.border(1.dp, t.line2, RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
        if (selecting) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { CheckDot(selected) }
        } else {
            IconBtn(Ic.more, "More options for ${track.title}", onMenu, tint = t.sub, iconSize = 20.dp)
        }
    }
    if (columns) Divider()
}

/** Round check mark used by multi-select rows. */
@Composable
fun CheckDot(on: Boolean, enabled: Boolean = true) {
    val t = LocalTokens.current
    Box(
        Modifier.size(24.dp).clip(CircleShape)
            .background(if (on) (if (enabled) t.accentFill else t.line2) else Color.Transparent)
            .border(2.dp, if (on) Color.Transparent else t.line2, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (on) Icon(Ic.check, null, tint = if (enabled) t.onAccent else t.sub, modifier = Modifier.size(15.dp))
    }
}

@Composable
fun ColumnHeader() {
    val t = LocalTokens.current
    Row(
        Modifier.fillMaxWidth().height(28.dp).padding(start = 20.dp, end = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("#", style = Type.label(10), color = t.faint, modifier = Modifier.width(24.dp))
        Text("TITLE", style = Type.label(10), color = t.faint, modifier = Modifier.weight(1f))
        Text("FORMAT", style = Type.label(10), color = t.faint, modifier = Modifier.width(78.dp))
        Text("TIME", style = Type.label(10), color = t.faint, modifier = Modifier.width(40.dp))
    }
    Divider()
}

@Composable
fun EmptyNote(text: String) {
    Text(text, style = Type.body(14), color = LocalTokens.current.sub, modifier = Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 32.dp))
}

@Composable
fun BigButton(label: String, icon: ImageVector?, primary: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    Row(
        modifier
            .height(50.dp)
            .clip(RoundedCornerShape(25.dp))
            .background(if (primary) t.accentFill else t.surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (primary) t.onAccent else t.text, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, style = Type.body(15, FontWeight.SemiBold), color = if (primary) t.onAccent else t.text)
    }
}
