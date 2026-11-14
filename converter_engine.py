"""
Ultimate Converter - conversion engine.

All the actual file-conversion logic lives here, completely independent of
the GUI (main.py). This makes it possible to test / reuse / extend the
converter without touching any Tkinter code.
"""
from __future__ import annotations

import io
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Optional

from PIL import Image, ImageOps, ImageSequence

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

try:
    from send2trash import send2trash as _send2trash  # moves files to the Recycle Bin
    HAS_TRASH = True
except ImportError:
    _send2trash = None
    HAS_TRASH = False

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


def natural_key(path: str) -> list:
    """Sort key that orders 'scan2.jpg' before 'scan10.jpg' (and ignores case),
    so a batch dropped in one go lands in the order a person expects."""
    parts = re.split(r"(\d+)", path.lower())
    # re.split with a capture group alternates text, number, text, ... so
    # odd positions are always digits and the lists compare cleanly.
    return [int(t) if i % 2 else t for i, t in enumerate(parts)]


_BAD_FILENAME_CHARS = re.compile(r'[<>:"/\\|?*\x00-\x1f]')
_RESERVED_NAMES = {"CON", "PRN", "AUX", "NUL",
                   *(f"COM{i}" for i in range(1, 10)), *(f"LPT{i}" for i in range(1, 10))}


def safe_filename(name: str, default: str = "combined") -> str:
    """Turn whatever the user typed into a safe Windows file name, without
    extension. Falls back to `default` if nothing usable is left."""
    name = _BAD_FILENAME_CHARS.sub("", name or "").strip()
    if name.lower().endswith(".pdf"):
        name = name[:-4]
    name = name.strip().strip(".").strip()
    if name.upper() in _RESERVED_NAMES:
        name += "_"
    return name[:120] or default


# --------------------------------------------------------------------------
# Remembered settings (last format, quality, etc.)
# --------------------------------------------------------------------------
def settings_path() -> Path:
    base = os.environ.get("APPDATA") or os.path.join(os.path.expanduser("~"), ".config")
    return Path(base) / "UltimateConverter" / "settings.json"


def load_settings() -> dict:
    """Never raises: a missing or damaged file just means 'use the defaults'."""
    try:
        data = json.loads(settings_path().read_text(encoding="utf-8"))
        return data if isinstance(data, dict) else {}
    except Exception:
        return {}


def save_settings(data: dict) -> None:
    """Best effort - failing to remember a setting must never break the app."""
    try:
        path = settings_path()
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(".tmp")
        tmp.write_text(json.dumps(data, indent=2), encoding="utf-8")
        tmp.replace(path)
    except Exception:
        pass


def finalize_overwrite(temp_path: str, src_path: str, dst_ext: str, recycle: bool = False) -> str:
    """After a conversion has been written safely to temp_path, replace the
    original src_path with it. The original is only ever removed once the
    full converted file already exists on disk - a failed or cancelled
    conversion never touches the original.

    With recycle=True the original goes to the Recycle Bin (so it can be
    restored) instead of being deleted for good. If that isn't possible this
    raises BEFORE touching anything - it never quietly falls back to a
    permanent delete the person didn't choose.

    If dst_ext differs from the source's extension, the old file is replaced
    by one with the same base name and the new extension. Returns the final
    path as a string."""
    src = Path(src_path)
    final = src.with_suffix(f".{dst_ext}")
    if final != src and final.exists():
        final = unique_path(final)
    if recycle:
        if not HAS_TRASH or _send2trash is None:
            raise RuntimeError("Recycle Bin support isn't available in this build.")
        if src.exists():
            try:
                _send2trash(str(src))
            except Exception as exc:
                raise RuntimeError(
                    f"Couldn't move the original to the Recycle Bin ({exc}). It was left untouched."
                ) from exc
    else:
        try:
            src.unlink()
        except FileNotFoundError:
            pass
    Path(temp_path).replace(final)
    return str(final)


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


# Pillow's Transpose.ROTATE_* constants turn an image COUNTER-clockwise, but
# people think in clockwise terms ("rotate right"), so this maps a clockwise
# angle onto the matching counter-clockwise constant.
_CW_TO_TRANSPOSE = {
    90: Image.Transpose.ROTATE_270,
    180: Image.Transpose.ROTATE_180,
    270: Image.Transpose.ROTATE_90,
}


def rotate_image(im: Image.Image, degrees_cw: int) -> Image.Image:
    """Rotate clockwise by a multiple of 90 degrees. This is a pure pixel
    shuffle (no resampling), so it costs no quality. Returns `im` itself,
    untouched, when no rotation is needed."""
    degrees_cw %= 360
    if degrees_cw == 0:
        return im
    return im.transpose(_CW_TO_TRANSPOSE[degrees_cw])


def convert_image(
    src: str,
    dst: str,
    target: str,
    quality: int = 85,
    lossless: bool = False,
    log: Optional[Callable[[str], None]] = None,
    rotation: int = 0,
) -> None:
    """Convert a single image file to another image format (not PDF -
    use images_to_pdf for that). `rotation` is extra clockwise degrees
    (0/90/180/270) applied on top of the image's own orientation."""
    target = target.upper()
    log = log or (lambda msg: None)

    with Image.open(src) as im:
        is_animated = getattr(im, "is_animated", False) and getattr(im, "n_frames", 1) > 1

        if target in ANIMATABLE_IMAGE_TARGETS and is_animated:
            frames = [rotate_image(f.convert("RGBA"), rotation) for f in ImageSequence.Iterator(im)]
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
            # Phones and cameras often store photos "sideways" plus an EXIF
            # tag saying how to turn them. Our output doesn't carry that tag,
            # so bake the turn into the pixels first (exif_transpose also
            # strips the tag, so nothing gets rotated twice), then apply
            # whatever extra rotation the user asked for.
            first = rotate_image(ImageOps.exif_transpose(im), rotation)
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


