package com.symmetricalpalmtree.notesproutsn.restore

/**
 * One backup a [RestoreSource] can offer (arc 27 / L1, D6 — the derived rule "name, notebook count
 * and the index's last-modified time", which is enough to tell two backups apart without opening
 * either). [totalBytes] comes free with the same listing, so the pre-flight can gate free space
 * before the first byte is fetched (D2 / R1).
 *
 * [handle] is **source-private** — a SAF document URI string today, a cloud folder id in L4. It is
 * never shown to the user and never logged: a tree URI can carry the folder's display name.
 */
data class RestoreBackup(
    val name: String,
    val notebookCount: Int,
    val indexModifiedAt: Long,
    val totalBytes: Long,
    val handle: String,
)
