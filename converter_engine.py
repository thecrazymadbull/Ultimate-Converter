"""
Ultimate Converter - conversion engine.

All the actual file-conversion logic lives here, completely independent of
the GUI (main.py). This makes it possible to test / reuse / extend the
converter without touching any Tkinter code.
"""
from __future__ import annotations

import io
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Callable, Optional

from PIL import Image, ImageSequence

# --------------------------------------------------------------------------
# Optional format plugins. The app still works without them - it just won't
# offer AVIF / HEIC support until they're installed (see requirements.txt).
# --------------------------------------------------------------------------
try:
    import pillow_avif  # noqa: F401  (importing this registers AVIF with Pillow)
    HAS_AVIF = True
except ImportError:
    HAS_AVIF = False

try:
    import pillow_heif
    pillow_heif.register_heif_opener()
    HAS_HEIF = True
except ImportError:
    HAS_HEIF = False

import img2pdf

# --------------------------------------------------------------------------
# Supported formats
# --------------------------------------------------------------------------
IMAGE_EXTS = {".png", ".jpg", ".jpeg", ".bmp", ".tif", ".tiff", ".gif", ".webp", ".ico"}
if HAS_AVIF:
    IMAGE_EXTS.add(".avif")
if HAS_HEIF:
    IMAGE_EXTS.update({".heic", ".heif"})

VIDEO_EXTS = {".mp4", ".mov", ".mkv", ".avi", ".webm", ".m4v", ".flv", ".wmv", ".mpg", ".mpeg", ".ts"}

IMAGE_TARGETS = ["PNG", "JPEG", "WEBP", "BMP", "TIFF", "GIF", "ICO", "PDF"]
if HAS_AVIF:
    IMAGE_TARGETS.insert(3, "AVIF")

VIDEO_TARGETS = ["MP4", "MKV", "WEBM", "MOV", "GIF", "AVIF", "MP3", "WAV"]

# Formats that can hold an animation / multiple frames
ANIMATABLE_IMAGE_TARGETS = {"GIF", "WEBP", "AVIF"}

# What a target format actually is, for routing
TARGET_KIND = {}
for _f in IMAGE_TARGETS:
    TARGET_KIND[_f] = "image"
for _f in VIDEO_TARGETS:
    TARGET_KIND.setdefault(_f, "video")
TARGET_KIND["PDF"] = "pdf"

ALL_TARGETS = list(dict.fromkeys(IMAGE_TARGETS + VIDEO_TARGETS))


def classify(path: str) -> str:
    """Return 'image', 'video' or 'unknown' based on file extension."""
    ext = Path(path).suffix.lower()
    if ext in IMAGE_EXTS:
        return "image"
    if ext in VIDEO_EXTS:
        return "video"
    return "unknown"


def valid_targets_for(kind: str) -> list[str]:
    if kind == "image":
        return IMAGE_TARGETS
    if kind == "video":
        return VIDEO_TARGETS
    return ALL_TARGETS


# --------------------------------------------------------------------------
# ffmpeg discovery - looks next to the .exe first, then falls back to PATH
# --------------------------------------------------------------------------
def _bundled_dir() -> Optional[Path]:
    if getattr(sys, "frozen", False):
        return Path(getattr(sys, "_MEIPASS", Path(sys.executable).parent))
    return None


def find_ffmpeg() -> Optional[str]:
    names = ["ffmpeg.exe", "ffmpeg"]
    search_dirs = []
    bd = _bundled_dir()
    if bd:
        search_dirs.append(bd)
    try:
        exe_dir = Path(sys.argv[0]).resolve().parent
        search_dirs.append(exe_dir)
    except Exception:
        pass
    search_dirs.append(Path.cwd())
    for d in search_dirs:
        for name in names:
            candidate = d / name
            if candidate.is_file():
                return str(candidate)
    return shutil.which("ffmpeg")


# --------------------------------------------------------------------------
# Helpers
# --------------------------------------------------------------------------
def unique_path(path: Path) -> Path:
    """If path exists, append ' (2)', ' (3)', ... before the extension."""
    if not path.exists():
        return path
    stem, suffix, parent = path.stem, path.suffix, path.parent
    i = 2
    while True:
        candidate = parent / f"{stem} ({i}){suffix}"
        if not candidate.exists():
            return candidate
        i += 1