def _image_payload(path: str, lossless: bool, quality: int, rotation: int = 0) -> bytes | str:
    """Return something img2pdf.convert() can embed for one source image."""
    ext = Path(path).suffix.lower()
    if lossless and rotation % 360 == 0 and ext in (".jpg", ".jpeg", ".png", ".tif", ".tiff"):
        # img2pdf can wrap these formats byte-for-byte with zero recompression
        # (it also honors the file's own EXIF orientation). A user rotation
        # can't be applied to the raw bytes, so those images fall through
        # to the re-encode path below.
        return path
    with Image.open(path) as opened:
        im = rotate_image(ImageOps.exif_transpose(opened), rotation)
        buf = io.BytesIO()
        if lossless:
            im.convert("RGBA" if "A" in im.mode else "RGB").save(buf, format="PNG", optimize=True)
        else:
            _flatten_alpha(im).save(buf, format="JPEG", quality=max(1, min(100, quality)), optimize=True)
        return buf.getvalue()


# Page sizes offered for PDF output, in millimetres. None means every page is
# sized to match its own image (the original behaviour).
PDF_PAGE_SIZES = {"Match each image": None, "A4": (210.0, 297.0), "Letter": (215.9, 279.4)}
PDF_PAGE_DEFAULT = "Match each image"
PDF_PAGE_MARGIN_MM = 10


def images_to_pdf(
    paths: list[str],
    dst: str,
    lossless: bool = True,
    quality: int = 85,
    rotations: Optional[list[int]] = None,
    page_size: Optional[str] = None,
) -> None:
    """Combine one or more images into a single PDF, one image per page, in
    the order given. `rotations`, if given, holds one clockwise angle
    (0/90/180/270) per image. `page_size` is a key of PDF_PAGE_SIZES: for a
    fixed size every image is fitted inside the page with a margin (the page
    turns landscape for a wide picture), otherwise each page matches its image."""
    payloads = [
        _image_payload(p, lossless, quality, rotations[i] if rotations else 0)
        for i, p in enumerate(paths)
    ]
    kwargs = {}
    dims = PDF_PAGE_SIZES.get(page_size or PDF_PAGE_DEFAULT)
    if dims:
        margin = img2pdf.mm_to_pt(PDF_PAGE_MARGIN_MM)
        kwargs["layout_fun"] = img2pdf.get_layout_fun(
            (img2pdf.mm_to_pt(dims[0]), img2pdf.mm_to_pt(dims[1])),
            border=(margin, margin),
            fit=img2pdf.FitMode.into,
            auto_orient=True,
        )
    with open(dst, "wb") as f:
        f.write(img2pdf.convert(payloads, **kwargs))


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


# --------------------------------------------------------------------------
# Previews - a small in-memory thumbnail plus basic info, so the GUI can
# show "what you're about to convert" before you commit to it.
# --------------------------------------------------------------------------
_RESOLUTION_RE = re.compile(r",\s*(\d{2,5})x(\d{2,5})[ ,]")


@dataclass
class PreviewInfo:
    image: Optional[Image.Image] = None  # small RGB thumbnail, or None if unavailable
    width: Optional[int] = None          # the SOURCE file's real dimensions,
    height: Optional[int] = None         # not the (possibly smaller) thumbnail's
    duration: Optional[float] = None     # seconds; video only


def generate_preview(
    src: str,
    kind: str,
    ffmpeg_path: Optional[str] = None,
    max_size: int = 220,
) -> PreviewInfo:
    """Best-effort preview. Never raises - on any failure (corrupt file,
    unsupported codec, missing ffmpeg) it just returns an empty PreviewInfo
    so the GUI can show "no preview available" instead of crashing."""
    try:
        if kind == "image":
            with Image.open(src) as opened:
                # Show it the way a normal photo viewer would (EXIF turn applied)
                im = ImageOps.exif_transpose(opened)
                w, h = im.size
                thumb = im.convert("RGBA" if "A" in im.mode else "RGB")
                thumb.thumbnail((max_size, max_size), Image.LANCZOS)
                return PreviewInfo(image=thumb.copy(), width=w, height=h)

        elif kind == "video" and ffmpeg_path:
            with tempfile.TemporaryDirectory() as td:
                out_path = os.path.join(td, "thumb.jpg")
                # One ffmpeg call does double duty: its stderr/stdout carries the
                # input's resolution & duration (same as every other ffmpeg run),
                # and -vframes 1 grabs a representative frame half a second in.
                proc = subprocess.run(
                    [
                        ffmpeg_path, "-y", "-ss", "0.5", "-i", src, "-vframes", "1",
                        "-vf", f"scale='min({max_size},iw)':-1", out_path,
                    ],
                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                    timeout=15, creationflags=_NO_WINDOW,
                )
                out_text = proc.stdout or ""
                dur = None
                m = _DURATION_RE.search(out_text)
                if m:
                    dur = _hms(*m.groups())
                w = h = None
                rm = _RESOLUTION_RE.search(out_text)
                if rm:
                    w, h = int(rm.group(1)), int(rm.group(2))

                img = None
                if os.path.exists(out_path):
                    with Image.open(out_path) as im:
                        im = im.convert("RGB")
                        im.load()
                        img = im.copy()
                return PreviewInfo(image=img, width=w, height=h, duration=dur)
    except Exception:
        pass
    return PreviewInfo()
