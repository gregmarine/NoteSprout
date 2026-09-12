package com.symmetricalpalmtree.notesproutsn.restore

import android.content.Context
import android.util.Log
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.crypto.PassphraseStore
import com.symmetricalpalmtree.notesproutsn.crypto.SecurePrefs
import com.symmetricalpalmtree.notesproutsn.data.backup.BackupConfig
import com.symmetricalpalmtree.notesproutsn.data.backup.BackupStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * **The backup destination is device-local and a restore never rewrites it** (arc 27 / L2, D4 —
 * decision 3, the user's own incident: a BOOX backup restored onto a Supernote silently re-aimed
 * the Supernote's backup folder at the BOOX's, and several runs later the BOOX's backup had been
 * overwritten). In SN the whole backup config — the SAF `treeUri`, `cloudEnabled`,
 * `cloudDeviceFolder` and **both** stamp maps — is one `backup` row inside the index being
 * replaced, so a naive restore installs the *source device's* destination over this device's.
 *
 * Two halves:
 *  - **The park.** Before the swap, the engine reads this device's three destination fields out of
 *    the live index and [park]s them in `SecurePrefs` under one blob — not a secret, but it rides
 *    the store that already survives the swap and the relaunch, and that Bootstrap already reads
 *    at the right moment. Written with `commit()` so a kill right after cannot lose it.
 *  - **The merge** ([merge], pure, JVM-tested). Applied on the **first successful index open after
 *    the relaunch** ([applyParked] — Bootstrap, and Unlock for the never-expected case where the
 *    relaunch still had to ask), because the index is encrypted under the restored library's key
 *    and cannot be written before then. The restored row keeps everything *except* the
 *    destination: this device's `treeUri` / `cloudEnabled` / `cloudDeviceFolder` replace the
 *    backup's — **always**, even when this device had none, because a restore never *sets* a
 *    destination — and both stamp maps and every last-run figure are cleared, because this device
 *    has never backed up this library. Idempotent: the same park merged twice yields the same row.
 */
object RestoreDestination {

    /** This device's destination, as it stood before the swap. Every field may be null/false. */
    @Serializable
    data class Parked(
        val treeUri: String? = null,
        val cloudEnabled: Boolean = false,
        val cloudDeviceFolder: String? = null,
    )

    /** D4's table. [parked] null means "this device had nothing configured" — same outcome as a
     *  park of three empty fields, which is what the engine always writes anyway. */
    fun merge(restored: BackupConfig, parked: Parked?): BackupConfig = restored.copy(
        treeUri = parked?.treeUri,
        cloudEnabled = parked?.cloudEnabled ?: false,
        cloudDeviceFolder = parked?.cloudDeviceFolder,
        stamps = emptyMap(),
        cloudStamps = emptyMap(),
        lastRunAt = null,
        lastCopied = null,
        lastSkipped = null,
        cloudLastRunAt = null,
        cloudLastCopied = null,
        cloudLastSkipped = null,
    )

    /** What this device's destination looks like, lifted from its live config. */
    fun parkedFrom(config: BackupConfig): Parked =
        Parked(treeUri = config.treeUri, cloudEnabled = config.cloudEnabled, cloudDeviceFolder = config.cloudDeviceFolder)

    // ── The park ─────────────────────────────────────────────────────────────

    private const val KEY = "restore_pending_destination"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Write the park. False when the store would not take it — the engine then refuses to swap,
     *  because a restore that cannot promise to put the destination back must not start. */
    fun park(context: Context, parked: Parked): Boolean = try {
        prefs(context).edit().putString(KEY, json.encodeToString(Parked.serializer(), parked)).commit()
    } catch (e: Exception) {
        Log.w(TAG, "park failed", e)
        false
    }

    /** The park, or null when none stands (or it will not decode — then there is nothing to apply). */
    fun parked(context: Context): Parked? = try {
        prefs(context).getString(KEY, null)?.let { json.decodeFromString(Parked.serializer(), it) }
    } catch (e: Exception) {
        Log.w(TAG, "park unreadable", e)
        null
    }

    fun clearPark(context: Context) {
        runCatching { prefs(context).edit().remove(KEY).commit() }
    }

    fun hasPark(context: Context): Boolean = prefs(context).contains(KEY)

    /**
     * The first-open step: if a park stands, merge it over the (restored) backup row and clear the
     * park — **write first, clear second**, so a kill between the two re-applies on the next
     * launch and the merge is the same both times. Never throws; a failed write leaves the park for
     * the next launch. Call only with the index open. IO-friendly (Room suspend).
     */
    suspend fun applyParked(context: Context, store: BackupStore = BackupStore()) {
        val app = context.applicationContext
        val parked = try {
            if (!hasPark(app)) return
            parked(app)
        } catch (e: Exception) {
            Log.w(TAG, "park check failed", e)
            return
        }
        try {
            val restored = store.read()
            if (!store.write(merge(restored, parked))) {
                Log.w(TAG, "destination re-apply did not write; park kept for the next launch")
                return
            }
            clearPark(app)
            Slog.d(TAG) { "destination re-applied after restore (tree=${parked?.treeUri != null}, cloud=${parked?.cloudEnabled == true})" }
        } catch (e: Exception) {
            Log.w(TAG, "destination re-apply failed; park kept for the next launch", e)
        }
    }

    private fun prefs(context: Context) = SecurePrefs.get(context, PassphraseStore.PREFS_FILE)

    private const val TAG = "RestoreDestination"
}
