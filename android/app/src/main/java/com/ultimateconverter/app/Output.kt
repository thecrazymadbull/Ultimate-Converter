package com.ultimateconverter.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream

/** Where converted files get saved. Nothing existing is ever overwritten. */
abstract class OutputSink(protected val ctx: Context) {
    /** Creates a new, empty file and returns its Uri. [sub] is an optional sub-folder name. */
    abstract fun create(name: String, mime: String, cat: OutCat, sub: String?): Uri

    /** Called once the file has been completely written. */
    open fun finish(uri: Uri) {}

    /** Removes a partly written file again. */
    abstract fun abort(uri: Uri)

    fun openStream(uri: Uri): OutputStream {
        val s = ctx.contentResolver.openOutputStream(uri, "w")
            ?: throw IOException("Couldn't write the file")
        return BufferedOutputStream(s, 64 * 1024)
    }
}

fun defaultFolderLabel(cat: OutCat): String = when (cat) {
    OutCat.IMAGE -> "Pictures/UltimateConverter"
    OutCat.VIDEO -> "Movies/UltimateConverter"
    OutCat.AUDIO -> "Music/UltimateConverter"
    OutCat.DOC -> "Download/UltimateConverter"
}

/** Default: the phone's normal Pictures / Movies / Music / Download folders (shows up in Gallery). */
class MediaStoreSink(ctx: Context) : OutputSink(ctx) {
    override fun create(name: String, mime: String, cat: OutCat, sub: String?): Uri {
        val collection = when (cat) {
            OutCat.IMAGE -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            OutCat.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            OutCat.AUDIO -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            OutCat.DOC -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val root = when (cat) {
            OutCat.IMAGE -> Environment.DIRECTORY_PICTURES
            OutCat.VIDEO -> Environment.DIRECTORY_MOVIES
            OutCat.AUDIO -> Environment.DIRECTORY_MUSIC
            OutCat.DOC -> Environment.DIRECTORY_DOWNLOADS
        }
        val relative = root + "/UltimateConverter" + (if (sub != null) "/$sub" else "")
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return ctx.contentResolver.insert(collection, values)
            ?: throw IOException("Couldn't create the file in $relative")
    }

    override fun finish(uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        ctx.contentResolver.update(uri, values, null, null)
    }

    override fun abort(uri: Uri) {
        try {
            ctx.contentResolver.delete(uri, null, null)
        } catch (ignored: Exception) {
        }
    }
}

/** A folder the person picked with the system folder chooser. */
class TreeSink(ctx: Context, private val tree: Uri) : OutputSink(ctx) {
    private val rootDocId: String = DocumentsContract.getTreeDocumentId(tree)
    private val rootUri: Uri = DocumentsContract.buildDocumentUriUsingTree(tree, rootDocId)
    private val subFolders = HashMap<String, Uri>()

    private fun findChildFolder(parentDocId: String, name: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        ctx.contentResolver.query(children, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == name && c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                }
            }
        }
        return null
    }

    private fun folderFor(sub: String?): Uri {
        if (sub == null) return rootUri
        subFolders[sub]?.let { return it }
        val uri = findChildFolder(rootDocId, sub)
            ?: DocumentsContract.createDocument(
                ctx.contentResolver, rootUri, DocumentsContract.Document.MIME_TYPE_DIR, sub,
            )
            ?: throw IOException("Couldn't create the folder \"$sub\"")
        subFolders[sub] = uri
        return uri
    }

    override fun create(name: String, mime: String, cat: OutCat, sub: String?): Uri {
        return DocumentsContract.createDocument(ctx.contentResolver, folderFor(sub), mime, name)
            ?: throw IOException("Couldn't create \"$name\" in the chosen folder")
    }

    override fun abort(uri: Uri) {
        try {
            DocumentsContract.deleteDocument(ctx.contentResolver, uri)
        } catch (ignored: Exception) {
        }
    }
}

/** "primary:DCIM/Converted" -> "DCIM/Converted" for display. */
fun treeLabel(tree: Uri): String {
    return try {
        val id = DocumentsContract.getTreeDocumentId(tree)
        id.substringAfter(':', id).ifEmpty { "Internal storage" }
    } catch (ignored: Exception) {
        "Chosen folder"
    }
}
