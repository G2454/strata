package com.strata.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.strata.player.AppModel
import com.strata.player.Sheet
import com.strata.player.data.EQ_LABELS
import com.strata.player.data.EQ_PRESETS
import com.strata.player.data.RgMode
import kotlin.math.roundToInt

private val STAGE_INFO = mapOf(
    "rg" to "ReplayGain",
    "eq" to "Equalizer",
    "widen" to "Stereo widening",
    "mono" to "Mono downmix",
    "balance" to "Balance",
    "limit" to "Advanced limiter",
)

@Composable
fun SoundScreen(model: AppModel) {
    val t = LocalTokens.current
    val s = model.settings
    val e = model.engine
    fun upd(f: (com.strata.player.data.Settings) -> com.strata.player.data.Settings) = model.updateSettings(f)

    val activeStages = s.chain.filter {
        when (it) {
            "rg" -> s.rgMode != RgMode.OFF
            "eq" -> s.eqOn
            "widen" -> s.widen
            "mono" -> s.mono
            "balance" -> s.balance != 0f
            "limit" -> s.limiter
            else -> false
        }
    }
    val devices = remember(e.preferredDeviceId, model.sheet) { e.outputDevices() }
    val device = devices.firstOrNull { it.id == e.preferredDeviceId }
    val outName = device?.productName?.toString()?.takeIf { it.isNotBlank() } ?: "System default"

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ScreenTitle("Sound")
            Text("${Fmt.plural(activeStages.size, "DSP")} active · 32-bit float processing", style = Type.mono(12), color = t.faint)
        }

        // Output
        CardBox {
            Row(
                Modifier.fillMaxWidth().clickable { model.sheet = Sheet.Output }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(t.soft), contentAlignment = Alignment.Center) {
                    Icon(Ic.headphones, null, tint = t.accent, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Output", style = Type.body(12), color = t.sub)
                    Text(outName, style = Type.body(16, FontWeight.SemiBold), color = t.text)
                    Text("Preferred device for playback", style = Type.mono(11), color = t.faint)
                }
                Text("Change", style = Type.body(13, FontWeight.SemiBold), color = t.accent)
            }
        }
        Spacer(Modifier.height(14.dp))

        // Equalizer
        CardBox {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Equalizer", style = Type.body(17, FontWeight.SemiBold), color = t.text)
                    Text("10-band graphic · ${if (s.eqOn) s.preset else "Off"}", style = Type.mono(11), color = t.faint)
                }
                Box(Modifier.size(width = 64.dp, height = 44.dp).testTag("eq-switch").clickable(onClickLabel = "Toggle equalizer") { upd { it.copy(eqOn = !it.eqOn) } }, contentAlignment = Alignment.Center) {
                    SwitchVisual(s.eqOn)
                }
            }
            EqCurve(s.bands, Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().height(84.dp).alpha(if (s.eqOn) 1f else 0.4f))
            HScroll(Modifier.padding(top = 4.dp, bottom = 6.dp), padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) {
                AccentChip("Custom", s.preset == "Custom") { upd { it.copy(preset = "Custom") } }
                EQ_PRESETS.forEach { (name, bands) ->
                    AccentChip(name, s.preset == name) { upd { it.copy(preset = name, bands = bands, eqOn = true) } }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp).alpha(if (s.eqOn) 1f else 0.4f)) {
                s.bands.forEachIndexed { i, v ->
                    BandSlider(EQ_LABELS[i], v, Modifier.weight(1f)) { nv ->
                        upd { st -> st.copy(bands = st.bands.toMutableList().also { it[i] = nv }, preset = "Custom", eqOn = true) }
                    }
                }
            }
            SliderLine("Preamp", Fmt.db1(s.preamp)) {
                StrataSlider(s.preamp, -12f..12f, 0.5f, { v -> upd { it.copy(preamp = v) } }, centered = true, label = "Equalizer preamp")
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                Text(
                    "Reset to flat", style = Type.body(13, FontWeight.SemiBold), color = t.accent,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { upd { it.copy(bands = List(10) { 0f }, preset = "Flat", preamp = 0f) } }.padding(12.dp),
                )
            }
        }

        SectionLabel("Effects", Modifier.padding(top = 14.dp))
        CardBox {
            SwitchRow("Stereo widening", "Mid/side widening for a roomier image on headphones", s.widen, { v -> upd { it.copy(widen = v) } })
            SwitchRow("Advanced limiter", "Catches peaks when EQ or gain pushes past 0 dBFS", s.limiter, { v -> upd { it.copy(limiter = v) } }, topLine = true)
            SwitchRow("Mono downmix", "Sum left and right channels", s.mono, { v -> upd { it.copy(mono = v) } }, topLine = true)
            Divider()
            SliderLine("Balance", if (s.balance == 0f) "C" else if (s.balance < 0) "L ${(-s.balance * 100).roundToInt()}" else "R ${(s.balance * 100).roundToInt()}") {
                StrataSlider(s.balance, -1f..1f, 0.05f, { v -> upd { it.copy(balance = v) } }, centered = true, label = "Balance")
            }
        }

        SectionLabel("Volume leveling · ReplayGain", Modifier.padding(top = 14.dp))
        CardBox {
            Box(Modifier.padding(12.dp)) {
                Segmented(RgMode.entries.map { it to it.label }, s.rgMode, { m -> upd { it.copy(rgMode = m) } })
            }
            SliderLine("With RG info", Fmt.db1(s.rgPreamp)) {
                StrataSlider(s.rgPreamp, -10f..10f, 0.5f, { v -> upd { it.copy(rgPreamp = v) } }, centered = true, label = "Preamp with ReplayGain")
            }
            SliderLine("Without RG", Fmt.db1(s.rgPreampNoInfo)) {
                StrataSlider(s.rgPreampNoInfo, -10f..10f, 0.5f, { v -> upd { it.copy(rgPreampNoInfo = v) } }, centered = true, label = "Preamp without ReplayGain")
            }
            SwitchRow("Prevent clipping", "Lower the gain when the tagged peak would clip", s.rgPreventClip, { v -> upd { it.copy(rgPreventClip = v) } }, topLine = true)
            Divider()
            val g = e.appliedGainDb
            Text(
                when {
                    s.rgMode == RgMode.OFF -> "Leveling is off."
                    g == null -> "Waiting for a track."
                    else -> "Now playing: ${Fmt.db(g)} applied" + if (e.currentTags?.rgTrackGain == null && e.currentTags?.rgAlbumGain == null) " (no ReplayGain tags in this file)" else ""
                },
                style = Type.mono(11), color = t.sub, modifier = Modifier.padding(16.dp),
            )
        }

        SectionLabel("Transitions & speed", Modifier.padding(top = 14.dp))
        CardBox {
            SwitchRow("Skip silence", "Trims silent runs inside and between tracks", s.skipSilence, { v -> upd { it.copy(skipSilence = v) } })
            Divider()
            SliderLine("Fade", if (s.fadeSec == 0) "Off" else "${s.fadeSec} s") {
                StrataSlider(s.fadeSec.toFloat(), 0f..10f, 1f, { v -> upd { it.copy(fadeSec = v.roundToInt()) } }, label = "Fade between tracks")
            }
            Text("Gapless playback is always on.", style = Type.mono(11), color = t.faint, modifier = Modifier.padding(start = 16.dp, bottom = 12.dp))
            Divider()
            Box(Modifier.padding(12.dp)) {
                Segmented(listOf(0.5f to "0.5×", 0.75f to "0.75", 1f to "1.0×", 1.25f to "1.25", 1.5f to "1.5", 2f to "2.0×"), s.speed, { v -> upd { it.copy(speed = v) } }, mono = true)
            }
            SwitchRow("Keep pitch", "Time-stretch without changing key", s.keepPitch, { v -> upd { it.copy(keepPitch = v) } })
        }

        SectionLabel("DSP chain · processing order", Modifier.padding(top = 14.dp))
        CardBox {
            if (activeStages.isEmpty()) {
                Text("No DSPs active. Output is untouched.", style = Type.body(13), color = t.sub, modifier = Modifier.padding(20.dp))
            }
            activeStages.forEachIndexed { i, key ->
                if (i > 0) Divider()
                Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 16.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(t.soft), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", style = Type.mono(12, FontWeight.Medium), color = t.accent)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(STAGE_INFO[key] ?: key, style = Type.body(15, FontWeight.Medium), color = t.text, modifier = Modifier.weight(1f))
                    IconBtn(Ic.up, "Move earlier", { moveStage(model, activeStages, key, -1) }, tint = if (i == 0) t.line2 else t.sub, iconSize = 18.dp)
                    IconBtn(Ic.down, "Move later", { moveStage(model, activeStages, key, 1) }, tint = if (i == activeStages.lastIndex) t.line2 else t.sub, iconSize = 18.dp)
                }
            }
        }
    }
}

