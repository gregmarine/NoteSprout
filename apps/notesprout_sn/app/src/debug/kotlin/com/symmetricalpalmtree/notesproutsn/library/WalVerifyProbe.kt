package com.symmetricalpalmtree.notesproutsn.library

import android.content.Context
import com.symmetricalpalmtree.notesproutsn.crypto.SoilCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

/**
 * Debug build only — the on-device proof of arc 34 / H1's [SoilCrypto.verifyPassphraseReadOnly],
 * since SQLCipher is not on the JVM. Builds the SAF backup's shape for an extension store in the
 * cache dir: a WAL-mode encrypted database whose rows sit **only in the `-wal`** (main + sidecar
 * copied while the writing connection is still open — `BackupEngine.copyDatabase`'s
 * WAL-alongside rule), then:
 *
 *  1. the read-only verify answers true, the wrong key false, and both files are byte-for-byte
 *     what they were — the staged bytes still weigh what a restore manifest measured;
 *  2. a read-only open reads the WAL's rows;
 *  3. the old read-write verify, run last, is the defect reproduced: the main file grows and the
 *     `-wal` is gone (SQLCipher's raw open also switches the file to `journal_mode=delete`).
 *
 * PASS iff 1 and 2 hold, and the rows still count after 3. No passphrase is real; nothing outside
 * the cache dir is touched.
 */
object WalVerifyProbe {

    private const val KEY = "probe-wal-passphrase"
    private const val WRONG = "not-the-key"
    private const val ROWS = 3L

    suspend fun run(context: Context): String = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "walprobe").apply { deleteRecursively(); mkdirs() }
        val live = File(dir, "live.db")
        val staged = File(dir, "staged.db")
        val stagedWal = File(staged.path + "-wal")
        val stagedShm = File(staged.path + "-shm")
        val out = StringBuilder()
        try {
            val db = SoilCrypto.createRaw(live, KEY)
            try {
                db.rawQuery("PRAGMA journal_mode=WAL", null).use { c -> c.moveToFirst(); out.append("journal_mode: ${c.getString(0)}\n") }
                db.execSQL("CREATE TABLE t(x INTEGER)")
                db.execSQL("INSERT INTO t VALUES (1), (2), (3)")
                live.copyTo(staged, overwrite = true)
                File(live.path + "-wal").copyTo(stagedWal, overwrite = true)
            } finally {
                runCatching { db.close() }
            }
            val main0 = staged.readBytes()
            val wal0 = stagedWal.readBytes()
            out.append("staged: ${main0.size} B main · ${wal0.size} B wal\n")

            val ro = SoilCrypto.verifyPassphraseReadOnly(staged, KEY)
            val roWrong = SoilCrypto.verifyPassphraseReadOnly(staged, WRONG)
            val sameMain = staged.readBytes().contentEquals(main0)
            val sameWal = stagedWal.isFile && stagedWal.readBytes().contentEquals(wal0)
            out.append("read-only verify: $ro (want true) · wrong key: $roWrong (want false)\n")
            out.append("  main byte-identical: $sameMain · wal byte-identical: $sameWal · shm left: ${stagedShm.exists()}\n")

            val roRows = count(SoilCrypto.openRawReadOnly(staged, KEY))
            out.append("  rows through the read-only open: $roRows (want $ROWS)\n")

            SoilCrypto.verifyPassphrase(staged, KEY)
            out.append("read-write verify (the old shape): main ${staged.length()} B (was ${main0.size}) · wal exists: ${stagedWal.exists()} (the defect: false)\n")
            val rwRows = count(SoilCrypto.openRaw(staged, KEY))
            out.append("  rows after: $rwRows\n")

            val ok = ro && !roWrong && sameMain && sameWal && roRows == ROWS && rwRows == ROWS
            out.append(if (ok) "PASS" else "FAIL")
        } catch (e: Exception) {
            out.append("FAIL — ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            dir.deleteRecursively()
        }
        out.toString()
    }

    private fun count(db: SQLiteDatabase): Long = try {
        db.rawQuery("SELECT count(*) FROM t", null).use { c -> c.moveToFirst(); c.getLong(0) }
    } finally {
        runCatching { db.close() }
    }
}
