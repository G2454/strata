package com.strata.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.strata.player.AppModel
import com.strata.player.Detail
import com.strata.player.LibTab
import com.strata.player.data.SortKey
import com.strata.player.data.Track

@Composable
fun LibraryScreen(model: AppModel) {
    val idx = model.index
    when (model.libTab) {
        LibTab.TRACKS -> TracksTab(model)
        LibTab.ALBUMS -> LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { Column(Modifier.bleed(20.dp)) { LibHeader(model) } }
            items(idx.albums, key = { it.id }) { al ->
                val first = model.track(al.trackIds.first())
                Column(Modifier.clickable { model.open(Detail.AlbumD(al.id)) }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Art(first, Modifier.fillMaxWidth().aspectRatio(1f), RoundedCornerShape(16.dp))
                    Column(Modifier.padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val t = LocalTokens.current
                        Text(al.title, style = Type.body(15, FontWeight.SemiBold), color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(al.artist, al.year.takeIf { it > 0 }?.toString()).joinToString(" · "), style = Type.body(13), color = t.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (first != null) Text(Fmt.badge(first, model.tech[first.id]), style = Type.mono(11), color = t.faint)
                    }
                }
            }
        }
        LibTab.GENRES -> LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { Column(Modifier.bleed(20.dp)) { LibHeader(model) } }
            items(idx.genres, key = { it.first }) { (name, ts) -> GenreTile(name, ts) { model.open(Detail.GenreD(name)) } }
        }
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { LibHeader(model) }
            when (model.libTab) {
                LibTab.ARTISTS -> items(idx.artists, key = { it.first }) { (name, ts) ->
                    val albums = ts.map { it.albumId }.distinct().size
                    NameRow(ts.first(), name, "${Fmt.plural(albums, "album")} · ${Fmt.plural(ts.size, "track")}", round = true) { model.open(Detail.ArtistD(name)) }
                }
                LibTab.FOLDERS -> {
                    item { Text("Folder view · grouped by directory", style = Type.mono(11), color = LocalTokens.current.faint, modifier = Modifier.padding(start = 20.dp, bottom = 8.dp)) }
                    items(idx.folders, key = { it.first }) { (path, ts) -> FolderRow(path, ts.size) { model.open(Detail.FolderD(path)) } }
                }
                LibTab.COMPOSERS -> items(idx.composers, key = { it.first }) { (name, ts) -> ComposerRow(name, ts.size) { model.open(Detail.ComposerD(name)) } }
                else -> {}
            }
        }
    }
}

@Composable
private fun TracksTab(model: AppModel) {
    val t = LocalTokens.current
    val s = model.settings
    val plays = if (s.sort == SortKey.PLAYS) model.user.plays else null
    val sorted = remember(model.index, s.sort, plays) { model.sortedTracks(s.sort) }
    val ids = remember(sorted) { sorted.map { it.id } }
    val current = model.engine.currentId
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
        item { LibHeader(model) }
        item {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(t.accentFill)
                        .clickable { model.shuffle(ids, "All tracks") }.padding(start = 12.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Ic.shuffle, null, tint = t.onAccent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Shuffle all", style = Type.body(14, FontWeight.SemiBold), color = t.onAccent)
                }
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).clickable {
                        val keys = SortKey.entries
                        model.updateSettings { it.copy(sort = keys[(keys.indexOf(it.sort) + 1) % keys.size]) }
                    }.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Ic.sort, null, tint = t.sub, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(s.sort.label, style = Type.body(13, FontWeight.Medium), color = t.sub)
                }
                IconBtn(Ic.select, "Select tracks", { model.beginSelecting(ids) }, tint = t.sub, iconSize = 20.dp)
                IconBtn(
                    Ic.columns, "Toggle column view", { model.updateSettings { it.copy(columns = !it.columns) } },
                    tint = t.sub, iconSize = 20.dp, background = if (s.columns) t.soft else Color.Transparent,
                )
            }
        }
        if (s.columns) item { ColumnHeader() }
        if (sorted.isEmpty()) item { LibraryEmpty(model) }
        items(sorted, key = { it.id }) { tr ->
            SelectableTrackRow(model, tr, ids, "All tracks", s.columns)
        }
    }
}

@Composable
private fun LibraryEmpty(model: AppModel) {
    if (model.loading) EmptyNote("Scanning your music…")
    else EmptyNote("No music found yet. Copy audio files into your phone's Music folder (or any folder the system media scanner indexes), then pull Rescan in Settings.")
}

