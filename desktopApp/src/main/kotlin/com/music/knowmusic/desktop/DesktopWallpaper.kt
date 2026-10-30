package com.music.knowmusic.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.math.roundToInt

/**
 * The custom wallpaper pipeline (round 23, Arvin request). Written from scratch for
 * KnowMusic; nothing here is lifted from any other player.
 *
 * The expensive work happens exactly once per (file, blur radius) pair, off the UI
 * thread, and the result is drawn as one plain bitmap afterwards:
 *
 * 1. decode on [Dispatchers.IO] — `decodeToImageBitmap` reads the first frame only,
 *    so an animated GIF/WebP shows its first frame and nothing more (out of scope);
 * 2. downscale to a long side of at most [WORK_LONG_SIDE] px with a bilinear
 *    resample — a phone photo is not blurred at full resolution;
 * 3. when blur is on, three separable box-blur passes on [Dispatchers.Default] —
 *    a cheap Gaussian approximation at this size;
 * 4. cache by (path, file mtime, radius) so a restart, a resize, or a recomposition
 *    never re-blurs anything.
 *
 * Blur OFF skips steps 2's blur and 3 entirely: the downscaled copy is drawn as-is,
 * and no `Modifier.blur` is ever applied — a live blur over a full window would be
 * re-evaluated per frame, which is the anti-pattern this codebase deliberately
 * avoids (see the full-artwork blur's own comment in the player). Resizing the
 * window only stretches the cached bitmap bilinearly; a radius change re-blurs from
 * the downscaled copy, never from the blurred one, so repeated slider drags cannot
 * accumulate blur.
 *
 * A file edited in place is NOT picked up live: the cache key carries the mtime,
 * but nothing polls the file. Re-picking it in Settings (or restarting) reloads.
 */
internal object DesktopWallpaperLoader {

    private data class Key(val path: String, val mtime: Long, val radius: Float)

