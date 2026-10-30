package com.music.knowmusic.desktop

import com.music.knowmusic.data.model.Song
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.util.Locale
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

/** Finds ordinary audio files without requiring an Android MediaStore. */
object DesktopLocalMusic {
    private val supportedExtensions = setOf("aac", "flac", "m4a", "mp3", "ogg", "wav", "webm")

    fun scan(): List<Song> {
        val filterNonMusic = DesktopPersistence().boolean(KEY_FILTER_NON_MUSIC_AUDIO, false)
        return roots()
            .filter(Files::isDirectory)
            .flatMap { scanDirectory(it, filterNonMusic) }
            .distinctBy(Song::localPath)
            .sortedBy { it.title.lowercase() }
    }

    /**
     * Where to look: the chosen folder, or the usual two when none has been chosen.
     *
     * Android's equivalent is a document tree the listener grants, with "all audio folders" as the
     * unset state. There is no such permission on a desktop, so an unset folder means the two
     * places music actually lands.
     */
    internal fun roots(): List<Path> {
        val chosen = folder()
        if (chosen != null) return listOf(chosen)
        val home = Paths.get(System.getProperty("user.home"))
        return listOf(home.resolve("Music"), home.resolve("Downloads"))
    }

    /** The folder the listener picked, or null for all of them. */
    fun folder(): Path? = DesktopPersistence().string(KEY_LOCAL_MUSIC_FOLDER)
        .takeIf(String::isNotBlank)
        ?.let { runCatching { Paths.get(it) }.getOrNull() }
        ?.takeIf(Files::isDirectory)

    fun setFolder(path: Path?) {
        DesktopPersistence().saveString(KEY_LOCAL_MUSIC_FOLDER, path?.toAbsolutePath()?.toString().orEmpty())
    }

    /** What the Local Music settings row says underneath its title. */
    fun folderLabel(): String = folder()?.fileName?.toString() ?: "All audio folders"

    internal const val KEY_LOCAL_MUSIC_FOLDER = "local_music_folder"

    /**
     * Asks for a folder with the platform's own chooser.
     *
     * Swing's, not a drawn one: this is a file-system question, and the desktop already has an
     * answer for it that knows about bookmarks, hidden files and network mounts.
     */
    fun chooseFolder(): Path? = runCatching {
        val chooser = javax.swing.JFileChooser(folder()?.toFile() ?: java.io.File(System.getProperty("user.home")))
        chooser.fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
        chooser.dialogTitle = "Local music folder"
        if (chooser.showOpenDialog(null) != javax.swing.JFileChooser.APPROVE_OPTION) return null
        chooser.selectedFile?.toPath()
    }.getOrNull()

    private fun scanDirectory(root: Path, filterNonMusic: Boolean): List<Song> = buildList {
        runCatching {
            Files.walk(root, 4).use { files ->
                files.filter(Files::isRegularFile)
                    .filter { it.extension.lowercase() in supportedExtensions }
                    .filter { !filterNonMusic || isMusic(it) }
                    .forEach { path -> add(path.toSong()) }
            }
        }
    }

    /**
     * Android reads MediaStore's `IS_MUSIC`, `IS_ALARM`, `IS_NOTIFICATION` and `IS_RINGTONE` flags
     * and a 30-second floor. A filesystem states none of that, so the same intent is read off the
     * name, the folder and the size — a clip short enough to be a system sound is a small file,
     * and probing every track's real duration would mean decoding the whole library on every scan.
     */
    internal fun isMusic(path: Path): Boolean {
        if (path.extension.lowercase(Locale.ROOT) == "wav") return false
        if (runCatching { Files.size(path) }.getOrDefault(Long.MAX_VALUE) < MIN_MUSIC_BYTES) return false
        val haystack = path.toString().lowercase(Locale.ROOT)
        return NON_MUSIC_MARKERS.none { it in haystack }
    }

    /** Folders and names that hold something other than music. */
    private val NON_MUSIC_MARKERS = listOf(
        "/ringtones/", "/notifications/", "/alarms/", "/ui/", "/recordings/", "/voice recorder/",
        "voice note", "voicenote", "recording", "ringtone", "notification", "alarm",
    )

    /**
     * The size below which a file cannot hold thirty seconds of music — thirty seconds of even a
     * 64 kbps stream is a quarter of a megabyte.
     */
    private const val MIN_MUSIC_BYTES = 240L * 1024

    internal const val KEY_FILTER_NON_MUSIC_AUDIO = "filter_non_music_audio"

