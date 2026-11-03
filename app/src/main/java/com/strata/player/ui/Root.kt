package com.strata.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.using
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.strata.player.AppModel
import com.strata.player.Tab
import com.strata.player.data.ThemeMode
import kotlinx.coroutines.delay

@Composable
fun StrataRoot(model: AppModel, onRequestPermission: () -> Unit) {
    val s = model.settings
    val dark = when (s.theme) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val cur = model.track(model.engine.currentId)
    val pair = if (s.colorFromArt) model.accentFor(cur) else null
    // The accent switches instantly: animating it would recompose every screen on every frame.
    val light = pair?.first ?: Color(0xFF7FD1FF)
    val deep = pair?.second ?: Color(0xFF0A6593)
    val t = remember(dark, light, deep) { tokens(dark, light, deep) }

    StrataTheme(t) {
        BackHandler(enabled = model.sheet != null || model.pickerFor != null || model.selecting || model.propsId != null || model.settingsOpen || model.nowOpen || model.details.isNotEmpty() || model.tab != Tab.LIBRARY) {
            model.back()
        }
        Box(Modifier.fillMaxSize().background(t.bg)) {
            if (!model.hasPermission) {
                Onboarding(onRequestPermission)
            } else {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth().statusBarsPadding()) {
                        Crossfade(model.tab, animationSpec = tween(180), label = "tab") { tab ->
                            when (tab) {
                                Tab.LIBRARY -> LibraryScreen(model)
                                Tab.PLAYLISTS -> PlaylistsScreen(model)
                                Tab.SEARCH -> SearchScreen(model)
                                Tab.SOUND -> SoundScreen(model)
                            }
                        }
                        DetailOverlay(model)
                    }
                    // The selection bar slides up over the mini player and tab bar, and back down when done.
                    AnimatedContent(
                        model.selecting,
                        transitionSpec = {
                            (slideInVertically(tween(240)) { it / 2 } + fadeIn(tween(200))) togetherWith fadeOut(tween(120)) using
                                SizeTransform(clip = false)
                        },
                        label = "bottom",
                    ) { selecting ->
                        if (selecting) {
                            SelectionBar(model)
                        } else {
                            Column {
                                AnimatedVisibility(cur != null, enter = expandVertically(tween(260)) + fadeIn(tween(260)), exit = shrinkVertically(tween(200)) + fadeOut(tween(160))) {
                                    MiniPlayer(model)
                                }
                                BottomNav(model)
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = model.nowOpen && cur != null,
                enter = slideInVertically(tween(320)) { it } + fadeIn(),
                exit = slideOutVertically(tween(260)) { it } + fadeOut(),
            ) {
                NowPlayingScreen(model)
            }
            AnimatedVisibility(model.settingsOpen, enter = slideInHorizontally { it } , exit = slideOutHorizontally { it }) {
                SettingsScreen(model)
            }
            val pid = model.propsId
            AnimatedVisibility(pid != null, enter = slideInHorizontally { it }, exit = slideOutHorizontally { it }) {
                var lastId by remember { mutableLongStateOf(-1L) }
                if (pid != null) lastId = pid
                if (lastId >= 0) PropertiesScreen(model, lastId)
            }
            val picker = model.pickerFor
            AnimatedVisibility(picker != null, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                var lastPid by remember { mutableStateOf("") }
                if (picker != null) lastPid = picker
                if (lastPid.isNotEmpty()) TrackPicker(model, lastPid)
            }
            SheetHost(model)
            ToastHost(model)
        }
    }
}

/** Album / artist / playlist pages slide over the current tab. Kept outside any Column/Row scope on purpose. */
@Composable
private fun DetailOverlay(model: AppModel) {
    val top = model.details.lastOrNull()
    var last by remember { mutableStateOf<com.strata.player.Detail?>(null) }
    if (top != null) last = top
    AnimatedVisibility(
        visible = top != null,
        enter = slideInHorizontally { it / 3 } + fadeIn(),
        exit = slideOutHorizontally { it / 3 } + fadeOut(),
    ) {
        val d = last
        if (d != null) {
            androidx.compose.runtime.key(d) { DetailScreen(model, d) }
        }
    }
}

@Composable
private fun Onboarding(onRequest: () -> Unit) {
    val t = LocalTokens.current
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Strata", style = Type.display(48), color = t.text)
        Spacer(Modifier.height(12.dp))
        Text(
            "A fast, local music player. Strata reads the music already on your phone. Nothing is uploaded anywhere.",
            style = Type.body(16), color = t.sub,
        )
        Spacer(Modifier.height(28.dp))
        BigButton("Allow access to music", null, true, onRequest, Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Text("Notifications are used only for the playback controls.", style = Type.body(13), color = t.faint)
    }
}

@Composable
private fun MiniPlayer(model: AppModel) {
    val t = LocalTokens.current
    val e = model.engine
    // Kept while the player hides, so the exit animation still has something to show.
    var last by remember { mutableStateOf(model.track(e.currentId)) }
    model.track(e.currentId)?.let { last = it }
    val cur = last ?: return
    val view = androidx.compose.ui.platform.LocalView.current
    var pos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(e.currentId, e.isPlaying, model.uiVisible) {
        while (true) {
            pos = e.player.currentPosition
            if (!e.isPlaying || !model.uiVisible) break
            delay(500)
        }
    }
    val prog = if (cur.durationMs > 0) (pos.toFloat() / cur.durationMs).coerceIn(0f, 1f) else 0f
    // Swipe left/right to skip (the next track slides in), swipe up to open the player.
    val swipe = rememberTrackSwipe()
    Box(Modifier.fillMaxWidth().testTag("miniplayer").background(t.bg).padding(start = 10.dp, end = 10.dp, top = 6.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .trackSwipe(swipe, e, skip = 56.dp, up = 40.dp, gap = 16.dp, onUp = { model.nowOpen = true }, onSkip = { haptic(view) })
                .graphicsLayer { translationY = (swipe.dy.coerceAtMost(0f) * 0.2f).coerceAtLeast(-24.dp.toPx()) }
                .height(62.dp)
                .shadow(10.dp, RoundedCornerShape(18.dp), clip = false, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f))
                .clip(RoundedCornerShape(18.dp))
                .background(t.surface),
        ) {
            Row(Modifier.fillMaxSize().padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TrackStrip(
                    swipe, model, cur, gap = 16.dp, clip = true,
                    modifier = Modifier.weight(1f).fillMaxHeight().clickable(onClickLabel = "Open now playing") { model.nowOpen = true },
                ) { tr ->
                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        Art(tr, Modifier.size(44.dp), RoundedCornerShape(10.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(tr.title, style = Type.body(14, FontWeight.SemiBold), color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(tr.artist, style = Type.body(12), color = t.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                val playDesc = if (e.playIntent) "Pause" else "Play"
                Box(
                    Modifier.pressScale().size(44.dp).clip(CircleShape)
                        .clickable(role = Role.Button, onClickLabel = playDesc, onClick = e::togglePlay)
                        .semantics { contentDescription = playDesc },
                    contentAlignment = Alignment.Center,
                ) { PlayPauseIcon(e.playIntent, t.text, 24.dp) }
                IconBtn(Ic.next, "Next track", e::next)
            }
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth(prog).height(3.dp).background(t.accentFill),
            )
        }
    }
}

@Composable
private fun BottomNav(model: AppModel) {
    val t = LocalTokens.current
    Row(
        Modifier.fillMaxWidth().background(t.bg).navigationBarsPadding().height(70.dp).padding(horizontal = 8.dp),
    ) {
        NavItem(Ic.library, "Library", model.tab == Tab.LIBRARY) { model.selectTab(Tab.LIBRARY) }
        NavItem(Ic.playlists, "Playlists", model.tab == Tab.PLAYLISTS) { model.selectTab(Tab.PLAYLISTS) }
        NavItem(Ic.search, "Search", model.tab == Tab.SEARCH) { model.selectTab(Tab.SEARCH) }
        NavItem(Ic.sound, "Sound", model.tab == Tab.SOUND) { model.selectTab(Tab.SOUND) }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavItem(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    val t = LocalTokens.current
    val bg by animateColorAsState(if (on) t.soft else Color.Transparent, label = "nav")
    // The pill grows out from the icon when a tab is picked.
    val pillW by animateDpAsState(if (on) 56.dp else 32.dp, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow), label = "navPill")
    Column(
        Modifier.weight(1f).fillMaxSize().testTag("nav-" + label.lowercase()).clickable(role = Role.Tab, onClick = onClick).pressScale(0.94f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(width = pillW, height = 30.dp).clip(RoundedCornerShape(15.dp)).background(bg), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (on) t.text else t.sub, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = Type.body(11, FontWeight.Medium), color = if (on) t.text else t.sub)
    }
}

@Composable
private fun ToastHost(model: AppModel) {
    val t = LocalTokens.current
    val msg = model.toast
    val action = model.toastAction
    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 150.dp, start = 24.dp, end = 24.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(msg != null, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut()) {
            var last by remember { mutableStateOf("") }
            var lastAction by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
            if (msg != null) { last = msg; lastAction = action }
            Row(
                Modifier.testTag("toast").clip(RoundedCornerShape(16.dp)).background(t.text).padding(start = 18.dp, end = if (lastAction != null) 6.dp else 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    last, style = Type.body(14, FontWeight.Medium), color = t.bg, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 12.dp).weight(1f, fill = false),
                )
                lastAction?.let { (label, run) ->
                    Spacer(Modifier.width(8.dp))
                    Text(
                        label, style = Type.body(14, FontWeight.Bold), color = if (t.dark) Color(0xFF0A6593) else t.accentRaw,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { run() }.padding(horizontal = 12.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}
