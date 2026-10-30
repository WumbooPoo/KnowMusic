# KnowMusic

A Windows-focused fork of [BitChord](https://github.com/kushagrasinghx/BitChord) — an aesthetic YouTube Music client.

<img src="desktopApp/packaging/icons/AppIcon.png" alt="KnowMusic app icon" width="128" />

> [!IMPORTANT]
> KnowMusic is a community fork. It is not affiliated with, endorsed by, or connected to YouTube or Google in any way. Use it at your own discretion.

## Credit

KnowMusic is based on **BitChord** by [kushagrasinghx](https://github.com/kushagrasinghx), licensed under the **GNU General Public License v3.0 (GPLv3)**. This fork keeps the same license; see [LICENSE](LICENSE). All credit for the original client goes to the BitChord authors and contributors.

## What is different on Windows

On top of everything BitChord already does (search, browse and play from YouTube Music, word-synced lyrics from many providers, gapless playback with crossfade, offline downloads, local library, scrobbling, Discord Rich Presence, Listen Together), KnowMusic adds a Windows-native desktop experience:

- **Native window** — system title bar, Mica/Acrylic backdrop, 1080×680 default size.
- **Mini-player pill** — a small always-on-top pill (top-centre) that appears automatically when the main window is minimized while music plays; hide it to the nearest screen edge (a ~12 dp strip stays, hover to bring it back).
- **Floating synced-lyrics card** — a display-only card under the pill with 3 lines (past / current / next), kanji above and romaji below each line.
- **Wallpaper mode** — artwork-driven desktop wallpaper.
- **System media integration** — SMTC media keys, keyboard media strip (prev / play-pause / next, volume knob), space toggles play/pause, closing the window quits the app.
- **Render backend** — OpenGL (Skiko), chosen for stability on Windows after a DirectX surface crash.

## Download

Grab the Windows installer from the [Releases](https://github.com/WumbooPoo/KnowMusic/releases) page: **KnowMusic-1.8.29.msi** (305 MB, VirusTotal 0/40).

Or build it yourself below.

Questions about installation, downloads, or updates? See the [FAQ](FAQ.md).

## Build (Windows)

Requirements: JDK 21, [WiX Toolset 3](https://wixtoolset.org/docs/wix3/) (only for the MSI).

```bat
gradlew.bat :desktopApp:createDistributable
```

The app lands in `desktopApp\build\compose\binaries\main\app\KnowMusic\` — run `KnowMusic.exe` there.

For an installer:

```bat
gradlew.bat :desktopApp:packageMsi
```

The `.msi` lands in `desktopApp\build\compose\binaries\main\msi\`.

## Disclaimer & Legal Notice

KnowMusic is an independent, community-driven third-party audio player and client. It is **not** associated with Google LLC, YouTube Music, Deezer, Telegram, or any of their parent companies.

- **No Media Hosting:** KnowMusic does not host, upload, or store copyrighted music files. It operates strictly as an interface to scan local device storage or stream media directly from public, public-facing, or user-authenticated APIs.
- **Fair Use & API Usage:** This software is created solely for personal research, educational, and fair-use purposes. The user is entirely responsible for ensuring their usage aligns with their local copyright laws and YouTube Terms of Service.
- **Copyleft:** KnowMusic is free software under the GPLv3. The license does not let anyone forbid others from selling or redistributing copies, but any distribution must come with the Corresponding Source under the same license.

## License

This project is licensed under the **GNU General Public License v3.0 (GPLv3)**. See the [LICENSE](LICENSE) file for details.