def _quality_to_crf(quality: int, lo: int, hi: int) -> int:
    """Map a 1-100 'quality' slider (100 = best) onto a codec's CRF range
    (where LOWER numbers mean better quality)."""
    quality = max(1, min(100, quality))
    return round(hi - (quality / 100.0) * (hi - lo))


# --------------------------------------------------------------------------
# Image conversion
# --------------------------------------------------------------------------
def _flatten_alpha(im: Image.Image, bg=(255, 255, 255)) -> Image.Image:
    if im.mode in ("RGBA", "LA") or (im.mode == "P" and "transparency" in im.info):
        base = Image.new("RGB", im.size, bg)
        rgba = im.convert("RGBA")
        base.paste(rgba, mask=rgba.split()[-1])
        return base
    return im.convert("RGB")


def convert_image(
    src: str,
    dst: str,
    target: str,
    quality: int = 85,
    lossless: bool = False,
    log: Optional[Callable[[str], None]] = None,
) -> None:
    """Convert a single image file to another image format (not PDF -
    use images_to_pdf for that)."""
    target = target.upper()
    log = log or (lambda msg: None)

    with Image.open(src) as im:
        is_animated = getattr(im, "is_animated", False) and getattr(im, "n_frames", 1) > 1

        if target in ANIMATABLE_IMAGE_TARGETS and is_animated:
            frames = [f.convert("RGBA") for f in ImageSequence.Iterator(im)]
            durations = im.info.get("duration", 100)
            first, rest = frames[0], frames[1:]
            save_kwargs = {
                "save_all": True,
                "append_images": rest,
                "loop": im.info.get("loop", 0),
                "duration": durations,
            }
        else:
            if is_animated:
                log("Source has multiple frames; only the first frame is kept for this format.")
            first = im.copy()
            save_kwargs = {}

        if target == "JPEG":
            first = _flatten_alpha(first)
            save_kwargs["quality"] = 100 if lossless else max(1, min(100, quality))
            save_kwargs["optimize"] = True
            if lossless:
                log("JPEG has no true lossless mode - saved at maximum quality instead.")

        elif target == "WEBP":
            save_kwargs["lossless"] = lossless
            save_kwargs["quality"] = 100 if lossless else max(1, min(100, quality))
            save_kwargs["method"] = 6

        elif target == "AVIF":
            if not HAS_AVIF:
                raise RuntimeError("AVIF support isn't installed (pillow-avif-plugin missing).")
            if lossless:
                save_kwargs["quality"] = -1
                save_kwargs["chroma"] = 444
            else:
                save_kwargs["quality"] = max(1, min(100, quality))

        elif target == "PNG":
            save_kwargs["optimize"] = True

        elif target == "ICO":
            first = first.convert("RGBA")
            w, h = first.size
            max_side = max(w, h)
            sizes = [s for s in (16, 32, 48, 64, 128, 256) if s <= max_side] or [max_side]
            save_kwargs["sizes"] = [(s, s) for s in sizes]

        elif target == "BMP":
            first = _flatten_alpha(first)

        elif target == "TIFF":
            save_kwargs["compression"] = "tiff_lzw" if not lossless else None

        elif target == "GIF" and "save_all" not in save_kwargs:
            first = first.convert("P", palette=Image.ADAPTIVE)

        first.save(dst, format=target, **save_kwargs)


def _image_payload(path: str, lossless: bool, quality: int) -> bytes | str:
    """Return something img2pdf.convert() can embed for one source image."""
    ext = Path(path).suffix.lower()
    if lossless and ext in (".jpg", ".jpeg", ".png", ".tif", ".tiff"):
        # img2pdf can wrap these formats byte-for-byte with zero recompression
        return path
    with Image.open(path) as im:
        buf = io.BytesIO()
        if lossless:
            im.convert("RGBA" if "A" in im.mode else "RGB").save(buf, format="PNG", optimize=True)
        else:
            _flatten_alpha(im).save(buf, format="JPEG", quality=max(1, min(100, quality)), optimize=True)
        return buf.getvalue()


def images_to_pdf(
    paths: list[str],
    dst: str,
    lossless: bool = True,
    quality: int = 85,
) -> None:
    """Combine one or more images into a single PDF file."""
    payloads = [_image_payload(p, lossless, quality) for p in paths]
    with open(dst, "wb") as f:
        f.write(img2pdf.convert(payloads))


