package com.strata.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.strata.player.AppModel
import com.strata.player.Detail
import com.strata.player.SMART_LISTS
import com.strata.player.Sheet
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.strata.player.data.Track
import com.strata.player.data.Query
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect

// ====================================================================== Playlists

@Composable
fun PlaylistsScreen(model: AppModel) {
    val t = LocalTokens.current
    val user = model.user
    val idx = model.index
    val counts = remember(user, idx) { SMART_LISTS.associate { it.id to model.smartListCount(it.id) } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                ScreenTitle("Playlists", Modifier.weight(1f))
                Row(
                    Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).border(1.dp, t.line2, RoundedCornerShape(20.dp))
                        .clickable { model.sheet = Sheet.NewPlaylist(emptyList(), false) }.padding(start = 10.dp, end = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Ic.plus, null, tint = t.text, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("New", style = Type.body(14, FontWeight.Medium), color = t.text)
                }
            }
        }
        item { Column {
            val icons = mapOf("fav" to Ic.heartFill, "most" to Ic.bars, "recent" to Ic.clock, "added" to Ic.added, "lost" to Ic.memories)
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SMART_LISTS.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEach { sl ->
                            SmartTile(icons.getValue(sl.id), sl.name, Fmt.plural(counts[sl.id] ?: 0, "track"), Modifier.weight(1f)) {
                                model.open(Detail.PlaylistD(sl.id))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
            SectionLabel("Your playlists")
        } }
        items(user.playlists, key = { it.id }) { p ->
            // A plain playlist only changes with its own edits; an auto playlist also with what its query reads.
            val q = remember(p.query) { p.query?.let { Query.parse(it) } }
            val ids = remember(p, idx, if (q?.usesUserData == true) user else null, if (q?.usesTech == true) model.tech else null) { model.playlistIds(p.id) }
            val ts = ids.mapNotNull { idx.byId[it] }
            Row(
                Modifier.animateItem().fillMaxWidth().heightIn(min = 76.dp).clickable { model.open(Detail.PlaylistD(p.id)) }.padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Mosaic(ts, Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, style = Type.body(16, FontWeight.Medium), color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (p.auto) {
                            Spacer(Modifier.width(8.dp))
                            Text("AUTO", style = Type.label(9), color = t.accent, modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(t.soft).padding(horizontal = 5.dp, vertical = 2.dp))
                        }
                    }
                    Text(Fmt.plural(ts.size, "track") + if (ts.isNotEmpty()) " · " + Fmt.long(ts.sumOf { it.durationMs }) else "", style = Type.body(13), color = t.sub)
                    if (p.query != null) Text(p.query, style = Type.mono(11), color = t.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconBtn(Ic.more, "Options for ${p.name}", { model.sheet = Sheet.PlaylistMenu(p.id) }, tint = t.sub, iconSize = 20.dp)
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().height(56.dp).clickable { model.sheet = Sheet.NewPlaylist(emptyList(), true) }.padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Ic.plus, null, tint = t.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text("New auto playlist from a query", style = Type.body(14, FontWeight.SemiBold), color = t.accent)
            }
        }
    }
}

@Composable
private fun SmartTile(icon: ImageVector, name: String, count: String, modifier: Modifier, onClick: () -> Unit) {
    val t = LocalTokens.current
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(t.surface).clickable(onClick = onClick).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Icon(icon, null, tint = t.accent, modifier = Modifier.size(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = Type.body(15, FontWeight.SemiBold), color = t.text)
            Text(count, style = Type.mono(11), color = t.sub)
        }
    }
}

// ====================================================================== Search

