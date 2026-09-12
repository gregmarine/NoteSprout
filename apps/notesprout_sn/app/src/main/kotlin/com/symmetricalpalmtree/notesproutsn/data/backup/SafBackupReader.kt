package com.symmetricalpalmtree.notesproutsn.data.backup

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.InputStream

/**
 * [SafBackupWriter]'s read twin (arc 27 / L1): a picked SAF tree, enumerated and read back for a
 * restore. Hand-rolled over [DocumentsContract] in exactly the writer's style —
 * `androidx.documentfile` is not on the classpath and the no-new-dependencies rule stands; the
 * three calls this needs (resolve the root, query children, open a stream) are all platform API.
 *
 * **Read only.** There is no create, rename or delete here, and there never will be: a restore
 * reads a folder and writes nothing back into it.
 *
 * **The grant is not persisted.** A restore takes a *read* grant on the tree the picker returned
 * and never calls `takePersistableUriPermission` — persisting the tree is what *setting a backup
 * destination* means, and decision 3 forbids a restore setting one ("restoring from a folder must
 * never rewrite the configured backup destination; restore-from-anywhere stays allowed"). The grant
 * dies with the task, which is exactly the lifetime a restore needs.
 *
 * Nothing here throws: every failure logs and answers null. Content URIs are never logged (a tree
 * URI can carry the folder's display name); file *names* are UUIDs or package names and are safe.
 */
class SafBackupReader(private val resolver: ContentResolver, private val treeUri: Uri) {

    /** One child of a directory, as the enumeration needs to see it. */
    data class Entry(
        val uri: Uri,
        val name: String,
        val size: Long,
        val isDir: Boolean,
        val lastModified: Long,
    )

    /**
     * The tree's root as a document URI, or null when the grant no longer resolves (folder
     * deleted, SD card ejected, permission never arrived) — the source's fail-fast, the same one
     * the writer makes.
     */
    fun root(): Uri? = try {
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri)
        )
        resolver.query(
            rootUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null
        )?.use { if (it.moveToFirst()) rootUri else null }
    } catch (e: Exception) {
        Log.w(TAG, "source root did not resolve", e)
        null
    }

    /** The root document's display name — what the chooser calls the backup. Null when the
     *  provider will not say, and the caller falls back to a generic label. */
    fun rootName(): String? = try {
        val rootUri = root()
        if (rootUri == null) null else resolver.query(
            rootUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (e: Exception) {
        Log.w(TAG, "source root name query failed", e)
        null
    }

    /**
     * The children of [dirUri], or null when the listing itself failed — never "empty" for a
     * failure, so an unreadable folder can never be mistaken for one that simply is not a backup.
     *
     * **One listing serves a whole enumeration** (the writer's K3 lesson): a listing is a
     * whole-directory provider query, and the manifest decides everything it needs from the names,
     * sizes and mime types it already carries. A missing size answers -1 and a missing timestamp 0,
     * both of which the manifest and the chooser handle as "the provider would not say".
     */
    fun list(dirUri: Uri): List<Entry>? = try {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getDocumentId(dirUri)
        )
        val out = ArrayList<Entry>()
        val cursor = resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ),
            null, null, null,
        )
        cursor?.use { c ->
            while (c.moveToNext()) {
                out.add(
                    Entry(
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0)),
                        name = c.getString(1) ?: continue,
                        size = if (c.isNull(2)) -1L else c.getLong(2),
                        isDir = c.getString(3) == DocumentsContract.Document.MIME_TYPE_DIR,
                        lastModified = if (c.isNull(4)) 0L else c.getLong(4),
                    )
                )
            }
            out
        }
    } catch (e: Exception) {
        Log.w(TAG, "source listing failed", e)
        null
    }

    /** A stream over one document, or null when it will not open — the caller closes it. */
    fun open(uri: Uri): InputStream? = try {
        resolver.openInputStream(uri)
    } catch (e: Exception) {
        Log.w(TAG, "source file did not open", e)
        null
    }

    private companion object {
        const val TAG = "SafBackupReader"
    }
}
