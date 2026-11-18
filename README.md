# Lumen - minimal Android TV launcher (v1.0)

Build: push to GitHub, Actions builds `app-debug.apk`. Install it, press HOME on the remote, choose **Lumen** and **Always**.

## Home screen
- Dock (bottom): your apps + All apps + Settings. Hold OK on a dock app: move left/right, remove, app info, uninstall.
- Poster row: every folder on the TV or on a USB drive that contains a jpg / jpeg / png becomes a poster (never cropped). Folders named `undefined` are skipped.
- OK on a poster: resumes the last episode where you stopped; if never started, opens the folder with all episodes, progress bars and played time.
- Top right: USB icons (one per drive), Wi-Fi icon, clock. Background colour follows the focused app / poster.

## Player (remote)
| Key | Action |
|---|---|
| Up / Down | previous / next episode |
| Left / Right | seek -10 s / +10 s (hold = faster) |
| OK | play / pause (hold OK = audio, subtitles, options) |
| Volume | system volume |
| Back | back to the episode list |

Video always uses the TV's hardware decoder (4K / HEVC / HDR). Audio uses hardware or HDMI passthrough first; the FFmpeg software decoder is only a fallback for tracks the TV cannot decode, and switches itself off if it fails.

## Wallpapers (Settings > Wallpaper)
Sources: Free catalog, Pexels, Unsplash, and My files (pictures on the TV or any USB drive). Pictures that are not 16:9 open a live adapt screen: Fill (move + zoom with the arrow keys), Fit + blur, Fit + colour, Stretch. Every screen except Home shows the wallpaper softly blurred (switch off in Settings).

Free catalog (no account), Pexels and Unsplash (free personal API key, typed once). Choose 4K / QHD / Full HD / HD / original.
