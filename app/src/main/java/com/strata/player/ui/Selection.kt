package com.strata.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.strata.player.AppModel
import com.strata.player.Sheet
import com.strata.player.data.SortKey

/** Replaces the mini player and tab bar while tracks are being selected. */
@Composable
fun SelectionBar(model: AppModel) {
    val t = LocalTokens.current
    val n = model.selected.size
    Column(Modifier.fillMaxWidth().testTag("selectionbar").background(t.surface).navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 6.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.close, "Cancel selection", { model.clearSelection() })
            Text(
                if (n == 0) "Tap tracks to select" else "$n selected",
                style = Type.body(16, FontWeight.SemiBold), color = t.text, modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            Text(
                "Select all", style = Type.body(14, FontWeight.SemiBold), color = t.accent,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { model.selectAll() }.padding(horizontal = 10.dp, vertical = 10.dp),
            )
        }
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp)) {
            val on = n > 0
            BarAction(Ic.play, "Play", on) { model.playSelected() }
            BarAction(Ic.playNext, "Play next", on) { model.queueSelected(next = true) }
            BarAction(Ic.queue, "Queue", on) { model.queueSelected(next = false) }
            BarAction(Ic.listAdd, "Playlist", on) { model.sheet = Sheet.AddToPlaylist(model.selected) }
            if (model.selectionPlaylist != null) BarAction(Ic.trash, "Remove", on) { model.removeSelectedFromPlaylist() }
        }
    }
}

@Composable
private fun RowScope.BarAction(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val t = LocalTokens.current
    Column(
        Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = if (enabled) t.text else t.faint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, style = Type.body(11, FontWeight.Medium), color = if (enabled) t.sub else t.faint)
    }
}

/**
 * Full-screen song picker for a playlist: filter with the search syntax, tick as many tracks as you like,
 * add them in one go.
 */
@Composable
fun TrackPicker(model: AppModel, pid: String) {
    val t = LocalTokens.current
    val playlist = model.user.playlists.firstOrNull { it.id == pid }
    var q by remember(pid) { mutableStateOf("") }
    var picked by remember(pid) { mutableStateOf<List<Long>>(emptyList()) }
    val all = remember(model.index) { model.sortedTracks(SortKey.TITLE) }
    val shown = remember(q, all, model.user, model.tech) { if (q.isBlank()) all else model.search(q).sortedBy { it.title.lowercase() } }
    val inList = playlist?.ids?.toSet() ?: emptySet()
    val addable = shown.filter { it.id !in inList }.map { it.id }

    Column(Modifier.fillMaxSize().testTag("picker").blockTouches().background(t.bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.back, "Close picker", { model.pickerFor = null })
            Column(Modifier.weight(1f)) {
                Text("Add songs", style = Type.body(16, FontWeight.SemiBold), color = t.text)
                Text(playlist?.name ?: "", style = Type.body(12), color = t.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp))
                .background(t.surface).padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Ic.search, null, tint = t.faint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (q.isEmpty()) Text("Filter: artist, album, genre:jazz…", style = Type.body(15), color = t.faint, maxLines = 1)
                BasicTextField(
                    q, { q = it }, singleLine = true, textStyle = Type.body(15).copy(color = t.text), cursorBrush = SolidColor(t.accentFill),
                    modifier = Modifier.fillMaxWidth().testTag("picker-filter"),
                )
            }
            if (q.isNotEmpty()) IconBtn(Ic.close, "Clear filter", { q = "" }, tint = t.sub, iconSize = 16.dp)
        }
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${Fmt.plural(shown.size, "track")} shown", style = Type.mono(11), color = t.faint, modifier = Modifier.weight(1f))
            val allPicked = addable.isNotEmpty() && addable.all { it in picked }
            Text(
                if (allPicked) "Unselect shown" else "Select all shown", style = Type.body(14, FontWeight.SemiBold), color = t.accent,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable {
                    picked = if (allPicked) picked.filter { it !in addable } else picked + addable.filter { it !in picked }
                }.padding(horizontal = 10.dp, vertical = 10.dp),
            )
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 8.dp)) {
            items(shown, key = { it.id }) { tr ->
                val already = tr.id in inList
                val on = already || tr.id in picked
                Row(
                    Modifier.fillMaxWidth().height(62.dp)
                        .clickable(enabled = !already) { picked = if (tr.id in picked) picked - tr.id else picked + tr.id }
                        .semantics { selected = on }
                        .padding(start = 20.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Art(tr, Modifier.size(42.dp), RoundedCornerShape(9.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(tr.title, style = Type.body(15, FontWeight.Medium), color = if (already) t.sub else t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (already) "Already in playlist" else "${tr.artist} · ${tr.album}",
                            style = Type.body(12), color = t.sub, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    CheckDot(on, enabled = !already)
                }
            }
            if (shown.isEmpty()) item { EmptyNote("Nothing matches this filter.") }
        }
        BigButton(
            if (picked.isEmpty()) "Done" else "Add ${Fmt.plural(picked.size, "song")}", Ic.check, picked.isNotEmpty(),
            {
                if (picked.isNotEmpty()) model.addToPlaylist(pid, picked)
                model.pickerFor = null
            },
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth(),
        )
    }
}