    private fun Path.toSong(): Song {
        val absolutePath = toAbsolutePath().normalize()
        val attributes = runCatching {
            Files.readAttributes(absolutePath, BasicFileAttributes::class.java)
        }.getOrNull()
        val modified = attributes?.lastModifiedTime()?.toInstant()
        val added = attributes?.creationTime()?.toInstant() ?: modified ?: Instant.now()
        val identity = absolutePath.toString()
        val parsed = parseFileName(nameWithoutExtension)
        val codec = extension.lowercase(Locale.ROOT).let { value ->
            when (value) {
                "flac" -> "FLAC"
                "wav" -> "WAV"
                "aac", "m4a" -> "AAC"
                "ogg" -> "OGG"
                "webm" -> "OPUS"
                else -> value.uppercase(Locale.ROOT)
            }
        }
        return Song(
            videoId = "local:$identity",
            title = parsed.title,
            artist = parsed.artist,
            thumbnailUrl = null,
            localUri = absolutePath.toUri().toString(),
            localPath = absolutePath.toString(),
            localDateAddedSeconds = added.epochSecond,
            localDateModifiedSeconds = modified?.epochSecond,
            sourceQuality = codec,
        )
    }

    private fun parseFileName(name: String): ParsedName {
        val parts = name.split(" - ", limit = 2)
        return if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            ParsedName(title = parts[1].trim(), artist = parts[0].trim())
        } else {
            ParsedName(title = name, artist = "On This Computer")
        }
    }

    private data class ParsedName(val title: String, val artist: String)
}

enum class DesktopLibraryView {
    LIST,
    GRID,
}

/**
 * Round 20: the ONE sort system for YTM playlists and the local library
 * (Local + Downloads). It replaces the old local-only sort outright —
 * there is no second sort beside it.
 *
 * A stored value is always "CRITERION_DIRECTION" (for example `TITLE_ASC`),
 * the same shape the old enum used, so a stored value reads the same way
 * in preferences. "Tanggal Rilis" is deliberately NOT a criterion: [Song]
 * has no release-date field, the YTM parser fills no date at all, and the
 * local scan only knows a file's added/modified times — inventing a release
 * date out of added/modified/file dates would be fabricated data, so the
 * option is hidden this round rather than faked.
 *
 * - PLAYLIST_ORDER exists only for playlists: a local library has no stored
 *   order to return to, so the local menu never offers it.
 * - DATE_ADDED / DATE_MODIFIED exist only for the local library: YTM rows
 *   carry no local timestamps, so the playlist menu never offers them.
 * - Artist sorts on [Song.artist], the combined byline string — NOT on
 *   [Song.artists], which is empty on most rows. Fallback bylines
 *   ("Unknown artist", "On This Computer") therefore cluster together;
 *   that is the data as it stands, not something to special-case.
 * - A null album or null local date is ALWAYS LAST, in both directions.
 *   That is implemented explicitly below: a naive `compareByDescending`
 *   would flip nulls to the front on DESC, and a naive `.reversed()`
 *   would flip the tie-breakers too. DESC here is always
 *   `compareByDescending`-style (the primary key reversed, the
 *   tie-breakers untouched).
 */
enum class DesktopSortCriterion {
    PLAYLIST_ORDER,
    TITLE,
    ARTIST,
    ALBUM,
    DATE_ADDED,
    DATE_MODIFIED,
}

enum class DesktopSort {
    PLAYLIST_ORDER_ASC,
    PLAYLIST_ORDER_DESC,
    TITLE_ASC,
    TITLE_DESC,
    ARTIST_ASC,
    ARTIST_DESC,
    ALBUM_ASC,
    ALBUM_DESC,
    DATE_ADDED_ASC,
    DATE_ADDED_DESC,
    DATE_MODIFIED_ASC,
    DATE_MODIFIED_DESC;

    /** Which menu item this value belongs to. */
    val criterion: DesktopSortCriterion get() = when (this) {
        PLAYLIST_ORDER_ASC, PLAYLIST_ORDER_DESC -> DesktopSortCriterion.PLAYLIST_ORDER
        TITLE_ASC, TITLE_DESC -> DesktopSortCriterion.TITLE
        ARTIST_ASC, ARTIST_DESC -> DesktopSortCriterion.ARTIST
        ALBUM_ASC, ALBUM_DESC -> DesktopSortCriterion.ALBUM
        DATE_ADDED_ASC, DATE_ADDED_DESC -> DesktopSortCriterion.DATE_ADDED
        DATE_MODIFIED_ASC, DATE_MODIFIED_DESC -> DesktopSortCriterion.DATE_MODIFIED
    }

    /** True for the `_DESC` half of every criterion. */
    val descending: Boolean get() = name.endsWith("_DESC")

