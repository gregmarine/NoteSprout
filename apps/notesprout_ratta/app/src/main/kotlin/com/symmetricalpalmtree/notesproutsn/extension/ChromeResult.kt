package com.symmetricalpalmtree.notesproutsn.extension

import android.content.Intent

/**
 * The chrome flag an ink screen (the pad, the calendar) echoes on its result Intent (arc 33 / F3)
 * — [ExtensionContract.EXTRA_CHROME_HIDDEN], the first datum ever on this seam's result.
 *
 * One rule: **present → its value, absent → null**, and the result code never enters into it. A
 * plain Back leaves with `RESULT_CANCELED` and still carries the flag (the screen's last state is
 * the truth whatever door it left by), while a screen whose process was killed under a live host
 * hands back `RESULT_CANCELED` with **no data** — and an extension that predates the extra returns
 * a result with none either. Null means "the host writes nothing", never "shown".
 */
object ChromeResult {

    /** The flag on [data], or null when there is no data or no extra. */
    fun read(data: Intent?): Boolean? =
        decode(
            present = data?.hasExtra(ExtensionContract.EXTRA_CHROME_HIDDEN) == true,
            value = data?.getBooleanExtra(ExtensionContract.EXTRA_CHROME_HIDDEN, false) == true,
        )

    /** The rule under [read], free of `Intent` so it can be tested: [value] only when [present]. */
    fun decode(present: Boolean, value: Boolean): Boolean? = if (present) value else null
}
