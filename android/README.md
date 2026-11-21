# Ultimate Converter for Android

Pick photos, videos and PDFs, choose a format, tap **Convert**. Everything happens on the
phone — nothing is uploaded anywhere. Built with big touch targets (48 dp+ buttons, one
screen, one thumb).

## What it does

| You add | You can convert to |
|---|---|
| Photos (JPEG, PNG, WEBP, GIF, BMP, HEIC, AVIF) | PNG, JPEG, WEBP, **AVIF**, **GIF**, **TIFF**, BMP, **ICO**, or PDF |
| PDFs | one picture per page — PNG, JPEG, WEBP, AVIF, GIF, TIFF, BMP (150 / 200 / 300 DPI) |
| Videos | MP4, **MOV**, **animated GIF**, or audio only (M4A, **WAV**) |
| Sound files (MP3, M4A, WAV, OGG, FLAC…) | **M4A** or **WAV** |

\* animated GIFs use their first frame. \*\* AVIF/HEIC need a recent Android (HEIC 9+, AVIF 12+).

- **Lossless or compressed** with a quality slider (JPEG, WEBP, PDF, MP4)
- **Several photos into one multi-page PDF**: choose the file name and page size
  (match each image / A4 / Letter); order the pages with the ▲ ▼ buttons on each card
  (shown only while PDF + "combine" is chosen)
- **Rotate** any photo with ↺ / ↻ (phone photos flagged as sideways are straightened automatically)
- **Preview**: thumbnails in the list, tap a card for a big preview
- **Share to the app** from Gallery / Files / any app (Share → Ultimate Converter)
- **Save to**: your normal Pictures / Movies / Music / Download folders (in an
  `UltimateConverter` folder), or any folder you choose. Originals are never changed.
- Remembers your last format, quality and PDF choices

## Differences from the Windows version

- No "Overwrite originals": Android doesn't let apps silently replace your files, so results
  are always saved as new files next to your library.
- No AVIF / TIFF / ICO / GIF *output*, and no video → GIF or WEBM: Android has no built-in
  encoders for them. (Say so if you want them added with an extra library.)
- Video quality is a bitrate setting, not truly "lossless".
- Conversion pauses if you leave the app — the screen is kept awake while it runs.

## Getting the APK

The Android project lives in the `android/` folder of the same repository as the Windows app.
Every push to `main` builds both, then a "Publish downloads" step puts them on the repo's
**Releases** page ("Ultimate Converter - latest downloads") as `UltimateConverter.exe` and
`UltimateConverter.apk`. Copy the APK to your phone, tap it, and allow "Install unknown apps"
for your browser/Files app when asked. Play Protect may show a one-time "unrecognised app"
notice — the APK is signed with a standard debug key, not a Play Store key.

## Project layout

```
android/app/src/main/java/com/ultimateconverter/app/
  MainActivity.kt          entry point + "share to app" handling
  Ui.kt                    the touch screens (Jetpack Compose)
  ConverterViewModel.kt    list, options, remembered settings, start/cancel
  Converter.kt             runs a batch, one file at a time
  ImageEngine.kt           PNG/JPEG/WEBP/BMP encoding, photos -> PDF
  VideoEngine.kt           video -> MP4 / audio (Media3 Transformer)
  MediaIO.kt               reading photos, PDFs, video frames, thumbnails
  Output.kt                saving to Pictures/Movies/... or a chosen folder
  PureLogic.kt             sorting, file names, BMP writer (unit-testable)
  Models.kt                data classes and format table
```