    /** The same criterion with the other direction. */
    fun flipped(): DesktopSort = criterion.withDirection(!descending)

    /**
     * What a menu tap on [tapped] does, Apple Music style: tapping the
     * already-selected criterion flips its direction; tapping another one
     * picks it with that criterion's last direction, or ascending when it
     * has none yet. Returns the new sort plus the updated per-criterion
     * direction memory, which the caller keeps for the page session.
     */
    fun afterMenuTap(
        tapped: DesktopSortCriterion,
        lastDirections: Map<DesktopSortCriterion, Boolean>,
    ): Pair<DesktopSort, Map<DesktopSortCriterion, Boolean>> {
        val next = if (tapped == criterion) {
            flipped()
        } else {
            tapped.withDirection(lastDirections[tapped] ?: false)
        }
        return next to (lastDirections + (tapped to next.descending))
    }

    companion object {
        /** The playlist menu's criteria, in menu order. */
        val PLAYLIST_CRITERIA = listOf(
            DesktopSortCriterion.PLAYLIST_ORDER,
            DesktopSortCriterion.TITLE,
            DesktopSortCriterion.ARTIST,
            DesktopSortCriterion.ALBUM,
        )

        /** The local menu's criteria, in menu order. */
        val LOCAL_CRITERIA = listOf(
            DesktopSortCriterion.TITLE,
            DesktopSortCriterion.ARTIST,
            DesktopSortCriterion.ALBUM,
            DesktopSortCriterion.DATE_ADDED,
            DesktopSortCriterion.DATE_MODIFIED,
        )

        /**
         * A stored value, or [default] for anything unrecognised — never a
         * crash on a hand-edited or future value. This is the
         * `runCatching` fallback the old local sort already had.
         */
        fun parseStored(raw: String?, default: DesktopSort): DesktopSort =
            runCatching { valueOf(raw.orEmpty()) }.getOrDefault(default)

        /**
         * The old local-only values, mapped onto the new shape. Only the
         * two date values change shape: the old `DATE_ADDED` /
         * `DATE_MODIFIED` both meant newest-first, so they map to the
         * `_DESC` halves. `TITLE_ASC` / `TITLE_DESC` are already in the
         * new shape and need no mapping. Anything else returns null,
         * which means "not a legacy value" (new value, blank, or unknown).
         */
        fun migrateLegacy(raw: String?): DesktopSort? = when (raw) {
            "DATE_ADDED" -> DATE_ADDED_DESC
            "DATE_MODIFIED" -> DATE_MODIFIED_DESC
            else -> null
        }

        /**
         * A local library's stored sort, following the round 19 lyrics
         * migration precedent: the legacy value is read once, and a
         * legacy date value is mapped AND written back in the new shape
         * the first time it is seen, so later launches never re-read the
         * old shape. A new key's presence always wins over legacy, and
         * an unknown value falls back to [default] in memory without
         * being overwritten. The key stays `${persistenceKey}_sort`
         * (`local_sort` / `downloaded_sort`), one edit still covering
         * Local + Downloads with separate keys.
         */
        fun loadLocal(persistence: DesktopPersistence, persistenceKey: String, default: DesktopSort): DesktopSort {
            val key = "${persistenceKey}_sort"
            val raw = persistence.string(key, default.name)
            val migrated = migrateLegacy(raw)
            if (migrated != null) {
                persistence.saveString(key, migrated.name)
                return migrated
            }
            return parseStored(raw, default)
        }

        /**
         * A playlist's stored sort, keyed per browse id so sorting
         * playlist A never leaks into playlist B. Local playlists
         * (opened via `openPlaylist`, type PLAYLIST) get a key from
         * their stable UUID id just like YTM `VL…` playlists do. There
         * is no legacy playlist value to migrate: playlist sorting is
         * new this round, and an unknown value falls back to stored
         * order.
         */
        fun playlistKey(browseId: String): String = "playlist_sort_$browseId"

        fun loadPlaylist(persistence: DesktopPersistence, browseId: String): DesktopSort =
            parseStored(persistence.string(playlistKey(browseId), PLAYLIST_ORDER_ASC.name), PLAYLIST_ORDER_ASC)

        fun savePlaylist(persistence: DesktopPersistence, browseId: String, sort: DesktopSort) {
            persistence.saveString(playlistKey(browseId), sort.name)
        }
    }
}

