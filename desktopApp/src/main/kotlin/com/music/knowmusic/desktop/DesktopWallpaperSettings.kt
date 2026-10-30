package com.music.knowmusic.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The custom-wallpaper and floating-miniplayer settings (round 23, Arvin request).
 *
 * Same shape as [DesktopAppearanceSettings], and the same rule that fixed the round 14/15
 * split-brain: exactly ONE flow per value, owned here. Every reader — the frame, the
 * sidebar, the top bar, the floating pill, the Settings sheet — collects these flows
 * directly. Nothing keeps a private copy, so a toggle can never disagree with itself.
 *
 * Only short scalars are persisted through [DesktopPersistence] (java.util.prefs, node
 * `com.music.knowmusic.desktop`): the image path as a string, the sliders as floats.
 * The bitmap itself is never written to prefs — it lives only in
 * [DesktopWallpaperLoader]'s memory cache and is re-decoded from the file on launch.
 *
 * Wallpaper "on" means a path is set ([wallpaperPath] non-blank). The frame's chrome
 * opacity sliders apply on that definition, even while the bitmap is still loading or
 * the file is missing (then the veil sits over the plain fallback background, which
 * reads as a slightly lighter solid — never a hole). The bitmap layer itself only draws
 * when [DesktopWallpaperLoader] actually produced one.
 *
 * Defaults are chosen for legibility first: the dim scrim sits at half black so lyrics
 * stay readable over a bright photo, and the sidebar — which carries the most text —
 * is the most opaque of the three areas.
 */
internal object DesktopWallpaperSettings {

    private fun flag(key: String, default: Boolean) =
        MutableStateFlow(DesktopPersistence().boolean(key, default))

    private fun ratio(key: String, default: Float) =
        MutableStateFlow(DesktopPersistence().float(key, default))

    private val _wallpaperPath =
        MutableStateFlow(DesktopPersistence().string(KEY_WALLPAPER_PATH, ""))
    private val _wallpaperBlurEnabled = flag(KEY_WALLPAPER_BLUR_ENABLED, true)
    private val _wallpaperBlurRadius = ratio(KEY_WALLPAPER_BLUR_RADIUS, DEFAULT_BLUR_RADIUS)
    private val _wallpaperDim = ratio(KEY_WALLPAPER_DIM, DEFAULT_DIM)
    private val _wallpaperOpacitySidebar = ratio(KEY_WALLPAPER_OPACITY_SIDEBAR, DEFAULT_OPACITY_SIDEBAR)
    private val _wallpaperOpacityContent = ratio(KEY_WALLPAPER_OPACITY_CONTENT, DEFAULT_OPACITY_CONTENT)
    private val _wallpaperOpacityMiniplayer = ratio(KEY_WALLPAPER_OPACITY_MINIPLAYER, DEFAULT_OPACITY_MINIPLAYER)
    private val _miniplayerEnabled = flag(KEY_MINIPLAYER_ENABLED, false)

    /** Absolute path of the chosen image, or "" when no wallpaper is set. */
    val wallpaperPath: StateFlow<String> = _wallpaperPath

    /**
     * Blur on/off. OFF skips blurring entirely — no decode of a blurred copy, no
     * box-blur pass, no `Modifier.blur` — the working bitmap is drawn as-is.
     */
    val wallpaperBlurEnabled: StateFlow<Boolean> = _wallpaperBlurEnabled

    /** Blur radius in working pixels (the working bitmap's long side is at most 512). */
    val wallpaperBlurRadius: StateFlow<Float> = _wallpaperBlurRadius

    /** Flat black scrim alpha over the wallpaper, 0..1. */
    val wallpaperDim: StateFlow<Float> = _wallpaperDim

    /** Sidebar chrome opacity while a wallpaper is set, 0..1. */
    val wallpaperOpacitySidebar: StateFlow<Float> = _wallpaperOpacitySidebar

