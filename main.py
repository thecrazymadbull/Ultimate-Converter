"""
Ultimate Converter
Drag-and-drop image/video converter. See README.md for build instructions.
"""
from __future__ import annotations

import os
import queue
import sys
import threading
import traceback
from dataclasses import dataclass
from pathlib import Path

import tkinter as tk
from tkinter import ttk, filedialog, messagebox
from PIL import Image, ImageTk

import converter_engine as ce

# tkinterdnd2 gives us real OS-level drag & drop. If it isn't installed the
# app still runs fine - people just use the "Add Files..." button instead.
try:
    from tkinterdnd2 import DND_FILES, TkinterDnD
    HAS_DND = True
except ImportError:
    HAS_DND = False

APP_TITLE = "Ultimate Converter"
BG = "#1e1f26"
PANEL = "#262833"
ACCENT = "#5b8cff"
ACCENT_DARK = "#4470e0"
TEXT = "#eceef5"
SUBTEXT = "#9297ab"
GOOD = "#54c98a"
BAD = "#e5636b"

EXT_MAP = {
    "JPEG": "jpg", "TIFF": "tiff", "PDF": "pdf", "PNG": "png", "WEBP": "webp",
    "BMP": "bmp", "GIF": "gif", "ICO": "ico", "AVIF": "avif", "MP4": "mp4",
    "MKV": "mkv", "WEBM": "webm", "MOV": "mov", "MP3": "mp3", "WAV": "wav",
}

NO_QUALITY_TARGETS = {"BMP", "ICO", "MP3", "WAV", "PNG", "GIF"}

PREVIEW_CACHE_SIZE = 220   # thumbnail resolution kept in memory per file
ROW_ICON_SIZE = 28         # small icon shown in the file list
PREVIEW_BOX = (200, 128)   # the bigger preview panel's fixed display size


def _compose_boxed(img: Image.Image, box_w: int, box_h: int, bg=(18, 19, 26)) -> Image.Image:
    """Center img inside a fixed box_w x box_h canvas, preserving aspect
    ratio, so the preview panel is a consistent size no matter the source's
    shape (portrait photo, widescreen video, square icon, ...)."""
    canvas = Image.new("RGB", (box_w, box_h), bg)
    thumb = img.convert("RGB").copy()
    thumb.thumbnail((box_w, box_h), Image.LANCZOS)
    x = (box_w - thumb.width) // 2
    y = (box_h - thumb.height) // 2
    canvas.paste(thumb, (x, y))
    return canvas


def resource_path(name: str) -> str:
    """Find a bundled resource (works both as a plain script and as a
    PyInstaller --onefile exe)."""
    base = getattr(sys, "_MEIPASS", None)
    if not base:
        base = os.path.dirname(os.path.abspath(sys.argv[0]))
    return os.path.join(base, name)


def human_size(num_bytes: int) -> str:
    size = float(num_bytes)
    for unit in ("B", "KB", "MB", "GB"):
        if size < 1024 or unit == "GB":
            return f"{size:.0f} {unit}" if unit == "B" else f"{size:.1f} {unit}"
        size /= 1024
    return f"{size:.1f} GB"


@dataclass
class Job:
    src: str
    kind: str
    row_id: str = ""
    status: str = "Queued"
    out_path: str = ""


