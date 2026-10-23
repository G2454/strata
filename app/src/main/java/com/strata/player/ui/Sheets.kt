package com.strata.player.ui

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.strata.player.AppModel
import com.strata.player.Detail
import com.strata.player.Sheet
import com.strata.player.data.Order

@Composable
fun SheetHost(model: AppModel) {
    val t = LocalTokens.current
    val sheet = model.sheet
    var last by remember { mutableStateOf<Sheet?>(null) }
    if (sheet != null) last = sheet

    AnimatedVisibility(sheet != null, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier.fillMaxSize().background(t.scrim)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { model.sheet = null },
        )
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(sheet != null, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .testTag("sheet")
                    .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.86f).dp)
                    // A shaped background instead of clip(): a clip with mixed corner radii makes every touch
                    // run a Path-intersection hit test (costly, and when it misses, the tap lands on the scrim
                    // and just closes the sheet). The scrolling content below clips itself.
                    .background(t.sheet, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .blockTouches()
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(t.line2))
                }
                val current = last
                if (current is Sheet.Queue) {
                    // The queue scrolls by itself so it can jump back to the current track.
                    QueueSheet(model)
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 18.dp)) {
                        when (val s = current) {
                            is Sheet.TrackMenu -> TrackMenu(model, s)
                            is Sheet.AddToPlaylist -> AddToPlaylist(model, s.ids)
                            is Sheet.NewPlaylist -> NewPlaylist(model, s)
                            is Sheet.PlaylistMenu -> PlaylistMenu(model, s.id)
                            Sheet.Sleep -> SleepSheet(model)
                            Sheet.Speed -> SpeedSheet(model)
                            Sheet.OrderSheet -> OrderSheet(model)
                            Sheet.Output -> OutputSheet(model)
                            Sheet.Queues -> QueuesSheet(model)
                            is Sheet.AddToQueue -> AddToQueueSheet(model, s.ids)
                            is Sheet.NewQueue -> NewQueueSheet(model, s.withTracks)
                            Sheet.Folders -> FoldersSheet(model)
                            Sheet.Queue, null -> {}
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SheetTitle(title: String, sub: String? = null, trailing: (@Composable () -> Unit)? = null) {
    val t = LocalTokens.current
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.display(21), color = t.text)
            if (sub != null) Text(sub, style = Type.body(13), color = t.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}

@Composable
fun ActionRow(label: String, hint: String = "", onClick: () -> Unit) {
    val t = LocalTokens.current
    Divider()
    Row(Modifier.fillMaxWidth().height(52.dp).clickable(onClick = onClick).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.body(16), color = t.text, modifier = Modifier.weight(1f))
        if (hint.isNotEmpty()) Text(hint, style = Type.mono(11), color = t.faint)
    }
}

// ------------------------------------------------------------------ track menu

@Composable
private fun TrackMenu(model: AppModel, s: Sheet.TrackMenu) {
    val t = LocalTokens.current
    val tr = model.track(s.id) ?: return
    val e = model.engine
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Art(tr, Modifier.size(52.dp), RoundedCornerShape(10.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(tr.title, style = Type.body(16, FontWeight.SemiBold), color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${tr.artist} · ${tr.album}", style = Type.body(13), color = t.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    StarRow(model.rating(tr)) { model.setRating(tr.id, it) }
    ActionRow("Play next") { e.playNext(tr); model.sheet = null; model.flash("Plays next") }
    val qn = e.queuedItemsAfterCurrent().size
    ActionRow("Add to playback queue", if (qn > 0) "queue · $qn" else "") { e.addToQueue(tr); model.sheet = null; model.flash("Added to queue (${qn + 1})") }
    ActionRow("Add to playlist…", "›") { model.sheet = Sheet.AddToPlaylist(listOf(tr.id)) }
    if (model.queues.isNotEmpty()) ActionRow("Add to another queue…", "›") { model.sheet = Sheet.AddToQueue(listOf(tr.id)) }
    ActionRow("Go to album") { model.goTo(Detail.AlbumD(tr.albumId)) }
    val artists = model.index.artistsOf(tr)
    if (artists.size <= 1) {
        ActionRow("Go to artist") { model.goTo(Detail.ArtistD(artists.firstOrNull() ?: tr.artist)) }
    } else {
        // "A feat. B": one row per performer.
        artists.forEach { a -> ActionRow("Go to $a", "artist") { model.goTo(Detail.ArtistD(a)) } }
    }
    val pid = s.fromPlaylist
    if (pid != null) {
        val ids = model.user.playlists.firstOrNull { it.id == pid }?.ids ?: emptyList()
        val i = ids.indexOf(tr.id)
        if (i > 0) ActionRow("Move up in playlist") { model.movePlaylistItem(pid, i, i - 1) }
        if (i >= 0 && i < ids.lastIndex) ActionRow("Move down in playlist") { model.movePlaylistItem(pid, i, i + 1) }
        ActionRow("Remove from playlist") { model.removeFromPlaylist(pid, tr.id); model.sheet = null; model.flash("Removed from “${model.playlistName(pid)}”") }
    }
    ActionRow("Properties", "details") { model.sheet = null; model.propsTab = 1; model.propsId = tr.id }
    ActionRow("Edit tags", "metadata") { model.sheet = null; model.propsTab = 0; model.propsId = tr.id }
    ActionRow("Share file") {
        model.sheet = null
        val send = Intent(Intent.ACTION_SEND).setType(tr.mime.ifEmpty { "audio/*" })
            .putExtra(Intent.EXTRA_STREAM, tr.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, tr.title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
fun StarRow(rating: Int, onRate: (Int) -> Unit) {
    val t = LocalTokens.current
    Row(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
        for (n in 1..5) {
            IconBtn(
                if (n <= rating) Ic.starFill else Ic.star, "Rate $n star" + if (n > 1) "s" else "",
                { onRate(if (rating == n) 0 else n) }, tint = if (n <= rating) t.accent else t.faint,
            )
        }
    }
}

// ------------------------------------------------------------------ playlists

@Composable
private fun AddToPlaylist(model: AppModel, ids: List<Long>) {
    val t = LocalTokens.current
    if (ids.isEmpty()) return
    val sub = if (ids.size == 1) model.track(ids[0])?.title ?: "" else Fmt.plural(ids.size, "track")
    SheetTitle("Add to playlist", sub)
    Row(
        Modifier.fillMaxWidth().height(60.dp).clickable { model.sheet = Sheet.NewPlaylist(ids, false) }.padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, t.line2, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Icon(Ic.plus, null, tint = t.accent, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text("New playlist", style = Type.body(15, FontWeight.SemiBold), color = t.accent)
    }
    model.user.playlists.filter { !it.auto }.forEach { p ->
        val have = ids.count { it in p.ids }
        Row(
            Modifier.fillMaxWidth().height(60.dp).clickable {
                model.addToPlaylist(p.id, ids)
                model.sheet = null
                model.clearSelection()
            }.padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Mosaic(p.ids.mapNotNull { model.track(it) }, Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, style = Type.body(15, FontWeight.Medium), color = t.text)
                Text(
                    Fmt.plural(p.ids.size, "track") + if (ids.size > 1 && have > 0) " · $have of these already in" else "",
                    style = Type.body(12), color = t.sub,
                )
            }
            if (have == ids.size) Icon(Ic.check, null, tint = t.accent, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, mono: Boolean = false, placeholder: String = "") {
    val t = LocalTokens.current
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(t.surface2).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(label.uppercase(), style = Type.label(10), color = t.faint)
        Box {
            if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, style = if (mono) Type.mono(14) else Type.body(16), color = t.faint)
            BasicTextField(
                value, onChange, singleLine = true,
                textStyle = (if (mono) Type.mono(14) else Type.body(16)).copy(color = t.text),
                cursorBrush = SolidColor(t.accentFill),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun NewPlaylist(model: AppModel, s: Sheet.NewPlaylist) {
    var name by remember { mutableStateOf(if (s.auto) "New auto playlist" else "") }
    var query by remember { mutableStateOf("") }
    val count = if (s.auto && query.isNotBlank()) model.search(query).size else null
    SheetTitle(if (s.auto) "New auto playlist" else "New playlist", if (s.auto) "Fills itself from a query, like foobar2000 autoplaylists" else null)
    Field("Name", name, { name = it }, placeholder = "My playlist")
    if (s.auto) {
        Field("Query", query, { query = it }, mono = true, placeholder = "genre:jazz rating>=4")
        Text(
            if (count == null) "Uses the same syntax as Search." else "${Fmt.plural(count, "track")} match right now.",
            style = Type.mono(11), color = LocalTokens.current.sub, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
    }
    if (!s.auto && s.withTracks.size > 1) {
        Text(
            "${Fmt.plural(s.withTracks.size, "track")} will be added.", style = Type.body(13), color = LocalTokens.current.sub,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
    }
    BigButton(if (!s.auto && s.withTracks.isEmpty()) "Create and add songs" else "Create", null, true, {
        val id = model.createPlaylist(name, if (s.auto) query.ifBlank { "rating>=4" } else null, if (s.auto) emptyList() else s.withTracks)
        model.sheet = null
        model.clearSelection()
        if (s.withTracks.isEmpty() || s.auto) {
            model.open(Detail.PlaylistD(id))
            // An empty playlist goes straight to the multi-select song picker.
            if (!s.auto) model.pickerFor = id
        }
    }, Modifier.padding(16.dp).fillMaxWidth())
}

@Composable
private fun PlaylistMenu(model: AppModel, id: String) {
    val p = model.user.playlists.firstOrNull { it.id == id } ?: return
    var name by remember(id) { mutableStateOf(p.name) }
    var query by remember(id) { mutableStateOf(p.query ?: "") }
    var confirm by remember(id) { mutableStateOf(false) }
    SheetTitle("Playlist options", p.name)
    Field("Name", name, { name = it })
    if (p.auto) Field("Query", query, { query = it }, mono = true)
    BigButton("Save", null, true, {
        model.renamePlaylist(id, name, if (p.auto) query else null)
        model.sheet = null
        model.flash("Saved")
    }, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth())
    val ids = model.playlistIds(id)
    if (ids.isNotEmpty()) {
        BigButton("Play", Ic.play, false, { model.play(ids, ids.first(), p.name); model.sheet = null }, Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp).fillMaxWidth())
    }
    val t = LocalTokens.current
    Text(
        if (confirm) "Tap again to delete “${p.name}”" else "Delete playlist",
        style = Type.body(15, FontWeight.SemiBold), color = Color(0xFFE5533F),
        modifier = Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, t.line2, RoundedCornerShape(14.dp))
            .clickable { if (confirm) { model.deletePlaylist(id); model.sheet = null } else confirm = true }.padding(16.dp),
    )
}

// ------------------------------------------------------------------ small radio sheets

@Composable
private fun SpeedSheet(model: AppModel) {
    val s = model.settings
    SheetTitle("Playback speed", "Applies to every track")
    listOf(0.5f, 0.75f, 0.9f, 1f, 1.1f, 1.25f, 1.5f, 2f).forEachIndexed { i, v ->
        RadioRow(if (v == 1f) "1.0× · normal" else "$v×", null, s.speed == v, {
            model.updateSettings { it.copy(speed = v) }
        }, topLine = i > 0)
    }
    Divider()
    SwitchRow("Keep pitch", "Time-stretch without changing key", s.keepPitch, { v -> model.updateSettings { it.copy(keepPitch = v) } })
}

@Composable
private fun OrderSheet(model: AppModel) {
    SheetTitle("Playback order", "What plays after the current track")
    Order.entries.forEachIndexed { i, o ->
        RadioRow(o.label, o.desc, model.settings.order == o, {
            model.updateSettings { it.copy(order = o) }
            model.sheet = null
        }, topLine = i > 0)
    }
}

@Composable
private fun OutputSheet(model: AppModel) {
    val e = model.engine
    val devices = remember { e.outputDevices() }
    SheetTitle("Audio output", "Preferred device for Strata's playback")
    RadioRow("System default", "Follow Android's routing", e.preferredDeviceId == 0, { e.setPreferredDevice(null) }, topLine = false)
    devices.forEach { d ->
        val type = when (d.type) {
            android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES, android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headphones"
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
            android.media.AudioDeviceInfo.TYPE_USB_DEVICE, android.media.AudioDeviceInfo.TYPE_USB_HEADSET -> "USB audio"
            android.media.AudioDeviceInfo.TYPE_HDMI -> "HDMI"
            else -> "Output"
        }
        val rates = d.sampleRates.takeIf { it.isNotEmpty() }?.let { r -> " · up to ${Fmt.rate(r.max())} kHz" } ?: ""
        RadioRow(d.productName?.toString()?.ifBlank { type } ?: type, type + rates, e.preferredDeviceId == d.id, { e.setPreferredDevice(d) }, topLine = true)
    }
}
