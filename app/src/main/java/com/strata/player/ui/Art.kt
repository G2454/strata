package com.strata.player.ui

import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.key.Keyer
import coil.request.ImageRequest
import coil.request.Options
import com.strata.player.data.Track
import com.strata.player.data.albumArtUri

/** Image model for a track's cover: the embedded picture via MediaStore thumbnails. */
data class AudioArt(val trackUri: Uri, val albumId: Long, val px: Int)

class AudioArtKeyer : Keyer<AudioArt> {
    override fun key(data: AudioArt, options: Options): String = "art:${data.albumId}:${data.px}"
}

class AudioArtFetcher(private val data: AudioArt, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        if (data.albumId in missing) throw IllegalStateException("No artwork")
        val cr = options.context.contentResolver
        val loaded: android.graphics.Bitmap? = try {
            if (Build.VERSION.SDK_INT >= 29) {
                cr.loadThumbnail(data.trackUri, Size(data.px, data.px), null)
            } else {
                cr.openInputStream(albumArtUri(data.albumId))?.use { BitmapFactory.decodeStream(it) }
            }
        } catch (e: Exception) {
            null
        }
        if (loaded == null) {
            missing.add(data.albumId)
            throw IllegalStateException("No artwork")
        }
        val bmp: android.graphics.Bitmap = loaded
        return DrawableResult(
            drawable = BitmapDrawable(options.context.resources, bmp),
            isSampled = true,
            dataSource = DataSource.DISK,
        )
    }

    companion object {
        /** Albums known to have no artwork, so scrolling doesn't retry them. Cleared on rescan. */
        val missing: MutableSet<Long> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    }

    class Factory : Fetcher.Factory<AudioArt> {
        override fun create(data: AudioArt, options: Options, imageLoader: ImageLoader): Fetcher = AudioArtFetcher(data, options)
    }
}

/** Album artwork with a generated geometric cover underneath for files without art. */
@Composable
fun Art(track: Track?, modifier: Modifier, shape: Shape, big: Boolean = false) {
    val ctx = LocalContext.current
    Box(modifier.clip(shape)) {
        GeneratedArt(track?.albumId ?: 0L, Modifier.fillMaxSize())
        if (track != null) {
            val px = if (big) 900 else 240
            val req = remember(track.albumId, px) {
                ImageRequest.Builder(ctx).data(AudioArt(track.uri, track.albumId, px)).crossfade(true).build()
            }
            AsyncImage(model = req, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

private fun hsl(h: Float, s: Float, l: Float, a: Float = 1f): Color {
    val c = ColorUtils.HSLToColor(floatArrayOf(((h % 360f) + 360f) % 360f, s, l))
    return Color(c).copy(alpha = a)
}

/** Deterministic abstract covers in the spirit of the prototype: sun & sea, map grid, orbit, rings, stripes, split. */
@Composable
fun GeneratedArt(seed: Long, modifier: Modifier) {
    val h = ((seed * 2654435761L) ushr 8).mod(360L).toFloat()
    val style = (((seed * 40503L) ushr 4).mod(6L)).toInt()
    val a = hsl(h, 0.75f, 0.68f)
    val dark = hsl(h + 180f, 0.45f, 0.14f)
    val mid = hsl(h + 30f, 0.55f, 0.32f)
    Canvas(modifier.background(dark)) {
        val w = size.width
        val ht = size.height
        when (style) {
            0 -> { // sun over sea
                drawRect(a, size = size.copy(height = ht * 0.58f))
                drawRect(mid, topLeft = Offset(0f, ht * 0.58f))
                drawCircle(hsl(h + 20f, 0.9f, 0.86f), radius = w * 0.15f, center = Offset(w * 0.68f, ht * 0.34f))
                for (i in 0 until 3) drawLine(Color.White.copy(alpha = 0.2f - i * 0.05f), Offset(0f, ht * (0.66f + i * 0.1f)), Offset(w, ht * (0.66f + i * 0.1f)), strokeWidth = w * 0.01f)
            }
            1 -> { // map grid
                val step = w / 12f
                for (i in 0..12) {
                    drawLine(a.copy(alpha = 0.3f), Offset(i * step, 0f), Offset(i * step, ht), strokeWidth = 1.5f)
                    drawLine(a.copy(alpha = 0.3f), Offset(0f, i * step), Offset(w, i * step), strokeWidth = 1.5f)
                }
                drawCircle(hsl(h + 140f, 0.9f, 0.65f), radius = w * 0.09f, center = Offset(w * 0.3f, ht * 0.68f))
                drawCircle(a, radius = w * 0.18f, center = Offset(w * 0.3f, ht * 0.68f), style = Stroke(w * 0.012f))
            }
            2 -> { // orbit
                drawCircle(a, radius = w * 0.46f, center = Offset(w * 0.5f, ht * 1.18f))
                drawCircle(mid, radius = w * 0.52f, center = Offset(w * 0.5f, ht * 1.18f), style = Stroke(w * 0.06f))
                drawCircle(Color.White, radius = w * 0.016f, center = Offset(w * 0.76f, ht * 0.24f))
                drawCircle(Color.White.copy(alpha = 0.7f), radius = w * 0.009f, center = Offset(w * 0.22f, ht * 0.34f))
                drawCircle(Color.White.copy(alpha = 0.6f), radius = w * 0.008f, center = Offset(w * 0.58f, ht * 0.14f))
            }
            3 -> { // rings
                drawRect(Brush.linearGradient(listOf(mid, a), Offset.Zero, Offset(w, ht)))
                drawCircle(hsl(h, 0.3f, 0.92f), radius = w * 0.23f, center = Offset(w * 0.38f, ht * 0.42f), style = Stroke(w * 0.035f))
                drawCircle(hsl(h, 0.3f, 0.92f, 0.55f), radius = w * 0.23f, center = Offset(w * 0.62f, ht * 0.58f), style = Stroke(w * 0.035f))
            }
            4 -> { // diagonal stripes
                rotate(-20f) {
                    val band = w / 9f
                    var x = -w
                    var i = 0
                    while (x < w * 2) {
                        drawRect(if (i % 2 == 0) a else mid, topLeft = Offset(x, -ht), size = size.copy(width = band, height = ht * 3))
                        x += band; i++
                    }
                }
            }
            else -> { // split with disc
                clipRect(left = w / 2f) { drawRect(a) }
                drawCircle(a, radius = w * 0.25f, center = Offset(w / 2f, ht / 2f), style = Stroke(w * 0.012f))
                drawCircle(dark, radius = w * 0.17f, center = Offset(w / 2f, ht / 2f))
            }
        }
    }
}
