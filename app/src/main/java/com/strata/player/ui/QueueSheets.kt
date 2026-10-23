package com.strata.player.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.strata.player.AppModel
import com.strata.player.Sheet
import com.strata.player.audio.UpItem
import com.strata.player.data.Order
import com.strata.player.isUnder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private val ROW_H = 58.dp

// ====================================================================== queue

/**
 * The play queue: what already played (dimmed), the current track, the playback queue and the rest of the list.
 * Drag the handle to reorder, swipe right to play next, swipe left to remove (with undo).
 */
@Composable
fun QueueSheet(model: AppModel) {
    val t = LocalTokens.current
    val e = model.engine
    e.timelineVersion // recompose on queue changes
    val cur = model.track(e.currentId)
    val history = e.history(25)
    val up = e.upNext(80)
    val queued = up.filter { it.queued }
    val rest = up.filter { !it.queued }
    val active = model.queues.firstOrNull { it.id == model.activeQueueId }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var currentY by remember { mutableIntStateOf(-1) }
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(currentY) {
        if (!opened && currentY >= 0) { scroll.scrollTo(currentY); opened = true }
    }

    Column(Modifier.testTag("queue")) {
        SheetTitle("Up next", active?.let { "Queue · ${it.name}" } ?: e.ctxName) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBtn(Ic.queues, "Saved queues", { model.sheet = Sheet.Queues }, tint = t.sub, iconSize = 20.dp)
                IconBtn(Ic.listAdd, "Save queue as playlist", {
                    model.sheet = Sheet.NewPlaylist(e.timelineIds().distinct(), false)
                }, tint = t.sub, iconSize = 20.dp)
                if (queued.isNotEmpty()) Text(
                    "Clear", style = Type.body(13, FontWeight.SemiBold), color = t.accent,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { e.clearQueue() }.padding(10.dp),
                )
            }
        }
        Box(Modifier.weight(1f, fill = false)) {
            Column(Modifier.verticalScroll(scroll).padding(bottom = 18.dp)) {
                if (history.isNotEmpty()) {
                    SectionLabel("Played", Modifier.padding(top = 4.dp))
                    history.forEach { u ->
                        key(u.index, u.trackId) {
                            QueueRow(model, u, dim = true, number = null, onRight = null, handle = null)
                        }
                    }
                }
                if (cur != null) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth().testTag("queue-current")
                            .onGloballyPositioned { currentY = it.positionInParent().y.roundToInt() }
                            .clip(RoundedCornerShape(14.dp)).background(t.soft).padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Art(cur, Modifier.size(44.dp), RoundedCornerShape(8.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("NOW PLAYING", style = Type.label(10), color = t.accent)
                            Text(cur.title, style = Type.body(14, FontWeight.SemiBold), color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (queued.isNotEmpty()) {
                    SectionLabel("Playback queue · plays first", Modifier.padding(top = 8.dp))
                    ReorderableRows(model, queued, numbered = true)
                }
                SectionLabel("Next from · ${e.ctxName}", Modifier.padding(top = 8.dp))
                if (rest.isEmpty()) {
                    Text(
                        when (model.settings.order) {
                            Order.REPEAT_TRACK -> "Repeat track is on. This track loops."
                            Order.DEFAULT -> "End of the list. Playback stops after this track."
                            else -> "Starts over from the top."
                        },
                        style = Type.body(13), color = t.sub, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                ReorderableRows(model, rest, numbered = false)
                Text(
                    "Drag ≡ to reorder · swipe right to play next · swipe left to remove",
                    style = Type.body(12), color = t.faint, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
            // "Jump to current" appears once the current track has scrolled out of view.
            val away = currentY >= 0 && abs(scroll.value - currentY) > 240
            if (away) {
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp).clip(RoundedCornerShape(20.dp)).background(t.accentFill)
                        .clickable(role = Role.Button) { scope.launch { scroll.animateScrollTo(currentY) } }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (scroll.value > currentY) Ic.up else Ic.down, null, tint = t.onAccent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Jump to current", style = Type.body(13, FontWeight.SemiBold), color = t.onAccent)
                }
            }
        }
    }
}

/** Rows that can be reordered among themselves by dragging their handle. */
@Composable
private fun ReorderableRows(model: AppModel, items: List<UpItem>, numbered: Boolean) {
    val e = model.engine
    val rowH = with(LocalDensity.current) { ROW_H.toPx() }
    var dragging by remember(items) { mutableIntStateOf(-1) }
    var offset by remember(items) { mutableFloatStateOf(0f) }
    val target = if (dragging < 0) -1 else (dragging + (offset / rowH).roundToInt()).coerceIn(0, items.lastIndex)
    items.forEachIndexed { i, u ->
        val shift = when {
            dragging < 0 || i == dragging -> 0f
            i in (dragging + 1)..target -> -rowH
            i in target until dragging -> rowH
            else -> 0f
        }
        val y = if (i == dragging) offset else shift
        key(u.index, u.trackId) {
            QueueRow(
                model, u, dim = false, number = if (numbered) "${i + 1}" else null,
                onRight = { e.moveToNext(u.index) },
                modifier = Modifier.zIndex(if (i == dragging) 1f else 0f).graphicsLayer { translationY = y },
                handle = Modifier.pointerInput(items, i) {
                    detectDragGestures(
                        onDragStart = { dragging = i; offset = 0f },
                        onDragEnd = {
                            val to = (i + (offset / rowH).roundToInt()).coerceIn(0, items.lastIndex)
                            dragging = -1; offset = 0f
                            if (to != i) e.move(items[i].index, items[to].index)
                        },
                        onDragCancel = { dragging = -1; offset = 0f },
                    ) { change, d -> change.consume(); offset += d.y }
                },
            )
        }
    }
}

/** One queue row: tap to play it, swipe right = play next, swipe left = remove (undo in the toast). */
@Composable
private fun QueueRow(
    model: AppModel,
    u: UpItem,
    dim: Boolean,
    number: String?,
    onRight: (() -> Unit)?,
    handle: Modifier?,
    modifier: Modifier = Modifier,
) {
    val t = LocalTokens.current
    val e = model.engine
    val tr = model.track(u.trackId) ?: return
    val ox = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val thr = with(LocalDensity.current) { 96.dp.toPx() }
    Box(modifier.fillMaxWidth().height(ROW_H)) {
        if (ox.value > 1f) {
            Row(
                Modifier.fillMaxSize().background(t.soft).padding(start = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Ic.playNext, null, tint = t.accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("Play next", style = Type.body(13, FontWeight.SemiBold), color = t.accent)
            }
        } else if (ox.value < -1f) {
            Row(
                Modifier.fillMaxSize().background(Color(0x33E5533F)).padding(end = 20.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End,
            ) {
                Text("Remove", style = Type.body(13, FontWeight.SemiBold), color = Color(0xFFE5533F))
                Spacer(Modifier.width(10.dp))
                Icon(Ic.trash, null, tint = Color(0xFFE5533F), modifier = Modifier.size(20.dp))
            }
        }
        Row(
            Modifier
                .fillMaxSize()
                .offset { IntOffset(ox.value.roundToInt(), 0) }
                .background(t.sheet)
                .pointerInput(u.index, u.trackId) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val v = ox.value
                            scope.launch {
                                when {
                                    v > thr && onRight != null -> { onRight(); model.flash("Plays next: ${tr.title}") }
                                    v < -thr -> model.removeFromQueueWithUndo(u.index)
                                }
                                ox.animateTo(0f)
                            }
                        },
                        onDragCancel = { scope.launch { ox.animateTo(0f) } },
                    ) { change, d ->
                        change.consume()
                        val min = -size.width.toFloat()
                        val max = if (onRight != null) size.width.toFloat() else 0f
                        scope.launch { ox.snapTo((ox.value + d).coerceIn(min, max)) }
                    }
                }
                .clickable { e.jumpTo(u.index) }
                // The swipes, for TalkBack users.
                .semantics {
                    customActions = listOfNotNull(
                        onRight?.let { r -> CustomAccessibilityAction("Play next") { r(); true } },
                        CustomAccessibilityAction("Remove from queue") { model.removeFromQueueWithUndo(u.index); true },
                    )
                }
                .padding(start = 20.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (number != null) Text(number, style = Type.mono(12), color = t.accent, modifier = Modifier.width(22.dp))
            Art(tr, Modifier.size(40.dp), RoundedCornerShape(8.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(tr.title, style = Type.body(14, FontWeight.Medium), color = if (dim) t.sub else t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(tr.artist, style = Type.body(12), color = if (dim) t.faint else t.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(Fmt.time(tr.durationMs), style = Type.mono(11), color = t.faint)
            if (handle != null) {
                Box(
                    handle.size(44.dp).semantics { contentDescription = "Reorder ${tr.title}" },
                    contentAlignment = Alignment.Center,
                ) { Icon(Ic.drag, null, tint = t.faint, modifier = Modifier.size(20.dp)) }
            } else {
                Spacer(Modifier.width(12.dp))
            }
        }
    }
}

// ====================================================================== sleep timer

@Composable
fun SleepSheet(model: AppModel) {
    val t = LocalTokens.current
    val e = model.engine
    val at = e.sleepAtMs
    val left by produceState(e.sleepRemainingMs() ?: 0L, at) {
        while (at != null) { value = e.sleepRemainingMs() ?: 0L; delay(1000) }
    }
    val sub = when {
        at != null -> "Pausing in ${Fmt.time(left)} · fades out over the last 30 s"
        e.sleepTracksLeft != null -> "Stops after ${Fmt.plural(e.sleepTracksLeft ?: 0, "song")}, counting this one"
        e.sleepEndOfTrack -> "Stops at the end of this track"
        else -> "Pause playback after a while"
    }
    SheetTitle("Sleep timer", sub)
    if (at != null) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(t.soft)
                .clickable(role = Role.Button) { e.addSleepMinutes(15); model.flash("15 more minutes") }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Ic.plus, null, tint = t.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text("Add 15 minutes", style = Type.body(15, FontWeight.SemiBold), color = t.accent)
        }
    }
    val opts = listOf<Pair<Int?, String>>(null to "Off", 15 to "15 minutes", 30 to "30 minutes", 45 to "45 minutes", 60 to "1 hour", 90 to "1 hour 30 minutes")
    opts.forEachIndexed { i, (min, label) ->
        val off = at == null && !e.sleepEndOfTrack && e.sleepTracksLeft == null
        val selected = if (min == null) off else at != null && e.sleepChoice == min
        RadioRow(label, null, selected, {
            e.setSleepMinutes(min)
            model.sheet = null
            model.flash(if (min == null) "Sleep timer off" else "Pausing in $label")
        }, topLine = i > 0)
    }
    RadioRow("End of current track", null, e.sleepEndOfTrack && e.sleepTracksLeft == null, {
        e.setSleepEndOfTrack(); model.sheet = null; model.flash("Stops after this track")
    }, topLine = true)
    listOf(2, 3, 5).forEach { n ->
        RadioRow("After $n songs", null, e.sleepTracksLeft == n, {
            e.setSleepAfterTracks(n); model.sheet = null; model.flash("Stops after $n songs")
        }, topLine = true)
    }
}

// ====================================================================== saved queues

@Composable
fun QueuesSheet(model: AppModel) {
    val t = LocalTokens.current
    val e = model.engine
    SheetTitle("Queues", "Each one remembers its track and position") {
        Text(
            "New", style = Type.body(14, FontWeight.SemiBold), color = t.accent,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { model.sheet = Sheet.NewQueue(emptyList()) }.padding(12.dp),
        )
    }
    if (model.activeQueueId == null && e.player.mediaItemCount > 0) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(t.soft).padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("PLAYING NOW", style = Type.label(10), color = t.accent)
                Text(e.ctxName, style = Type.body(15, FontWeight.SemiBold), color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Not saved: starting something else replaces it", style = Type.body(12), color = t.sub)
            }
            Text(
                "Save", style = Type.body(14, FontWeight.SemiBold), color = t.accent,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) {
                    model.saveCurrentAsQueue(e.ctxName)
                }.padding(12.dp),
            )
        }
    }
    if (model.queues.isEmpty()) {
        EmptyNote("Make a queue for later (say, tonight's), add songs to it from any track menu or selection, and switch to it whenever you like. Each queue keeps its own place.")
    }
    model.queues.sortedByDescending { it.updatedAt }.forEach { q ->
        val isActive = q.id == model.activeQueueId
        val curTrack = q.currentId?.let { model.track(it) }
        Divider()
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { if (!isActive) { model.switchQueue(q.id); model.sheet = null } }
                .padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(q.name, style = Type.body(15, FontWeight.SemiBold), color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (isActive) {
                        Spacer(Modifier.width(8.dp))
                        Text("PLAYING", style = Type.label(9), color = t.accent, modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(t.soft).padding(horizontal = 5.dp, vertical = 2.dp))
                    } else if (q.auto) {
                        Spacer(Modifier.width(8.dp))
                        Text("AUTO", style = Type.label(9), color = t.sub, modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(t.surface2).padding(horizontal = 5.dp, vertical = 2.dp))
                    }
                }
                val where = curTrack?.let { " · ${it.title}" + if (q.positionMs > 0) " at ${Fmt.time(q.positionMs)}" else "" } ?: ""
                Text(Fmt.plural(q.ids.size, "track") + where, style = Type.body(12), color = t.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconBtn(Ic.close, "Delete queue ${q.name}", { model.deleteQueue(q.id) }, tint = t.sub, iconSize = 18.dp)
        }
    }
}

@Composable
fun AddToQueueSheet(model: AppModel, ids: List<Long>) {
    val t = LocalTokens.current
    if (ids.isEmpty()) return
    SheetTitle("Add to queue", if (ids.size == 1) model.track(ids[0])?.title else Fmt.plural(ids.size, "track"))
    fun done() { model.sheet = null; model.clearSelection() }
    ActionRow("Playing queue", "after queued tracks") { model.addToSavedQueue(null, ids); done() }
    model.queues.filter { it.id != model.activeQueueId }.sortedByDescending { it.updatedAt }.forEach { q ->
        ActionRow(q.name, Fmt.plural(q.ids.size, "track")) { model.addToSavedQueue(q.id, ids); done() }
    }
    Divider()
    Row(
        Modifier.fillMaxWidth().height(56.dp).clickable { model.sheet = Sheet.NewQueue(ids) }.padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Ic.plus, null, tint = t.accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text("New queue…", style = Type.body(15, FontWeight.SemiBold), color = t.accent)
    }
}

@Composable
fun NewQueueSheet(model: AppModel, ids: List<Long>) {
    var name by remember { mutableStateOf("") }
    SheetTitle("New queue", if (ids.isEmpty()) "Add songs to it later from any track menu" else "${Fmt.plural(ids.size, "track")} to start with")
    Field("Name", name, { name = it }, placeholder = "Tonight")
    BigButton("Create queue", null, true, {
        model.createQueue(name, ids)
        model.sheet = null
        model.clearSelection()
    }, Modifier.padding(16.dp).fillMaxWidth())
}

// ====================================================================== folders

/** A folder tree built from the scanned folders; switch off any branch to leave it out of the library. */
@Composable
fun FoldersSheet(model: AppModel) {
    val t = LocalTokens.current
    val folders = model.scannedFolders
    val root = remember(folders) { commonRoot(folders.keys) }
    var path by remember(root) { mutableStateOf(root) }
    val children = remember(folders, path) { childDirs(folders, path) }
    val hidden = model.settings.excludedFolders
    SheetTitle("Folders", if (hidden.isEmpty()) "Everything the media scanner found is in your library" else "${Fmt.plural(hidden.size, "folder")} left out")

    val junk = model.junkFolders().filter { !model.isFolderExcluded(it) }
    if (junk.isNotEmpty()) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(t.soft)
                .clickable(role = Role.Button) { model.hideJunkFolders() }.padding(14.dp),
        ) {
            Text("Hide voice notes & recordings", style = Type.body(15, FontWeight.SemiBold), color = t.accent)
            Text(
                junk.joinToString(" · ") { it.substringAfterLast('/') }, style = Type.body(12), color = t.sub,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }

    Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 16.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (path != root) IconBtn(Ic.back, "Up one folder", { path = path.substringBeforeLast('/').let { if (it.length < root.length) root else it } })
        else Spacer(Modifier.width(16.dp))
        Text(
            if (path == root) "All folders" else path.removePrefix(root).trimStart('/'),
            style = Type.mono(12), color = t.sub, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
    }
    folders[path]?.let { n ->
        Text(
            "${Fmt.plural(n, "file")} directly in this folder (switched with it from the level above)",
            style = Type.body(12), color = t.faint, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
    }
    if (children.isEmpty()) EmptyNote("No folders with music here.")
    children.forEach { c ->
        val byParent = hidden.any { it != c.path && isUnder(c.path, it) }
        val included = !model.isFolderExcluded(c.path)
        Divider()
        Row(
            Modifier.fillMaxWidth().heightIn(min = 60.dp)
                .clickable(enabled = c.hasChildren) { path = c.path }
                .padding(start = 20.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Ic.folder, null, tint = if (included) t.accent else t.faint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(c.name, style = Type.body(15, FontWeight.Medium), color = if (included) t.text else t.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    Fmt.plural(c.count, "file") + (if (c.hasChildren) " · tap to open" else "") + (if (byParent) " · hidden with its parent" else ""),
                    style = Type.body(12), color = t.sub,
                )
            }
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(24.dp))
                    .clickable(enabled = !byParent, role = Role.Checkbox) { model.setFolderIncluded(c.path, !included) }
                    .semantics { contentDescription = (if (included) "Leave out " else "Include ") + c.name },
                contentAlignment = Alignment.Center,
            ) { CheckDot(included, enabled = !byParent) }
        }
    }
}

data class FolderNode(val path: String, val name: String, val count: Int, val hasChildren: Boolean)

/** The deepest folder that contains every scanned folder. */
fun commonRoot(paths: Collection<String>): String {
    if (paths.isEmpty()) return "/"
    val split = paths.map { it.trimEnd('/').split('/') }
    val first = split.first()
    var n = first.size
    for (p in split) {
        var i = 0
        while (i < n && i < p.size && p[i] == first[i]) i++
        n = i
    }
    // The root must sit above every scanned folder, so each one can be switched off.
    n = minOf(n, split.minOf { it.size } - 1).coerceAtLeast(1)
    return first.take(n).joinToString("/").ifEmpty { "/" }
}

/** Direct sub-folders of [path] that contain music somewhere below them. */
fun childDirs(folders: Map<String, Int>, path: String): List<FolderNode> {
    val base = path.trimEnd('/')
    val out = LinkedHashMap<String, Pair<Int, Boolean>>()
    for ((f, count) in folders) {
        if (!f.startsWith("$base/")) continue
        val rest = f.removePrefix("$base/")
        val seg = rest.substringBefore('/')
        val child = "$base/$seg"
        val (c, deeper) = out[child] ?: (0 to false)
        out[child] = (c + count) to (deeper || rest.contains('/'))
    }
    return out.map { (p, v) -> FolderNode(p, p.substringAfterLast('/'), v.first, v.second) }
        .sortedWith(com.strata.player.data.Natural.by { it.name })
}