private val SYNTAX_CHIPS = listOf("format:flac", "bits>=24", "genre:jazz|ambient", "rating>=4", "plays>10", "fav:1", "year:2024", "length>360", "-genre:podcast")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(model: AppModel) {
    val t = LocalTokens.current
    val q = model.query
    val idx = model.index
    val results = rememberSearch(model, q)
    val ids = remember(results) { results.map { it.id } }
    // Worked out once per result set, not on every redraw (a broad query can match thousands of tracks).
    val artists = remember(results, idx) { results.flatMap { idx.artistsOf(it) }.distinct() }
    val albums = remember(results) { results.map { it.albumId }.distinct() }
    val artistSamples = remember(results, artists) {
        artists.take(12).associateWith { a -> results.first { a in idx.artistsOf(it) } }
    }
    val focus = LocalFocusManager.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Column {
            ScreenTitle("Search", Modifier.padding(start = 20.dp, top = 18.dp, bottom = 14.dp))
            Row(
                Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp))
                    .background(t.surface).border(1.dp, t.line, RoundedCornerShape(16.dp)).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Ic.search, null, tint = t.faint, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (q.isEmpty()) Text("Songs, artists, or genre:jazz bits>=24", style = Type.body(16), color = t.faint, maxLines = 1)
                    BasicTextField(
                        value = q, onValueChange = { model.query = it }, singleLine = true,
                        textStyle = Type.body(16).copy(color = t.text), cursorBrush = SolidColor(t.accentFill),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); model.addRecentSearch(q) }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (q.isNotEmpty()) IconBtn(Ic.close, "Clear search", { model.query = "" }, tint = t.sub, iconSize = 18.dp)
            }
            Text("QUERY SYNTAX · TAP TO ADD", style = Type.label(10), color = t.faint, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 6.dp))
            HScroll(Modifier.padding(bottom = 16.dp)) {
                SYNTAX_CHIPS.forEach { c ->
                    Box(
                        Modifier.height(40.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, t.line2, RoundedCornerShape(10.dp))
                            .clickable { model.query = (if (q.isBlank()) "" else q.trim() + " ") + c }.padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(c, style = Type.mono(12, FontWeight.Medium), color = t.sub) }
                }
            }
        } }
        if (q.isBlank()) {
            if (model.user.recent.isNotEmpty()) {
                item { Text("Recent searches", style = Type.body(13, FontWeight.SemiBold), color = t.sub, modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 4.dp)) }
                items(model.user.recent) { r ->
                    Row(
                        Modifier.fillMaxWidth().height(52.dp).clickable { model.query = r }.padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Ic.clock, null, tint = t.faint, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(14.dp))
                        Text(r, style = Type.body(15), color = t.text)
                    }
                }
            }
            item { Column {
                Text("Browse genres", style = Type.body(13, FontWeight.SemiBold), color = t.sub, modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 10.dp))
                FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    idx.genres.forEach { (g, _) ->
                        Box(
                            Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(t.surface).clickable { model.open(Detail.GenreD(g)) }.padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(g, style = Type.body(14, FontWeight.Medium), color = t.text) }
                    }
                }
            } }
        } else {
            item { Column {
                Text(
                    "${Fmt.plural(results.size, "track")} · ${Fmt.plural(artists.size, "artist")} · ${Fmt.plural(albums.size, "album")}",
                    style = Type.mono(12), color = t.sub, modifier = Modifier.padding(start = 20.dp, bottom = 10.dp),
                )
                if (artists.isNotEmpty()) {
                    HScroll(Modifier.padding(bottom = 16.dp), gap = 14.dp) {
                        artists.take(12).forEach { a ->
                            val sample = artistSamples.getValue(a)
                            Column(Modifier.width(76.dp).clickable { model.open(Detail.ArtistD(a)) }, horizontalAlignment = Alignment.CenterHorizontally) {
                                Art(sample, Modifier.size(68.dp), CircleShape)
                                Spacer(Modifier.height(8.dp))
                                Text(a, style = Type.body(12), color = t.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                if (albums.isNotEmpty()) {
                    HScroll(Modifier.padding(bottom = 18.dp), gap = 12.dp) {
                        albums.take(12).forEach { aid ->
                            val al = idx.albumById[aid] ?: return@forEach
                            Column(Modifier.width(120.dp).clickable { model.open(Detail.AlbumD(aid)) }) {
                                Art(model.track(al.trackIds.first()), Modifier.size(120.dp), RoundedCornerShape(14.dp))
                                Spacer(Modifier.height(6.dp))
                                Text(al.title, style = Type.body(13, FontWeight.SemiBold), color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            } }
            if (results.isNotEmpty()) item {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Tracks", style = Type.body(13, FontWeight.SemiBold), color = t.sub, modifier = Modifier.weight(1f))
                    IconBtn(Ic.select, "Select tracks", { focus.clearFocus(); model.beginSelecting(ids) }, tint = t.sub, iconSize = 20.dp)
                }
            }
            items(results.take(SEARCH_ROWS), key = { it.id }) { tr ->
                SelectableTrackRow(model, tr, ids, "Search: ${q.trim()}", false, beforePlay = { focus.clearFocus() })
            }
            if (results.size > SEARCH_ROWS) item {
                // Playing or selecting from here still uses every match, not just the rows shown.
                Text(
                    "Showing ${SEARCH_ROWS} of ${Fmt.plural(results.size, "track")}. Narrow the search to see the rest; play or select uses all of them.",
                    style = Type.body(13), color = t.sub, modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp).testTag("search-more"),
                )
            }
            if (results.isEmpty()) item { EmptyNote("Nothing matches. Try a field like artist: or a comparison like plays>10.") }
        }
    }
}

/** Search shows at most this many rows (the rest are still played or selected with the rest). */
const val SEARCH_ROWS = 300

/** Libraries at least this big search in the background, so typing never waits for it. Smaller ones search instantly. */
const val ASYNC_SEARCH_FROM = 3000

/**
 * Search results for [q]. Results are only recomputed when something they depend on changes: play counts and
 * ratings only matter to queries like `plays>10`, the format scan only to `bits>=24`.
 */
@Composable
fun rememberSearch(model: AppModel, q: String): List<Track> {
    val idx = model.index
    val parsed = remember(q) { Query.parse(q) }
    val userKey = if (parsed.usesUserData) model.user else null
    val techKey = if (parsed.usesTech) model.tech else null
    if (idx.tracks.size < ASYNC_SEARCH_FROM) {
        return remember(parsed, idx, userKey, techKey) { model.search(parsed, idx) }
    }
    // Keeps showing the previous results while the next ones are worked out.
    val state = remember { mutableStateOf<List<Track>>(emptyList()) }
    LaunchedEffect(parsed, idx, userKey, techKey) {
        if (parsed.isEmpty) { state.value = emptyList(); return@LaunchedEffect }
        state.value = withContext(Dispatchers.Default) { model.search(parsed, idx) }
    }
    return state.value
}
