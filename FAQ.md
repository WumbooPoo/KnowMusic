# KnowMusic FAQ

## What is KnowMusic?

KnowMusic is a Windows-focused fork of [BitChord](https://github.com/kushagrasinghx/BitChord) — a YouTube Music client with clean, Apple Music-inspired aesthetics. It adds a Windows-native desktop experience on top of BitChord: a mini-player pill, a floating synced-lyrics card, wallpaper mode, and system media-key integration.

KnowMusic is based on BitChord by kushagrasinghx and keeps the same **GPLv3** license. All credit for the original client goes to the BitChord authors and contributors.

## Installation

### Where do I download it?

From the [Releases](https://github.com/WumbooPoo/KnowMusic/releases) page. Download `KnowMusic-<version>.msi` and run it.

### Do I need Java installed?

No. The MSI bundles its own runtime — nothing else is required.

### What Windows versions are supported?

Windows 10/11 (x64).

### Does installing need admin rights?

No. The MSI installs for the current user only.

### Can I choose where it installs?

Yes. The installer shows a **Destination Folder** step — pick any drive or folder (for example `D:\Apps\KnowMusic`). Note this only moves the app itself; your downloads and settings live elsewhere (see below).

### MSI or EXE?

Releases are **MSI-only** for now. A portable build (`KnowMusic.exe` inside the app folder) exists for local builds, but no setup `.exe` is published yet.

### Windows warns the app is unsigned. Is it safe?

The MSI is currently unsigned (code-signing certificates are paid), so Windows SmartScreen may ask you to confirm — click "More info" → "Run anyway". The published MSI was scanned on VirusTotal with **0 detections** (see the release notes).

### Why was the `.exe` installer flagged by some antivirus engines?

The `.exe` setup wrapper was flagged by 2 heuristic/ML engines as a false positive on the unsigned WiX bootstrapper. The MSI contains the same app and scored 0 detections, so releases are MSI-only until signing or vendor false-positive reports are sorted out.

## Updates

### The app says an update is available right after I installed the latest release. Why?

This happened with the first `1.8.29` MSI: the app inside reported version `1.8.2` while the release was tagged `1.8.29`, so the updater (correctly, by its numbers) thought an update existed. It was fixed in the re-uploaded `1.8.29` MSI — download the MSI from the release page again and the dialog goes away.

### How do updates work?

On launch, the app checks the latest GitHub release. If a newer version exists, it shows an update dialog; the Download button opens that release's MSI. Install the new MSI over the old one — your settings and downloads are kept.

## Downloads, data & privacy

### Where are downloaded songs stored?

In your Windows user profile, at `C:\Users\<you>\Music\KnowMusic` — **not** in the install folder. The folder is created on the first download. This is also why a custom install folder never contains a music folder.

### I reinstalled (or moved the install folder) and I'm still logged in, with the same settings and wallpaper. Is that normal?

Yes. The installer only replaces the app files. Your login session, settings, wallpaper choice, downloads, and cache live in your Windows user profile and survive reinstalls — nothing is uploaded anywhere, it all stays on your machine. This is the same behavior as Spotify or Chrome staying logged in after a reinstall.

### Does uninstalling delete my downloads and settings?

No. Uninstalling removes the app files only. If you want a full clean slate, delete `Music\KnowMusic` and the app data under your user profile manually (logs live at `.local\state\knowmusic\desktop.log`).

### Where does the rest of the app data live?

Under your Windows user profile: downloads in `Music\KnowMusic`, logs in `.local\state\knowmusic`, plus settings in the Windows registry (Java preferences). The install folder itself contains only the app (executable, runtime, config).

## Performance & rendering

### Why does KnowMusic use around 1 GB of RAM?

The app runs on the JVM with Skiko rendering, a Chromium WebView, and native audio decoders, while native players use OS frameworks directly. The Java heap itself is capped (256 MB); the rest is native memory. Performance on low-end machines is accepted as-is for now; memory optimization is a possible post-release project.

### Why OpenGL instead of DirectX?

A DirectX surface crash (the app froze on minimize with a fatal error in the Skiko Direct3D renderer) forced a switch to the OpenGL renderer for stability on Windows.

## Features & plans

### Where do lyrics come from?

Public lyric providers (KuGou, LRCLIB, Musixmatch, Genius) with candidate scoring reimplemented from scratch.

### Is there a Linux version?

Planned, not yet published: deb, rpm, and AppImage builds are configured in CI and will follow once the Windows release is stable.

### How do I report a bug?

Open an issue on GitHub with: the app version (shown in Settings), your Windows version, what you were doing, and the log file (`.local\state\knowmusic\desktop.log`) if it seems relevant.
