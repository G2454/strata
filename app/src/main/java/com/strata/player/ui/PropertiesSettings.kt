package com.strata.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.strata.player.AppModel
import com.strata.player.data.TagInfo
import com.strata.player.data.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
private fun OverlayHeader(title: String, onBack: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    val t = LocalTokens.current
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBtn(Ic.back, "Back", onBack)
        Text(title, style = Type.body(17, FontWeight.SemiBold), color = t.text, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

// ====================================================================== Properties

private val META_FIELDS = listOf(
    "title" to "Title", "artist" to "Artist", "albumArtist" to "Album artist", "album" to "Album",
    "year" to "Date", "genre" to "Genre", "composer" to "Composer", "trackNo" to "Track number", "discNo" to "Disc number",
)

@Composable
fun PropertiesScreen(model: AppModel, id: Long) {
    val t = LocalTokens.current
    val tr = model.track(id) ?: return
    val orig = model.originalTrack(id) ?: tr
    val info by produceState<TagInfo?>(model.tags.cached(id), id) {
        value = withContext(Dispatchers.IO) { model.tags.read(orig) }
    }
    val draft = remember(id) {
        mutableStateMapOf<String, String>().apply {
            put("title", tr.title); put("artist", tr.artist); put("albumArtist", tr.albumArtist); put("album", tr.album)
            put("year", if (tr.year > 0) tr.year.toString() else ""); put("genre", tr.genre); put("composer", tr.composer)
            put("trackNo", tr.trackNo.toString()); put("discNo", tr.discNo.toString())
        }
    }
    val changed = META_FIELDS.any { (k, _) ->
        draft[k] != when (k) {
            "title" -> tr.title; "artist" -> tr.artist; "albumArtist" -> tr.albumArtist; "album" -> tr.album
            "year" -> if (tr.year > 0) tr.year.toString() else ""; "genre" -> tr.genre; "composer" -> tr.composer
            "trackNo" -> tr.trackNo.toString(); else -> tr.discNo.toString()
        }
    }

    Column(Modifier.fillMaxSize().testTag("properties").background(t.bg).blockTouches().statusBarsPadding().navigationBarsPadding().imePadding()) {
        OverlayHeader("Properties", { model.propsId = null }) {
            if (model.propsTab == 0 && changed) {
                Text(
                    "Save", style = Type.body(15, FontWeight.SemiBold), color = t.accent,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { model.saveOverrides(id, draft.toMap()) }.padding(12.dp),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Art(tr, Modifier.size(60.dp), RoundedCornerShape(12.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(tr.title, style = Type.display(19), color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${tr.artist} · ${tr.album}", style = Type.body(13), color = t.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
            Segmented(listOf(0 to "Metadata", 1 to "Details", 2 to "Statistics"), model.propsTab, { model.propsTab = it })
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            when (model.propsTab) {
                0 -> {
                    CardBox {
                        META_FIELDS.forEachIndexed { i, (k, label) ->
                            if (i > 0) Divider()
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                                Text(label.uppercase(), style = Type.label(10), color = t.faint)
                                BasicTextField(
                                    draft[k] ?: "", { draft[k] = it }, singleLine = true,
                                    textStyle = Type.body(15).copy(color = t.text), cursorBrush = SolidColor(t.accentFill),
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                )
                            }
                        }
                    }
                    Text(
                        "Edits are stored in Strata's library and show up everywhere in the app. Your audio files are not modified.",
                        style = Type.body(12), color = t.sub, modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                    )
                    if (model.user.overrides.containsKey(id)) {
                        Text(
                            "Revert to file tags", style = Type.body(14, FontWeight.SemiBold), color = t.accent,
                            modifier = Modifier.padding(horizontal = 12.dp).clip(RoundedCornerShape(10.dp)).clickable {
                                model.saveOverrides(id, emptyMap())
                                model.propsId = null
                            }.padding(10.dp),
                        )
                    }
                }
                1 -> {
                    val i = info
                    val tech = i?.tech ?: model.tech[id]
                    val rows = buildList {
                        add("File name" to orig.fileName)
                        add("Folder" to orig.folder)
                        add("File size" to Fmt.size(orig.sizeBytes))
                        add("Duration" to Fmt.time(orig.durationMs) + (i?.totalSamples?.takeIf { it > 0 }?.let { " (%,d samples)".fmt(it) } ?: ""))
                        add("Codec" to orig.codec + if (orig.lossless) " · lossless" else " · lossy")
                        add("MIME type" to orig.mime)
                        add("Sample rate" to if (tech != null && tech.sampleRate > 0) "${tech.sampleRate} Hz" else "—")
                        add("Bits / sample" to if (tech != null && tech.bits > 0) "${tech.bits}" else "—")
                        add("Channels" to (tech?.channels?.toString() ?: "—"))
                        add("Bitrate" to if (orig.bitrateBps > 0) "${orig.bitrateBps / 1000} kbps" else "—")
                        add("Tag type" to (i?.tagType?.ifEmpty { null } ?: "—"))
                        add("Track gain" to (i?.rgTrackGain?.let { Fmt.db(it) } ?: "—"))
                        add("Track peak" to (i?.rgTrackPeak?.let { "%.6f".fmt(it) } ?: "—"))
                        add("Album gain" to (i?.rgAlbumGain?.let { Fmt.db(it) } ?: "—"))
                        add("Album peak" to (i?.rgAlbumPeak?.let { "%.6f".fmt(it) } ?: "—"))
                        add("Embedded art" to if (i?.hasPicture == true) "Yes" else "No")
                        add("Lyrics" to if (i?.lyrics != null) "Embedded" else "None embedded")
                    }
                    InfoCard(rows)
                }
                else -> {
                    CardBox {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Rating", style = Type.mono(11), color = t.sub, modifier = Modifier.width(100.dp))
                            StarRow(model.rating(tr)) { model.setRating(id, it) }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    fun date(ms: Long?) = ms?.let { df.format(Date(it)) } ?: "Never"
                    InfoCard(
                        listOf(
                            "Play count" to model.plays(tr).toString(),
                            "First played" to date(model.user.firstPlayed[id]),
                            "Last played" to date(model.user.lastPlayed[id]),
                            "Added" to df.format(Date(orig.dateAddedSec * 1000)),
                            "Favorite" to if (model.isFav(id)) "Yes" else "No",
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoCard(rows: List<Pair<String, String>>) {
    val t = LocalTokens.current
    CardBox {
        rows.forEachIndexed { i, (k, v) ->
            if (i > 0) Divider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(k, style = Type.mono(11), color = t.sub, modifier = Modifier.width(110.dp))
                Text(v, style = Type.body(14), color = t.text, modifier = Modifier.weight(1f))
            }
        }
    }
}

// ====================================================================== Settings

@Composable
fun SettingsScreen(model: AppModel) {
    val t = LocalTokens.current
    val s = model.settings
    Column(Modifier.fillMaxSize().testTag("settings").background(t.bg).blockTouches().statusBarsPadding().navigationBarsPadding()) {
        OverlayHeader("Settings", { model.settingsOpen = false })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SectionLabel("Appearance")
            CardBox {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Theme", style = Type.body(15, FontWeight.Medium), color = t.text, modifier = Modifier.padding(start = 4.dp))
                    Segmented(ThemeMode.entries.map { it to it.label }, s.theme, { m -> model.updateSettings { it.copy(theme = m) } })
                    Text("Track lists", style = Type.body(15, FontWeight.Medium), color = t.text, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
                    Segmented(listOf(false to "Comfortable", true to "Columns"), s.columns, { v -> model.updateSettings { it.copy(columns = v) } })
                }
                SwitchRow("Color from album art", "Accent follows the playing album", s.colorFromArt, { v -> model.updateSettings { it.copy(colorFromArt = v) } }, topLine = true)
            }

            SectionLabel("Library", Modifier.padding(top = 14.dp))
            CardBox {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Ic.folder, null, tint = t.accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("All music indexed by Android", style = Type.body(15, FontWeight.Medium), color = t.text)
                        Text("${model.index.tracks.size} tracks in ${model.index.folders.size} folders", style = Type.mono(11), color = t.faint)
                    }
                }
                Divider()
                Row(
                    Modifier.fillMaxWidth().height(52.dp).clickable { AudioArtFetcher.missing.clear(); model.rescan(announce = true) }.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Ic.refresh, null, tint = t.accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(if (model.loading) "Scanning…" else "Rescan library", style = Type.body(14, FontWeight.SemiBold), color = t.accent)
                }
                SwitchRow("Ignore short files", "Skip anything under 30 seconds (ringtones, notifications)", s.ignoreShort, { v -> model.updateSettings { it.copy(ignoreShort = v) } }, topLine = true)
            }

            SectionLabel("Playback", Modifier.padding(top = 14.dp))
            CardBox {
                SwitchRow("Pause on disconnect", "Pause when headphones are unplugged or Bluetooth drops", s.pauseOnDisconnect, { v -> model.updateSettings { it.copy(pauseOnDisconnect = v) } })
            }

            SectionLabel("About", Modifier.padding(top = 14.dp))
            CardBox {
                Text(
                    "Strata 0.1 · Media3 ExoPlayer engine with Strata's 32-bit float DSP. Fonts: Bricolage Grotesque and Geist (SIL Open Font License).",
                    style = Type.body(13), color = t.sub, modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}
