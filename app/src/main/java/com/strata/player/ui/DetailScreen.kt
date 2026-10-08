package com.strata.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.strata.player.AppModel
import com.strata.player.Detail
import com.strata.player.SMART_LISTS
import com.strata.player.Sheet
import com.strata.player.data.Album
import com.strata.player.data.Track

private data class DetailData(
    val kicker: String,
    val title: String,
    val sub: String,
    val meta: String,
    val tracks: List<Track>,
    val round: Boolean = false,
    val mosaic: Boolean = false,
    val query: String? = null,
    val albums: List<Album> = emptyList(),
    val numbered: Boolean = false,
    val playlistId: String? = null,
    val editable: Boolean = false,
)

@Composable
fun DetailScreen(model: AppModel, d: Detail) {
    val t = LocalTokens.current
    val idx = model.index
    val user = model.user
    val data = remember(d, idx, user) { build(model, d) }
    val list = rememberLazyListState()
    val scrolled by remember { derivedStateOf { list.firstVisibleItemIndex > 0 } }

    Column(Modifier.fillMaxSize().background(t.bg)) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.back, "Back", { model.back() })
            Text(
                if (scrolled) data.title else "", style = Type.body(15, FontWeight.SemiBold), color = t.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (data.playlistId != null && data.playlistId !in SMART_LISTS.map { it.id }) {
                IconBtn(Ic.more, "Playlist options", { model.sheet = Sheet.PlaylistMenu(data.playlistId) })
            }
        }
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    val shape = if (data.round) CircleShape else RoundedCornerShape(22.dp)
                    if (data.mosaic) {
                        Mosaic(data.tracks, Modifier.size(196.dp).shadow(24.dp, shape).clip(shape))
                    } else {
                        Art(data.tracks.firstOrNull(), Modifier.size(196.dp).shadow(24.dp, shape), shape, big = true)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(data.kicker, style = Type.label(10), color = t.accent)
                        Text(data.title, style = Type.display(28), color = t.text, textAlign = TextAlign.Center)
                        if (data.sub.isNotEmpty()) Text(data.sub, style = Type.body(15), color = t.sub, textAlign = TextAlign.Center)
                        Text(data.meta, style = Type.mono(11), color = t.faint, textAlign = TextAlign.Center)
                    }
                    if (data.query != null) {
                        Text(
                            data.query, style = Type.mono(12), color = t.sub,
                            modifier = Modifier.border(1.dp, t.line2, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                    if (data.tracks.isNotEmpty()) {
                        val ids = data.tracks.map { it.id }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            BigButton("Play", Ic.play, true, { model.play(ids, ids.first(), data.title) }, Modifier.weight(1f))
                            BigButton("Shuffle", Ic.shuffle, false, { model.shuffle(ids, data.title) }, Modifier.weight(1f))
                        }
                    }
                }
            }
            if (data.albums.isNotEmpty()) {
                item { Column {
                    Text("Albums", style = Type.body(13, FontWeight.SemiBold), color = t.sub, modifier = Modifier.padding(start = 20.dp, bottom = 8.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 18.dp)) {
                        items(data.albums, key = { it.id }) { al ->
                            Column(Modifier.width(128.dp).clickable { model.open(Detail.AlbumD(al.id)) }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Art(model.track(al.trackIds.first()), Modifier.size(128.dp), RoundedCornerShape(14.dp))
                                Text(al.title, style = Type.body(13, FontWeight.SemiBold), color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (al.year > 0) Text(al.year.toString(), style = Type.body(12), color = t.sub)
                            }
                        }
                    }
                } }
            }
            if (model.settings.columns && data.tracks.isNotEmpty()) item { ColumnHeader() }
            trackItems(model, data.tracks, data.title, data.numbered) { tr ->
                model.sheet = Sheet.TrackMenu(tr.id, data.playlistId?.takeIf { data.editable })
            }
            if (data.tracks.isEmpty()) {
                item {
                    EmptyNote(
                        if (data.query != null) "Nothing matches this query yet."
                        else "Nothing here yet. Add tracks from any song's menu with “Add to playlist”.",
                    )
                }
            }
        }
    }
}