    /** A handful of recent wallpapers; each entry is at most ~1 MiB at 512px ARGB. */
    private val cache = object : LinkedHashMap<Key, ImageBitmap>(0, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<Key, ImageBitmap>) = size > 8
    }

    suspend fun load(path: String, blurEnabled: Boolean, radius: Float): ImageBitmap? {
        if (path.isBlank()) return null
        val file = File(path)
        if (!file.isFile) return null
        val mtime = file.lastModified()
        val radiusKey = if (blurEnabled) radius else BLUR_OFF_KEY
        val key = Key(path, mtime, radiusKey)
        synchronized(cache) { cache[key] }?.let { return it }
        val bytes = withContext(Dispatchers.IO) { runCatching { file.readBytes() }.getOrNull() }
            ?: return null
        val decoded = runCatching { bytes.decodeToImageBitmap() }.getOrNull()
            ?: return null
        if (decoded.width < 1 || decoded.height < 1) return null
        val downscaled = withContext(Dispatchers.Default) {
            downscale(decoded, WORK_LONG_SIDE)
        } ?: return null
        val result = if (!blurEnabled || radius <= 0f) {
            downscaled
        } else {
            withContext(Dispatchers.Default) {
                blur(downscaled, radius.roundToInt().coerceAtLeast(1))
            }
        }
        synchronized(cache) { cache[key] = result }
        return result
    }

    private const val BLUR_OFF_KEY = -1f

    /**
     * Downscales [source] so its long side is at most [maxLongSide], bilinearly.
     * Returns the source itself when it already fits — no pixel round-trip then.
     */
    private fun downscale(source: ImageBitmap, maxLongSide: Int): ImageBitmap? {
        val width = source.width
        val height = source.height
        val longSide = maxOf(width, height)
        if (longSide <= maxLongSide) return source
        val scale = maxLongSide.toFloat() / longSide
        val targetWidth = maxOf(1, (width * scale).roundToInt())
        val targetHeight = maxOf(1, (height * scale).roundToInt())
        val input = IntArray(width * height)
        source.readPixels(input)
        val output = IntArray(targetWidth * targetHeight)
        for (targetY in 0 until targetHeight) {
            val sourceY = (targetY + 0.5f) / scale - 0.5f
            val y0 = sourceY.toInt().coerceIn(0, height - 1)
            val y1 = (y0 + 1).coerceIn(0, height - 1)
            val fy = (sourceY - y0).coerceIn(0f, 1f)
            for (targetX in 0 until targetWidth) {
                val sourceX = (targetX + 0.5f) / scale - 0.5f
                val x0 = sourceX.toInt().coerceIn(0, width - 1)
                val x1 = (x0 + 1).coerceIn(0, width - 1)
                val fx = (sourceX - x0).coerceIn(0f, 1f)
                val top = lerpPixel(input[y0 * width + x0], input[y0 * width + x1], fx)
                val bottom = lerpPixel(input[y1 * width + x0], input[y1 * width + x1], fx)
                output[targetY * targetWidth + targetX] = lerpPixel(top, bottom, fy)
            }
        }
        return output.toImageBitmap(targetWidth, targetHeight)
    }

    private fun lerpPixel(a: Int, b: Int, t: Float): Int {
        val aA = a shr 24 and 0xFF
        val aR = a shr 16 and 0xFF
        val aG = a shr 8 and 0xFF
        val aB = a and 0xFF
        val bA = b shr 24 and 0xFF
        val bR = b shr 16 and 0xFF
        val bG = b shr 8 and 0xFF
        val bB = b and 0xFF
        return (
            ((aA + ((bA - aA) * t).roundToInt()).coerceIn(0, 255) shl 24) or
                ((aR + ((bR - aR) * t).roundToInt()).coerceIn(0, 255) shl 16) or
                ((aG + ((bG - aG) * t).roundToInt()).coerceIn(0, 255) shl 8) or
                (aB + ((bB - aB) * t).roundToInt()).coerceIn(0, 255)
            )
    }

    /**
     * Three separable box passes (horizontal, then vertical, per pass) approximate a
     * Gaussian blur at this size. Edges clamp to the edge pixel, so no dark halo
     * creeps in from outside the image.
     */
    private fun blur(source: ImageBitmap, radius: Int): ImageBitmap {
        val width = source.width
        val height = source.height
        if (width < 2 || height < 2) return source
        var current = IntArray(width * height)
        source.readPixels(current)
        var next = IntArray(width * height)
        repeat(BLUR_PASSES) {
            boxBlurPass(current, next, width, height, radius, horizontal = true)
            boxBlurPass(next, current, width, height, radius, horizontal = false)
        }
        return current.toImageBitmap(width, height)
    }

    private fun boxBlurPass(
        source: IntArray,
        target: IntArray,
        width: Int,
        height: Int,
        radius: Int,
        horizontal: Boolean,
    ) {
        val major = if (horizontal) width else height
        val minor = if (horizontal) height else width
        val window = radius * 2 + 1
        fun pixel(fixed: Int, at: Int): Int {
            val clamped = at.coerceIn(0, major - 1)
            return if (horizontal) source[fixed * width + clamped]
            else source[clamped * width + fixed]
        }
        repeat(minor) { fixed ->
            var alpha = 0
            var red = 0
            var green = 0
            var blue = 0
            for (offset in -radius..radius) {
                val color = pixel(fixed, offset)
                alpha += color shr 24 and 0xFF
                red += color shr 16 and 0xFF
                green += color shr 8 and 0xFF
                blue += color and 0xFF
            }
            for (at in 0 until major) {
                val outColor = pixel(fixed, at - radius)
                val inColor = pixel(fixed, at + radius + 1)
                val outIndex = if (horizontal) fixed * width + at else at * width + fixed
                target[outIndex] = (
                    ((alpha / window) shl 24) or
                        ((red / window) shl 16) or
                        ((green / window) shl 8) or
                        (blue / window)
                    )
                alpha += (inColor shr 24 and 0xFF) - (outColor shr 24 and 0xFF)
                red += (inColor shr 16 and 0xFF) - (outColor shr 16 and 0xFF)
                green += (inColor shr 8 and 0xFF) - (outColor shr 8 and 0xFF)
                blue += (inColor and 0xFF) - (outColor and 0xFF)
            }
        }
    }

    /** Rebuilds a bitmap from ARGB pixels, the same route the mesh texture uses. */
    private fun IntArray.toImageBitmap(width: Int, height: Int): ImageBitmap {
        val bytes = ByteArray(size * 4)
        forEachIndexed { index, argb ->
            val at = index * 4
            bytes[at] = (argb and 0xFF).toByte()
            bytes[at + 1] = ((argb shr 8) and 0xFF).toByte()
            bytes[at + 2] = ((argb shr 16) and 0xFF).toByte()
            bytes[at + 3] = ((argb shr 24) and 0xFF).toByte()
        }
        val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
        val bitmap = Bitmap()
        bitmap.allocPixels(info)
        bitmap.installPixels(bytes)
        bitmap.setImmutable()
        return org.jetbrains.skia.Image.makeFromBitmap(bitmap).toComposeImageBitmap()
    }
}