# --------------------------------------------------------------------------
# Video conversion (shells out to ffmpeg)
# --------------------------------------------------------------------------
_DURATION_RE = re.compile(r"Duration:\s*(\d+):(\d+):(\d+\.\d+)")
_TIME_RE = re.compile(r"time=(\d+):(\d+):(\d+\.\d+)")

_NO_WINDOW = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0


def _hms(h, m, s) -> float:
    return int(h) * 3600 + int(m) * 60 + float(s)


def probe_duration(ffmpeg_path: str, src: str) -> Optional[float]:
    try:
        proc = subprocess.run(
            [ffmpeg_path, "-i", src],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            timeout=20,
            creationflags=_NO_WINDOW,
        )
        m = _DURATION_RE.search(proc.stdout or "")
        if m:
            return _hms(*m.groups())
    except Exception:
        pass
    return None


_H264_LIKE = {
    "MP4": "libx264",
    "MKV": "libx264",
    "MOV": "libx264",
}


def build_ffmpeg_command(
    ffmpeg_path: str,
    src: str,
    dst: str,
    target: str,
    quality: int = 80,
    lossless: bool = False,
) -> list[str]:
    target = target.upper()
    cmd = [ffmpeg_path, "-y", "-i", src]

    if target in ("MP3", "WAV"):
        cmd += ["-vn"]
        if target == "MP3":
            cmd += ["-codec:a", "libmp3lame", "-q:a", "2"]
        else:
            cmd += ["-codec:a", "pcm_s16le"]

    elif target == "GIF":
        vf = (
            "fps=12,scale=480:-1:flags=lanczos,split[s0][s1];"
            "[s0]palettegen=stats_mode=diff[p];[s1][p]paletteuse=dither=bayer"
        )
        cmd += ["-filter_complex", vf, "-loop", "0"]

    elif target == "AVIF":
        crf = 0 if lossless else _quality_to_crf(quality, 0, 63)
        cmd += [
            "-c:v", "libaom-av1",
            "-crf", str(crf), "-b:v", "0",
            "-cpu-used", "6",
            "-pix_fmt", "yuv420p",
            "-an",
        ]

    elif target == "WEBM":
        cmd += ["-c:v", "libvpx-vp9", "-deadline", "good", "-cpu-used", "4"]
        if lossless:
            cmd += ["-lossless", "1"]
        else:
            cmd += ["-crf", str(_quality_to_crf(quality, 0, 63)), "-b:v", "0"]
        cmd += ["-c:a", "libopus"]

    elif target in _H264_LIKE:
        cmd += ["-c:v", _H264_LIKE[target], "-preset", "veryfast"]
        cmd += ["-crf", "0" if lossless else str(_quality_to_crf(quality, 0, 51))]
        cmd += ["-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k"]

    else:
        raise ValueError(f"Unsupported video target: {target}")

    cmd += [dst]
    return cmd


class ConversionCancelled(Exception):
    pass


def convert_video(
    ffmpeg_path: str,
    src: str,
    dst: str,
    target: str,
    quality: int = 80,
    lossless: bool = False,
    progress_cb: Optional[Callable[[float], None]] = None,
    is_cancelled: Optional[Callable[[], bool]] = None,
) -> None:
    if not ffmpeg_path:
        raise RuntimeError(
            "ffmpeg.exe was not found. Place it next to the app, or rebuild "
            "with it bundled (see README.md)."
        )

    duration = probe_duration(ffmpeg_path, src)
    cmd = build_ffmpeg_command(ffmpeg_path, src, dst, target, quality, lossless)

    process = subprocess.Popen(
        cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
        creationflags=_NO_WINDOW,
    )

    tail: list[str] = []
    try:
        for line in process.stdout:  # type: ignore[union-attr]
            tail.append(line)
            if len(tail) > 40:
                tail.pop(0)

            if is_cancelled and is_cancelled():
                process.terminate()
                raise ConversionCancelled()

            if progress_cb and duration:
                m = _TIME_RE.search(line)
                if m:
                    current = _hms(*m.groups())
                    progress_cb(min(1.0, current / duration))
    finally:
        process.wait()

    if process.returncode != 0:
        raise RuntimeError(
            f"ffmpeg exited with code {process.returncode}:\n" + "".join(tail[-15:])
        )
    if progress_cb:
        progress_cb(1.0)
