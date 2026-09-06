package com.symmetricalpalmtree.notesproutsn.restore

/**
 * Which kind of destination a backup is being read back from (arc 27 / L1, D1). The one place the
 * two legs differ on the *read* side is the WAL rule:
 *
 * - [LOCAL] — a `SafBackupWriter` destination holds live files exactly as the run copied them, so
 *   a `-wal` sitting beside a main file is that main file's missing writes. It is taken **with**
 *   its main, never alone, and both must land or the fetch fails.
 * - [CLOUD] — `SelfContainedSnapshot` absorbs the WAL into the copy before it is uploaded, so
 *   every cloud main file is already complete. A `-wal` in a cloud device folder is therefore
 *   **stale by construction** — the residue of a stale-sidecar delete that failed, which
 *   `CloudBackupLeg` guards against on the write side. Pairing it with a fresh main is exactly the
 *   corruption that guard exists to prevent, so the read side never takes one (R3).
 */
enum class RestoreLeg { LOCAL, CLOUD }