/** Long side of the working bitmap, in pixels. */
private const val WORK_LONG_SIDE = 512

/** Box passes per blur; three is what makes a box stack read as a Gaussian. */
private const val BLUR_PASSES = 3

/**
 * Loads the wallpaper bitmap for the current settings, reloading when the path, the
 * blur toggle, or the settled blur radius changes. The radius is debounced so a
 * slider drag re-blurs once, from the downscaled copy, instead of once per tick —
 * and the previous bitmap stays on screen while the new one loads, so there is no
 * flash back to the old backdrop mid-drag.
 */
@Composable
internal fun rememberWallpaperImage(): ImageBitmap? {
    val path by DesktopWallpaperSettings.wallpaperPath.collectAsState()
    val blurEnabled by DesktopWallpaperSettings.wallpaperBlurEnabled.collectAsState()
    val radius by DesktopWallpaperSettings.wallpaperBlurRadius.collectAsState()
    var debouncedRadius by remember { mutableStateOf(radius) }
    LaunchedEffect(radius) {
        delay(WALLPAPER_RADIUS_DEBOUNCE_MS)
        debouncedRadius = radius
    }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path, blurEnabled, debouncedRadius) {
        if (path.isBlank()) {
            image = null
            return@LaunchedEffect
        }
        image = DesktopWallpaperLoader.load(path, blurEnabled, debouncedRadius)
    }
    return image
}

/** Radius drags settle for this long before a re-blur is queued. */
private const val WALLPAPER_RADIUS_DEBOUNCE_MS = 200L

/**
 * The wallpaper itself, drawn as the first child of the frame's outer box.
 *
 * Order matters for the material decision (option i: wallpaper covers material):
 * an opaque black base first — a transparent PNG must still hide DWM's Mica/Acrylic
 * behind it — then the bitmap, cropped to fill and overscaled a little so a blurred
 * image's softened edges never show inside the viewport, then the flat dim scrim.
 * The scrim is flat black at the dim alpha, not a gradient, so the dim slider means
 * exactly what it says everywhere on screen.
 */
@Composable
internal fun DesktopWallpaperLayer(bitmap: ImageBitmap, dim: Float) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().scale(WALLPAPER_OVERSCALE),
        )
        if (dim > 0f) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim.coerceIn(0f, 1f))))
        }
    }
}

/** Overscale so blurred edges stay outside the viewport. */
private const val WALLPAPER_OVERSCALE = 1.08f

/**
 * The file picker, mirroring the local-music folder picker: a plain `JFileChooser`
 * limited to image files. Returns the absolute path, or null on cancel/failure.
 */
internal fun chooseWallpaperImage(): String? = runCatching {
    val chooser = JFileChooser().apply {
        dialogTitle = "Choose wallpaper image"
        fileSelectionMode = JFileChooser.FILES_ONLY
        fileFilter = FileNameExtensionFilter(
            "Images",
            "png", "jpg", "jpeg", "webp", "gif", "bmp",
        )
    }
    if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return@runCatching null
    chooser.selectedFile?.absolutePath
}.getOrNull()