@Composable
fun Mosaic(tracks: List<Track>, modifier: Modifier) {
    val albums = tracks.distinctBy { it.albumId }.take(4)
    Box(modifier.background(LocalTokens.current.surface2)) {
        if (albums.isEmpty()) return@Box
        Column(Modifier.fillMaxSize()) {
            for (r in 0 until 2) {
                Row(Modifier.weight(1f)) {
                    for (c in 0 until 2) {
                        val tr = albums[(r * 2 + c) % albums.size]
                        Art(tr, Modifier.weight(1f).fillMaxSize(), RoundedCornerShape(0.dp))
                    }
                }
            }
        }
    }
}

private fun build(model: AppModel, d: Detail): DetailData {
    val idx = model.index
    fun total(ts: List<Track>) = Fmt.long(ts.sumOf { it.durationMs })
    return when (d) {
        is Detail.AlbumD -> {
            val al = idx.albumById[d.id]
            val ts = al?.trackIds?.mapNotNull { idx.byId[it] } ?: emptyList()
            val first = ts.firstOrNull()
            val tech = first?.let { model.tech[it.id] }
            val fmt = if (first == null) "" else if (first.lossless && tech != null && tech.bits > 0) "${first.codec} · ${tech.bits}-bit / ${Fmt.rate(tech.sampleRate)} kHz" else first.codec
            DetailData(
                kicker = "ALBUM", title = al?.title ?: "Album", sub = al?.artist ?: "",
                meta = listOfNotNull(al?.year?.takeIf { it > 0 }?.toString(), first?.genre, Fmt.plural(ts.size, "track"), total(ts)).joinToString(" · ") + "\n" + fmt,
                tracks = ts, numbered = true,
            )
        }
        is Detail.ArtistD -> {
            val ts = idx.albumOrdered(idx.tracks.filter { it.artist == d.name })
            val albums = idx.albums.filter { al -> al.trackIds.any { idx.byId[it]?.artist == d.name } }
            DetailData("ARTIST", d.name, "${Fmt.plural(albums.size, "album")} · ${Fmt.plural(ts.size, "track")}", total(ts), ts, round = true, albums = albums)
        }
        is Detail.GenreD -> {
            val ts = idx.albumOrdered(idx.tracks.filter { it.genre == d.name })
            DetailData("GENRE", d.name, Fmt.plural(ts.size, "track"), total(ts), ts)
        }
        is Detail.FolderD -> {
            val ts = idx.tracks.filter { it.folder == d.path }.sortedWith(compareBy({ it.discNo }, { it.trackNo }, { it.fileName }))
            DetailData("FOLDER", d.path.substringAfterLast('/'), d.path.substringBeforeLast('/', "").removePrefix("/storage/emulated/0"), "${Fmt.plural(ts.size, "file")} · ${total(ts)}", ts, numbered = true)
        }
        is Detail.ComposerD -> {
            val ts = idx.albumOrdered(idx.tracks.filter { it.composer == d.name })
            DetailData("COMPOSER", d.name, Fmt.plural(ts.size, "work"), total(ts), ts, round = true)
        }
        is Detail.PlaylistD -> {
            val ids = model.playlistIds(d.id)
            val ts = ids.mapNotNull { idx.byId[it] }
            val pl = model.user.playlists.firstOrNull { it.id == d.id }
            val smart = SMART_LISTS.any { it.id == d.id }
            DetailData(
                kicker = when { smart -> "SMART PLAYLIST"; pl?.auto == true -> "AUTO PLAYLIST"; else -> "PLAYLIST" },
                title = model.playlistName(d.id), sub = Fmt.plural(ts.size, "track"),
                meta = if (ts.isEmpty()) "Empty" else total(ts), tracks = ts, mosaic = true, query = pl?.query,
                playlistId = d.id, editable = pl != null && !pl.auto,
            )
        }
    }
}
