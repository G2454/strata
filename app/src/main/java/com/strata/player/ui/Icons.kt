package com.strata.player.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Strata's icon set, drawn from the same stroke geometry as the design prototype. */
object Ic {
    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r},${cy}a$r,$r 0 1,0 ${2 * r},0a$r,$r 0 1,0 ${-2 * r},0"

    private fun stroke(name: String, vararg d: String, w: Float = 1.8f): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            d.forEach {
                addPath(
                    pathData = addPathNodes(it),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = w,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    private fun filled(name: String, vararg d: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            d.forEach { addPath(pathData = addPathNodes(it), fill = SolidColor(Color.Black)) }
        }.build()

    private const val HEART = "M12 20s-7.5-4.6-7.5-10.2A4.3 4.3 0 0 1 12 7.2a4.3 4.3 0 0 1 7.5 2.6C19.5 15.4 12 20 12 20z"
    private const val STAR = "M12 3.8l2.5 5.2 5.7.8-4.1 4 1 5.6L12 16.7l-5.1 2.7 1-5.6-4.1-4 5.7-.8z"
    private const val SHUFFLE = "M3 7h3.5c2 0 3.2 1 4.3 2.7l2.4 4.6c1.1 1.7 2.3 2.7 4.3 2.7H21M17 13.5l4 3.5-4 3.5M3 17h3.5c1.2 0 2.1-.4 2.9-1.1M13.6 8.1c.8-.7 1.7-1.1 2.9-1.1H21M17 3.5L21 7l-4 3.5"

    val search = stroke("search", circle(11f, 11f, 7f), "M20 20l-3.5-3.5")
    val settings = stroke("settings", "M4 7h10M18 7h2M4 17h4M12 17h8", circle(16f, 7f, 2.2f), circle(10f, 17f, 2.2f))
    val more = filled("more", circle(12f, 5f, 1.7f), circle(12f, 12f, 1.7f), circle(12f, 19f, 1.7f))
    val play = filled("play", "M8 5.5v13l11-6.5z")
    val pause = filled("pause", "M7.7 5h1.6a1.2 1.2 0 0 1 1.2 1.2v11.6a1.2 1.2 0 0 1-1.2 1.2H7.7a1.2 1.2 0 0 1-1.2-1.2V6.2A1.2 1.2 0 0 1 7.7 5z", "M14.7 5h1.6a1.2 1.2 0 0 1 1.2 1.2v11.6a1.2 1.2 0 0 1-1.2 1.2h-1.6a1.2 1.2 0 0 1-1.2-1.2V6.2A1.2 1.2 0 0 1 14.7 5z")
    val next = filled("next", "M6 5.5v13l9-6.5z", "M16 5h2.4v14H16z")
    val prev = filled("prev", "M18 5.5v13l-9-6.5z", "M5.6 5h2.4v14H5.6z")
    val shuffle = stroke("shuffle", SHUFFLE, w = 1.9f)
    val repeat = stroke("repeat", "M4 11V9a3 3 0 0 1 3-3h13M17 3l3 3-3 3M20 13v2a3 3 0 0 1-3 3H4M7 21l-3-3 3-3", w = 1.9f)
    val heart = stroke("heart", HEART)
    val heartFill = filled("heartFill", HEART)
    val chevronDown = stroke("chevronDown", "M6 9l6 6 6-6", w = 2f)
    val back = stroke("back", "M15 5l-7 7 7 7", w = 2f)
    val chevronRight = stroke("chevronRight", "M9 5l7 7-7 7")
    val queue = stroke("queue", "M4 6h12M4 11h12M4 16h7M16 14v6l5-3z")
    val moon = stroke("moon", "M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z")
    val gauge = stroke("gauge", "M4.5 17a8.5 8.5 0 1 1 15 0M12 13l4-4")
    val headphones = stroke("headphones", "M4 15v-3a8 8 0 0 1 16 0v3", "M4.5 14h1A1.5 1.5 0 0 1 7 15.5v3A1.5 1.5 0 0 1 5.5 20h-1A1.5 1.5 0 0 1 3 18.5v-3A1.5 1.5 0 0 1 4.5 14z", "M18.5 14h1a1.5 1.5 0 0 1 1.5 1.5v3a1.5 1.5 0 0 1-1.5 1.5h-1a1.5 1.5 0 0 1-1.5-1.5v-3a1.5 1.5 0 0 1 1.5-1.5z")
    val sound = stroke("sound", "M3 12h1.5M7 8.5v7M11 5v14M15 8v8M19 10v4")
    val library = stroke("library", "M5 4v16M9.5 4v16M14 4.5l5 15")
    val playlists = stroke("playlists", "M4 6h10M4 11h10M4 16h6M18.5 17.5V6l3 1.5", circle(16.2f, 17.5f, 2.3f))
    val plus = stroke("plus", "M12 5v14M5 12h14", w = 2f)
    val check = stroke("check", "M5 12.5l4.5 4.5L19 7.5", w = 2.2f)
    val up = stroke("up", "M12 19V5M6 11l6-6 6 6", w = 2f)
    val down = stroke("down", "M12 5v14M6 13l6 6 6-6", w = 2f)
    val folder = stroke("folder", "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z")
    val close = stroke("close", "M6 6l12 12M18 6L6 18", w = 2f)
    val star = stroke("star", STAR, w = 1.6f)
    val starFill = filled("starFill", STAR)
    val columns = stroke("columns", "M4 6h16M4 10h16M4 14h16M4 18h16")
    val sort = stroke("sort", "M7 4v16M3 16l4 4 4-4M14 6h7M14 11h5M14 16h3")
    val clock = stroke("clock", circle(12f, 12f, 8.5f), "M12 7.5V12l3 2")
    val bars = stroke("bars", "M5 20v-6M10 20V8M15 20v-9M20 20V4")
    val added = stroke("added", "M12 4v10M7 9l5 5 5-5M5 19h14")
    val eq = stroke("eq", "M5 4v6M5 14v6M12 4v2M12 10v10M19 4v9M19 17v3M3 12h4M10 8h4M17 15h4")
    val trash = stroke("trash", "M4 7h16M10 11v6M14 11v6M6 7l1 12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-12M9 7V4h6v3")
    val edit = stroke("edit", "M4 20h4L19 9l-4-4L4 16z")
    val select = stroke("select", "M4 6.5h8M4 12h8M4 17.5h8", "M14.5 12.5l2.5 2.5 4.5-5", w = 1.9f)
    val playNext = stroke("playNext", "M4 6h10M4 11h7M4 16h7M14 12v7l6-3.5z")
    val listAdd = stroke("listAdd", "M4 6h11M4 11h11M4 16h7M18 13v8M14 17h8")
    val refresh = stroke("refresh", "M20 11a8 8 0 1 0-2.3 5.7M20 4v7h-7")
}