private fun moveStage(model: AppModel, active: List<String>, key: String, dir: Int) {
    val i = active.indexOf(key)
    val j = i + dir
    if (j !in active.indices) return
    val other = active[j]
    model.updateSettings { s ->
        val ch = s.chain.toMutableList()
        val a = ch.indexOf(key)
        val b = ch.indexOf(other)
        ch[a] = other; ch[b] = key
        s.copy(chain = ch)
    }
}

@Composable
private fun SliderLine(label: String, value: String, slider: @Composable () -> Unit) {
    val t = LocalTokens.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.body(14), color = t.sub, modifier = Modifier.width(96.dp))
        Box(Modifier.weight(1f)) { slider() }
        Text(value, style = Type.mono(12), color = t.text, modifier = Modifier.width(64.dp).padding(start = 8.dp))
    }
}

@Composable
private fun EqCurve(bands: List<Float>, modifier: Modifier) {
    val t = LocalTokens.current
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val mid = h / 2
        drawLine(t.line2, Offset(0f, mid), Offset(w, mid), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f)))
        val pts = bands.mapIndexed { i, v -> Offset(i * w / 9f, mid - v / 12f * (h * 0.43f)) }
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) {
                val p0 = pts[i - 1]
                val p1 = pts[i]
                val cx = (p0.x + p1.x) / 2
                cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
            }
        }
        val fill = Path().apply {
            addPath(path)
            lineTo(w, mid)
            lineTo(0f, mid)
            close()
        }
        drawPath(fill, t.soft)
        drawPath(path, t.accentFill, style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun BandSlider(label: String, value: Float, modifier: Modifier, onChange: (Float) -> Unit) {
    val t = LocalTokens.current
    val cb by rememberUpdatedState(onChange)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            (if (value > 0) "+" else "") + (if (value == value.roundToInt().toFloat()) value.roundToInt().toString() else "%.1f".fmt(value)),
            style = Type.mono(10), color = if (value == 0f) t.faint else t.text,
        )
        fun valueAt(y: Float, height: Float, pad: Float): Float {
            val f = 1f - ((y - pad) / (height - pad * 2)).coerceIn(0f, 1f)
            return ((f * 24f - 12f) * 2f).roundToInt() / 2f
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(160.dp)
                .testTag("band-$label")
                .semantics { contentDescription = "$label Hz band, $value dB" }
                .pointerInput(Unit) { detectTapGestures { cb(valueAt(it.y, size.height.toFloat(), 10.dp.toPx())) } }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(onDragStart = { cb(valueAt(it.y, size.height.toFloat(), 10.dp.toPx())) }) { ch, _ ->
                        ch.consume()
                        cb(valueAt(ch.position.y, size.height.toFloat(), 10.dp.toPx()))
                    }
                },
        ) {
            val pad = 10.dp.toPx()
            val cx = size.width / 2
            val top = pad
            val hgt = size.height - pad * 2
            val tw = 4.dp.toPx()
            drawRoundRect(t.surface2, Offset(cx - tw / 2, top), Size(tw, hgt), CornerRadius(tw / 2))
            val ky = top + (1f - (value + 12f) / 24f) * hgt
            val zy = top + hgt / 2
            drawRoundRect(t.accentFill, Offset(cx - tw / 2, minOf(ky, zy)), Size(tw, kotlin.math.abs(zy - ky)), CornerRadius(tw / 2))
            drawCircle(if (t.dark) t.surface else androidx.compose.ui.graphics.Color.White, radius = 9.dp.toPx(), center = Offset(cx, ky))
            drawCircle(t.accentFill, radius = 9.dp.toPx(), center = Offset(cx, ky), style = Stroke(2.dp.toPx()))
        }
        Text(label, style = Type.mono(10), color = t.faint)
    }
}