@Composable
fun LibHeader(model: AppModel) {
    val t = LocalTokens.current
    val idx = model.index
    Column {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 18.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            ScreenTitle("Library", Modifier.weight(1f))
            IconBtn(Ic.search, "Search", { model.selectTab(com.strata.player.Tab.SEARCH) })
            IconBtn(Ic.settings, "Settings", { model.settingsOpen = true })
        }
        Text(
            "${idx.tracks.size} tracks · ${idx.albums.size} albums · ${Fmt.long(idx.totalDurationMs)} · ${Fmt.size(idx.totalBytes)}",
            style = Type.mono(12), color = t.faint, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 14.dp),
        )
        HScroll(Modifier.padding(bottom = 14.dp)) {
            LibTab.entries.forEach { lt -> Pill(lt.label, model.libTab == lt, { model.libTab = lt }) }
        }
    }
}

@Composable
private fun NameRow(sample: Track, name: String, meta: String, round: Boolean, onClick: () -> Unit) {
    val t = LocalTokens.current
    Row(
        Modifier.fillMaxWidth().height(70.dp).clickable(onClick = onClick).padding(start = 20.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Art(sample, Modifier.size(52.dp), if (round) CircleShape else RoundedCornerShape(12.dp))
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(name, style = Type.body(16, FontWeight.Medium), color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(meta, style = Type.body(13), color = t.sub)
        }
        Icon(Ic.chevronRight, null, tint = t.faint, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun FolderRow(path: String, count: Int, onClick: () -> Unit) {
    val t = LocalTokens.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 68.dp).clickable(onClick = onClick).padding(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(t.surface), contentAlignment = Alignment.Center) {
            Icon(Ic.folder, null, tint = t.accent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(path.substringAfterLast('/'), style = Type.body(15, FontWeight.Medium), color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(path.substringBeforeLast('/', "").removePrefix("/storage/emulated/0").replace("/", " / ").trim(), style = Type.mono(11), color = t.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(Fmt.plural(count, "file"), style = Type.mono(11), color = t.sub)
    }
}

@Composable
private fun ComposerRow(name: String, count: Int, onClick: () -> Unit) {
    val t = LocalTokens.current
    val initials = name.split(' ', '.', '&').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase()
    Row(
        Modifier.fillMaxWidth().height(64.dp).clickable(onClick = onClick).padding(start = 20.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(t.surface2), contentAlignment = Alignment.Center) {
            Text(initials, style = Type.display(15), color = t.sub)
        }
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(name, style = Type.body(16, FontWeight.Medium), color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(Fmt.plural(count, "work"), style = Type.body(13), color = t.sub)
        }
    }
}

@Composable
private fun GenreTile(name: String, ts: List<Track>, onClick: () -> Unit) {
    val t = LocalTokens.current
    Box(Modifier.fillMaxWidth().height(100.dp).clip(RoundedCornerShape(18.dp)).background(t.surface).clickable(onClick = onClick)) {
        Art(ts.first(), Modifier.align(Alignment.BottomEnd).offset(x = 16.dp, y = 18.dp).rotate(14f).size(74.dp), RoundedCornerShape(12.dp))
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(name, style = Type.display(18, FontWeight.SemiBold), color = t.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(0.72f))
            Text(Fmt.plural(ts.size, "track"), style = Type.mono(11), color = t.sub)
        }
    }
}

/** Shared list body for any group of tracks, used by detail pages. */
fun LazyListScope.trackItems(
    model: AppModel, tracks: List<Track>, ctxName: String, numbered: Boolean,
    playlist: String? = null, onMenu: (Track) -> Unit,
) {
    val ids = tracks.map { it.id }
    items(tracks, key = { it.id }) { tr ->
        SelectableTrackRow(
            model, tr, ids, ctxName, model.settings.columns, playlist = playlist, onMenu = { onMenu(tr) },
            subtitle = if (numbered) "${tr.artist} · ${Fmt.time(tr.durationMs)}" else "${tr.artist} · ${tr.album}",
        )
    }
}

/**
 * A track row wired for playback and multi-select: tap plays (or toggles while selecting),
 * long-press starts selecting (or selects a range while selecting).
 */
@Composable
fun SelectableTrackRow(
    model: AppModel,
    tr: Track,
    ids: List<Long>,
    ctxName: String,
    columns: Boolean,
    playlist: String? = null,
    onMenu: () -> Unit = { model.sheet = com.strata.player.Sheet.TrackMenu(tr.id) },
    subtitle: String = "${tr.artist} · ${tr.album}",
    beforePlay: () -> Unit = {},
) {
    val selecting = model.selecting
    TrackRow(
        tr, Fmt.badge(tr, model.tech[tr.id]), tr.id == model.engine.currentId, columns,
        onClick = { if (model.selecting) model.toggleSelected(ids, tr.id) else { beforePlay(); model.play(ids, tr.id, ctxName) } },
        onMenu = onMenu,
        subtitle = subtitle,
        onLongClick = { model.startSelection(ids, tr.id, playlist) },
        selecting = selecting,
        selected = selecting && tr.id in model.selected,
    )
}
