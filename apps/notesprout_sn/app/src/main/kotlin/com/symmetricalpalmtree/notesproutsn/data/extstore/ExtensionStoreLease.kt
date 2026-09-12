package com.symmetricalpalmtree.notesproutsn.data.extstore

import android.content.Context
import android.content.pm.PackageManager
import com.symmetricalpalmtree.notesproutsn.core.Slog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * **Open one extension's store and wrap it in a uid-bound binder, or answer null** (arc 31 / HV4).
 *
 * The pre-open rule made a function: [ExtensionStores.open] is blocking and, cold, is a SQLCipher
 * KDF — so it runs on IO **before** any bind, never inside a call's timeout window. The binder it
 * comes back in is minted for one lending and is the caller's to [ExtensionStoreBinder.revoke] in a
 * `finally`, whatever the call did.
 *
 * Null is every failure that means *this extension has no store today* — the package went, the key
 * is not in session ([com.symmetricalpalmtree.notesproutsn.crypto.SoilLockedException]), a newer
 * host wrote the file. Each is logged under the caller's own [tag] and none of them is an exception
 * the caller has to sort out: a store that will not open is an extension that cannot be asked, and
 * every caller turns that into its own refusal.
 *
 * It exists because four clients had written the same twelve lines
 * ([com.symmetricalpalmtree.notesproutsn.extension.HeldInkClient],
 * `TagClient`, `CloudClient`, `CloudConnectClient`) and a fifth was owed one — which is the
 * sibling-copy trap in miniature. [tag] is the only thing that ever differed.
 */
suspend fun ExtensionStores.lease(context: Context, pkg: String, tag: String): ExtensionStoreBinder? =
    try {
        val appContext = context.applicationContext
        val db = withContext(Dispatchers.IO) { open(appContext, pkg) }
        val extUid = appContext.packageManager.getPackageUid(pkg, 0)
        ExtensionStoreBinder(db, extUid)
    } catch (e: CancellationException) {
        throw e
    } catch (e: PackageManager.NameNotFoundException) {
        Slog.d(tag) { "store open failed: package gone $pkg" }
        null
    } catch (e: Exception) {
        Slog.d(tag) { "store open failed: ${e.javaClass.simpleName}: ${e.message}" }
        null
    }
