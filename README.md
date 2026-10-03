[README.md](https://github.com/user-attachments/files/32999985/README.md)
# Ultimate Converter

A drag-and-drop image & video converter. Drop in files, pick a format, hit
Convert. Once it's built, it's **one .exe file** — no installer, no Python,
no separate ffmpeg install for the person using it.

## What it does

- **Drag & drop** one or many images/videos at once (or use "Add Files…" / "Add Folder…")
- **Images →** PNG, JPEG, WEBP, AVIF, BMP, TIFF, GIF, ICO, or **PDF**
- **Videos →** MP4, MKV, WEBM, MOV, GIF, **AVIF** (animated), or extract MP3/WAV audio
- Reads AVIF and HEIC/HEIC (iPhone photos) as input too
- **Lossless or compressed**, with a quality slider, for every format that supports it
- Combine multiple images into a single PDF (lossless or compressed)
- Batch processing with a progress bar, per-file status, and a log
- Everything runs locally — nothing is uploaded anywhere

## Important: what "building" means here

I can't hand you a compiled Windows `.exe` directly — I don't have a Windows
machine to compile on. What I've given you instead is the **complete, working
source code** plus a script that turns it into that single `.exe` in about
2–5 minutes on your own PC. You do this build **once**. After that, the
`.exe` it produces is the "just double-click it" file you wanted — you (or
anyone else) never needs Python or ffmpeg installed to use it.

## Files in this folder

| File | Purpose |
|---|---|
| `main.py` | The GUI (window, drag & drop, buttons) |
| `converter_engine.py` | The actual conversion logic (tested and working) |
| `requirements.txt` | Python packages needed to build |
| `build.bat` | One-click build script |
| `icon.ico` | App icon |
| `README.md` | This file |

## How to build your .exe (one-time setup)

**1. Install Python** (skip if you already have it)
Download from [python.org](https://www.python.org/downloads/) — get 3.10 or
newer. During install, **tick "Add python.exe to PATH"**.

**2. Get ffmpeg** (the engine that does video conversion)
1. Go to <https://www.gyan.dev/ffmpeg/builds/>
2. Under the **"release full"** section, download `ffmpeg-release-full.7z`
   (use "full", not "essentials" — full is the one that includes AV1/AVIF
   encoding, which is what powers video → AVIF conversion)
3. Extract it (Windows 11 24H2+ can open `.7z` natively; otherwise grab the
   free [7-Zip](https://www.7-zip.org/))
4. Inside the extracted folder, find `bin\ffmpeg.exe`
5. Copy just that one file into this project folder, next to `build.bat`

**3. Run the build**
Double-click `build.bat`. It will install the needed Python packages and
then build the app. First run takes a few minutes (mostly downloading
packages); the actual build itself is fast.

**4. Done**
Your app is at `dist\UltimateConverter.exe`. Move/copy that single file
anywhere — desktop, USB stick, another PC — and double-click it. That's the
only file you need from now on.

## Using the app

1. Drag image/video files (or a whole folder) onto the window
2. Pick the output format from the dropdown on the right
3. Check **Lossless** for zero quality loss, or drag the **Quality** slider
   for a smaller file (higher = better quality, bigger file)
4. Choose where converted files go — by default they land in a `converted`
   subfolder next to each source file, or pick one folder for everything
5. Hit **Convert All** — watch progress per file, cancel any time
6. Click **Open output folder** when it's done

Converting to PDF from multiple images offers a "combine into one PDF"
checkbox — turn it off to get one PDF per image instead.

## Notes & honest caveats

- **AV1/AVIF video encoding is slow.** It's one of the most CPU-intensive
  codecs around — a long clip can take a while even on a fast machine.
  Regular video formats (MP4/WEBM) convert much faster. This is a hardware
  reality, not a bug in the app.
- **JPEG has no true lossless mode.** Checking "Lossless" for a JPEG target
  just saves at maximum quality (100) — noted in the log when it happens.
- **The .exe will be large** (100–250 MB) because it bundles the "full"
  ffmpeg build inside it. If you don't need video → AVIF and want a smaller
  app, use ffmpeg's "essentials" build instead — everything else still works.
- **Antivirus / SmartScreen may flag it.** This is a very common false
  positive for PyInstaller-built exe's (they're unsigned and self-extracting,
  which some AV heuristics dislike), not a sign of anything actually wrong.
  If it bothers you, code-signing the exe (needs a paid certificate) removes
  the warning — not something this build script does automatically.
- Nothing here uploads your files anywhere; every conversion happens on your
  machine via the bundled ffmpeg and Pillow.

## Troubleshooting

- **"ffmpeg was not found"** in the app's log → `ffmpeg.exe` wasn't in the
  project folder when you built, or wasn't bundled correctly. Re-check step 2
  and rebuild.
- **Build fails on `pip install`** → make sure you ticked "Add to PATH" when
  installing Python, then open a *new* command prompt / re-double-click
  `build.bat` (PATH changes don't apply to already-open windows).
- **Drag & drop doesn't do anything** → very rare, but if `tkinterdnd2`
  failed to bundle, the "Add Files…" / "Add Folder…" buttons still work
  exactly the same as drag & drop.
- **Want to tweak anything?** It's plain, commented Python — `converter_engine.py`
  is completely separate from the GUI, so codec settings, quality defaults, or
  supported formats are all easy to find and change, then just re-run `build.bat`.