    /** Content-ground opacity while a wallpaper is set, 0..1. */
    val wallpaperOpacityContent: StateFlow<Float> = _wallpaperOpacityContent

    /** Floating pill glass opacity while a wallpaper is set, 0..1. */
    val wallpaperOpacityMiniplayer: StateFlow<Float> = _wallpaperOpacityMiniplayer

    /**
     * Floating pill master switch. Off by default so nothing surprises: the pill then
     * only appears on its own while the main window is minimized with a song playing.
     */
    val miniplayerEnabled: StateFlow<Boolean> = _miniplayerEnabled

    fun setWallpaperPath(value: String) {
        DesktopPersistence().saveString(KEY_WALLPAPER_PATH, value)
        _wallpaperPath.value = value
    }

    fun setWallpaperBlurEnabled(value: Boolean) =
        writeBoolean(KEY_WALLPAPER_BLUR_ENABLED, value, _wallpaperBlurEnabled)

    fun setWallpaperBlurRadius(value: Float) =
        writeFloat(KEY_WALLPAPER_BLUR_RADIUS, value.coerceIn(MIN_BLUR_RADIUS, MAX_BLUR_RADIUS), _wallpaperBlurRadius)

    fun setWallpaperDim(value: Float) =
        writeFloat(KEY_WALLPAPER_DIM, value.coerceIn(0f, 1f), _wallpaperDim)

    fun setWallpaperOpacitySidebar(value: Float) =
        writeFloat(KEY_WALLPAPER_OPACITY_SIDEBAR, value.coerceIn(0f, 1f), _wallpaperOpacitySidebar)

    fun setWallpaperOpacityContent(value: Float) =
        writeFloat(KEY_WALLPAPER_OPACITY_CONTENT, value.coerceIn(0f, 1f), _wallpaperOpacityContent)

    fun setWallpaperOpacityMiniplayer(value: Float) =
        writeFloat(KEY_WALLPAPER_OPACITY_MINIPLAYER, value.coerceIn(0f, 1f), _wallpaperOpacityMiniplayer)

    fun setMiniplayerEnabled(value: Boolean) =
        writeBoolean(KEY_MINIPLAYER_ENABLED, value, _miniplayerEnabled)

    private fun writeBoolean(key: String, value: Boolean, into: MutableStateFlow<Boolean>) {
        DesktopPersistence().saveBoolean(key, value)
        into.value = value
    }

    private fun writeFloat(key: String, value: Float, into: MutableStateFlow<Float>) {
        DesktopPersistence().saveFloat(key, value)
        into.value = value
    }

    /** Blur radius slider range, in working pixels. */
    const val MIN_BLUR_RADIUS = 0f
    const val MAX_BLUR_RADIUS = 24f
    const val DEFAULT_BLUR_RADIUS = 12f

    const val DEFAULT_DIM = 0.5f
    const val DEFAULT_OPACITY_SIDEBAR = 0.85f
    const val DEFAULT_OPACITY_CONTENT = 0.55f
    const val DEFAULT_OPACITY_MINIPLAYER = 0.75f

    internal const val KEY_WALLPAPER_PATH = "wallpaper_path"
    internal const val KEY_WALLPAPER_BLUR_ENABLED = "wallpaper_blur_enabled"
    internal const val KEY_WALLPAPER_BLUR_RADIUS = "wallpaper_blur_radius"
    internal const val KEY_WALLPAPER_DIM = "wallpaper_dim"
    internal const val KEY_WALLPAPER_OPACITY_SIDEBAR = "wallpaper_opacity_sidebar"
    internal const val KEY_WALLPAPER_OPACITY_CONTENT = "wallpaper_opacity_content"
    internal const val KEY_WALLPAPER_OPACITY_MINIPLAYER = "wallpaper_opacity_miniplayer"
    internal const val KEY_MINIPLAYER_ENABLED = "miniplayer_enabled"
}