class ConverterApp:
    def __init__(self, root):
        self.root = root
        self.root.title(APP_TITLE)
        self.root.geometry("900x700")
        self.root.minsize(780, 600)
        self.root.configure(bg=BG)

        self.jobs: dict[str, Job] = {}
        self.msg_queue: "queue.Queue[tuple]" = queue.Queue()
        self.cancel_event = threading.Event()
        self.worker_thread: threading.Thread | None = None
        self.custom_out_dir: str | None = None
        self.ffmpeg_path = ce.find_ffmpeg()

        # Preview state: preview_info holds the real PreviewInfo (thumbnail +
        # dimensions/duration) per row; row_photo/preview_photo keep Tk
        # PhotoImage objects alive (Tkinter drops images with no live Python
        # reference, even while they're still on screen).
        self.preview_info: dict[str, ce.PreviewInfo] = {}
        self.row_photo: dict[str, ImageTk.PhotoImage] = {}
        self.preview_photo: ImageTk.PhotoImage | None = None
        self.thumb_queue: "queue.Queue[tuple[str, str, str]]" = queue.Queue()
        threading.Thread(target=self._thumbnail_worker, daemon=True).start()

        self._build_style()
        self._build_layout()
        self._refresh_target_options()
        self._poll_queue()

        if not self.ffmpeg_path:
            self._log(
                "ffmpeg was not found - video conversions (and video-to-AVIF) "
                "won't work until it's placed next to this app. Image conversions "
                "work fine without it."
            )

    # ------------------------------------------------------------------ UI
    def _build_style(self):
        style = ttk.Style(self.root)
        try:
            style.theme_use("clam")
        except tk.TclError:
            pass
        style.configure("TFrame", background=BG)
        style.configure("Panel.TFrame", background=PANEL)
        style.configure("TLabel", background=BG, foreground=TEXT, font=("Segoe UI", 10))
        style.configure("Panel.TLabel", background=PANEL, foreground=TEXT, font=("Segoe UI", 10))
        style.configure("Sub.TLabel", background=PANEL, foreground=SUBTEXT, font=("Segoe UI", 9))
        style.configure("Title.TLabel", background=BG, foreground=TEXT, font=("Segoe UI", 15, "bold"))
        style.configure("TCheckbutton", background=PANEL, foreground=TEXT, font=("Segoe UI", 10))
        style.map("TCheckbutton", background=[("active", PANEL)])
        style.configure("TRadiobutton", background=PANEL, foreground=TEXT, font=("Segoe UI", 10))
        style.map("TRadiobutton", background=[("active", PANEL)])
        style.configure("TCombobox", fieldbackground="white", background="white")
        style.configure(
            "Accent.TButton", background=ACCENT, foreground="white",
            font=("Segoe UI", 11, "bold"), padding=8, borderwidth=0,
        )
        style.map("Accent.TButton", background=[("active", ACCENT_DARK), ("disabled", "#3a3d4c")])
        style.configure(
            "Flat.TButton", background=PANEL, foreground=TEXT,
            font=("Segoe UI", 10), padding=6, borderwidth=0,
        )
        style.map("Flat.TButton", background=[("active", "#33364433")])
        style.configure("TProgressbar", background=ACCENT, troughcolor=PANEL, borderwidth=0)
        style.configure(
            "Treeview", background="#12131a", fieldbackground="#12131a",
            foreground=TEXT, rowheight=36, borderwidth=0, font=("Segoe UI", 9),
        )
        style.configure("Treeview.Heading", background=PANEL, foreground=SUBTEXT, borderwidth=0)
        style.map("Treeview", background=[("selected", "#33415e")])
        style.configure("Horizontal.TScale", background=PANEL)

    def _build_layout(self):
        root = self.root
        root.grid_columnconfigure(0, weight=3)
        root.grid_columnconfigure(1, weight=2)
        root.grid_rowconfigure(1, weight=1)

        header = ttk.Frame(root, style="TFrame")
        header.grid(row=0, column=0, columnspan=2, sticky="ew", padx=16, pady=(14, 6))
        ttk.Label(header, text="🔁  Ultimate Converter", style="Title.TLabel").pack(side="left")

        left = ttk.Frame(root, style="TFrame")
        left.grid(row=1, column=0, sticky="nsew", padx=(16, 8), pady=8)
        left.grid_rowconfigure(1, weight=1)
        left.grid_columnconfigure(0, weight=1)

        self._build_dropzone(left)
        self._build_file_list(left)
        self._build_log(left)

        right = ttk.Frame(root, style="Panel.TFrame")
        right.grid(row=1, column=1, sticky="nsew", padx=(8, 16), pady=8)
        self._build_options(right)

        bottom = ttk.Frame(root, style="TFrame")
        bottom.grid(row=2, column=0, columnspan=2, sticky="ew", padx=16, pady=(4, 14))
        self._build_action_bar(bottom)

    def _build_dropzone(self, parent):
        zone = tk.Frame(parent, bg="#2a2d3a", highlightthickness=2,
                         highlightbackground="#3d4258", highlightcolor=ACCENT, bd=0)
        zone.grid(row=0, column=0, sticky="ew", pady=(0, 10))
        zone.grid_columnconfigure(0, weight=1)

        hint = "Drag & drop images or videos here" if HAS_DND else "Add images or videos to convert"
        label = tk.Label(zone, text=f"📥  {hint}", bg="#2a2d3a", fg=TEXT, font=("Segoe UI", 12), pady=22)
        label.grid(row=0, column=0, sticky="ew")

        btn_row = tk.Frame(zone, bg="#2a2d3a")
        btn_row.grid(row=1, column=0, pady=(0, 14))
        ttk.Button(btn_row, text="Add Files…", style="Flat.TButton", command=self.add_files_dialog).pack(side="left", padx=4)
        ttk.Button(btn_row, text="Add Folder…", style="Flat.TButton", command=self.add_folder_dialog).pack(side="left", padx=4)

        if HAS_DND:
            for widget in (zone, label):
                widget.drop_target_register(DND_FILES)
                widget.dnd_bind("<<Drop>>", self._on_drop)

    def _build_file_list(self, parent):
        wrap = ttk.Frame(parent, style="TFrame")
        wrap.grid(row=1, column=0, sticky="nsew", pady=(0, 10))
        wrap.grid_rowconfigure(0, weight=1)
        wrap.grid_columnconfigure(0, weight=1)

        columns = ("name", "type", "status")
        # show="tree headings" keeps the normally-hidden "#0" column visible -
        # that's the only column a ttk.Treeview can put a per-row image in,
        # which is what makes the thumbnails next to each filename possible.
        self.tree = ttk.Treeview(wrap, columns=columns, show="tree headings", selectmode="extended")
        self.tree.heading("#0", text="")
        self.tree.column("#0", width=46, minwidth=46, stretch=False, anchor="center")
        self.tree.heading("name", text="File")
        self.tree.heading("type", text="Type")
        self.tree.heading("status", text="Status")
        self.tree.column("name", width=300, anchor="w")
        self.tree.column("type", width=70, anchor="center")
        self.tree.column("status", width=140, anchor="w")
        self.tree.grid(row=0, column=0, sticky="nsew")
        self.tree.bind("<<TreeviewSelect>>", self._on_tree_select)

        scroll = ttk.Scrollbar(wrap, orient="vertical", command=self.tree.yview)
        self.tree.configure(yscrollcommand=scroll.set)
        scroll.grid(row=0, column=1, sticky="ns")

        if HAS_DND:
            self.tree.drop_target_register(DND_FILES)
            self.tree.dnd_bind("<<Drop>>", self._on_drop)

        row2 = ttk.Frame(parent, style="TFrame")
        row2.grid(row=2, column=0, sticky="ew")
        ttk.Button(row2, text="Remove selected", style="Flat.TButton", command=self.remove_selected).pack(side="left")
        ttk.Button(row2, text="Clear all", style="Flat.TButton", command=self.clear_all).pack(side="left", padx=6)
        self.queue_count_label = ttk.Label(row2, text="0 files queued", style="TLabel")
        self.queue_count_label.pack(side="right")

    def _build_log(self, parent):
        wrap = ttk.Frame(parent, style="TFrame")
        wrap.grid(row=3, column=0, sticky="ew", pady=(4, 0))
        wrap.grid_columnconfigure(0, weight=1)
        ttk.Label(wrap, text="Log", style="TLabel").grid(row=0, column=0, sticky="w")
        self.log_text = tk.Text(wrap, height=6, bg="#12131a", fg=SUBTEXT, insertbackground=TEXT,
                                 font=("Consolas", 9), relief="flat", wrap="word")
        self.log_text.grid(row=1, column=0, sticky="ew", pady=(2, 0))
        self.log_text.configure(state="disabled")

    def _build_options(self, parent):
        parent.grid_columnconfigure(0, weight=1)
        pad = {"padx": 16, "pady": (12, 4)}
        r = 0  # running row counter so inserting/reordering sections later is just editing, not renumbering everything

        # ---- Preview -----------------------------------------------------
        ttk.Label(parent, text="Preview", style="Panel.TLabel", font=("Segoe UI", 11, "bold")).grid(
            row=r, column=0, sticky="w", **pad); r += 1

        box = tk.Frame(parent, bg="#12131a", width=PREVIEW_BOX[0], height=PREVIEW_BOX[1],
                        highlightthickness=1, highlightbackground="#3d4258")
        box.grid(row=r, column=0, padx=16, sticky="w"); r += 1
        box.grid_propagate(False)
        self.preview_image_label = tk.Label(
            box, bg="#12131a", fg=SUBTEXT, text="Select a file\nto preview",
            font=("Segoe UI", 9), justify="center",
        )
        self.preview_image_label.place(relx=0.5, rely=0.5, anchor="center")

        self.preview_caption = ttk.Label(parent, text="", style="Sub.TLabel", wraplength=260, justify="left")
        self.preview_caption.grid(row=r, column=0, sticky="w", padx=16, pady=(6, 0)); r += 1

        ttk.Separator(parent, orient="horizontal").grid(row=r, column=0, sticky="ew", padx=16, pady=16); r += 1

        # ---- Convert to ----------------------------------------------------
        ttk.Label(parent, text="Convert to", style="Panel.TLabel", font=("Segoe UI", 11, "bold")).grid(
            row=r, column=0, sticky="w", padx=16); r += 1
        self.target_var = tk.StringVar(value="")
        self.target_combo = ttk.Combobox(parent, textvariable=self.target_var, state="readonly")
        self.target_combo.grid(row=r, column=0, sticky="ew", padx=16); r += 1
        self.target_combo.bind("<<ComboboxSelected>>", lambda e: self._on_target_change())

        # Quality / lossless
        self.quality_frame = ttk.Frame(parent, style="Panel.TFrame")
        self.quality_frame.grid(row=r, column=0, sticky="ew", padx=16, pady=(14, 0)); r += 1
        self.quality_frame.grid_columnconfigure(0, weight=1)

        self.lossless_var = tk.BooleanVar(value=False)
        self.lossless_check = ttk.Checkbutton(
            self.quality_frame, text="Lossless (larger file, zero quality loss)",
            variable=self.lossless_var, command=self._on_lossless_toggle,
        )
        self.lossless_check.grid(row=0, column=0, sticky="w")

        self.quality_var = tk.IntVar(value=85)
        self.quality_label = ttk.Label(self.quality_frame, text="Quality: 85", style="Panel.TLabel")
        self.quality_label.grid(row=1, column=0, sticky="w", pady=(10, 0))
        self.quality_scale = ttk.Scale(
            self.quality_frame, from_=1, to=100, orient="horizontal",
            variable=self.quality_var, command=self._on_quality_change,
        )
        self.quality_scale.grid(row=2, column=0, sticky="ew", pady=(2, 0))
        self.quality_note = ttk.Label(self.quality_frame, text="", style="Sub.TLabel", wraplength=260, justify="left")
        self.quality_note.grid(row=3, column=0, sticky="w", pady=(6, 0))

        # PDF-only option
        self.combine_pdf_var = tk.BooleanVar(value=True)
        self.combine_pdf_check = ttk.Checkbutton(
            parent, text="Combine all images into one PDF", variable=self.combine_pdf_var,
            command=self._on_combine_toggle,
        )
        combine_row = r; r += 1

        ttk.Separator(parent, orient="horizontal").grid(row=r, column=0, sticky="ew", padx=16, pady=16); r += 1

        # ---- Output location -----------------------------------------------
        ttk.Label(parent, text="Save converted files to", style="Panel.TLabel", font=("Segoe UI", 11, "bold")).grid(
            row=r, column=0, sticky="w", padx=16); r += 1
        self.out_mode_var = tk.StringVar(value="alongside")
        ttk.Radiobutton(
            parent, text="A 'converted' subfolder next to each file", value="alongside",
            variable=self.out_mode_var, command=self._on_out_mode_change,
        ).grid(row=r, column=0, sticky="w", padx=16, pady=(6, 0)); r += 1
        ttk.Radiobutton(
            parent, text="Choose a folder…", value="custom",
            variable=self.out_mode_var, command=self._on_out_mode_change,
        ).grid(row=r, column=0, sticky="w", padx=16, pady=(4, 0)); r += 1
        self.overwrite_radio = ttk.Radiobutton(
            parent, text="Overwrite the original files", value="overwrite",
            variable=self.out_mode_var, command=self._on_out_mode_change,
        )
        self.overwrite_radio.grid(row=r, column=0, sticky="w", padx=16, pady=(4, 0)); r += 1
        self.overwrite_warning = ttk.Label(
            parent, text="⚠ Replaces your original files. This can't be undone.",
            style="Sub.TLabel", foreground=BAD, wraplength=260, justify="left",
        )
        self.overwrite_warning.grid(row=r, column=0, sticky="w", padx=16, pady=(4, 0)); r += 1
        self.overwrite_warning.grid_remove()
        self.out_dir_label = ttk.Label(parent, text="", style="Sub.TLabel", wraplength=260, justify="left")
        self.out_dir_label.grid(row=r, column=0, sticky="w", padx=16, pady=(4, 0)); r += 1

        # the combine-pdf checkbox is placed last so its row is below the
        # quality section regardless of how many rows preview/options take
        self._combine_pdf_row = combine_row
        self.combine_pdf_check.grid(row=combine_row, column=0, sticky="w", padx=16, pady=(0, 4))
        self.combine_pdf_check.grid_remove()

    def _build_action_bar(self, parent):
        parent.grid_columnconfigure(0, weight=1)
        self.progress = ttk.Progressbar(parent, style="TProgressbar", maximum=100)
        self.progress.grid(row=0, column=0, sticky="ew", padx=(0, 12))
        self.status_label = ttk.Label(parent, text="Ready", style="TLabel")
        self.status_label.grid(row=1, column=0, sticky="w", pady=(4, 0))

        btns = ttk.Frame(parent, style="TFrame")
        btns.grid(row=0, column=1, rowspan=2, sticky="e")
        self.open_folder_btn = ttk.Button(btns, text="Open output folder", style="Flat.TButton",
                                           command=self._open_last_output, state="disabled")
        self.open_folder_btn.pack(side="left", padx=(0, 8))
        self.cancel_btn = ttk.Button(btns, text="Cancel", style="Flat.TButton",
                                      command=self._cancel, state="disabled")
        self.cancel_btn.pack(side="left", padx=(0, 8))
        self.convert_btn = ttk.Button(btns, text="Convert All", style="Accent.TButton",
                                       command=self._start_conversion)
        self.convert_btn.pack(side="left")

    # ------------------------------------------------------------- helpers
    def _log(self, msg: str):
        self.log_text.configure(state="normal")
        self.log_text.insert("end", msg.rstrip() + "\n")
        self.log_text.see("end")
        self.log_text.configure(state="disabled")

    def _current_kinds(self) -> set[str]:
        return {j.kind for j in self.jobs.values()}

    def _refresh_target_options(self):
        kinds = self._current_kinds()
        if kinds == {"image"}:
            options = ce.IMAGE_TARGETS
        elif kinds == {"video"}:
            options = ce.VIDEO_TARGETS
        else:
            options = ce.ALL_TARGETS
        current = self.target_var.get()
        self.target_combo["values"] = options
        if current not in options:
            self.target_var.set(options[0] if options else "")
        self._on_target_change()

    def _on_target_change(self):
        target = self.target_var.get().upper()
        no_quality = target in NO_QUALITY_TARGETS
        state = "disabled" if no_quality else "normal"
        self.lossless_check.configure(state=state)
        self.quality_scale.configure(state="disabled" if (no_quality or self.lossless_var.get()) else "normal")

        if target == "PNG":
            self.quality_note.configure(text="PNG is always lossless - no settings needed.")
        elif target == "GIF":
            self.quality_note.configure(text="GIF uses a fixed palette - no quality setting needed.")
        elif no_quality:
            self.quality_note.configure(text="This format has no quality/lossless setting.")
        else:
            self.quality_note.configure(text="")

        if target == "PDF":
            self.combine_pdf_check.grid(row=self._combine_pdf_row, column=0, sticky="w", padx=16, pady=(0, 4))
        else:
            self.combine_pdf_check.grid_remove()

        self._update_overwrite_lock()

    def _on_lossless_toggle(self):
        self._on_target_change()

    def _on_quality_change(self, _value):
        self.quality_label.configure(text=f"Quality: {int(self.quality_var.get())}")

    def _on_combine_toggle(self):
        self._update_overwrite_lock()

    def _update_overwrite_lock(self):
        """Overwriting doesn't make sense when several images are being
        merged into one combined PDF - there's no single original to
        replace - so that combo is disabled rather than silently ignored."""
        combining = self.target_var.get().upper() == "PDF" and self.combine_pdf_var.get()
        self.overwrite_radio.configure(state="disabled" if combining else "normal")
        if combining and self.out_mode_var.get() == "overwrite":
            self.out_mode_var.set("alongside")
            self.overwrite_warning.grid_remove()

    def _on_out_mode_change(self):
        mode = self.out_mode_var.get()
        if mode == "custom":
            chosen = filedialog.askdirectory(title="Choose output folder")
            if chosen:
                self.custom_out_dir = chosen
                self.out_dir_label.configure(text=chosen)
            else:
                self.out_mode_var.set("alongside")
                self.out_dir_label.configure(text="")
        else:
            self.out_dir_label.configure(text="")

        if mode == "overwrite":
            self.overwrite_warning.grid()
        else:
            self.overwrite_warning.grid_remove()

    # --------------------------------------------------------------- queue
    def _on_drop(self, event):
        paths = list(self.root.tk.splitlist(event.data))
        self.add_files(paths)

    def add_files_dialog(self):
        paths = filedialog.askopenfilenames(title="Choose images or videos")
        if paths:
            self.add_files(list(paths))

    def add_folder_dialog(self):
        folder = filedialog.askdirectory(title="Choose a folder")
        if not folder:
            return
        found = []
        for dirpath, _dirs, files in os.walk(folder):
            for name in files:
                found.append(os.path.join(dirpath, name))
        self.add_files(found)

    def add_files(self, paths: list[str]):
        added = 0
        for p in paths:
            p = os.path.normpath(p)
            if not os.path.isfile(p) or p in self.jobs:
                continue
            kind = ce.classify(p)
            if kind == "unknown":
                continue
            row_id = self.tree.insert("", "end", text="", values=(os.path.basename(p), kind, "Queued"))
            self.jobs[p] = Job(src=p, kind=kind, row_id=row_id)
            self._queue_thumbnail(row_id, p, kind)
            added += 1
        if added:
            self._refresh_target_options()
            self._update_queue_count()

    def _forget_preview(self, row_id: str):
        self.preview_info.pop(row_id, None)
        self.row_photo.pop(row_id, None)

    def remove_selected(self):
        for row_id in self.tree.selection():
            src = next((s for s, j in self.jobs.items() if j.row_id == row_id), None)
            if src:
                del self.jobs[src]
            self._forget_preview(row_id)
            self.tree.delete(row_id)
        self._refresh_target_options()
        self._update_queue_count()
        self._update_preview_panel()

    def clear_all(self):
        self.tree.delete(*self.tree.get_children())
        self.jobs.clear()
        self.preview_info.clear()
        self.row_photo.clear()
        self._refresh_target_options()
        self._update_queue_count()
        self._update_preview_panel()

    def _update_queue_count(self):
        n = len(self.jobs)
        self.queue_count_label.configure(text=f"{n} file{'s' if n != 1 else ''} queued")

    # --------------------------------------------------------------- preview
    def _thumbnail_worker(self):
        """Runs forever on a background thread. Generating a thumbnail means
        decoding an image or running ffmpeg to grab a video frame - both too
        slow to do on the GUI thread without the window freezing, especially
        when a dozen files just got dropped at once."""
        while True:
            row_id, src, kind = self.thumb_queue.get()
            info = ce.generate_preview(src, kind, ffmpeg_path=self.ffmpeg_path, max_size=PREVIEW_CACHE_SIZE)
            self.msg_queue.put(("thumb_ready", row_id, info))

    def _queue_thumbnail(self, row_id: str, src: str, kind: str):
        self.thumb_queue.put((row_id, src, kind))

    def _on_tree_select(self, _event=None):
        self._update_preview_panel()

    def _file_size_label(self, path: str) -> str | None:
        try:
            return human_size(os.path.getsize(path))
        except OSError:
            return None

    def _set_preview_placeholder(self, text: str):
        self.preview_image_label.configure(image="", text=text)
        self.preview_photo = None

    def _update_preview_panel(self):
        sel = self.tree.selection()
        if len(sel) == 0:
            self._set_preview_placeholder("Select a file\nto preview")
            self.preview_caption.configure(text="")
            return
        if len(sel) > 1:
            self._set_preview_placeholder(f"{len(sel)} files\nselected")
            self.preview_caption.configure(text="")
            return

        row_id = sel[0]
        src = next((s for s, j in self.jobs.items() if j.row_id == row_id), None)
        if not src:
            return

        info = self.preview_info.get(row_id)
        if info is None:
            self._set_preview_placeholder("Loading preview…")
            self.preview_caption.configure(text=os.path.basename(src))
            return
        if info.image is None:
            self._set_preview_placeholder("No preview\navailable")
        else:
            boxed = _compose_boxed(info.image, *PREVIEW_BOX)
            photo = ImageTk.PhotoImage(boxed)
            self.preview_photo = photo  # keep a reference alive - Tk drops GC'd images
            self.preview_image_label.configure(image=photo, text="")

        meta_bits = []
        if info.width and info.height:
            meta_bits.append(f"{info.width} × {info.height}")
        if info.duration:
            m_, s_ = divmod(int(info.duration), 60)
            meta_bits.append(f"{m_}:{s_:02d}")
        size_label = self._file_size_label(src)
        if size_label:
            meta_bits.append(size_label)
        caption = os.path.basename(src)
        if meta_bits:
            caption += "\n" + "  ·  ".join(meta_bits)
        self.preview_caption.configure(text=caption)

    def _apply_thumbnail(self, row_id: str, info: "ce.PreviewInfo"):
        """Runs on the main thread (called from _poll_queue). Creating a
        Tk PhotoImage must happen here, never on the background thread that
        generated the underlying PIL image."""
        self.preview_info[row_id] = info
        if info.image is not None and self.tree.exists(row_id):
            icon = info.image.copy()
            icon.thumbnail((ROW_ICON_SIZE, ROW_ICON_SIZE), Image.LANCZOS)
            photo = ImageTk.PhotoImage(icon)
            self.row_photo[row_id] = photo  # keep alive, same reason as preview_photo
            self.tree.item(row_id, image=photo)
        if row_id in self.tree.selection():
            self._update_preview_panel()

    # ---------------------------------------------------------- conversion
    def _start_conversion(self):
        if not self.jobs:
            messagebox.showinfo(APP_TITLE, "Add some files first.")
            return
        target = self.target_var.get()
        if not target:
            messagebox.showinfo(APP_TITLE, "Choose an output format.")
            return

        if self.out_mode_var.get() == "overwrite":
            n = len(self.jobs)
            proceed = messagebox.askyesno(
                APP_TITLE,
                f"This will replace {n} original file{'s' if n != 1 else ''} with "
                "the converted version, permanently. This can't be undone.\n\n"
                "Continue?",
                icon="warning",
            )
            if not proceed:
                return

        self.cancel_event.clear()
        self.convert_btn.configure(state="disabled")
        self.cancel_btn.configure(state="normal")
        self.open_folder_btn.configure(state="disabled")
        self.progress.configure(value=0)
        self.last_out_dir: str | None = None

        for job in self.jobs.values():
            job.status = "Queued"
            self.tree.set(job.row_id, "status", "Queued")

        quality = int(self.quality_var.get())
        lossless = self.lossless_var.get()
        combine_pdf = self.combine_pdf_var.get()
        out_mode = self.out_mode_var.get()
        custom_dir = self.custom_out_dir

        self.worker_thread = threading.Thread(
            target=self._worker,
            args=(list(self.jobs.values()), target, quality, lossless, combine_pdf, out_mode, custom_dir),
            daemon=True,
        )
        self.worker_thread.start()

    def _cancel(self):
        self.cancel_event.set()
        self.msg_queue.put(("status", "Cancelling…"))

    def _resolve_out_dir(self, src: str, out_mode: str, custom_dir: str | None) -> Path:
        if out_mode == "custom" and custom_dir:
            out_dir = Path(custom_dir)
        else:
            out_dir = Path(src).resolve().parent / "converted"
        out_dir.mkdir(parents=True, exist_ok=True)
        return out_dir

    def _worker(self, jobs: list[Job], target: str, quality: int, lossless: bool,
                combine_pdf: bool, out_mode: str, custom_dir: str | None):
        target_up = target.upper()
        ext = EXT_MAP.get(target_up, target_up.lower())
        total = len(jobs)
        done = 0
        last_out_dir = None

        def is_cancelled():
            return self.cancel_event.is_set()

        # Special case: combine several images into one PDF
        if target_up == "PDF" and combine_pdf:
            image_jobs = [j for j in jobs if j.kind == "image"]
            other_jobs = [j for j in jobs if j.kind != "image"]
            if len(image_jobs) >= 1:
                out_dir = self._resolve_out_dir(image_jobs[0].src, out_mode, custom_dir)
                out_path = ce.unique_path(out_dir / "combined.pdf")
                try:
                    ce.images_to_pdf([j.src for j in image_jobs], str(out_path), lossless=lossless, quality=quality)
                    for j in image_jobs:
                        self.msg_queue.put(("job_status", j.row_id, "Done"))
                    self.msg_queue.put(("log", f"Combined {len(image_jobs)} image(s) -> {out_path.name}"))
                    last_out_dir = str(out_dir)
                except Exception as exc:
                    for j in image_jobs:
                        self.msg_queue.put(("job_status", j.row_id, "Error"))
                    self.msg_queue.put(("log", f"Failed to build combined PDF: {exc}"))
                done += len(image_jobs)
                self.msg_queue.put(("progress", done / total * 100 if total else 100))
            for j in other_jobs:
                self.msg_queue.put(("job_status", j.row_id, "Skipped"))
                self.msg_queue.put(("log", f"Skipped {os.path.basename(j.src)}: not an image, can't join into the PDF."))
                done += 1
                self.msg_queue.put(("progress", done / total * 100 if total else 100))
            self.msg_queue.put(("finished", last_out_dir))
            return

        overwrite = (out_mode == "overwrite")

        for job in jobs:
            if is_cancelled():
                self.msg_queue.put(("job_status", job.row_id, "Cancelled"))
                done += 1
                continue

            self.msg_queue.put(("job_status", job.row_id, "Converting…"))
            src_path = Path(job.src)
            # Overwrite mode NEVER converts in place. It writes the full
            # result to a temp file next to the original first; only once
            # that's verified to exist does it delete the original and move
            # the temp file into place. A failed/cancelled run below simply
            # never reaches that step, so the original is untouched.
            if overwrite:
                work_dir = src_path.parent
                temp_path = work_dir / f"~converting_{src_path.stem}.{ext}"
            else:
                work_dir = self._resolve_out_dir(job.src, out_mode, custom_dir)
                temp_path = ce.unique_path(work_dir / f"{src_path.stem}.{ext}")
            last_out_dir = str(work_dir)

            try:
                if target_up == "PDF":
                    if job.kind != "image":
                        raise RuntimeError("only images can be converted to PDF")
                    ce.images_to_pdf([job.src], str(temp_path), lossless=lossless, quality=quality)

                elif job.kind == "image" and target_up != "PDF":
                    if target_up in ce.VIDEO_TARGETS and target_up not in ce.IMAGE_TARGETS:
                        raise RuntimeError(f"can't convert an image to {target_up}")
                    ce.convert_image(job.src, str(temp_path), target_up, quality=quality,
                                      lossless=lossless, log=lambda m: self.msg_queue.put(("log", m)))

                elif job.kind == "video":
                    if target_up in ce.IMAGE_TARGETS and target_up not in ce.VIDEO_TARGETS:
                        raise RuntimeError(f"can't convert a video to {target_up}")

                    def progress_cb(frac, _done=done, _total=total):
                        overall = (_done + frac) / _total * 100
                        self.msg_queue.put(("progress", overall))

                    ce.convert_video(self.ffmpeg_path, job.src, str(temp_path), target_up,
                                      quality=quality, lossless=lossless,
                                      progress_cb=progress_cb, is_cancelled=is_cancelled)
                else:
                    raise RuntimeError("unsupported conversion")

                if overwrite:
                    final_path = Path(ce.finalize_overwrite(str(temp_path), job.src, ext))
                else:
                    final_path = temp_path

                size = human_size(final_path.stat().st_size) if final_path.exists() else "?"
                self.msg_queue.put(("job_status", job.row_id, "Done"))
                self.msg_queue.put(("log", f"{os.path.basename(job.src)} -> {final_path.name} ({size})"))

            except ce.ConversionCancelled:
                self.msg_queue.put(("job_status", job.row_id, "Cancelled"))
                self._cleanup_temp(overwrite, temp_path)
            except Exception as exc:
                self.msg_queue.put(("job_status", job.row_id, "Error"))
                self.msg_queue.put(("log", f"Failed: {os.path.basename(job.src)} - {exc}"))
                self._cleanup_temp(overwrite, temp_path)

            done += 1
            self.msg_queue.put(("progress", done / total * 100))

        self.msg_queue.put(("finished", last_out_dir))

    @staticmethod
    def _cleanup_temp(overwrite: bool, temp_path: Path):
        """On a failed or cancelled overwrite-mode conversion, remove the
        partial temp file so it doesn't linger next to the original."""
        if overwrite:
            try:
                temp_path.unlink(missing_ok=True)
            except Exception:
                pass

    # ---------------------------------------------------------------- poll
    def _poll_queue(self):
        try:
            while True:
                msg = self.msg_queue.get_nowait()
                kind = msg[0]
                if kind == "job_status":
                    _, row_id, status = msg
                    self.tree.set(row_id, "status", status)
                elif kind == "progress":
                    self.progress.configure(value=msg[1])
                elif kind == "log":
                    self._log(msg[1])
                elif kind == "status":
                    self.status_label.configure(text=msg[1])
                elif kind == "finished":
                    self._on_finished(msg[1])
                elif kind == "thumb_ready":
                    self._apply_thumbnail(msg[1], msg[2])
        except queue.Empty:
            pass
        self.root.after(100, self._poll_queue)

    def _on_finished(self, out_dir: str | None):
        self.convert_btn.configure(state="normal")
        self.cancel_btn.configure(state="disabled")
        self.status_label.configure(text="Done" if not self.cancel_event.is_set() else "Cancelled")
        if out_dir:
            self.last_out_dir = out_dir
            self.open_folder_btn.configure(state="normal")

    def _open_last_output(self):
        out_dir = getattr(self, "last_out_dir", None)
        if not out_dir:
            return
        try:
            if sys.platform.startswith("win"):
                os.startfile(out_dir)  # type: ignore[attr-defined]
            elif sys.platform == "darwin":
                os.system(f'open "{out_dir}"')
            else:
                os.system(f'xdg-open "{out_dir}"')
        except Exception as exc:
            messagebox.showerror(APP_TITLE, f"Couldn't open folder: {exc}")


def main():
    root = TkinterDnD.Tk() if HAS_DND else tk.Tk()
    try:
        root.iconbitmap(resource_path("icon.ico"))
    except Exception:
        pass
    app = ConverterApp(root)
    root.protocol("WM_DELETE_WINDOW", root.destroy)
    root.mainloop()


if __name__ == "__main__":
    try:
        main()
    except Exception:
        # A --windowed build has no console, so make sure a crash is visible.
        try:
            import tkinter.messagebox as mb
            mb.showerror("Ultimate Converter - crashed", traceback.format_exc())
        except Exception:
            pass
        raise
