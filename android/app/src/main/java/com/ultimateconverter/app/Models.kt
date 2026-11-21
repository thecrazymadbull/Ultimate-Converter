package com.ultimateconverter.app

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class Kind { IMAGE, VIDEO, PDF }

enum class JobState { QUEUED, CONVERTING, DONE, ERROR, SKIPPED, CANCELLED }

/** Which kind of folder an output belongs in (photos, movies, music, documents). */
enum class OutCat { IMAGE, VIDEO, AUDIO, DOC }

enum class PageSize(val label: String) {
    MATCH("Match each image"),
    A4("A4"),
    LETTER("Letter"),
}

enum class Target(
    val label: String,
    val ext: String,
    val mime: String,
    val cat: OutCat,
    val accepts: Set<Kind>,
) {
    PNG("PNG", "png", "image/png", OutCat.IMAGE, setOf(Kind.IMAGE, Kind.PDF)),
    JPEG("JPEG", "jpg", "image/jpeg", OutCat.IMAGE, setOf(Kind.IMAGE, Kind.PDF)),
    WEBP("WEBP", "webp", "image/webp", OutCat.IMAGE, setOf(Kind.IMAGE, Kind.PDF)),
    BMP("BMP", "bmp", "image/bmp", OutCat.IMAGE, setOf(Kind.IMAGE, Kind.PDF)),
    PDF("PDF", "pdf", "application/pdf", OutCat.DOC, setOf(Kind.IMAGE)),
    MP4("MP4 video", "mp4", "video/mp4", OutCat.VIDEO, setOf(Kind.VIDEO)),
    M4A("Audio only (M4A)", "m4a", "audio/mp4", OutCat.AUDIO, setOf(Kind.VIDEO)),
    ;

    val isPicture: Boolean get() = cat == OutCat.IMAGE
}

class Options(
    val target: Target,
    val quality: Int,
    val lossless: Boolean,
    val combinePdf: Boolean,
    val pdfName: String,
    val pageSize: PageSize,
    val dpi: Int,
)

val DPI_CHOICES = listOf(150, 200, 300)

class FileJob(
    val id: Long,
    val uri: Uri,
    val name: String,
    val kind: Kind,
    val sizeBytes: Long,
) {
    var rotation by mutableIntStateOf(0)
    var state by mutableStateOf(JobState.QUEUED)
    var message by mutableStateOf("")
    var thumbBase by mutableStateOf<Bitmap?>(null)
    var info by mutableStateOf("")
    var outputs by mutableStateOf<List<Uri>>(emptyList())
}