fun DesktopSortCriterion.withDirection(descending: Boolean): DesktopSort = when (this) {
    DesktopSortCriterion.PLAYLIST_ORDER -> if (descending) DesktopSort.PLAYLIST_ORDER_DESC else DesktopSort.PLAYLIST_ORDER_ASC
    DesktopSortCriterion.TITLE -> if (descending) DesktopSort.TITLE_DESC else DesktopSort.TITLE_ASC
    DesktopSortCriterion.ARTIST -> if (descending) DesktopSort.ARTIST_DESC else DesktopSort.ARTIST_ASC
    DesktopSortCriterion.ALBUM -> if (descending) DesktopSort.ALBUM_DESC else DesktopSort.ALBUM_ASC
    DesktopSortCriterion.DATE_ADDED -> if (descending) DesktopSort.DATE_ADDED_DESC else DesktopSort.DATE_ADDED_ASC
    DesktopSortCriterion.DATE_MODIFIED -> if (descending) DesktopSort.DATE_MODIFIED_DESC else DesktopSort.DATE_MODIFIED_ASC
}

/** A nullable text key that is last no matter which direction is asked for. */
private fun compareNullableTextLast(a: String?, b: String?, descending: Boolean): Int = when {
    a == null && b == null -> 0
    a == null -> 1
    b == null -> -1
    else -> if (descending) {
        String.CASE_INSENSITIVE_ORDER.compare(b, a)
    } else {
        String.CASE_INSENSITIVE_ORDER.compare(a, b)
    }
}

/** A nullable epoch key that is last no matter which direction is asked for. */
private fun compareNullableLongLast(a: Long?, b: Long?, descending: Boolean): Int = when {
    a == null && b == null -> 0
    a == null -> 1
    b == null -> -1
    else -> if (descending) b.compareTo(a) else a.compareTo(b)
}

/**
 * The sort as a comparator over the original positions, so the playlist
 * page can sort its rows without losing where each row came from: the
 * queue is built from the sorted positions, while a row tap still has to
 * find its own original index. Tie-breakers: title (case-insensitive)
 * for Artist/Album/Date, then the original index for everything — so
 * equal titles keep their stored order in BOTH directions (DESC reverses
 * the titles, never the ties, because `sortedWith` is stable and the
 * index tie-breaker only ever runs ascending).
 */
fun desktopSortComparator(sort: DesktopSort): Comparator<IndexedValue<Song>> {
    val descending = sort.descending
    val primary: Comparator<IndexedValue<Song>> = when (sort.criterion) {
        DesktopSortCriterion.PLAYLIST_ORDER -> Comparator { a, b ->
            if (descending) b.index.compareTo(a.index) else a.index.compareTo(b.index)
        }
        DesktopSortCriterion.TITLE -> Comparator { a, b ->
            if (descending) {
                String.CASE_INSENSITIVE_ORDER.compare(b.value.title, a.value.title)
            } else {
                String.CASE_INSENSITIVE_ORDER.compare(a.value.title, b.value.title)
            }
        }
        DesktopSortCriterion.ARTIST -> Comparator { a, b ->
            if (descending) {
                String.CASE_INSENSITIVE_ORDER.compare(b.value.artist, a.value.artist)
            } else {
                String.CASE_INSENSITIVE_ORDER.compare(a.value.artist, b.value.artist)
            }
        }
        DesktopSortCriterion.ALBUM -> Comparator { a, b ->
            compareNullableTextLast(a.value.albumName, b.value.albumName, descending)
        }
        DesktopSortCriterion.DATE_ADDED -> Comparator { a, b ->
            compareNullableLongLast(a.value.localDateAddedSeconds, b.value.localDateAddedSeconds, descending)
        }
        DesktopSortCriterion.DATE_MODIFIED -> Comparator { a, b ->
            compareNullableLongLast(a.value.localDateModifiedSeconds, b.value.localDateModifiedSeconds, descending)
        }
    }
    val withTitleTie = when (sort.criterion) {
        DesktopSortCriterion.TITLE, DesktopSortCriterion.PLAYLIST_ORDER -> primary
        else -> primary.thenBy(String.CASE_INSENSITIVE_ORDER) { it.value.title }
    }
    return withTitleTie.thenBy { it.index }
}

/** [songs] in [sort] order. */
fun List<Song>.sortedWithDesktopSort(sort: DesktopSort): List<Song> =
    indexedSortedWithDesktopSort(sort).map { it.value }

/**
 * [songs] in [sort] order, each row still carrying its original index.
 * The playlist page sorts through this: the queue is the values in this
 * order, and a tapped row's queue position is where its original index
 * lands here.
 */
fun List<Song>.indexedSortedWithDesktopSort(sort: DesktopSort): List<IndexedValue<Song>> =
    withIndex().sortedWith(desktopSortComparator(sort))
